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
RUTINA_BUEN_DIA = ("Rutina de buen día: saludame según la hora, contame el clima de hoy, mis recordatorios de hoy y si tengo "
                   "algo en la lista de pendientes, y cerrá con una frase linda. Todo en 4 a 6 oraciones, natural, como un amigo. "
                   "Usá clima, ver_recordatorios y lista_ver.")
PARA_BUEN_DIA = {"buen dia", "buenos dias", "buen dia nova", "arranquemos el dia", "arrancamos el dia",
                 "como arranca el dia", "que tengo hoy", "resumen del dia"}
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
        herramientas.al_modo_estudio = self._modo_estudio
        herramientas.mostrar_imagen = self._mostrar_imagen
        herramientas.anunciar = self._anunciar
        self.vinculo = None
        self._conectar_celular()
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
            if not activa:
                self.cerebro.estudio = None
        self.emitir("charla", {"activa": activa})

    def _modo_estudio(self, tema):
        if self.cerebro:
            self.cerebro.estudio = tema
        self._charla(True)

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

    def _mostrar_imagen(self, datos, tipo):
        import base64
        self.emitir("imagen", {"src": f"data:{tipo};base64,{base64.b64encode(datos).decode()}"})

    def _anunciar(self, texto):
        """Algo que terminó en segundo plano (un video): lo dice si no está ocupada."""
        self._mensaje(False, texto)
        if not self._lock.locked():
            self.boca.decir(texto)

    def _recordatorio(self, texto):
        if texto.startswith("@"):          # rutina de buen día programada
            threading.Thread(target=self.atender, args=(RUTINA_BUEN_DIA,), daemon=True).start()
            return
        if texto.startswith("!"):          # aviso que hace algo (no solo avisa)
            threading.Thread(target=self.atender, args=(f"(Aviso programado por el usuario: hacelo directamente.) {texto[1:]}",),
                             daemon=True).start()
            return
        aviso = f"Te aviso: {texto}"
        self._mensaje(False, aviso)
        self.boca.decir(aviso)

    # ------------------------------------------------------------ CEREBRO
    def _preparar_cerebro(self):
        if not config.GEMINI_API_KEY:
            self.cerebro = None
            return
        from cerebro import Cerebro
        self.cerebro = Cerebro()

    # ------------------------------------------------------------ CELULAR
    def _conectar_celular(self):
        from vinculo import Vinculo, codigo_nuevo
        if not config.VINCULO:
            config.guardar({"VINCULO": codigo_nuevo()})      # la primera vez se crea el código
        if self.vinculo:
            self.vinculo.detener()
        self.vinculo = Vinculo(config.VINCULO, self._orden_del_celular)
        self.vinculo.iniciar()
        herramientas.vinculo = self.vinculo

    def _orden_del_celular(self, m):
        """El celular le pide algo a la PC: lo hace y le responde (sin hablar: vos no estás acá)."""
        if m.get("tipo") != "orden" or not self.cerebro:
            return
        texto = m.get("texto", "")
        self._mensaje(True, f"Desde el celular: {texto}")
        with self._lock:
            try:
                respuesta = self.cerebro.pensar(f"(Orden que te mandó {config.USUARIO} desde su celular; no está frente a la PC, "
                                                f"así que respondé con el resultado concreto.) {texto}")
            except Exception as e:
                respuesta = f"No pude hacerlo: {str(e)[:60]}"
        self._mensaje(False, respuesta)
        try:
            self.vinculo.enviar("respuesta", respuesta, m.get("id"))
        except Exception:
            pass

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
            self._mensaje(True, "Rutina de buen día" if pedido == RUTINA_BUEN_DIA else pedido)
            self.emitir("parcial", {"texto": ""})
            if normalizar(pedido).strip(" .!?¿¡,") in PARA_BUEN_DIA:
                pedido = RUTINA_BUEN_DIA
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
            import libreta
            rutina = libreta.rutina_de_voz(pedido)
            if rutina and self.cerebro:          # rutina por voz: Nova hace lo que le enseñaste
                pedido = (f"(Rutina «{rutina['frase']}» que el usuario creó: hacelo directamente, sin preguntar, "
                          f"salvo pagar o borrar.) {rutina['hacer']}. Al terminar, confirmá en una oración corta.")
            try:
                rapido = None if rutina else rapidos.intentar(pedido)
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
            tanda = self.boca.tanda()      # empieza a hablar con la primera frase, sin esperar el resto

            def al_texto(frase):
                if not tanda.dijo_algo:
                    self._estado("hablando")
                tanda.agregar(frase)
            try:
                respuesta = self.cerebro.pensar(pedido, al_texto=al_texto)
            except Exception as e:
                traceback.print_exc()
                respuesta = f"Uy, tuve un problema: {str(e)[:80]}. ¿Probamos de nuevo?"
            finally:
                herramientas.ejecutar = original
            seguir = self.charla or (config.SEGUIR_CHARLANDO and respuesta.rstrip().endswith("?"))
            libre = getattr(self.cerebro, "ultimo_libre", None)
            self._mensaje(False, respuesta + (f"\n\n— respondió el cerebro libre ({libre})" if libre else ""))
            if not tanda.dijo_algo:
                self._estado("hablando")
                tanda.agregar(respuesta)
            tanda.terminar()
            self._estado("espera" if self.activo else "apagado")
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
            if self.cerebro:
                self.cerebro.calentar()      # mientras hablás, ya abre la conexión con Google
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
