"""Descarga el modelo de voz offline en español (~40 MB) para la palabra de activación."""
import io
import urllib.request
import zipfile

import config

URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"


def asegurar_modelo(progreso=None):
    """`progreso(porcentaje)` se llama mientras descarga (para mostrarlo en la ventana)."""
    if config.VOSK_MODELO.exists():
        return
    print("Descargando el modelo de voz en español (solo la primera vez, ~40 MB)...")
    with urllib.request.urlopen(URL) as r:
        total = int(r.headers.get("Content-Length") or 45_000_000)
        datos, leidos, ultimo = bytearray(), 0, -1
        while True:
            trozo = r.read(256 * 1024)
            if not trozo:
                break
            datos += trozo
            leidos += len(trozo)
            pct = min(99, leidos * 100 // total)
            if progreso and pct != ultimo:
                ultimo = pct
                progreso(pct)
    config.VOSK_MODELO.parent.mkdir(parents=True, exist_ok=True)
    zipfile.ZipFile(io.BytesIO(bytes(datos))).extractall(config.VOSK_MODELO.parent)
    if progreso:
        progreso(100)
    print("Modelo instalado en", config.VOSK_MODELO)


if __name__ == "__main__":
    asegurar_modelo()
