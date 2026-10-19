package com.mateo.nova

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton

/** Ajustes: pasos para que funcione, personalización y cerebro. */
class AjustesActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var nombre: EditText
    private lateinit var usuario: EditText
    private lateinit var ciudad: EditText
    private lateinit var clave: EditText
    private lateinit var limite: EditText
    private lateinit var clavePollinations: EditText
    private lateinit var modelo: EditText
    private lateinit var codigoVinculo: EditText
    private lateinit var repo: EditText
    private lateinit var tokenGithub: EditText
    private lateinit var confirmar: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var leerMensajes: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var libreEnCharla: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var libreClave: EditText
    private lateinit var libreModelo: EditText
    private var libreProveedor = ""

    private val pedirPermisos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        dibujarPasos()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ajustes)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenido)) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(top = barras.top, bottom = maxOf(barras.bottom, teclado.bottom))
            insets
        }

        nombre = findViewById(R.id.nombre)
        usuario = findViewById(R.id.usuario)
        ciudad = findViewById(R.id.ciudad)
        clave = findViewById(R.id.clave)
        limite = findViewById(R.id.limite)
        clavePollinations = findViewById(R.id.clavePollinations)
        clavePollinations.setText(prefs.clavePollinations)
        modelo = findViewById(R.id.modelo)

        nombre.setText(prefs.nombre)
        usuario.setText(prefs.usuario)
        ciudad.setText(prefs.ciudad)
        clave.setText(prefs.apiKey)
        limite.setText(prefs.limitePorHora.toString())
        modelo.setText(prefs.modelo)

        codigoVinculo = findViewById(R.id.codigoVinculo); codigoVinculo.setText(prefs.codigoVinculo)
        repo = findViewById(R.id.repo); repo.setText(prefs.repo)
        tokenGithub = findViewById(R.id.tokenGithub); tokenGithub.setText(prefs.tokenGithub)
        findViewById<TextView>(R.id.versionActual).text = "Tenés la versión 1.${Actualizador.versionActual(this)}"
        findViewById<View>(R.id.buscarActualizacion).setOnClickListener { boton ->
            prefs.repo = repo.text.toString(); prefs.tokenGithub = tokenGithub.text.toString()
            boton.isEnabled = false
            Thread {
                val (novedad, texto) = Actualizador.revisar(this)
                runOnUiThread {
                    boton.isEnabled = true
                    if (novedad == null) Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
                    else AlertDialog.Builder(this).setTitle("Versión nueva").setMessage("$texto ¿La instalo?")
                        .setPositiveButton("Actualizar") { _, _ ->
                            Thread { val r = Actualizador.descargarEInstalar(this, novedad)
                                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() } }.start()
                        }.setNegativeButton("Después", null).show()
                }
            }.start()
        }

        confirmar = findViewById(R.id.confirmar)
        confirmar.isChecked = prefs.confirmar
        val ayuda = findViewById<TextView>(R.id.ayudaConfirmar)
        fun textoAyuda(on: Boolean) {
            ayuda.text = if (on) "Te pregunta antes de mandar mensajes, llamar o publicar."
                         else "Modo libre: hace todo directo. Igual pregunta antes de pagar o borrar."
            // Apagado se ve gris, encendido dorado
            confirmar.trackTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.estrella else R.color.linea))
            confirmar.thumbTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.sobre_estrella else R.color.bruma))
        }
        textoAyuda(confirmar.isChecked)
        confirmar.setOnCheckedChangeListener { _, on -> textoAyuda(on) }

        leerMensajes = findViewById(R.id.leerMensajes)
        leerMensajes.isChecked = prefs.leerMensajes
        fun colorLeer(on: Boolean) {
            leerMensajes.trackTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.estrella else R.color.linea))
            leerMensajes.thumbTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.sobre_estrella else R.color.bruma))
        }
        colorLeer(leerMensajes.isChecked)
        leerMensajes.setOnCheckedChangeListener { _, on -> colorLeer(on) }

        // Cerebro libre
        libreProveedor = prefs.libreProveedor
        libreClave = findViewById(R.id.libreClave); libreClave.setText(prefs.libreClave)
        libreModelo = findViewById(R.id.libreModelo); libreModelo.setText(prefs.libreModelo)
        libreEnCharla = findViewById(R.id.libreEnCharla); libreEnCharla.isChecked = prefs.libreEnCharla
        fun colorLibre(on: Boolean) {
            libreEnCharla.trackTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.estrella else R.color.linea))
            libreEnCharla.thumbTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.sobre_estrella else R.color.bruma))
        }
        colorLibre(libreEnCharla.isChecked)
        libreEnCharla.setOnCheckedChangeListener { _, on -> colorLibre(on) }
        mostrarLibre()
        findViewById<View>(R.id.filaLibre).setOnClickListener { elegirLibre() }
        findViewById<View>(R.id.linkOpenRouter).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")))
        }

        findViewById<View>(R.id.atras).setOnClickListener { finish() }
        mostrarVozYPersonalidad()
        findViewById<View>(R.id.filaVoz).setOnClickListener { elegirVoz() }
        findViewById<View>(R.id.filaPersonalidad).setOnClickListener { elegirPersonalidad() }
        findViewById<View>(R.id.guardar).setOnClickListener { guardar() }
        findViewById<View>(R.id.linkPollinations).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://enter.pollinations.ai/keys")))
        }
        findViewById<View>(R.id.linkClave).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        findViewById<View>(R.id.borrarCharla).setOnClickListener {
            Bus.conversacion.clear()
            Toast.makeText(this, "Conversación borrada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        dibujarPasos()
        val usadas = prefs.usoUltimaHora()
        findViewById<TextView>(R.id.uso).text =
            if (prefs.limitePorHora <= 0) "Sin límite propio. Usaste $usadas en la última hora."
            else "Usaste $usadas de ${prefs.limitePorHora} en la última hora. 0 = sin límite."
    }

    // ------------------------------------------------------------ PASOS
    private fun dibujarPasos() {
        val cont = findViewById<LinearLayout>(R.id.permisos)
        cont.removeAllViews()
        Pasos.todos.forEachIndexed { i, paso ->
            val fila = LayoutInflater.from(this).inflate(R.layout.fila_paso, cont, false)
            val hecho = Pasos.hecho(this, paso.tipo)
            fila.findViewById<TextView>(R.id.titulo).text = paso.titulo
            fila.findViewById<TextView>(R.id.descripcion).text = paso.descripcion
            val numero = fila.findViewById<TextView>(R.id.numero)
            if (hecho) {
                numero.text = "✓"
                numero.setTextColor(ContextCompat.getColor(this, R.color.cielo))
                numero.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.ok))
            } else {
                numero.text = "${i + 1}"
            }
            fila.findViewById<View>(R.id.listo).visibility = if (hecho) View.VISIBLE else View.GONE
            val boton = fila.findViewById<MaterialButton>(R.id.activar)
            boton.visibility = if (hecho) View.GONE else View.VISIBLE
            if (paso.tipo == Pasos.Tipo.CLAVE) {
                boton.text = "Cargar"
                boton.setOnClickListener { clave.requestFocus() }
            } else boton.setOnClickListener { activar(paso.tipo) }
            if (i > 0) cont.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.linea))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(46) })
            cont.addView(fila)
        }
    }

    private fun activar(tipo: Pasos.Tipo) {
        when (tipo) {
            Pasos.Tipo.MICROFONO -> pedirPermisos.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            Pasos.Tipo.TELEFONO -> pedirPermisos.launch(Pasos.permisosTelefono)
            Pasos.Tipo.AGENDA -> pedirPermisos.launch(Pasos.permisosAgenda)
            Pasos.Tipo.FOTOS -> pedirPermisos.launch(Pasos.permisosFotos)
            Pasos.Tipo.BLUETOOTH -> pedirPermisos.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            Pasos.Tipo.UBICACION ->
                // Android pide en dos pasos: primero "mientras se usa" y después "todo el tiempo"
                if (!Automatizaciones.tienePermisoUbicacion(this))
                    pedirPermisos.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                else AlertDialog.Builder(this)
                    .setTitle("Ubicación todo el tiempo")
                    .setMessage("En la pantalla que se abre elegí «Permitir todo el tiempo». Nova solo la usa para saber " +
                        "si llegaste o saliste de los lugares que guardaste; no la guarda ni la manda a ningún lado.")
                    .setPositiveButton("Continuar") { _, _ ->
                        pedirPermisos.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            Pasos.Tipo.NOTIFICACIONES -> pedirPermisos.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            Pasos.Tipo.BATERIA -> runCatching { startActivity(Pasos.intentBateria(this)) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            Pasos.Tipo.PANTALLA -> AlertDialog.Builder(this)
                .setTitle("Activar el control de pantalla")
                .setMessage("En la pantalla que se abre, buscá «${prefs.nombre}» y activalo.\n\n" +
                    "Si Android dice «configuración restringida»: andá a Ajustes › Apps › ${prefs.nombre} › ⋮ › " +
                    "Permitir configuración restringida, y volvé a intentar.")
                .setPositiveButton("Ir a Accesibilidad") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Ahora no", null)
                .show()
            Pasos.Tipo.MENSAJES -> AlertDialog.Builder(this)
                .setTitle("Leer tus mensajes")
                .setMessage("En la pantalla que se abre, buscá «${prefs.nombre}» y activalo.\n\n" +
                    "Nova solo usa los mensajes para leértelos y responderlos cuando se lo pedís; no se guardan ni se mandan a ningún lado " +
                    "salvo a Gemini cuando le pedís un resumen.\n\n" +
                    "Si Android dice «configuración restringida»: Ajustes › Apps › ${prefs.nombre} › ⋮ › Permitir configuración restringida.")
                .setPositiveButton("Ir a los ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Ahora no", null)
                .show()
            Pasos.Tipo.CLAVE -> clave.requestFocus()
        }
    }

    // ------------------------------------------------------------ VOZ Y PERSONALIDAD
    private fun mostrarVozYPersonalidad() {
        findViewById<TextView>(R.id.filaVozValor).text = VozNatural.VOCES[prefs.voz] ?: prefs.voz
        findViewById<TextView>(R.id.filaPersonalidadValor).text =
            if (prefs.personalidad == "propia") "Propia: ${prefs.personalidadPropia.take(40)}…"
            else Personalidad.ESTILOS[prefs.personalidad]?.first ?: "Cálida"
    }

    private val nombresLibre = linkedMapOf("" to "Apagado", "pollinations" to "Pollinations (usa tu clave de imágenes)",
        "openrouter" to "OpenRouter (gratis, unos 200 mensajes por día)")

    private fun mostrarLibre() {
        findViewById<TextView>(R.id.filaLibreValor).text = nombresLibre[libreProveedor] ?: "Apagado"
        findViewById<View>(R.id.grupoLibreClave).visibility = if (libreProveedor == "openrouter") View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.ayudaLibreModelo).text = when (libreProveedor) {
            "pollinations" -> "Recomendado: deepseek/deepseek-v4-flash. Otros abiertos: moonshotai/kimi-k3, z-ai/glm-5.3, x-ai/grok-4.3."
            "openrouter" -> "Recomendado: openrouter/free (elige solo un modelo gratis). Podés poner cualquiera que termine en :free."
            else -> "Elegí un proveedor para activarlo."
        }
    }

    private fun elegirLibre() {
        val claves = nombresLibre.keys.toList()
        AlertDialog.Builder(this).setTitle("Cerebro libre")
            .setSingleChoiceItems(nombresLibre.values.toTypedArray(), claves.indexOf(libreProveedor)) { d, i ->
                libreProveedor = claves[i]; mostrarLibre(); d.dismiss()
                if (libreProveedor == "pollinations" && clavePollinations.text.isBlank())
                    Toast.makeText(this, "Cargá también la clave de Pollinations, más arriba.", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun elegirVoz() {
        val claves = VozNatural.VOCES.keys.toList()
        AlertDialog.Builder(this).setTitle("Voz")
            .setSingleChoiceItems(VozNatural.VOCES.values.toTypedArray(), claves.indexOf(prefs.voz)) { d, i ->
                prefs.voz = claves[i]; mostrarVozYPersonalidad(); d.dismiss()
            }.show()
    }

    private fun elegirPersonalidad() {
        val claves = Personalidad.ESTILOS.keys.toList()
        val nombres = Personalidad.ESTILOS.values.map { it.first }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Personalidad")
            .setSingleChoiceItems(nombres, claves.indexOf(prefs.personalidad)) { d, i ->
                d.dismiss()
                if (claves[i] != "propia") { prefs.personalidad = claves[i]; mostrarVozYPersonalidad(); return@setSingleChoiceItems }
                val campo = EditText(this).apply {
                    setText(prefs.personalidadPropia); hint = "Ej: una porteña fanática del fútbol que habla rápido y tira referencias"
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                }
                AlertDialog.Builder(this).setTitle("¿Cómo querés que sea?").setView(campo)
                    .setPositiveButton("Guardar") { _, _ ->
                        prefs.personalidadPropia = campo.text.toString(); prefs.personalidad = "propia"; mostrarVozYPersonalidad()
                    }.setNegativeButton("Cancelar", null).show()
            }.show()
    }

    // ------------------------------------------------------------ GUARDAR
    private fun guardar() {
        val nombreAnterior = prefs.nombre
        prefs.nombre = nombre.text.toString()
        prefs.usuario = usuario.text.toString()
        prefs.ciudad = ciudad.text.toString()
        prefs.apiKey = clave.text.toString()
        prefs.limitePorHora = limite.text.toString().toIntOrNull() ?: 0
        prefs.clavePollinations = clavePollinations.text.toString()
        prefs.libreProveedor = libreProveedor
        prefs.libreClave = libreClave.text.toString()
        prefs.libreModelo = libreModelo.text.toString()
        prefs.libreEnCharla = libreEnCharla.isChecked
        prefs.modelo = modelo.text.toString()
        prefs.confirmar = confirmar.isChecked
        val vinculoCambio = codigoVinculo.text.toString().trim() != prefs.codigoVinculo
        prefs.codigoVinculo = codigoVinculo.text.toString()
        prefs.repo = repo.text.toString()
        prefs.tokenGithub = tokenGithub.text.toString()
        prefs.leerMensajes = leerMensajes.isChecked

        // El nombre nuevo y el vínculo con la PC recién se aplican al reiniciar el servicio
        if ((prefs.nombre != nombreAnterior || vinculoCambio) && AsistenteService.corriendo) {
            startService(Intent(this, AsistenteService::class.java).setAction(AsistenteService.ACCION_DETENER))
            findViewById<View>(R.id.guardar).postDelayed({
                if (Pasos.puedeEncender(this))
                    ContextCompat.startForegroundService(this, Intent(this, AsistenteService::class.java)
                        .setAction(AsistenteService.ACCION_ENCENDER))
            }, 700)
        }
        Toast.makeText(this, "Cambios guardados", Toast.LENGTH_SHORT).show()
        dibujarPasos()
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
