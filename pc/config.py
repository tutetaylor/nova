"""Configuración del asistente. Todo se puede cambiar desde el archivo .env."""
import os
import sys
from pathlib import Path

from dotenv import load_dotenv

# Como script: todo vive en la carpeta pc/. Como Nova.exe: en C:\Users\<vos>\Nova
EMPAQUETADO = getattr(sys, "frozen", False)
BASE = Path.home() / "Nova" if EMPAQUETADO else Path(__file__).resolve().parent
BASE.mkdir(parents=True, exist_ok=True)
ARCHIVO_ENV = BASE / ".env"


def _primer_inicio():
    """La primera vez pregunta lo básico y crea el .env, así el .exe funciona sin tocar archivos."""
    print("=" * 60 + "\n  Bienvenido a Nova. Configuración inicial (una sola vez)\n" + "=" * 60)
    clave = ""
    while not clave.startswith("sk-ant-"):
        clave = input("Pegá tu clave de la API de Anthropic (sk-ant-...): ").strip()
    nombre = input("¿Cómo querés que se llame tu asistente? [Nova]: ").strip() or "Nova"
    usuario = input("¿Cómo te llamás? [Mateo]: ").strip() or "Mateo"
    ciudad = input("¿En qué ciudad estás? [Buenos Aires]: ").strip() or "Buenos Aires"
    ARCHIVO_ENV.write_text(
        f"ANTHROPIC_API_KEY={clave}\nNOMBRE={nombre}\nUSUARIO={usuario}\nCIUDAD={ciudad}\n"
        "VOZ=es-AR-ElenaNeural\nVELOCIDAD_VOZ=+8%\nMODELO=claude-sonnet-5-5\nSEGUIR_CHARLANDO=1\n",
        encoding="utf-8")
    print(f"\nListo. Podés cambiar todo después en {ARCHIVO_ENV}\n")


if not ARCHIVO_ENV.exists() and not os.getenv("ANTHROPIC_API_KEY") and sys.stdin and sys.stdin.isatty():
    _primer_inicio()
load_dotenv(ARCHIVO_ENV)

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
