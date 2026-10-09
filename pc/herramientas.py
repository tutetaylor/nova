"""Las manos del asistente: todo lo que puede hacer en la PC.

Para agregar una habilidad nueva, escribí una función con el decorador @herramienta.
Claude la ve automáticamente y decide cuándo usarla.
"""
import datetime as dt
import json
import os
import platform
import shutil
import subprocess
import threading
import urllib.parse
import webbrowser

import requests

import config

SISTEMA = platform.system()          # "Windows", "Darwin" (Mac) o "Linux"
REGISTRO = {}                        # nombre -> (función, esquema)
avisar = print                       # main.py lo reemplaza por la voz, para recordatorios


def herramienta(descripcion, **params):
    """params: nombre=("tipo", "descripción") ; si la descripción empieza con '?' es opcional."""
    def deco(fn):
        props, req = {}, []
        for nombre, (tipo, desc) in params.items():
            opcional = desc.startswith("?")
            props[nombre] = {"type": tipo, "description": desc.lstrip("?")}
            if not opcional:
                req.append(nombre)
        REGISTRO[fn.__name__] = (fn, {
            "name": fn.__name__, "description": descripcion,
            "input_schema": {"type": "object", "properties": props, "required": req}})
        return fn
    return deco


def esquemas():
    return [e for _, e in REGISTRO.values()]


def ejecutar(nombre, entrada):
    fn, _ = REGISTRO[nombre]
    try:
        res = fn(**entrada)
        return res if isinstance(res, str) else json.dumps(res, ensure_ascii=False)
    except Exception as e:
        return f"ERROR: {type(e).__name__}: {e}"


def _teclas():
    import pyautogui
    pyautogui.FAILSAFE = False
    return pyautogui


# ------------------------------------------------------------------ INFO
@herramienta("Fecha y hora actuales.")
def fecha_hora():
    ahora = dt.datetime.now()
    dias = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]
    return f"{dias[ahora.weekday()]} {ahora:%d/%m/%Y}, {ahora:%H:%M}"


@herramienta("Clima actual y pronóstico de los próximos días.",
             ciudad=("string", "?Ciudad. Si no se dice, usar la del usuario."))
def clima(ciudad=None):
    ciudad = ciudad or config.CIUDAD
    geo = requests.get("https://geocoding-api.open-meteo.com/v1/search",
                       params={"name": ciudad, "count": 1, "language": "es"}, timeout=10).json()
    if not geo.get("results"):
        return f"No encontré la ciudad {ciudad}"
    lugar = geo["results"][0]
    d = requests.get("https://api.open-meteo.com/v1/forecast", timeout=10, params={
        "latitude": lugar["latitude"], "longitude": lugar["longitude"], "timezone": "auto",
        "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
        "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
        "forecast_days": 4}).json()
    return {"lugar": f'{lugar["name"]}, {lugar.get("country", "")}', "ahora": d["current"],
            "proximos_dias": d["daily"], "nota": "weather_code es código WMO"}


@herramienta("Busca en internet y devuelve resultados resumidos para responder preguntas de actualidad.",
             consulta=("string", "Qué buscar"))
def buscar_info(consulta):
    r = requests.get("https://api.duckduckgo.com/", timeout=10,
                     params={"q": consulta, "format": "json", "no_html": 1, "kl": "ar-es"}).json()
    partes = [r.get("AbstractText", "")] + [t.get("Text", "") for t in r.get("RelatedTopics", [])[:5]
                                             if isinstance(t, dict)]
    texto = " | ".join(p for p in partes if p)
    return texto or "Sin resultados directos. Podés abrir la búsqueda en el navegador con abrir_web."


# ------------------------------------------------------------------ NAVEGADOR
@herramienta("Abre una página o una búsqueda en el navegador. Sirve para Google, YouTube, Maps, Spotify web, etc.",
             destino=("string", "URL completa, o texto a buscar"),
             sitio=("string", "?google | youtube | maps | wikipedia | mercadolibre. Por defecto google"))
def abrir_web(destino, sitio="google"):
    if destino.startswith("http"):
        url = destino
    else:
        q = urllib.parse.quote(destino)
        url = {"youtube": f"https://www.youtube.com/results?search_query={q}",
               "maps": f"https://www.google.com/maps/search/{q}",
               "wikipedia": f"https://es.wikipedia.org/w/index.php?search={q}",
               "mercadolibre": f"https://listado.mercadolibre.com.ar/{q}",
               }.get(sitio, f"https://www.google.com/search?q={q}")
    webbrowser.open(url)
    return f"Abierto: {url}"


# ------------------------------------------------------------------ PROGRAMAS
@herramienta("Abre un programa o aplicación instalada en la PC (Spotify, Chrome, VS Code, calculadora, explorador...).",
             nombre=("string", "Nombre del programa"))
def abrir_programa(nombre):
    alias = {"calculadora": {"Windows": "calc", "Darwin": "Calculator", "Linux": "gnome-calculator"},
             "explorador": {"Windows": "explorer", "Darwin": "Finder", "Linux": "xdg-open ."},
             "bloc de notas": {"Windows": "notepad", "Darwin": "TextEdit", "Linux": "gedit"},
             "vs code": {"Windows": "code", "Darwin": "Visual Studio Code", "Linux": "code"},
             "terminal": {"Windows": "wt", "Darwin": "Terminal", "Linux": "x-terminal-emulator"}}
    clave = nombre.lower().strip()
    real = alias.get(clave, {}).get(SISTEMA, nombre)
    if SISTEMA == "Windows":
        # El menú inicio encuentra casi cualquier app instalada por su nombre
        if shutil.which(real):
            subprocess.Popen(real, shell=True)
        else:
            t = _teclas()
            t.press("win"); t.sleep(0.6); t.write(nombre, interval=0.03); t.sleep(0.8); t.press("enter")
    elif SISTEMA == "Darwin":
        subprocess.Popen(["open", "-a", real])
    else:
        subprocess.Popen(real, shell=True)
    return f"Abriendo {nombre}"


@herramienta("Abre una carpeta del usuario o un archivo.",
             ruta=("string", "Ruta o carpeta conocida: descargas, documentos, escritorio, imagenes, musica"))
def abrir_carpeta(ruta):
    conocidas = {"descargas": "Downloads", "documentos": "Documents", "escritorio": "Desktop",
                 "imagenes": "Pictures", "musica": "Music", "videos": "Videos"}
    p = os.path.expanduser("~/" + conocidas[ruta.lower()]) if ruta.lower() in conocidas else os.path.expanduser(ruta)
    if not os.path.exists(p):
        return f"No existe {p}"
    if SISTEMA == "Windows":
        os.startfile(p)  # type: ignore[attr-defined]
    else:
        subprocess.Popen(["open" if SISTEMA == "Darwin" else "xdg-open", p])
    return f"Abierto {p}"


# ------------------------------------------------------------------ TECLADO, MEDIOS, PANTALLA
@herramienta("Controla la música o el video que esté sonando.",
             accion=("string", "play_pausa | siguiente | anterior"))
def multimedia(accion):
    tecla = {"play_pausa": "playpause", "siguiente": "nexttrack", "anterior": "prevtrack"}[accion]
    _teclas().press(tecla)
    return "Hecho"


@herramienta("Sube, baja o silencia el volumen de la PC.",
             accion=("string", "subir | bajar | silenciar"),
             pasos=("integer", "?Cuántos pasos (cada uno ~2%). Por defecto 5"))
def volumen(accion, pasos=5):
    t = _teclas()
    tecla = {"subir": "volumeup", "bajar": "volumedown", "silenciar": "volumemute"}[accion]
    t.press(tecla, presses=1 if accion == "silenciar" else pasos)
    return "Volumen ajustado"


@herramienta("Escribe texto donde esté el cursor, como si el usuario tecleara.",
             texto=("string", "Texto a escribir"))
def escribir(texto):
    t = _teclas()
    try:
        import pyperclip  # pegar soporta acentos y ñ mejor que tipear
        pyperclip.copy(texto)
        t.hotkey("command" if SISTEMA == "Darwin" else "ctrl", "v")
    except Exception:
        t.write(texto, interval=0.02)
    return "Escrito"


@herramienta("Presiona una tecla o combinación (ej: 'ctrl+c', 'alt+tab', 'enter', 'win+d', 'ctrl+shift+esc').",
             combinacion=("string", "Teclas separadas por +"))
def atajo(combinacion):
    _teclas().hotkey(*[k.strip().lower() for k in combinacion.split("+")])
    return f"Presionado {combinacion}"


@herramienta("Saca una captura de pantalla y la guarda.")
def captura_pantalla():
    config.CARPETA_CAPTURAS.mkdir(parents=True, exist_ok=True)
    ruta = config.CARPETA_CAPTURAS / f"captura_{dt.datetime.now():%Y%m%d_%H%M%S}.png"
    _teclas().screenshot(str(ruta))
    return f"Guardada en {ruta}"


@herramienta("Bloquea, suspende, reinicia o apaga la PC. Confirmá con el usuario antes de reiniciar o apagar.",
             accion=("string", "bloquear | suspender | reiniciar | apagar"))
def energia(accion):
    cmds = {
        "Windows": {"bloquear": "rundll32.exe user32.dll,LockWorkStation",
                    "suspender": "rundll32.exe powrprof.dll,SetSuspendState 0,1,0",
                    "reiniciar": "shutdown /r /t 5", "apagar": "shutdown /s /t 5"},
        "Darwin": {"bloquear": "pmset displaysleepnow", "suspender": "pmset sleepnow",
                   "reiniciar": "osascript -e 'tell app \"System Events\" to restart'",
                   "apagar": "osascript -e 'tell app \"System Events\" to shut down'"},
        "Linux": {"bloquear": "loginctl lock-session", "suspender": "systemctl suspend",
                  "reiniciar": "systemctl reboot", "apagar": "systemctl poweroff"}}
    subprocess.Popen(cmds[SISTEMA][accion], shell=True)
    return f"Ejecutando: {accion}"


# ------------------------------------------------------------------ PRODUCTIVIDAD
def _cargar_rec():
    if config.ARCHIVO_RECORDATORIOS.exists():
        return json.loads(config.ARCHIVO_RECORDATORIOS.read_text(encoding="utf-8"))
    return []


def _guardar_rec(lista):
    config.ARCHIVO_RECORDATORIOS.write_text(json.dumps(lista, ensure_ascii=False, indent=1), encoding="utf-8")


def _programar(rec):
    seg = (dt.datetime.fromisoformat(rec["cuando"]) - dt.datetime.now()).total_seconds()
    if seg < -60:
        return

    def disparar():
        avisar(f"Recordatorio: {rec['texto']}")
        _guardar_rec([r for r in _cargar_rec() if r["id"] != rec["id"]])
    t = threading.Timer(max(seg, 0), disparar)
    t.daemon = True
    t.start()


def iniciar_recordatorios():
    """Reprograma los recordatorios guardados al arrancar."""
    for r in _cargar_rec():
        _programar(r)


@herramienta("Crea un recordatorio, alarma o temporizador. Te aviso por voz cuando llegue el momento.",
             texto=("string", "Qué recordar"),
             cuando=("string", "Fecha y hora ISO 'AAAA-MM-DDTHH:MM' calculada con fecha_hora, o 'en N minutos'"))
def recordatorio(texto, cuando):
    if cuando.startswith("en "):
        n = int("".join(c for c in cuando if c.isdigit()) or 0)
        unidad = 3600 if "hora" in cuando else 1 if "segundo" in cuando else 60
        momento = dt.datetime.now() + dt.timedelta(seconds=n * unidad)
    else:
        momento = dt.datetime.fromisoformat(cuando)
    rec = {"id": dt.datetime.now().timestamp(), "texto": texto, "cuando": momento.isoformat(timespec="minutes")}
    lista = _cargar_rec(); lista.append(rec); _guardar_rec(lista)
    _programar(rec)
    return f"Recordatorio para {momento:%d/%m %H:%M}: {texto}"


@herramienta("Lista los recordatorios pendientes.")
def ver_recordatorios():
    return _cargar_rec() or "No hay recordatorios pendientes"


@herramienta("Guarda una nota o idea en la libreta del asistente.", texto=("string", "Contenido de la nota"))
def anotar(texto):
    with open(config.ARCHIVO_NOTAS, "a", encoding="utf-8") as f:
        f.write(f"- [{dt.datetime.now():%d/%m/%Y %H:%M}] {texto}\n")
    return "Anotado"


@herramienta("Lee las últimas notas guardadas.", cantidad=("integer", "?Cuántas. Por defecto 10"))
def leer_notas(cantidad=10):
    if not config.ARCHIVO_NOTAS.exists():
        return "No hay notas"
    return "".join(config.ARCHIVO_NOTAS.read_text(encoding="utf-8").splitlines(True)[-cantidad:])
