package com.mateo.nova

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.Calendar

/** Pantalla principal: la estrella, la conversación y una barra para escribir. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var estrella: Estrella
    private lateinit var estadoTexto: TextView
    private lateinit var parcial: TextView
    private lateinit var chat: LinearLayout
    private lateinit var sugerencias: View
    private lateinit var scroll: ScrollView
    private lateinit var entrada: EditText

    private val ideas = listOf(
        "¿Qué hora es?", "Poné una alarma a las 7", "¿Cómo está el clima mañana?",
        "Prendé la linterna", "Abrí WhatsApp", "Contame un dato curioso")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        ajustarBordes(findViewById(R.id.contenido))

        estrella = findViewById(R.id.estrella)
        estadoTexto = findViewById(R.id.estadoTexto)
        parcial = findViewById(R.id.parcial)
        chat = findViewById(R.id.chat)
        sugerencias = findViewById(R.id.sugerencias)
        scroll = findViewById(R.id.scroll)
        entrada = findViewById(R.id.entrada)

        estrella.setOnClickListener { tocarEstrella() }
        findViewById<View>(R.id.botonAjustes).setOnClickListener { abrirAjustes() }
        findViewById<View>(R.id.avisoBoton).setOnClickListener { abrirAjustes() }
        findViewById<View>(R.id.encendido).setOnClickListener { alternarEncendido() }
        findViewById<View>(R.id.enviar).setOnClickListener { enviarTexto() }
        entrada.setOnEditorActionListener { _, accion, _ ->
            if (accion == EditorInfo.IME_ACTION_SEND) { enviarTexto(); true } else false
        }

        val chips = findViewById<ChipGroup>(R.id.chips)
        for (idea in ideas) chips.addView(Chip(this).apply {
            text = idea
            setTextColor(ContextCompat.getColor(context, R.color.texto))
            chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.nube))
            chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.linea))
            chipStrokeWidth = dp(1).toFloat()
            chipCornerRadius = dp(18).toFloat()
            chipMinHeight = dp(40).toFloat()
            setOnClickListener { enviar(idea) }
        })

    }

    override fun onResume() {
        super.onResume()
        refrescar()
        Bus.alCambiarEstado = { e, d -> mostrarEstado(e, d) }
        Bus.alMensaje = { m -> agregarBurbuja(m, animar = true) }
        Bus.alParcial = { t ->
            parcial.text = t
            parcial.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
        }
        Bus.alNivel = { n -> estrella.nivel = n }
    }

    override fun onPause() {
        Bus.alCambiarEstado = null; Bus.alMensaje = null; Bus.alParcial = null; Bus.alNivel = null
        super.onPause()
    }

    // ------------------------------------------------------------ ESTADO
    private fun refrescar() {
        prefs = Prefs(this)
        findViewById<TextView>(R.id.marca).text = prefs.nombre
        entrada.hint = "Escribile a ${prefs.nombre}…"
        findViewById<TextView>(R.id.saludo).text = saludo()
        mostrarEstado(if (AsistenteService.corriendo) Bus.estado else Bus.Estado.APAGADO, Bus.detalle)
        mostrarEncendido()
        mostrarAviso()
        reconstruirChat()
    }

    private fun saludo(): String {
        val hora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val parte = when (hora) { in 5..12 -> "Buen día"; in 13..19 -> "Buenas tardes"; else -> "Buenas noches" }
        return if (prefs.usuario.isBlank()) parte else "$parte, ${prefs.usuario}"
    }

    private fun mostrarEstado(e: Bus.Estado, detalle: String) {
        estrella.estado = e
        if (e != Bus.Estado.ESCUCHA) estrella.nivel = 0f
        estadoTexto.text = when (e) {
            Bus.Estado.APAGADO -> "${prefs.nombre} está apagada. Tocá la estrella para encenderla."
            Bus.Estado.PREPARANDO -> detalle.ifBlank { "Preparando…" }
            Bus.Estado.ESPERA -> "Decí «${prefs.nombre}» o tocá la estrella"
            Bus.Estado.ESCUCHA -> "Te escucho…"
            Bus.Estado.PENSANDO -> detalle.ifBlank { "Pensando…" }
            Bus.Estado.HABLANDO -> "Tocá la estrella para interrumpir"
        }
        if (e != Bus.Estado.ESCUCHA) parcial.visibility = View.GONE
        mostrarEncendido()
    }

    private fun mostrarEncendido() {
        val activa = AsistenteService.corriendo
        findViewById<TextView>(R.id.encendidoTexto).text = if (activa) "Activa" else "Apagada"
        findViewById<ImageView>(R.id.encendidoPunto).imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (activa) R.color.ok else R.color.bruma))
        findViewById<View>(R.id.encendido).contentDescription =
            if (activa) "${prefs.nombre} está activa. Tocá para apagarla." else "${prefs.nombre} está apagada. Tocá para encenderla."
    }

    private fun mostrarAviso() {
        val faltan = Pasos.pendientes(this)
        val aviso = findViewById<View>(R.id.aviso)
        if (faltan.isEmpty()) { aviso.visibility = View.GONE; return }
        aviso.visibility = View.VISIBLE
        val esenciales = faltan.filter { it.esencial }
        findViewById<TextView>(R.id.avisoTitulo).text = when {
            esenciales.isNotEmpty() -> "Falta${if (esenciales.size > 1) "n" else ""} ${esenciales.size} paso${if (esenciales.size > 1) "s" else ""} para encenderla"
            else -> "Podés hacer más con ${faltan.size} permiso${if (faltan.size > 1) "s" else ""}"
        }
        findViewById<TextView>(R.id.avisoTexto).text = faltan.joinToString(" · ") { it.titulo }
    }

    // ------------------------------------------------------------ ACCIONES
    private fun tocarEstrella() {
        if (!AsistenteService.corriendo) { encender(); return }
        startService(Intent(this, AsistenteService::class.java).setAction(AsistenteService.ACCION_ESCUCHAR))
    }

    private fun alternarEncendido() {
        if (AsistenteService.corriendo) {
            startService(Intent(this, AsistenteService::class.java).setAction(AsistenteService.ACCION_DETENER))
            estrella.postDelayed({ refrescar() }, 400)
        } else encender()
    }

    private fun encender(): Boolean {
        if (!Pasos.puedeEncender(this)) {
            Toast.makeText(this, "Primero cargá la clave y dale permiso al micrófono", Toast.LENGTH_LONG).show()
            abrirAjustes(); return false
        }
        ContextCompat.startForegroundService(this, Intent(this, AsistenteService::class.java)
            .setAction(AsistenteService.ACCION_ENCENDER))
        estrella.postDelayed({ refrescar() }, 400)
        return true
    }

    private fun enviarTexto() {
        val t = entrada.text.toString().trim()
        if (t.isEmpty()) return
        if (enviar(t)) entrada.setText("")
    }

    private fun enviar(texto: String): Boolean {
        if (!Pasos.puedeEncender(this)) {
            Toast.makeText(this, "Primero cargá la clave y dale permiso al micrófono", Toast.LENGTH_LONG).show()
            abrirAjustes(); return false
        }
        // Funciona aunque Nova esté apagada: la enciende para este pedido
        ContextCompat.startForegroundService(this, Intent(this, AsistenteService::class.java)
            .setAction(AsistenteService.ACCION_TEXTO).putExtra(AsistenteService.EXTRA_TEXTO, texto))
        return true
    }

    private fun abrirAjustes() = startActivity(Intent(this, AjustesActivity::class.java))

    // ------------------------------------------------------------ CONVERSACIÓN
    private fun reconstruirChat() {
        while (chat.childCount > 1) chat.removeViewAt(1)   // el primer hijo son las sugerencias
        for (m in Bus.conversacion) agregarBurbuja(m, animar = false)
        sugerencias.visibility = if (Bus.conversacion.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun agregarBurbuja(m: Bus.Mensaje, animar: Boolean) {
        sugerencias.visibility = View.GONE
        val ancho = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val burbuja = TextView(this).apply {
            text = m.texto
            textSize = 16f
            maxWidth = ancho
            setLineSpacing(0f, 1.2f)
            setPadding(dp(18), dp(12), dp(18), dp(13))
            setTextIsSelectable(true)
            if (m.deVos) {
                setBackgroundResource(R.drawable.bg_burbuja_vos)
                setTextColor(ContextCompat.getColor(context, R.color.sobre_estrella))
            } else {
                setBackgroundResource(R.drawable.bg_burbuja_nova)
                setTextColor(ContextCompat.getColor(context, R.color.texto))
            }
        }
        chat.addView(burbuja, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (m.deVos) Gravity.END else Gravity.START
            topMargin = dp(10)
        })
        if (animar) {
            burbuja.alpha = 0f
            burbuja.translationY = dp(10).toFloat()
            burbuja.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
        scroll.post { scroll.smoothScrollTo(0, chat.bottom) }
    }

    // ------------------------------------------------------------ UTILIDADES
    private fun ajustarBordes(v: View) {
        ViewCompat.setOnApplyWindowInsetsListener(v) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            vista.updatePadding(top = barras.top, bottom = maxOf(barras.bottom, teclado.bottom))
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
