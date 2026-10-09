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
        get() = sp.getString("modelo", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        set(v) = sp.edit().putString("modelo", v.trim().ifEmpty { "claude-sonnet-5-5" }).apply()
    var codigoPais: String
        get() = sp.getString("codigo_pais", "54") ?: "54"
        set(v) = sp.edit().putString("codigo_pais", v.filter { it.isDigit() }.ifEmpty { "54" }).apply()
    var silenciarPitidos: Boolean
        get() = sp.getBoolean("silenciar_pitidos", true)
        set(v) = sp.edit().putBoolean("silenciar_pitidos", v).apply()
}

fun normalizar(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()
