"""Actualización automática de Nova.exe desde tu repo privado de GitHub (con token de solo lectura)."""
import re
import subprocess
import sys
from pathlib import Path

import requests

import config


def version_actual():
    """El número de compilación que GitHub grabó en version.txt al armar el .exe (0 si corrés desde el código)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    try:
        return int((base / "version.txt").read_text().strip())
    except Exception:
        return 0


def revisar():
    """Devuelve (url_de_descarga | None, texto)."""
    if not config.REPO or not config.TOKEN_GITHUB:
        return None, "Para actualizar sola, cargá tu repo y tu token de GitHub en Ajustes › Conexiones."
    try:
        r = requests.get(f"https://api.github.com/repos/{config.REPO.strip()}/releases/tags/pc-ultima", timeout=20,
                         headers={"Authorization": f"Bearer {config.TOKEN_GITHUB.strip()}",
                                  "Accept": "application/vnd.github+json"})
    except Exception:
        return None, "No pude conectarme con GitHub."
    if r.status_code in (401, 403):
        return None, "GitHub rechazó el token. Revisá que tenga permiso de lectura del repo."
    if r.status_code == 404:
        return None, "No encontré el release. Revisá el nombre del repo (usuario/repo)."
    if not r.ok:
        return None, f"GitHub respondió con error {r.status_code}."
    datos = r.json()
    m = re.search(r"Versión 1\.(\d+)", datos.get("body", ""))
    exe = next((a for a in datos.get("assets", []) if a["name"].lower().endswith(".exe")), None)
    if not m or not exe:
        return None, "No pude leer la versión del release."
    nueva, actual = int(m.group(1)), version_actual()
    if actual and nueva <= actual:
        return None, f"Ya tenés la última versión (1.{actual})."
    return exe["url"], f"Hay una versión nueva: 1.{nueva}" + (f" (tenés la 1.{actual})." if actual else ".")


def descargar_e_instalar(url):
    """Baja el .exe nuevo, cierra Nova, lo reemplaza y la vuelve a abrir."""
    if not getattr(sys, "frozen", False):
        return "Desde el código no hace falta: bajá los cambios del repo con git pull."
    nuevo = config.BASE / "Nova_nuevo.exe"
    with requests.get(url, stream=True, timeout=120,
                      headers={"Authorization": f"Bearer {config.TOKEN_GITHUB.strip()}",
                               "Accept": "application/octet-stream"}) as r:
        if not r.ok:
            return f"No pude descargar la actualización (error {r.status_code})."
        with open(nuevo, "wb") as f:
            for trozo in r.iter_content(1 << 16):
                f.write(trozo)
    actual = Path(sys.executable)
    bat = config.BASE / "actualizar_nova.bat"
    bat.write_text(
        "@echo off\r\n"
        "timeout /t 2 /nobreak >nul\r\n"
        f'move /y "{nuevo}" "{actual}" >nul\r\n'
        f'start "" "{actual}"\r\n'
        'del "%~f0"\r\n', encoding="utf-8")
    subprocess.Popen(["cmd", "/c", str(bat)], creationflags=0x08000000)   # sin ventana
    return "SALIR"
