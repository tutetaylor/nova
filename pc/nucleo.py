"""El núcleo del asistente: escucha, piensa y habla en segundo plano, y avisa de cada cambio.

Lo usan la ventana (app.py) y el modo consola (main.py). Los avisos van a `emitir(tipo, datos)`:
  estado   {"estado": "apagado|preparando|espera|escucha|pensando|hablando", "detalle": "..."}
  mensaje  {"deVos": bool, "texto": "..."}
  parcial  {"texto": "..."}
  nivel    {"nivel": 0..1}
"""
import random
import threading
import traceback

import config
import herramientas
import rapidos
from voz import Boca, normalizar

PARA_CORTAR = {"nada", "chau", "gracias", "listo", "eso es todo", "nada mas", "cancelar"}
PARA_OLVIDAR = {"olvida todo", "nueva conversacion", "empecemos de nuevo"}
PARA_CHARLAR = {"charlemos", "hablemos", "modo charla", "quiero charlar", "charlemos un rato", "hablemos un rato",
                "quiero hablar", "quiero hablar con vos", "charlemos un poco", "hablemos un poco"}
PARA_TERMINAR_CHARLA = PARA_CORTAR | {"terminamos", "termina la charla", "basta", "fin de la charla", "nos vemos",
                                      "hasta luego", "me voy", "despues seguimos"}
APERTURAS = ["¡Dale, charlemos! ¿Cómo venís hoy, {u}?", "¡Me encanta! Contame, ¿qué estuviste haciendo hoy?",
             "¡De una! ¿De qué tenés ganas de hablar, {u}?", "Acá estoy. ¿Cómo te trató el día?"]

HERRAMIENTAS_LEGIBLES = {
    "clima": "Mirando el clima…", "buscar_info": "Buscando en internet…", "abrir_web": "Abriendo el navegador…",
    "abrir_programa": "Abriendo el programa…", "abrir_carpeta": "Abriendo la carpeta…",
    "recordatorio": "Agendando el recordatorio…", "anotar": "Anotando…", "captura_pantalla": "Sacando la captura…",
    "escribir": "Escribiendo…", "atajo": "Usando el teclado…",
}


class Nucleo:
    def __init__(self, emitir=None):
        self.emitir = emitir or (lambda tipo, datos: None)
        self.boca = Boca()
        self.cerebro = None
        self.oido = None
        self.estado = "apagado"
        self.detalle = ""
        self.conversacion = []
        self._lock = threading.Lock()          # un pedido a la vez
        self._despertar = threading.Event()    # tocaron la estrella
        self._detener = threading.Event()      # apagaron el asistente
        self._hilo = None
        self._por_toque = False                # si se activó tocando la estrella, no hace falta decir "¿Sí?"
        self.charla = False                    # modo charla: conversación continua, sin decir el nombre
        self._abrir_charla = False             # al entrar tocando el botón, Nova saluda primero
        herramientas.avisar = self._recordatorio
        herramientas.iniciar_recordatorios()
        self._preparar_cerebro()

    # ------------------------------------------------------------ AVISOS
    def _estado(self, estado, detalle=""):
        self.estado, self.detalle = estado, detalle
        self.emitir("estado", {"estado": estado, "detalle": detalle})

    def _mensaje(self, de_vos, texto):
        self.conversacion.append({"deVos": de_vos, "texto": texto})
        del self.conversacion[:-40]
        self.emitir("mensaje", {"deVos": de_vos, "texto": texto})

    def _charla(self, activa):
        self.charla = activa
        if self.cerebro:
            self.cerebro.charla = activa
        self.emitir("charla", {"activa": activa})

    def alternar_charla(self):
        """Botón «Charlar» de la ventana."""
        if self.charla:
            self._charla(False)
            self.boca.callar()
            return
        self._charla(True)
        self._abrir_charla = True
        self.hablar_ahora()

    def _apertura(self):
        return random.choice(APERTURAS).format(u=config.USUARIO or "")

    def _recordatorio(self, texto):
        self._mensaje(False, texto)
        self.boca.decir(texto)

    # ------------------------------------------------------------ CEREBRO
    def _preparar_cerebro(self):
        if not config.GEMINI_API_KEY:
            self.cerebro = None
            return
        from cerebro import Cerebro
        self.cerebro = Cerebro()

    def configuracion_lista(self):
        return bool(config.GEMINI_API_KEY)

    def aplicar_ajustes(self, cambios):
        config.guardar(cambios)
        if self.cerebro is None:
            self._preparar_cerebro()
        elif self.cerebro:
            self.cerebro.limite.maximo = config.LIMITE_POR_HORA
            self.cerebro.modelos = [config.MODELO] + [m for m in config.MODELOS_RESPALDO if m != config.MODELO]
        if self.estado == "espera":
            self._estado("espera")      # para que la ventana muestre el nombre nuevo

    # ------------------------------------------------------------ PEDIDOS
    def atender(self, pedido):
        """Procesa un pedido. Devuelve True si conviene seguir escuchando sin decir el nombre."""
        with self._lock:
            self._mensaje(True, pedido)
            self.emitir("parcial", {"texto": ""})
            p = normalizar(pedido).strip(" .!?¿¡,")
            if self.charla and p in PARA_TERMINAR_CHARLA:
                self._charla(False)
                return self._responder("¡Qué linda charla! Cuando quieras seguimos.")
            if p in PARA_CHARLAR:
                self._charla(True)
                self._responder(self._apertura())
                return True
            if p in PARA_CORTAR:
                return self._responder("Dale, cualquier cosa me llamás.")
            if p in PARA_OLVIDAR:
                if self.cerebro:
                    self.cerebro.olvidar()
                return self._responder("Listo, empezamos de cero.")
            if "cuanto uso" in p or "limite de uso" in p:
                return self._responder(self.cerebro.limite.resumen() if self.cerebro else "Todavía no cargaste la clave.")
            try:
                rapido = rapidos.intentar(pedido)
            except Exception as e:
                print("Atajo falló:", e)
                rapido = None
            if rapido is not None:
                self._responder(rapido or "Listo.", hablar=bool(rapido))
                return self.charla
            if not self.cerebro:
                return self._responder("Me falta la clave gratis de Gemini. Cargala en los ajustes, el engranaje de arriba.")
            self._estado("pensando", "Pensando…")
            original = herramientas.ejecutar

            def con_aviso(nombre, entrada):
                self._estado("pensando", HERRAMIENTAS_LEGIBLES.get(nombre, "Trabajando…"))
                return original(nombre, entrada)
            herramientas.ejecutar = con_aviso
            try:
                respuesta = self.cerebro.pensar(pedido)
            except Exception as e:
                traceback.print_exc()
                respuesta = f"Uy, tuve un problema: {str(e)[:80]}. ¿Probamos de nuevo?"
            finally:
                herramientas.ejecutar = original
            seguir = self.charla or (config.SEGUIR_CHARLANDO and respuesta.rstrip().endswith("?"))
            self._responder(respuesta)
            return seguir

    def _responder(self, texto, hablar=True):
        self._mensaje(False, texto)
        if hablar:
            self._estado("hablando")
            self.boca.decir(texto)
        self._estado("espera" if self.activo else "apagado")
        return False

    def enviar_texto(self, texto):
        """Pedido escrito desde la ventana. No bloquea."""
        def correr():
            seguir = self.atender(texto)
            if seguir and self.charla and self.activo:   # escribiste «charlemos»: sigue por voz
                self._por_toque = True
                self._despertar.set()
        threading.Thread(target=correr, daemon=True).start()

    # ------------------------------------------------------------ VOZ
    @property
    def activo(self):
        return self._hilo is not None and self._hilo.is_alive() and not self._detener.is_set()

    def encender(self):
        if self.activo:
            return
        self._detener.clear()
        self._hilo = threading.Thread(target=self._bucle_voz, daemon=True)
        self._hilo.start()

    def apagar(self):
        self._detener.set()
        self.boca.callar()
        self._estado("apagado")

    def hablar_ahora(self):
        """Tocaron la estrella: si está hablando la interrumpe; si está esperando, escucha ya."""
        if not self.activo:
            self.encender()
        self.boca.callar()
        self._por_toque = True
        self._despertar.set()

    def _bucle_voz(self):
        try:
            if self.oido is None:
                from instalar_modelo import asegurar_modelo
                from voz import Oido
                self._estado("preparando", "Preparando la escucha…")
                asegurar_modelo(lambda pct: self._estado("preparando", f"Bajando la voz offline · {pct}%"))
                self.oido = Oido()
        except Exception as e:
            traceback.print_exc()
            self._estado("apagado", f"No pude usar el micrófono: {str(e)[:90]}")
            self._detener.set()
            return

        while not self._detener.is_set():
            self._estado("espera")
            if not self.oido.esperar_activacion(self._despertar, self._detener):
                break
            if self._abrir_charla:            # tocaron «Charlar»: Nova arranca la conversación
                self._abrir_charla = False
                self._responder(self._apertura())
            elif not self._por_toque:
                self.boca.decir("¿Sí?")      # queda guardado, sale al instante
            self._por_toque = False
            seguir, silencios = True, 0
            while seguir and not self._detener.is_set():
                self._estado("escucha")
                pedido = self.oido.escuchar_pedido(al_nivel=lambda n: self.emitir("nivel", {"nivel": n}))
                if not pedido:
                    if not self.charla:
                        break
                    silencios += 1               # en modo charla te espera un poco antes de irse
                    if silencios >= 3:
                        self._charla(False)
                        self._responder("Te dejo tranquilo. Cuando quieras seguimos charlando.")
                        break
                    continue
                silencios = 0
                seguir = self.atender(pedido)
        self._estado("apagado")
