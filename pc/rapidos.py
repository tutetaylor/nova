"""Atajos instantáneos: los pedidos comunes se resuelven en la PC sin consultar a Gemini.
Responden al toque y no gastan cuota. Si no reconoce el pedido devuelve None y va al cerebro."""
import datetime as dt
import re

import herramientas as h
from voz import normalizar

DIAS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]
MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
         "septiembre", "octubre", "noviembre", "diciembre"]
NUMEROS = {"un": 1, "una": 1, "uno": 1, "dos": 2, "tres": 3, "cuatro": 4, "cinco": 5, "diez": 10,
           "quince": 15, "veinte": 20, "treinta": 30, "media": 30, "cuarenta": 40, "sesenta": 60}


def intentar(pedido: str):
    p = re.sub(r"\s+", " ", normalizar(pedido).strip(" .!?¿¡"))
    ahora = dt.datetime.now()

    if re.fullmatch(r"(que hora es|hora|decime la hora)", p):
        return f"Son las {ahora:%H:%M}."
    if re.fullmatch(r"(que (dia|fecha) es( hoy)?|que dia estamos)", p):
        return f"Hoy es {DIAS[ahora.weekday()]} {ahora.day} de {MESES[ahora.month - 1]}."

    if re.match(r"(subi|subile|subir|sube)( (el|al))? volumen", p):
        h.ejecutar("volumen", {"accion": "subir"}); return ""
    if re.match(r"(baja|bajale|bajar)( (el|al))? volumen", p):
        h.ejecutar("volumen", {"accion": "bajar"}); return ""
    if re.match(r"(silencia|mutea)", p):
        h.ejecutar("volumen", {"accion": "silenciar"}); return ""
    if re.fullmatch(r"(pausa|pausa la musica|para la musica|frena la musica|play|reproduci|segui|continua)", p):
        h.ejecutar("multimedia", {"accion": "play_pausa"}); return ""
    if re.fullmatch(r"(siguiente|proxima|pasala|saltea)( cancion| tema)?", p):
        h.ejecutar("multimedia", {"accion": "siguiente"}); return ""
    if re.fullmatch(r"(anterior|tema anterior|cancion anterior)", p):
        h.ejecutar("multimedia", {"accion": "anterior"}); return ""
    if re.match(r"(saca|hace|toma) (una )?captura", p):
        h.ejecutar("captura_pantalla", {}); return "Listo, captura guardada."
    if re.fullmatch(r"bloquea( la)?( pc| compu| computadora| pantalla)?", p):
        h.ejecutar("energia", {"accion": "bloquear"}); return ""

    m = re.search(r"recordame en ([\w ]+?) (minutos?|horas?|segundos?) (que |de )?(.+)", p)
    if m:
        n = int(m.group(1)) if m.group(1).isdigit() else NUMEROS.get(m.group(1))
        if n:
            unidad = "horas" if m.group(2).startswith("hora") else "segundos" if m.group(2).startswith("seg") else "minutos"
            h.ejecutar("recordatorio", {"texto": m.group(4), "cuando": f"en {n} {unidad}"})
            return f"Dale, te aviso en {m.group(1)} {m.group(2)}."

    m = re.fullmatch(r"(abri|abrime|abre|abrir) (el |la )?(.+)", p)
    if m and len(m.group(3).split()) <= 3:
        destino = m.group(3)
        if destino in ("descargas", "documentos", "escritorio", "imagenes", "musica", "videos"):
            h.ejecutar("abrir_carpeta", {"ruta": destino})
        elif destino in ("youtube", "gmail", "whatsapp web", "netflix"):
            urls = {"youtube": "https://youtube.com", "gmail": "https://mail.google.com",
                    "whatsapp web": "https://web.whatsapp.com", "netflix": "https://netflix.com"}
            h.ejecutar("abrir_web", {"destino": urls[destino]})
        else:
            h.ejecutar("abrir_programa", {"nombre": destino})
        return ""
    return None
