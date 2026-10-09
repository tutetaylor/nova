"""Configuración del asistente. Todo se puede cambiar desde el archivo .env."""
import os
from pathlib import Path

from dotenv import load_dotenv

BASE = Path(__file__).resolve().parent
load_dotenv(BASE / ".env")

# --- Identidad ---
NOMBRE = os.getenv("NOMBRE", "Nova")                 # palabra de activación y nombre del asistente
USUARIO = os.getenv("USUARIO", "Mateo")
CIUDAD = os.getenv("CIUDAD", "Buenos Aires")

# --- Cerebro (Claude) ---
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")
MODELO = os.getenv("MODELO", "claude-sonnet-5-5")
MAX_HISTORIAL = int(os.getenv("MAX_HISTORIAL", "20"))  # mensajes que recuerda dentro de la charla

# --- Voz ---
VOZ = os.getenv("VOZ", "es-AR-ElenaNeural")          # otras: es-AR-TomasNeural, es-ES-AlvaroNeural
VELOCIDAD_VOZ = os.getenv("VELOCIDAD_VOZ", "+8%")
IDIOMA_STT = os.getenv("IDIOMA_STT", "es-AR")

# --- Palabra de activación (offline con Vosk) ---
VOSK_MODELO = Path(os.getenv("VOSK_MODELO", BASE / "modelos" / "vosk-model-small-es-0.42"))
SEGUNDOS_ESCUCHA = float(os.getenv("SEGUNDOS_ESCUCHA", "8"))     # máximo para dictar un pedido
SEGUIR_CHARLANDO = os.getenv("SEGUIR_CHARLANDO", "1") == "1"      # si pregunta algo, escucha sin wake word

# --- Datos locales ---
DATOS = BASE / "datos"
DATOS.mkdir(exist_ok=True)
ARCHIVO_NOTAS = DATOS / "notas.md"
ARCHIVO_RECORDATORIOS = DATOS / "recordatorios.json"
CARPETA_CAPTURAS = Path(os.getenv("CARPETA_CAPTURAS", Path.home() / "Pictures" / NOMBRE))
