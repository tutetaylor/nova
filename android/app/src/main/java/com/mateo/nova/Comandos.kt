package com.mateo.nova

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Atajos instantáneos: los pedidos comunes se resuelven en el celular, sin consultar a Gemini.
 * Responden en menos de un segundo y no gastan cuota. Si no reconoce el pedido devuelve null
 * y el pedido va al cerebro como siempre.
 */
class Comandos(private val h: Herramientas) {

    private fun ej(nombre: String, vararg args: Pair<String, Any>) =
        h.ejecutar(nombre, JSONObject().apply { args.forEach { (k, v) -> put(k, v) } })

    private val numeros = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "quince" to 15,
        "veinte" to 20, "media" to 30, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45, "sesenta" to 60)

    private fun numero(s: String?): Int? = s?.trim()?.let { it.toIntOrNull() ?: numeros[it] }

    fun intentar(pedido: String): String? {
        val p = normalizar(pedido).trim(' ', '.', '!', '?', '¿', '¡').replace(Regex("\\s+"), " ")

        // ---------- Información
        if (Regex("^(que hora es|hora|decime la hora|que hora tenes)$").matches(p))
            return "Son las " + SimpleDateFormat("H:mm", Locale.US).format(Date()) + "."
        if (Regex("^(que (dia|fecha) es( hoy)?|que dia estamos)$").matches(p))
            return "Hoy es " + SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "AR")).format(Date()) + "."
        if (Regex("^(cuanta )?bateria( tengo| me queda)?$").matches(p) || p == "cuanta bateria tengo")
            return "Tenés " + ej("bateria") + "."

        // ---------- Linterna
        if (Regex("(prende|prendé|encende|activa|pone) (la )?linterna").containsMatchIn(p))
            return ej("linterna", "encender" to true).let { if (it.startsWith("Linterna")) "Listo." else it }
        if (Regex("(apaga|desactiva|saca) (la )?linterna").containsMatchIn(p))
            return ej("linterna", "encender" to false).let { if (it.startsWith("Linterna")) "Listo." else it }

        // ---------- Volumen y música
        if (Regex("^(subi|subile|subir|sube)( (el|al))? volumen").containsMatchIn(p)) { ej("volumen", "accion" to "subir"); return "" }
        if (Regex("^(baja|bajale|bajar)( (el|al))? volumen").containsMatchIn(p)) { ej("volumen", "accion" to "bajar"); return "" }
        Regex("volumen (al )?(\\d{1,3})( ?%| por ciento)?$").find(p)?.let {
            ej("volumen", "accion" to "fijar", "porcentaje" to it.groupValues[2].toInt()); return ""
        }
        if (Regex("^(silencia|mutea|silencio total)").containsMatchIn(p)) { ej("volumen", "accion" to "silenciar"); return "" }
        if (Regex("^(pausa|pausá|paus[aá] la musica|frena la musica|para la musica|play|reproduci|segui|continua)$").matches(p)) {
            ej("multimedia", "accion" to "play_pausa"); return ""
        }
        if (Regex("^(siguiente|proxima|pasa(la)?|saltea)( cancion| tema)?$").matches(p)) { ej("multimedia", "accion" to "siguiente"); return "" }
        if (Regex("^(anterior|volve(la)? a poner|tema anterior|cancion anterior)$").matches(p)) { ej("multimedia", "accion" to "anterior"); return "" }

        // ---------- Botones del sistema
        if (Regex("^(volve al inicio|anda al inicio|inicio|pantalla de inicio)$").matches(p)) { ej("boton_sistema", "accion" to "inicio"); return "" }
        if (Regex("^(atras|volve atras|volve)$").matches(p)) { ej("boton_sistema", "accion" to "atras"); return "" }
        if (Regex("^(saca|hace|toma) (una )?captura").containsMatchIn(p)) { ej("boton_sistema", "accion" to "captura"); return "" }
        if (Regex("^(bloquea|bloquear)( el)?( celular| telefono| pantalla)?$").matches(p)) { ej("boton_sistema", "accion" to "bloquear"); return "" }
        if (Regex("^(mostrame |abri )?(las )?notificaciones$").matches(p)) { ej("boton_sistema", "accion" to "notificaciones"); return "" }

        // ---------- Temporizador: "poné un temporizador de 10 minutos"
        Regex("(temporizador|timer|cronometro)( de)? ([\\w ]+?) (segundos?|minutos?|horas?)").find(p)?.let {
            val n = numero(it.groupValues[3]) ?: return@let
            val unidad = it.groupValues[4]
            val seg = when { unidad.startsWith("hora") -> n * 3600; unidad.startsWith("minuto") -> n * 60; else -> n }
            ej("temporizador", "segundos" to seg)
            return "Listo, temporizador de ${it.groupValues[3]} ${unidad}."
        }

        // ---------- Alarma: "poné una alarma a las 7", "despertame a las 6 y media", "alarma para las 7:30 de la tarde"
        Regex("(alarma|despertame)( para| a)?( las| la)? (\\d{1,2})(?:[:.](\\d{2})| y (media|cuarto|\\d{1,2}))?( de la (manana|tarde|noche))?")
            .find(p)?.let {
                var hora = it.groupValues[4].toInt()
                val minuto = it.groupValues[5].toIntOrNull() ?: when (it.groupValues[6]) {
                    "media" -> 30; "cuarto" -> 15; "" -> 0; else -> it.groupValues[6].toInt()
                }
                if (it.groupValues[8] in listOf("tarde", "noche") && hora < 12) hora += 12
                if (hora > 23 || minuto > 59) return@let
                ej("alarma", "hora" to hora, "minuto" to minuto)
                return "Listo, alarma para las $hora:${"%02d".format(minuto)}."
            }

        // ---------- Abrir apps: "abrí WhatsApp", "abrime Spotify"
        Regex("^(abri|abrime|abre|abrir|anda a|entra a|entra en) (la app )?(de )?(.+)$").find(p)?.let {
            val app = it.groupValues[4].trim()
            if (app.split(" ").size > 3) return@let   // pedido largo ("abrí WhatsApp y mandale..."): que lo resuelva Gemini
            val r = ej("abrir_app", "nombre" to app)
            return if (r.startsWith("Abierta")) "" else null   // si no la encontró, que lo intente Gemini
        }
        return null
    }
}
