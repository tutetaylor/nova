package com.mateo.nova

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/** Si está en modo manejo, el cerebro responde cortísimo y sin leer cosas largas. */
object Manejo {
    @Volatile var activo = false
}

/**
 * Modo manejo: pantalla siempre prendida, la estrella grande y cuatro botones enormes.
 * Todo se hace por voz; los botones son para un solo toque sin mirar.
 */
class ManejoActivity : AppCompatActivity() {

    private lateinit var estrella: Estrella
    private lateinit var estado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density

        estrella = Estrella(this).apply { setOnClickListener { hablar() } }
        estado = TextView(this).apply {
            textSize = 22f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.bruma))
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (16 * dp).toInt())
        }
        fun boton(texto: String, principal: Boolean, accion: () -> Unit) = MaterialButton(this).apply {
            text = texto; textSize = 22f; setAllCaps(false); typeface = Typeface.DEFAULT_BOLD
            cornerRadius = (28 * dp).toInt()
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (principal) R.color.estrella else R.color.nube_alta))
            setTextColor(ContextCompat.getColor(context, if (principal) R.color.sobre_estrella else R.color.texto))
            setOnClickListener { accion() }
        }
        fun fila(vararg botones: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            botones.forEach { addView(it, LinearLayout.LayoutParams(0, (110 * dp).toInt(), 1f).apply {
                marginStart = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() }) }
        }
        val columna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            addView(TextView(context).apply {
                text = "Modo manejo"; textSize = 18f; setTextColor(ContextCompat.getColor(context, R.color.estrella))
                setPadding((10 * dp).toInt(), (8 * dp).toInt(), 0, 0)
            })
            addView(estrella, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(estado)
            addView(fila(boton("Hablar", true) { hablar() }, boton("Mensajes", false) { pedir("leeme mis mensajes sin leer") }))
            addView(fila(boton("Música", false) { pedir("poné música para manejar") },
                boton("A casa", false) { pedir("llevame a casa") }), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * dp).toInt() })
            addView(MaterialButton(context).apply {
                text = "Salir del modo manejo"; setAllCaps(false); textSize = 16f
                backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                setTextColor(ContextCompat.getColor(context, R.color.bruma))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        }
        setContentView(FrameLayout(this).apply { addView(Cielo(context)); addView(columna) })
        ViewCompat.setOnApplyWindowInsetsListener(columna) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = b.top + (8 * dp).toInt(), bottom = b.bottom + (14 * dp).toInt()); insets
        }
    }

    override fun onResume() {
        super.onResume()
        Manejo.activo = true
        mostrar(Bus.estado, Bus.detalle)
        Bus.alCambiarEstado = { e, d -> mostrar(e, d) }
        Bus.alNivel = { n -> estrella.nivel = n }
        Bus.alParcial = { t -> if (t.isNotBlank()) estado.text = t }
        Bus.alMensaje = { m -> if (!m.deVos) estado.text = m.texto.take(140) }
        // Encender Nova si estaba apagada (y hay permisos)
        if (!AsistenteService.corriendo && Pasos.puedeEncender(this))
            ContextCompat.startForegroundService(this, Intent(this, AsistenteService::class.java).setAction(AsistenteService.ACCION_ENCENDER))
    }

    override fun onPause() {
        Bus.alCambiarEstado = null; Bus.alNivel = null; Bus.alParcial = null; Bus.alMensaje = null
        super.onPause()
    }

    override fun onDestroy() { Manejo.activo = false; super.onDestroy() }

    private fun mostrar(e: Bus.Estado, detalle: String) {
        estrella.estado = e
        estado.text = when (e) {
            Bus.Estado.ESPERA -> "Decí «${Prefs(this).nombre}» o tocá la estrella"
            Bus.Estado.ESCUCHA -> "Te escucho…"
            Bus.Estado.PENSANDO -> detalle.ifBlank { "Pensando…" }
            Bus.Estado.HABLANDO -> estado.text
            Bus.Estado.PREPARANDO -> detalle.ifBlank { "Preparando…" }
            Bus.Estado.APAGADO -> "Nova está apagada"
        }
    }

    private fun hablar() = startService(Intent(this, AsistenteService::class.java).setAction(AsistenteService.ACCION_ESCUCHAR))

    private fun pedir(texto: String) {
        if (!Pasos.puedeEncender(this)) return
        ContextCompat.startForegroundService(this, Intent(this, AsistenteService::class.java)
            .setAction(AsistenteService.ACCION_TEXTO).putExtra(AsistenteService.EXTRA_TEXTO, texto))
    }
}
