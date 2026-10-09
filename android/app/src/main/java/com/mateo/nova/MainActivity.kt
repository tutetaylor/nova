package com.mateo.nova

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Pantalla de configuración: clave, nombre, permisos y encendido. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var estadoPermisos: TextView
    private lateinit var log: TextView
    private lateinit var botonServicio: Button

    private val permisos = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = prefs.nombre

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(32)) }
        fun titulo(t: String) = col.addView(TextView(this).apply { text = t; textSize = 17f; setPadding(0, dp(18), 0, dp(6)) })
        fun campo(hint: String, valor: String, secreto: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(valor); setSingleLine()
            if (secreto) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            col.addView(this)
        }
        fun boton(t: String, accion: () -> Unit) = Button(this).apply { text = t; setOnClickListener { accion() }; col.addView(this) }

        titulo("1. Personalizá tu asistente")
        val key = campo("Clave GRATIS de Gemini (AIza…) — aistudio.google.com/apikey", prefs.apiKey, secreto = true)
        val nombre = campo("Nombre / palabra de activación", prefs.nombre)
        val usuario = campo("Tu nombre", prefs.usuario)
        val ciudad = campo("Tu ciudad", prefs.ciudad)
        val modelo = campo("Modelo", prefs.modelo)
        val limite = campo("Límite de consultas por hora (0 = sin límite)", prefs.limitePorHora.toString())
            .apply { inputType = InputType.TYPE_CLASS_NUMBER }
        boton("Guardar") {
            prefs.apiKey = key.text.toString(); prefs.nombre = nombre.text.toString()
            prefs.usuario = usuario.text.toString(); prefs.ciudad = ciudad.text.toString()
            prefs.modelo = modelo.text.toString()
            prefs.limitePorHora = limite.text.toString().toIntOrNull() ?: 60
            title = prefs.nombre
            mostrar("Guardado. Reiniciá el asistente para aplicar el nombre nuevo.")
        }

        titulo("2. Permisos")
        estadoPermisos = TextView(this).also { col.addView(it) }
        boton("Dar permisos (micrófono, contactos, llamadas, SMS)") { ActivityCompat.requestPermissions(this, permisos, 1) }
        boton("Activar control de pantalla (Accesibilidad)") {
            mostrar("Buscá \"${prefs.nombre}\" en la lista de Accesibilidad y activalo.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        boton("Sacar optimización de batería") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        titulo("3. Encender")
        botonServicio = boton("") { alternarServicio() }

        titulo("Probar escribiendo")
        val prueba = campo("Ej: poné una alarma a las 7", "")
        boton("Enviar") {
            val t = prueba.text.toString().trim()
            if (t.isNotEmpty()) {
                if (!AsistenteService.corriendo) alternarServicio()
                startService(Intent(this, AsistenteService::class.java)
                    .setAction(AsistenteService.ACCION_TEXTO).putExtra(AsistenteService.EXTRA_TEXTO, t))
                prueba.setText("")
            }
        }
        log = TextView(this).apply { setPadding(0, dp(12), 0, 0); gravity = Gravity.START; setTextIsSelectable(true) }
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })
        AsistenteService.alCambiarEstado = { t -> runOnUiThread { mostrar(t); actualizar() } }
    }

    override fun onResume() { super.onResume(); actualizar() }

    override fun onDestroy() { AsistenteService.alCambiarEstado = null; super.onDestroy() }

    private fun alternarServicio() {
        val i = Intent(this, AsistenteService::class.java)
        if (AsistenteService.corriendo) {
            startService(i.setAction(AsistenteService.ACCION_DETENER))
        } else {
            if (prefs.apiKey.isBlank()) { mostrar("Primero cargá y guardá tu clave gratis de Gemini (aistudio.google.com/apikey)."); return }
            if (!tiene(Manifest.permission.RECORD_AUDIO)) { mostrar("Falta el permiso de micrófono."); return }
            ContextCompat.startForegroundService(this, i)
        }
        botonServicio.postDelayed({ actualizar() }, 600)
    }

    private fun actualizar() {
        val faltan = permisos.filter { !tiene(it) }.map { it.substringAfterLast(".") }
        estadoPermisos.text = buildString {
            append(if (faltan.isEmpty()) "✅ Permisos completos\n" else "⚠️ Faltan: ${faltan.joinToString()}\n")
            append(if (ControlAccesibilidad.activo) "✅ Control de pantalla activo" else "⚠️ Control de pantalla apagado")
        }
        botonServicio.text = if (AsistenteService.corriendo) "Detener ${prefs.nombre}" else "Iniciar ${prefs.nombre}"
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r); actualizar()
    }

    private fun tiene(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun mostrar(t: String) { log.text = "$t\n\n${log.text}".take(4000); log.visibility = View.VISIBLE }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
