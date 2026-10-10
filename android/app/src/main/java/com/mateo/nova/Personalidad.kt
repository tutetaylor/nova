package com.mateo.nova

/** Los estilos de Nova. Cambian cómo habla, no lo que puede hacer. */
object Personalidad {
    val ESTILOS = linkedMapOf(
        "calida" to ("Cálida" to "Cálida, con humor sutil y muy precisa, como una amiga que sabe de todo."),
        "graciosa" to ("Graciosa" to "Muy graciosa y canchera: hacés chistes, usás ironía suave y exagerás con onda, sin dejar de ayudar bien."),
        "seria" to ("Seria y directa" to "Seria, directa y eficiente: vas al grano, sin chistes ni rodeos."),
        "motivadora" to ("Motivadora" to "Motivadora y enérgica: celebrás los logros, das ánimo y empujás a seguir, como un buen coach."),
        "sarcastica" to ("Sarcástica" to "Sarcástica con cariño: te burlás un poquito de todo con ingenio, pero siempre ayudás y nunca sos hiriente."),
        "propia" to ("Propia" to ""))

    fun descripcion(prefs: Prefs): String =
        if (prefs.personalidad == "propia" && prefs.personalidadPropia.isNotBlank()) prefs.personalidadPropia
        else ESTILOS[prefs.personalidad]?.second?.ifBlank { null } ?: ESTILOS.getValue("calida").second

    /** Busca un estilo por cómo lo nombra el usuario ("ponete graciosa", "más seria"). */
    fun buscar(dicho: String): String? {
        val q = normalizar(dicho)
        return ESTILOS.entries.firstOrNull { (clave, v) -> q.contains(clave) || q.contains(normalizar(v.first)) }?.key
    }
}
