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

    def _decir_neural(self, texto):
        import hashlib
        import edge_tts
        if not self._pygame:
            raise RuntimeError("sin pygame")
        if len(texto) <= 40:   # frases cortas y repetidas ("¿Sí?", "Listo"): se generan una vez y quedan guardadas
            cache = config.DATOS / "voz_cache"
            cache.mkdir(exist_ok=True)
            clave = hashlib.md5(f"{config.VOZ}|{config.VELOCIDAD_VOZ}|{texto}".encode()).hexdigest()
            mp3 = cache / f"{clave}.mp3"
        else:
            mp3 = Path(tempfile.gettempdir()) / f"{config.NOMBRE.lower()}_voz.mp3"
        if not mp3.exists() or mp3.parent == Path(tempfile.gettempdir()):
            com = edge_tts.Communicate(texto, config.VOZ, rate=config.VELOCIDAD_VOZ)
            tmp = mp3.with_suffix(".tmp")
            asyncio.run(com.save(str(tmp)))
            tmp.replace(mp3)   # recién ahora queda guardado, así nunca se cachea un audio a medias
        m = self._pygame.mixer.music
        m.load(str(mp3))
        m.play()
        while m.get_busy() and not self._parar.is_set():
            self._pygame.time.wait(50)
        m.unload()

    def _decir_offline(self, texto):
        import pyttsx3
        motor = pyttsx3.init()
        for v in motor.getProperty("voices"):
            if "spanish" in v.name.lower() or "es" in str(v.languages).lower():
                motor.setProperty("voice", v.id)
                break
        motor.say(texto)
        motor.runAndWait()


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
        self.wake = normalizar(config.NOMBRE)
        # Gramática cerrada: solo reconoce el nombre (o "ruido"). Es liviano y casi no da falsos positivos.
        variantes = {self.wake, f"oye {self.wake}", f"hola {self.wake}", f"che {self.wake}"}
        self.gramatica = json.dumps(sorted(variantes) + ["[unk]"])

    def _stream(self, cola):
        import sounddevice as sd

        def cb(indata, frames, t, status):
            cola.put(bytes(indata))
        return sd.RawInputStream(samplerate=self.TASA, blocksize=4000, dtype="int16",
                                 channels=1, callback=cb)

    def esperar_activacion(self):
        """Bloquea hasta escuchar el nombre. Devuelve True."""
        rec = self.KaldiRecognizer(self.modelo, self.TASA, self.gramatica)
        cola = queue.Queue()
        with self._stream(cola):
            while True:
                datos = cola.get()
                if rec.AcceptWaveform(datos):
                    texto = json.loads(rec.Result()).get("text", "")
                else:
                    texto = json.loads(rec.PartialResult()).get("partial", "")
                if self.wake in normalizar(texto):
                    return True

    def escuchar_pedido(self, max_seg=None) -> str:
        """Graba hasta que hay silencio y devuelve el texto dicho."""
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
                if energia > umbral:
                    hablo, silencio = True, 0
                else:
                    silencio += 1
                if hablo and silencio >= 4:          # ~1 s de silencio después de hablar
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
