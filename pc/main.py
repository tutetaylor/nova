"""Arranca Nova.

  python main.py            con ventana (la estrella, la charla y los ajustes)
  python main.py --consola  solo voz, en la terminal
  python main.py --texto    escribiendo en la terminal, sin micrófono
"""
import sys
import traceback

import config


def modo_texto(nucleo):
    print(f"Modo texto. Escribile a {config.NOMBRE} (Ctrl+C para salir).")
    while True:
        pedido = input("\n🧑 Vos: ").strip()
        if pedido:
            nucleo.atender(pedido)


def modo_consola(nucleo):
    import time
    print(f'Decí "{config.NOMBRE}" para hablarle (Ctrl+C para salir).')
    nucleo.encender()
    while nucleo.activo or nucleo.estado == "preparando":
        time.sleep(0.5)


def consola():
    from nucleo import Nucleo

    def mostrar(tipo, datos):
        if tipo == "estado" and datos["estado"] in ("espera", "escucha", "preparando"):
            textos = {"espera": f'👂 Esperando "{config.NOMBRE}"...', "escucha": "🎙️  Escuchando...",
                      "preparando": datos["detalle"]}
            print(textos[datos["estado"]])
        elif tipo == "mensaje" and datos["deVos"]:
            print(f"🧑 Vos: {datos['texto']}")

    nucleo = Nucleo(emitir=mostrar)
    if not nucleo.cerebro:
        raise SystemExit("Falta GEMINI_API_KEY en el archivo .env (es gratis en aistudio.google.com/apikey)")
    try:
        modo_texto(nucleo) if "--texto" in sys.argv else modo_consola(nucleo)
    except KeyboardInterrupt:
        print("\nChau 👋")


def ventana():
    try:
        import webview  # noqa: F401
    except ImportError:
        print("No está instalada la ventana (pip install pywebview). Arranco en modo consola.")
        return consola()
    import app
    app.main()


def avisar_error(texto):
    """Sin consola (el .exe con ventana), el error se guarda en un archivo y se muestra en un cartel."""
    (config.BASE / "error.txt").write_text(texto, encoding="utf-8")
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            0, f"Nova se cerró por un error.\n\nEl detalle quedó en:\n{config.BASE / 'error.txt'}", "Nova", 0x10)
    else:
        print(texto)


if __name__ == "__main__":
    for flujo in (sys.stdout, sys.stderr):        # la consola de Windows a veces no muestra acentos ni emojis
        try:
            flujo.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    try:
        consola() if config.MODO_CONSOLA else ventana()
    except (KeyboardInterrupt, SystemExit):
        raise
    except BaseException:
        if config.EMPAQUETADO:
            avisar_error(traceback.format_exc())
        else:
            raise
