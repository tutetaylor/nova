"""Canal entre la PC y el celular, desde cualquier lugar, por ntfy.sh (gratis).

El canal tiene un nombre secreto y todo va cifrado (AES-GCM) con una clave que sale del código de vinculación:
nadie más puede leer ni mandar órdenes. Mensajes viejos (más de 2 minutos) se descartan.
Mismo formato que el celular: {"de", "tipo": orden|respuesta|sonar, "id", "texto", "t"}.
"""
import base64
import hashlib
import json
import os
import secrets
import threading
import time
import uuid

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SERVIDOR = "https://ntfy.sh"


def codigo_nuevo():
    """Código para vincular: 32 caracteres al azar, fácil de copiar."""
    return secrets.token_hex(16)


class Vinculo:
    def __init__(self, codigo, al_recibir):
        codigo = codigo.strip()
        self.canal = "nova-" + hashlib.sha256(("nova-canal:" + codigo).encode()).hexdigest()[:28]
        self.clave = AESGCM(hashlib.sha256(("nova-clave:" + codigo).encode()).digest())
        self.al_recibir = al_recibir
        self.esperando = {}
        self.activo = False

    def _cifrar(self, texto):
        iv = os.urandom(12)
        return base64.b64encode(iv + self.clave.encrypt(iv, texto.encode(), None)).decode()

    def _descifrar(self, b64):
        try:
            datos = base64.b64decode(b64)
            return self.clave.decrypt(datos[:12], datos[12:], None).decode()
        except Exception:
            return None          # no es nuestro o fue alterado: se ignora

    def enviar(self, tipo, texto, id_=None):
        id_ = id_ or uuid.uuid4().hex[:12]
        m = {"de": "pc", "tipo": tipo, "id": id_, "texto": texto[:2500], "t": int(time.time() * 1000)}
        requests.post(f"{SERVIDOR}/{self.canal}", data=self._cifrar(json.dumps(m)), timeout=20)
        return id_

    def ordenar_celular(self, texto, segundos=60):
        """Manda una orden al celular y espera la respuesta. Bloqueante."""
        id_ = uuid.uuid4().hex[:12]
        evento, caja = threading.Event(), {}
        self.esperando[id_] = (evento, caja)
        try:
            self.enviar("orden", texto, id_)
            if evento.wait(segundos):
                return caja.get("texto") or "Listo."
            return "El celular no respondió. ¿Tiene internet y Nova encendida?"
        except Exception as e:
            return f"No pude comunicarme con el celular ({str(e)[:40]})."
        finally:
            self.esperando.pop(id_, None)

    def iniciar(self):
        if self.activo:
            return
        self.activo = True
        threading.Thread(target=self._escuchar, daemon=True, name="nova-vinculo").start()

    def detener(self):
        self.activo = False

    def _escuchar(self):
        desde = str(int(time.time()))
        while self.activo:
            try:
                with requests.get(f"{SERVIDOR}/{self.canal}/json", params={"since": desde}, stream=True, timeout=(20, 90)) as r:
                    for linea in r.iter_lines(decode_unicode=True):
                        if not self.activo:
                            return
                        if not linea:
                            continue
                        ev = json.loads(linea)
                        if ev.get("event") != "message":
                            continue
                        desde = ev.get("id", desde)
                        claro = self._descifrar(ev.get("message", ""))
                        if not claro:
                            continue
                        m = json.loads(claro)
                        if m.get("de") == "pc" or time.time() * 1000 - m.get("t", 0) > 120_000:
                            continue
                        if m.get("tipo") == "respuesta":
                            espera = self.esperando.get(m.get("id"))
                            if espera:
                                espera[1]["texto"] = m.get("texto")
                                espera[0].set()
                        else:
                            threading.Thread(target=self.al_recibir, args=(m,), daemon=True).start()
            except Exception:
                if self.activo:
                    time.sleep(5)       # sin internet o se cortó: reintenta
