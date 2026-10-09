"""El cerebro: Gemini (capa gratuita de Google) decide qué responder y qué herramientas usar."""
import json
import time

import requests

import config
import herramientas

PERSONALIDAD = f"""Sos {config.NOMBRE}, el asistente de voz personal de {config.USUARIO}, que vive en {config.CIUDAD}.
Hablás en español rioplatense (voseo), con calidez, buen humor sutil y mucha precisión.

Reglas de voz (todo lo que escribís se lee en voz alta):
- Respuestas cortas: 1 a 3 oraciones, salvo que te pidan detalle.
- Nada de markdown, listas, emojis, URLs ni símbolos raros. Números y horas escritos para decirse.
- Si hiciste una acción, confirmala en pocas palabras ("Listo, abrí Spotify").

Reglas de acción:
- Usá las herramientas para hacer cosas reales en la PC; no digas que no podés si hay una herramienta que sirve.
- Para fechas relativas ("mañana a las 9") primero consultá fecha_hora.
- Antes de apagar o reiniciar la PC, preguntá y esperá un sí.
- Si no entendiste el pedido, preguntá breve.
"""

URL = "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent"


class SinCuota(Exception):
    pass


class Limitador:
    """Cuenta las llamadas de la última hora (se guarda en disco). Protege la cuota gratis."""

    def __init__(self, maximo, archivo):
        self.maximo, self.archivo = maximo, archivo
        try:
            self.marcas = json.loads(archivo.read_text())
        except Exception:
            self.marcas = []

    def _limpiar(self):
        hace_una_hora = time.time() - 3600
        self.marcas = [m for m in self.marcas if m > hace_una_hora]

    def disponibles(self):
        if self.maximo <= 0:          # 0 = sin límite propio (solo el de Google)
            return 10**9
        self._limpiar()
        return self.maximo - len(self.marcas)

    def registrar(self):
        self.marcas.append(time.time())
        self._limpiar()
        self.archivo.write_text(json.dumps(self.marcas))

    def minutos_para_liberar(self):
        self._limpiar()
        if not self.marcas:
            return 0
        return max(1, int((self.marcas[0] + 3600 - time.time()) / 60) + 1)

    def resumen(self):
        if self.maximo <= 0:
            return "No tengo límite propio por hora; solo el de la capa gratis de Google."
        return f"Usé {len(self.marcas)} de {self.maximo} consultas en la última hora."


def _declaraciones():
    """Convierte las herramientas al formato de Gemini."""
    decl = []
    for e in herramientas.esquemas():
        d = {"name": e["name"], "description": e["description"]}
        if e["input_schema"]["properties"]:
            d["parameters"] = e["input_schema"]
        decl.append(d)
    return [{"functionDeclarations": decl}]


class Cerebro:
    def __init__(self):
        if not config.GEMINI_API_KEY:
            raise SystemExit("Falta GEMINI_API_KEY en el archivo .env (es gratis en aistudio.google.com)")
        self.historial = []
        self.limite = Limitador(config.LIMITE_POR_HORA, config.DATOS / "uso.json")
        self.modelos = [config.MODELO] + [m for m in config.MODELOS_RESPALDO if m != config.MODELO]
        self.http = requests.Session()

    def olvidar(self):
        self.historial = []

    def _llamar(self):
        cuerpo = {"systemInstruction": {"parts": [{"text": PERSONALIDAD}]},
                  "contents": self.historial, "tools": _declaraciones(),
                  "generationConfig": {"maxOutputTokens": 1024}}
        ultimo_error = ""
        for modelo in self.modelos:              # si uno se quedó sin cuota gratis, prueba el siguiente
            r = self.http.post(URL.format(modelo), json=cuerpo, timeout=60,
                               headers={"x-goog-api-key": config.GEMINI_API_KEY})
            if r.status_code in (429, 404, 503):
                ultimo_error = f"{modelo}: {r.status_code}"
                continue
            if not r.ok:
                raise RuntimeError(f"Gemini {r.status_code}: {r.text[:300]}")
            return r.json()
        raise SinCuota(ultimo_error)

    def pensar(self, pedido: str) -> str:
        if self.limite.disponibles() <= 0:
            return (f"Llegué al límite de uso de esta hora. "
                    f"Vuelvo a estar disponible en {self.limite.minutos_para_liberar()} minutos.")
        inicio = len(self.historial)
        self.historial.append({"role": "user", "parts": [{"text": pedido}]})
        texto = ""
        try:
            for _ in range(10):  # hasta 10 rondas de herramientas por pedido
                if self.limite.disponibles() <= 0:
                    del self.historial[inicio:]
                    return ("Llegué al límite de uso de esta hora a mitad de la tarea, así que la dejé ahí. "
                            f"En {self.limite.minutos_para_liberar()} minutos la retomamos.")
                self.limite.registrar()
                resp = self._llamar()
                candidato = (resp.get("candidates") or [{}])[0]
                contenido = candidato.get("content") or {"role": "model", "parts": []}
                contenido["role"] = "model"
                partes = contenido.setdefault("parts", [])
                self.historial.append(contenido)       # se guarda tal cual (incluye firmas de razonamiento)
                texto = " ".join(p["text"] for p in partes if "text" in p and not p.get("thought")).strip()
                llamadas = [p["functionCall"] for p in partes if "functionCall" in p]
                if not llamadas:
                    break
                respuestas = []
                for ll in llamadas:
                    print(f"   ⚙️  {ll['name']}({ll.get('args', {})})")
                    salida = herramientas.ejecutar(ll["name"], ll.get("args") or {})
                    respuestas.append({"functionResponse": {"name": ll["name"], "response": {"resultado": salida}}})
                self.historial.append({"role": "user", "parts": respuestas})
        except SinCuota:
            del self.historial[inicio:]
            return "Se me terminó la cuota gratis de Google por ahora. Probá de nuevo en un rato."
        except Exception:
            del self.historial[inicio:]
            raise
        self._recortar()
        return texto or "Listo."

    def _recortar(self):
        """Mantiene la charla corta, empezando siempre en un pedido de texto del usuario."""
        while len(self.historial) > config.MAX_HISTORIAL:
            self.historial.pop(0)
            while self.historial and not (self.historial[0]["role"] == "user"
                                          and "text" in self.historial[0]["parts"][0]):
                self.historial.pop(0)
