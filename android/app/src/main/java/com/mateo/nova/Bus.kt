package com.mateo.nova

import android.os.Handler
import android.os.Looper

/** Canal entre el servicio (que escucha y piensa) y la pantalla (que lo muestra). Todo llega en el hilo principal. */
object Bus {
    enum class Estado { APAGADO, PREPARANDO, ESPERA, ESCUCHA, PENSANDO, HABLANDO }
    data class Mensaje(val deVos: Boolean, val texto: String)

    private val main = Handler(Looper.getMainLooper())

    var estado = Estado.APAGADO; private set
    /** Texto chico debajo de la estrella ("Descargando la voz: 40%", "Abriendo WhatsApp…"). */
    var detalle = ""; private set
    val conversacion = mutableListOf<Mensaje>()

    var alCambiarEstado: ((Estado, String) -> Unit)? = null
    var alMensaje: ((Mensaje) -> Unit)? = null
    var alParcial: ((String) -> Unit)? = null
    /** Volumen de tu voz mientras te escucha, de 0 a 1 (la estrella late con eso). */
    var alNivel: ((Float) -> Unit)? = null

    fun estado(e: Estado, detalle: String = "") = main.post {
        estado = e; this.detalle = detalle
        alCambiarEstado?.invoke(e, detalle)
    }

    fun mensaje(deVos: Boolean, texto: String) = main.post {
        val m = Mensaje(deVos, texto)
        conversacion.add(m)
        while (conversacion.size > 40) conversacion.removeAt(0)
        alMensaje?.invoke(m)
    }

    fun parcial(texto: String) = main.post { alParcial?.invoke(texto) }

    fun nivel(rmsDb: Float) = main.post { alNivel?.invoke(((rmsDb + 2f) / 12f).coerceIn(0f, 1f)) }
}
