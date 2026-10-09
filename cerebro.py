"""El cerebro: Claude decide qué responder y qué herramientas usar."""
import anthropic

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


class Cerebro:
    def __init__(self):
        if not config.ANTHROPIC_API_KEY:
            raise SystemExit("Falta ANTHROPIC_API_KEY en el archivo .env")
        self.cliente = anthropic.Anthropic(api_key=config.ANTHROPIC_API_KEY)
        self.historial = []

    def olvidar(self):
        self.historial = []

    def pensar(self, pedido: str) -> str:
        self.historial.append({"role": "user", "content": pedido})
        for _ in range(10):  # hasta 10 rondas de herramientas por pedido
            resp = self.cliente.messages.create(
                model=config.MODELO, max_tokens=1024, system=PERSONALIDAD,
                tools=herramientas.esquemas(), messages=self.historial)
            self.historial.append({"role": "assistant", "content": resp.content})
            if resp.stop_reason != "tool_use":
                break
            resultados = []
            for bloque in resp.content:
                if bloque.type == "tool_use":
                    print(f"   ⚙️  {bloque.name}({bloque.input})")
                    salida = herramientas.ejecutar(bloque.name, bloque.input)
                    resultados.append({"type": "tool_result", "tool_use_id": bloque.id, "content": salida})
            self.historial.append({"role": "user", "content": resultados})
        self._recortar()
        return " ".join(b.text for b in resp.content if b.type == "text").strip()

    def _recortar(self):
        """Mantiene la charla corta sin cortar a la mitad un par herramienta/resultado."""
        while len(self.historial) > config.MAX_HISTORIAL:
            self.historial.pop(0)
            while self.historial and not (self.historial[0]["role"] == "user"
                                          and isinstance(self.historial[0]["content"], str)):
                self.historial.pop(0)
