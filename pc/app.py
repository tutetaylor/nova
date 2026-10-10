"""La ventana de Nova para PC: la estrella, la conversación y los ajustes."""
import json
import sys
import threading
import webbrowser
from pathlib import Path

import config
from nucleo import Nucleo


def carpeta_interfaz() -> Path:
    # Dentro del .exe los archivos quedan en una carpeta temporal (sys._MEIPASS)
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / "interfaz"


class Puente:
    """Lo que la ventana (JavaScript) puede pedirle a Python. Cada método es window.pywebview.api.<nombre>()."""

    def __init__(self, app):
        self._app = app

    def iniciar(self):
        n = self._app.nucleo
        usadas = 0
        if n.cerebro:
            n.cerebro.limite._limpiar()
            usadas = len(n.cerebro.limite.marcas)
        return {
            "nombre": config.NOMBRE, "usuario": config.USUARIO, "ciudad": config.CIUDAD, "voz": config.VOZ,
            "clave": config.GEMINI_API_KEY, "limite": config.LIMITE_POR_HORA, "modelo": config.MODELO,
            "usadas": usadas, "vozOffline": Path(config.VOSK_MODELO).exists(),
            "estado": n.estado, "detalle": n.detalle, "conversacion": n.conversacion,
            "charla": n.charla, "confirmar": config.CONFIRMAR,
            "personalidad": config.PERSONALIDAD, "personalidadPropia": config.PERSONALIDAD_PROPIA,
            "vinculo": config.VINCULO, "repo": config.REPO, "token": config.TOKEN_GITHUB,
            "version": __import__("actualizar").version_actual(),
        }

    def enviar(self, texto):
        self._app.nucleo.enviar_texto(str(texto)[:2000])

    def charlar(self):
        self._app.nucleo.alternar_charla()

    def hablar(self):
        self._app.nucleo.hablar_ahora()

    def encender(self, activo):
        if activo:
            self._app.nucleo.encender()
        else:
            self._app.nucleo.apagar()

    def guardar_ajustes(self, cambios):
        self._app.nucleo.aplicar_ajustes(dict(cambios))
        return self.iniciar()

    def buscar_actualizacion(self):
        import actualizar
        url, texto = actualizar.revisar()
        return {"hay": bool(url), "texto": texto}

    def instalar_actualizacion(self):
        import actualizar
        url, texto = actualizar.revisar()
        if not url:
            return texto
        r = actualizar.descargar_e_instalar(url)
        if r == "SALIR":
            self._app.ventana.destroy()
            return "Actualizando…"
        return r

    def nuevo_codigo_vinculo(self):
        from vinculo import codigo_nuevo
        config.guardar({"VINCULO": codigo_nuevo()})
        self._app.nucleo._conectar_celular()
        return config.VINCULO

    def abrir_link(self, url):
        if str(url).startswith("https://"):
            webbrowser.open(url)


class App:
    def __init__(self):
        import webview
        self.webview = webview
        self.ventana = None
        self._lista = threading.Event()
        self.nucleo = Nucleo(emitir=self.emitir)
        self.ventana = webview.create_window(
            config.NOMBRE, url=str(carpeta_interfaz() / "index.html"), js_api=Puente(self),
            width=1100, height=720, min_size=(420, 600), background_color="#121033")
        self.ventana.events.loaded += self._al_cargar

    def _al_cargar(self):
        self._lista.set()
        if config.GEMINI_API_KEY:
            self.nucleo.encender()        # con la clave cargada, arranca escuchando
        threading.Thread(target=self._revisar_actualizacion, daemon=True).start()

    def _revisar_actualizacion(self):
        """Al abrir, se fija si hay versión nueva (solo si cargaste repo y token) y lo avisa en la ventana."""
        import actualizar
        url, texto = actualizar.revisar()
        if url:
            self.emitir("actualizacion", {"texto": texto})

    def emitir(self, tipo, datos):
        """Avisa a la ventana lo que pasa (estado, mensajes, volumen de tu voz)."""
        if not self.ventana or not self._lista.is_set():
            return
        try:
            self.ventana.evaluate_js(f"window.nova && window.nova.evento({json.dumps(tipo)}, {json.dumps(datos)})")
        except Exception:
            pass

    def correr(self):
        self.webview.start(debug="--debug" in sys.argv)


def main():
    App().correr()


if __name__ == "__main__":
    main()
