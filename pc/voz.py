"""Oído y boca del asistente: palabra de activación, reconocimiento de voz y habla."""
import asyncio
import json
import queue
import tempfile
import threading
import unicodedata
from pathlib import Path

import config


def normalizar(texto: str) -> str:
    texto = unicodedata.normalize("NFD", texto.lower())
    return "".join(c for c in texto if unicodedata.category(c) != "Mn").strip()


# ---------------------------------------------------------------- HABLAR
class Boca:
    """Voz neural (edge-tts). Si no hay internet cae a la voz del sistema (pyttsx3)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._parar = threading.Event()
        self._pygame = None
        try:
            import pygame
            pygame.mixer.init()
            self._pygame = pygame
        except Exception:
            pass

    def decir(self, texto: str):
        if not texto:
            return
        print(f"\n🔊 {config.NOMBRE}: {texto}\n")
        with self._lock:
            self._parar.clear()
            try:
                self._decir_neural(texto)
            except Exception:
                self._decir_offline(texto)

    def callar(self):
        self._parar.set()
        if self._pygame:
            self._pygame.mixer.music.stop()

    def tanda(self):
        """Para hablar mientras la respuesta todavía se está escribiendo: agregás frases y suenan en orden,
        generando el audio de la siguiente mientras dice la anterior."""
        return Tanda(self)

    def _sintetizar(self, texto) -> Path:
        """Genera el MP3 de la frase (las cortas quedan guardadas para la próxima)."""
        import hashlib
        import uuid
        import edge_tts
        if len(texto) <= 40:   # frases cortas y repetidas ("¿Sí?", "Listo"): se generan una vez y quedan guardadas
            cache = config.DATOS / "voz_cache"
            cache.mkdir(exist_ok=True)
            mp3 = cache / f"{hashlib.md5(f'{config.VOZ}|{config.VELOCIDAD_VOZ}|{texto}'.encode()).hexdigest()}.mp3"
            if mp3.exists():
                return mp3
        else:
            mp3 = Path(tempfile.gettempdir()) / f"{config.NOMBRE.lower()}_voz_{uuid.uuid4().hex[:8]}.mp3"
        com = edge_tts.Communicate(texto, config.VOZ, rate=config.VELOCIDAD_VOZ)
        tmp = mp3.with_suffix(".tmp")
        asyncio.run(com.save(str(tmp)))
        tmp.replace(mp3)   # recién ahora queda guardado, así nunca se cachea un audio a medias
        return mp3

    def _reproducir(self, mp3: Path):
        m = self._pygame.mixer.music
        m.load(str(mp3))
        m.play()
        while m.get_busy() and not self._parar.is_set():
            self._pygame.time.wait(30)
        m.unload()
        if mp3.parent == Path(tempfile.gettempdir()):
            try:
                mp3.unlink()
            except OSError:
                pass

    def _decir_neural(self, texto):
        if not self._pygame:
            raise RuntimeError("sin pygame")
        self._reproducir(self._sintetizar(texto))

    def _decir_offline(self, texto):
        import pyttsx3
        motor = pyttsx3.init()
        for v in motor.getProperty("voices"):
            if "spanish" in v.name.lower() or "es" in str(v.languages).lower():
                motor.setProperty("voice", v.id)
                break
        motor.say(texto)
        motor.runAndWait()


class Tanda:
    """Frases que se van diciendo a medida que llegan. Un hilo genera audio, otro lo reproduce."""

    def __init__(self, boca: Boca):
        self.boca = boca
        self.textos, self.audios = queue.Queue(), queue.Queue()
        self.dijo_algo = False
        self._hilo_voz = None
        boca._parar.clear()               # habla nueva: lo que se haya cortado antes no cuenta
        threading.Thread(target=self._generar, daemon=True).start()

    def agregar(self, frase: str):
        frase = frase.strip()
        if not frase:
            return
        self.dijo_algo = True
        self.textos.put(frase)
        if self._hilo_voz is None:       # con la primera frase arranca a hablar
            self._hilo_voz = threading.Thread(target=self._hablar, daemon=True)
            self._hilo_voz.start()

    def terminar(self, esperar=True):
        """No llegan más frases. Si `esperar`, bloquea hasta que termine de hablar."""
        self.textos.put(None)
        if esperar and self._hilo_voz is not None:
            self._hilo_voz.join()

    def _generar(self):
        neural = self.boca._pygame is not None
        while True:
            t = self.textos.get()
            if t is None:
                self.audios.put(None)
                return
            if self.boca._parar.is_set():
                continue
            try:
                self.audios.put(("mp3", self.boca._sintetizar(t)) if neural else ("texto", t))
            except Exception:
                neural = False                      # sin internet: el resto con la voz del sistema
                self.audios.put(("texto", t))

    def _hablar(self):
        with self.boca._lock:
            self.boca._parar.clear()
            while True:
                item = self.audios.get()
                if item is None:
                    return
                if self.boca._parar.is_set():       # la interrumpiste: descarta lo que falta
                    continue
                tipo, dato = item
                try:
                    if tipo == "mp3":
                        self.boca._reproducir(dato)
                    else:
                        self.boca._decir_offline(dato)
                except Exception:
                    pass


# ---------------------------------------------------------------- ESCUCHAR
class Oido:
    """Escucha permanente offline con Vosk buscando el nombre del asistente.
    Después de la activación graba el pedido y lo transcribe (Google; si falla, Vosk)."""

    TASA = 16000

    def __init__(self):
        import sounddevice  # noqa: F401  (falla temprano si no hay audio)
        from vosk import KaldiRecognizer, Model, SetLogLevel
        SetLogLevel(-1)
        if not Path(config.VOSK_MODELO).exists():
            raise FileNotFoundError(
                f"No encuentro el modelo Vosk en {config.VOSK_MODELO}. Corré: python instalar_modelo.py")
        self.modelo = Model(str(config.VOSK_MODELO))
        self.KaldiRecognizer = KaldiRecognizer

    def _gramatica(self):
        # Gramática cerrada: solo reconoce el nombre (o "ruido"). Es liviano y casi no da falsos positivos.
        # Se arma cada vez para tomar el nombre nuevo si lo cambiás en los ajustes.
        wake = normalizar(config.NOMBRE)
        variantes = {wake, f"oye {wake}", f"hola {wake}", f"che {wake}"}
        return wake, json.dumps(sorted(variantes) + ["[unk]"])

    def _stream(self, cola):
        import sounddevice as sd

        def cb(indata, frames, t, status):
            cola.put(bytes(indata))
        return sd.RawInputStream(samplerate=self.TASA, blocksize=4000, dtype="int16",
                                 channels=1, callback=cb)

    def esperar_activacion(self, despertar=None, detener=None):
        """Bloquea hasta escuchar el nombre (True). También vuelve si tocan la estrella
        (evento `despertar`, True) o si apagan el asistente (evento `detener`, False)."""
        wake, gramatica = self._gramatica()
        variantes = set(json.loads(gramatica)) - {"[unk]"}
        rec = self.KaldiRecognizer(self.modelo, self.TASA, gramatica)
        rec.SetWords(True)      # incluye la seguridad de cada palabra
        cola = queue.Queue()
        with self._stream(cola):
            while True:
                if detener is not None and detener.is_set():
                    return False
                if despertar is not None and despertar.is_set():
                    despertar.clear()
                    return True
                try:
                    datos = cola.get(timeout=0.2)
                except queue.Empty:
                    continue
                if not rec.AcceptWaveform(datos):
                    continue
                # Solo frases terminadas, que sean exactamente el nombre y con buena seguridad:
                # así no se activa con ruido ni con palabras parecidas en medio de una charla.
                r = json.loads(rec.Result())
                if r.get("text", "").strip() not in variantes:
                    continue
                seguridad = min((w.get("conf", 0) for w in r.get("result", []) if w.get("word") == wake), default=0)
                if seguridad >= config.SEGURIDAD_ACTIVACION:
                    return True

    def escuchar_pedido(self, max_seg=None, al_nivel=None) -> str:
        """Graba hasta que hay silencio y devuelve el texto dicho. `al_nivel(0..1)` recibe el volumen de tu voz."""
        import numpy as np
        max_seg = max_seg or config.SEGUNDOS_ESCUCHA
        cola = queue.Queue()
        bloques, silencio, hablo = [], 0, False
        umbral = 450              # energía mínima para considerar que hay voz
        bloques_max = int(max_seg * self.TASA / 4000)
        with self._stream(cola):
            for _ in range(bloques_max):
                b = cola.get()
                bloques.append(b)
                energia = np.abs(np.frombuffer(b, dtype=np.int16)).mean()
                if al_nivel:
                    al_nivel(min(1.0, float(energia) / 2500))
                if energia > umbral:
                    hablo, silencio = True, 0
                else:
                    silencio += 1
                if hablo and silencio >= 3:          # ~0,75 s de silencio después de hablar
                    break
                if not hablo and len(bloques) >= 16:   # 4 s sin que digas nada
                    return ""
        audio = b"".join(bloques)
        return self._transcribir(audio)

    def _transcribir(self, audio: bytes) -> str:
        try:
            import speech_recognition as sr
            datos = sr.AudioData(audio, self.TASA, 2)
            return sr.Recognizer().recognize_google(datos, language=config.IDIOMA_STT)
        except Exception:
            rec = self.KaldiRecognizer(self.modelo, self.TASA)
            rec.AcceptWaveform(audio)
            return json.loads(rec.FinalResult()).get("text", "")
