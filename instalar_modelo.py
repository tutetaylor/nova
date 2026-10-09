"""Descarga el modelo de voz offline en español (~40 MB) para la palabra de activación."""
import io
import urllib.request
import zipfile

import config

URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

if config.VOSK_MODELO.exists():
    print("El modelo ya está instalado en", config.VOSK_MODELO)
else:
    print("Descargando modelo de voz en español...")
    datos = urllib.request.urlopen(URL).read()
    config.VOSK_MODELO.parent.mkdir(parents=True, exist_ok=True)
    zipfile.ZipFile(io.BytesIO(datos)).extractall(config.VOSK_MODELO.parent)
    print("Listo:", config.VOSK_MODELO)
