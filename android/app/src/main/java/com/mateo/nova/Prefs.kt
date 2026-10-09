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
    /** Consultas permitidas por hora. 0 = sin límite. */
    var limitePorHora: Int
        get() = sp.getInt("limite_hora", 60)
        set(v) = sp.edit().putInt("limite_hora", v.coerceAtLeast(0)).apply()
    /** Momentos (ms) de las llamadas de la última hora, separados por coma. */
    var marcasUso: String
        get() = sp.getString("marcas_uso", "") ?: ""
        set(v) = sp.edit().putString("marcas_uso", v).apply()
    var silenciarPitidos: Boolean
        get() = sp.getBoolean("silenciar_pitidos", true)
        set(v) = sp.edit().putBoolean("silenciar_pitidos", v).apply()
}

fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()
