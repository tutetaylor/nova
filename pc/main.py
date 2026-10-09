"""Arranca el asistente.

  python main.py            modo voz (dice el nombre y hablale)
  python main.py --texto    modo texto, para probar sin micrófono
"""
import sys

import config
import herramientas
from cerebro import Cerebro
from voz import Boca, normalizar

PARA_CORTAR = {"nada", "chau", "gracias", "listo", "eso es todo", "nada mas", "cancelar"}
PARA_OLVIDAR = {"olvida todo", "nueva conversacion", "empecemos de nuevo"}


def atender(cerebro, boca, pedido):
    """Procesa un pedido. Devuelve True si conviene seguir escuchando sin wake word."""
    p = normalizar(pedido).strip(" .!?")
    if p in PARA_CORTAR:
        boca.decir("Dale, cualquier cosa me llamás.")
        return False
    if p in PARA_OLVIDAR:
        cerebro.olvidar()
        boca.decir("Listo, empezamos de cero.")
        return False
    try:
        respuesta = cerebro.pensar(pedido)
    except Exception as e:
        print("Error:", e)
        respuesta = "Uy, tuve un problema para pensar eso. ¿Probamos de nuevo?"
    boca.decir(respuesta)
    return config.SEGUIR_CHARLANDO and respuesta.rstrip().endswith("?")


def modo_texto(cerebro, boca):
    print(f"Modo texto. Escribile a {config.NOMBRE} (Ctrl+C para salir).")
    while True:
        pedido = input("\n🧑 Vos: ").strip()
        if pedido:
            atender(cerebro, boca, pedido)


def modo_voz(cerebro, boca):
    from instalar_modelo import asegurar_modelo
    from voz import Oido
    asegurar_modelo()
    oido = Oido()
    boca.decir(f"Hola {config.USUARIO}, soy {config.NOMBRE}. Decí mi nombre cuando me necesites.")
    while True:
        print(f'👂 Esperando "{config.NOMBRE}"...')
        oido.esperar_activacion()
        seguir = True
        primera = True
        while seguir:
            if primera:
                boca.decir("¿Sí?")
            primera = False
            print("🎙️  Escuchando...")
            pedido = oido.escuchar_pedido()
            if not pedido:
                break
            print(f"🧑 Vos: {pedido}")
            seguir = atender(cerebro, boca, pedido)


def main():
    boca = Boca()
    herramientas.avisar = boca.decir           # los recordatorios hablan
    herramientas.iniciar_recordatorios()
    cerebro = Cerebro()
    try:
        modo_texto(cerebro, boca) if "--texto" in sys.argv else modo_voz(cerebro, boca)
    except KeyboardInterrupt:
        print("\nChau 👋")


if __name__ == "__main__":
    for flujo in (sys.stdout, sys.stderr):        # la consola de Windows a veces no muestra acentos ni emojis
        try:
            flujo.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    try:
        main()
    except BaseException as e:
        if config.EMPAQUETADO and not isinstance(e, (KeyboardInterrupt, SystemExit)):
            import traceback
            traceback.print_exc()
            input("\nNova se cerró por un error. Copiá el mensaje de arriba y apretá Enter para salir.")
        elif isinstance(e, SystemExit) and config.EMPAQUETADO:
            print(e)
            input("\nApretá Enter para salir.")
        else:
            raise
