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
    print("Necesitás una clave GRATIS de Google: entrá a https://aistudio.google.com/apikey y tocá 'Create API key'.")
    clave = ""
    while len(clave) < 20:
        clave = input("Pegá tu clave de Gemini (empieza con AIza...): ").strip()
    nombre = input("¿Cómo querés que se llame tu asistente? [Nova]: ").strip() or "Nova"
    usuario = input("¿Cómo te llamás? [Mateo]: ").strip() or "Mateo"
    ciudad = input("¿En qué ciudad estás? [Buenos Aires]: ").strip() or "Buenos Aires"
    ARCHIVO_ENV.write_text(
        f"GEMINI_API_KEY={clave}\nNOMBRE={nombre}\nUSUARIO={usuario}\nCIUDAD={ciudad}\n"
        "VOZ=es-AR-ElenaNeural\nVELOCIDAD_VOZ=+8%\nMODELO=gemini-3.8-flash\nSEGUIR_CHARLANDO=1\n"
        "LIMITE_POR_HORA=0\n",
        encoding="utf-8")
    print(f"\nListo. Podés cambiar todo después en {ARCHIVO_ENV}\n")


# Solo en modo consola: con ventana, la configuración inicial se hace desde los ajustes de la ventana
MODO_CONSOLA = "--texto" in sys.argv or "--consola" in sys.argv
if (MODO_CONSOLA and not ARCHIVO_ENV.exists() and not os.getenv("GEMINI_API_KEY")
        and sys.stdin and sys.stdin.isatty()):
    _primer_inicio()
load_dotenv(ARCHIVO_ENV)

# --- Identidad ---
NOMBRE = os.getenv("NOMBRE", "Nova")                 # palabra de activación y nombre del asistente
USUARIO = os.getenv("USUARIO", "Mateo")
CIUDAD = os.getenv("CIUDAD", "Buenos Aires")

# --- Cerebro (Gemini, capa gratuita) ---
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
MODELO = os.getenv("MODELO", "gemini-3.8-flash")
# Si el modelo principal se queda sin cuota gratis, prueba estos en orden
MODELOS_RESPALDO = [m.strip() for m in os.getenv(
    "MODELOS_RESPALDO", "gemini-3.5-flash,gemini-3.5-flash-lite,gemini-3.1-flash-lite").split(",") if m.strip()]
LIMITE_POR_HORA = int(os.getenv("LIMITE_POR_HORA", "0"))  # consultas por hora; 0 = sin límite propio
MAX_HISTORIAL = int(os.getenv("MAX_HISTORIAL", "20"))  # mensajes que recuerda dentro de la charla

# --- Voz ---
VOZ = os.getenv("VOZ", "es-AR-ElenaNeural")          # otras: es-AR-TomasNeural, es-ES-AlvaroNeural
VELOCIDAD_VOZ = os.getenv("VELOCIDAD_VOZ", "+8%")
IDIOMA_STT = os.getenv("IDIOMA_STT", "es-AR")

# --- Palabra de activación (offline con Vosk) ---
VOSK_MODELO = Path(os.getenv("VOSK_MODELO", BASE / "modelos" / "vosk-model-small-es-0.42"))
SEGUNDOS_ESCUCHA = float(os.getenv("SEGUNDOS_ESCUCHA", "8"))     # máximo para dictar un pedido
# --- Conexiones ---
VINCULO = os.getenv("VINCULO", "")                 # código para vincular con el celular (se crea solo)
REPO = os.getenv("REPO", "")                       # usuario/repo de GitHub, para actualizarse sola
TOKEN_GITHUB = os.getenv("TOKEN_GITHUB", "")       # token de solo lectura del repo
POLLINATIONS_KEY = os.getenv("POLLINATIONS_KEY", "")  # opcional: imágenes y videos (enter.pollinations.ai)
# Cerebro libre: otro modelo que responde cuando Gemini se niega (o siempre en modo charla)
LIBRE_PROVEEDOR = os.getenv("LIBRE_PROVEEDOR", "")    # "" (apagado) | pollinations | openrouter
LIBRE_CLAVE = os.getenv("LIBRE_CLAVE", "")            # clave de OpenRouter (Pollinations usa POLLINATIONS_KEY)
LIBRE_MODELO = os.getenv("LIBRE_MODELO", "")          # vacío = el recomendado del proveedor
LIBRE_EN_CHARLA = os.getenv("LIBRE_EN_CHARLA", "0") == "1"

PERSONALIDAD = os.getenv("PERSONALIDAD", "calida")          # calida | graciosa | seria | motivadora | sarcastica | propia
PERSONALIDAD_PROPIA = os.getenv("PERSONALIDAD_PROPIA", "")
CONFIRMAR = os.getenv("CONFIRMAR", "1") == "1"   # pedir permiso antes de mandar mensajes, publicar, apagar
SEGURIDAD_ACTIVACION = float(os.getenv("SEGURIDAD_ACTIVACION", "0.85"))  # más alto = menos activaciones falsas
SEGUIR_CHARLANDO = os.getenv("SEGUIR_CHARLANDO", "1") == "1"      # si pregunta algo, escucha sin wake word

# --- Datos locales ---
DATOS = BASE / "datos"
DATOS.mkdir(exist_ok=True)
ARCHIVO_NOTAS = DATOS / "notas.md"
ARCHIVO_RECORDATORIOS = DATOS / "recordatorios.json"
CARPETA_CAPTURAS = Path(os.getenv("CARPETA_CAPTURAS", Path.home() / "Pictures" / NOMBRE))


# --- Guardar desde la ventana de ajustes ---
CAMPOS_EDITABLES = {"NOMBRE": str, "USUARIO": str, "CIUDAD": str, "GEMINI_API_KEY": str,
                    "MODELO": str, "VOZ": str, "LIMITE_POR_HORA": int, "CONFIRMAR": str,
                    "PERSONALIDAD": str, "PERSONALIDAD_PROPIA": str,
                    "VINCULO": str, "REPO": str, "TOKEN_GITHUB": str,
                    "POLLINATIONS_KEY": str, "SIN_LIMITE_V2": str,
                    "LIBRE_PROVEEDOR": str, "LIBRE_CLAVE": str, "LIBRE_MODELO": str, "LIBRE_EN_CHARLA": str}
SI_O_NO = {"CONFIRMAR", "LIBRE_EN_CHARLA"}


def guardar(cambios: dict):
    """Escribe los cambios en el .env (sin borrar lo demás) y los aplica al instante."""
    lineas = ARCHIVO_ENV.read_text(encoding="utf-8").splitlines() if ARCHIVO_ENV.exists() else []
    pendientes = {}
    for clave, valor in cambios.items():
        if clave not in CAMPOS_EDITABLES:
            continue
        tipo = CAMPOS_EDITABLES[clave]
        try:
            valor = tipo(str(valor).strip())
        except ValueError:
            continue
        if clave == "NOMBRE" and not valor:
            valor = "Nova"
        if clave in SI_O_NO:
            valor = "1" if str(valor).lower() in ("1", "true", "si", "sí") else "0"
            globals()[clave] = valor == "1"
            os.environ[clave] = valor
            pendientes[clave] = valor
            continue
        globals()[clave] = valor
        os.environ[clave] = str(valor)
        pendientes[clave] = str(valor)
    nuevas = []
    for linea in lineas:
        clave = linea.split("=", 1)[0].strip()
        if clave in pendientes:
            nuevas.append(f"{clave}={pendientes.pop(clave)}")
        else:
            nuevas.append(linea)
    nuevas += [f"{k}={v}" for k, v in pendientes.items()]
    ARCHIVO_ENV.write_text("\n".join(nuevas) + "\n", encoding="utf-8")


# Desde la versión "sin límites": el viejo límite de 60 por hora pasa a 0 (sin límite propio) una sola vez.
if ARCHIVO_ENV.exists() and os.getenv("LIMITE_POR_HORA") == "60" and not os.getenv("SIN_LIMITE_V2"):
    guardar({"LIMITE_POR_HORA": 0, "SIN_LIMITE_V2": "1"})
