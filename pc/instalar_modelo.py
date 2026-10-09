"""Descarga el modelo de voz offline en español (~40 MB) para la palabra de activación."""
import io
import urllib.request
import zipfile

import config

URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"


def asegurar_modelo():
    if config.VOSK_MODELO.exists():
        return
    print("Descargando el modelo de voz en español (solo la primera vez, ~40 MB)...")
    datos = urllib.request.urlopen(URL).read()
    config.VOSK_MODELO.parent.mkdir(parents=True, exist_ok=True)
    zipfile.ZipFile(io.BytesIO(datos)).extractall(config.VOSK_MODELO.parent)
    print("Modelo instalado en", config.VOSK_MODELO)


if __name__ == "__main__":
    asegurar_modelo()
