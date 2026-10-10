package com.mateo.nova

import android.content.Context
import java.text.Normalizer

/** Configuración guardada en el celular. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()
    var nombre: String
        get() = sp.getString("nombre", "Nova") ?: "Nova"
        set(v) = sp.edit().putString("nombre", v.trim().ifEmpty { "Nova" }).apply()
    var usuario: String
        get() = sp.getString("usuario", "Mateo") ?: "Mateo"
        set(v) = sp.edit().putString("usuario", v.trim()).apply()
    var ciudad: String
        get() = sp.getString("ciudad", "Buenos Aires") ?: "Buenos Aires"
        set(v) = sp.edit().putString("ciudad", v.trim()).apply()
    var modelo: String
        get() = sp.getString("modelo", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        set(v) = sp.edit().putString("modelo", v.trim().ifEmpty { "gemini-3.8-flash" }).apply()
    var codigoPais: String
        get() = sp.getString("codigo_pais", "54") ?: "54"
        set(v) = sp.edit().putString("codigo_pais", v.filter { it.isDigit() }.ifEmpty { "54" }).apply()
    /** Si el usuario dejó a Nova encendida. Android puede reiniciar el servicio solo; esto decide si debe seguir. */
    var quiereEncendido: Boolean
        get() = sp.getBoolean("quiere_encendido", false)
        set(v) = sp.edit().putBoolean("quiere_encendido", v).apply()
    /** Pedir permiso antes de mandar mensajes, llamar o publicar. Apagado = modo libre. */
    var confirmar: Boolean
        get() = sp.getBoolean("confirmar", true)
        set(v) = sp.edit().putBoolean("confirmar", v).apply()
    /** Consultas permitidas por hora. 0 = sin límite. */
    var limitePorHora: Int
        get() = sp.getInt("limite_hora", 60)
        set(v) = sp.edit().putInt("limite_hora", v.coerceAtLeast(0)).apply()
    /** Momentos (ms) de las llamadas de la última hora, separados por coma. */
    var marcasUso: String
        get() = sp.getString("marcas_uso", "") ?: ""
        set(v) = sp.edit().putString("marcas_uso", v).apply()

    /** Anota una consulta (para el límite por hora). */
    fun registrarUso() {
        val haceUnaHora = System.currentTimeMillis() - 3_600_000
        val m = marcasUso.split(",").mapNotNull { it.toLongOrNull() }.filter { it > haceUnaHora } + System.currentTimeMillis()
        marcasUso = m.joinToString(",")
    }

    /** Voz: un nombre de VozNatural.VOCES, o "sistema". */
    var voz: String
        get() = sp.getString("voz", "es-AR-ElenaNeural") ?: "es-AR-ElenaNeural"
        set(v) = sp.edit().putString("voz", v).apply()
    /** Estilo de personalidad: una clave de Personalidad.ESTILOS, o "propia". */
    var personalidad: String
        get() = sp.getString("personalidad", "calida") ?: "calida"
        set(v) = sp.edit().putString("personalidad", v).apply()
    var personalidadPropia: String
        get() = sp.getString("personalidad_propia", "") ?: ""
        set(v) = sp.edit().putString("personalidad_propia", v.trim()).apply()

    /** Código de vinculación con la PC (lo muestra Nova en la PC, en Ajustes). */
    var codigoVinculo: String
        get() = sp.getString("codigo_vinculo", "") ?: ""
        set(v) = sp.edit().putString("codigo_vinculo", v.trim()).apply()
    /** Repo de GitHub (usuario/repo) y token de solo lectura, para buscar actualizaciones. */
    var repo: String
        get() = sp.getString("repo", "") ?: ""
        set(v) = sp.edit().putString("repo", v.trim().removePrefix("https://github.com/").trim('/')).apply()
    var tokenGithub: String
        get() = sp.getString("token_github", "") ?: ""
        set(v) = sp.edit().putString("token_github", v.trim()).apply()
    var ultimaRevision: Long
        get() = sp.getLong("ultima_revision", 0)
        set(v) = sp.edit().putLong("ultima_revision", v).apply()

    /** App que maneja las luces y aparatos de la casa (Google Home, Smart Life, Tuya…). */
    var appCasa: String
        get() = sp.getString("app_casa", "") ?: ""
        set(v) = sp.edit().putString("app_casa", v.trim()).apply()

    /** Leer en voz alta los mensajes que llegan. */
    var leerMensajes: Boolean
        get() = sp.getBoolean("leer_mensajes", true)
        set(v) = sp.edit().putBoolean("leer_mensajes", v).apply()

    /** Consultas hechas en la última hora. */
    fun usoUltimaHora(): Int {
        val haceUnaHora = System.currentTimeMillis() - 3_600_000
        return marcasUso.split(",").mapNotNull { it.toLongOrNull() }.count { it > haceUnaHora }
    }
}

fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()
