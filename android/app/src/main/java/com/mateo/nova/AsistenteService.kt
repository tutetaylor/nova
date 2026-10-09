package com.mateo.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Corre siempre en primer plano:
 *  ESPERA    -> escucha continua y offline del nombre ("Nova") con Vosk: sin bip y sin pausar tu audio
 *  ESCUCHA   -> graba el pedido completo
 *  PENSANDO  -> Gemini decide y usa herramientas
 *  HABLANDO  -> responde con voz; si terminó con pregunta vuelve a ESCUCHA, si no a ESPERA
 */
class AsistenteService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACCION_ESCUCHAR = "com.mateo.nova.ESCUCHAR"
        const val ACCION_DETENER = "com.mateo.nova.DETENER"
        const val ACCION_TEXTO = "com.mateo.nova.TEXTO"
        const val EXTRA_TEXTO = "texto"
        private const val CANAL = "nova"
        private const val TAG = "Nova"

        @Volatile var corriendo = false
        /** La pantalla principal se suscribe para mostrar lo que pasa. */
        var alCambiarEstado: ((String) -> Unit)? = null
    }

    private enum class Estado { ESPERA, ESCUCHA, PENSANDO, HABLANDO }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var cerebro: Cerebro
    private lateinit var comandos: Comandos
    private lateinit var oido: PalabraClave
    private var oidoListo = false
    private var reco: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var estado = Estado.ESPERA
    private var seguirDespuesDeHablar = false

    // ------------------------------------------------------------ CICLO DE VIDA
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val herramientas = Herramientas(this, prefs)
        comandos = Comandos(herramientas)
        cerebro = Cerebro(prefs, herramientas).apply {
            alUsarHerramienta = { n -> main.post { notificar("Usando $n…") } }
        }
        oido = PalabraClave(this, prefs.nombre)
        tts = TextToSpeech(this, this)
        crearCanal()
        val notif = notificacion("Decí \"${prefs.nombre}\" para hablarme")
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notif)
            corriendo = true
            prepararOido()
        } catch (e: Exception) {
            // Android 14+ no deja usar el micrófono si el servicio arranca solo desde segundo plano
            Log.e(TAG, "No se pudo iniciar en primer plano", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> { stopSelf(); return START_NOT_STICKY }
            ACCION_ESCUCHAR -> main.post { if (estado != Estado.PENSANDO) { tts?.stop(); escucharPedido() } }
            ACCION_TEXTO -> intent.getStringExtra(EXTRA_TEXTO)?.let { t -> main.post { procesar(t) } }
            else -> main.post { if (estado == Estado.ESPERA) esperarNombre() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        corriendo = false
        oido.liberar()
        reco?.destroy(); reco = null
        tts?.shutdown()
        scope.cancel()
        alCambiarEstado?.invoke("Detenido")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ VOZ (TTS)
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        val r = t.setLanguage(Locale("es", "AR"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale("es"))
        t.setSpeechRate(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { terminoDeHablar() } }
            @Deprecated("") override fun onError(id: String?) { main.post { terminoDeHablar() } }
        })
        ttsListo = true
    }

    private fun decir(texto: String, seguir: Boolean) {
        if (texto.isBlank()) { esperarNombre(); return }   // acción hecha, no hace falta hablar
        estado = Estado.HABLANDO
        seguirDespuesDeHablar = seguir
        frenarReconocimiento()
        oido.detener()          // que no se escuche a sí mismo
        notificar(texto)
        alCambiarEstado?.invoke("${prefs.nombre}: $texto")
        if (!ttsListo) { main.postDelayed({ terminoDeHablar() }, 500); return }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, Bundle(), "r${System.currentTimeMillis()}")
    }

    private fun terminoDeHablar() {
        if (estado != Estado.HABLANDO) return
        if (seguirDespuesDeHablar) escucharPedido() else esperarNombre()
    }

    // ------------------------------------------------------------ OÍDO (STT)
    private fun intentReco() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
    }

    private fun nuevoReconocedor(): SpeechRecognizer {
        reco?.destroy()
        return SpeechRecognizer.createSpeechRecognizer(this).also { reco = it }
    }

    private fun frenarReconocimiento() {
        reco?.cancel()
    }

    /** Primera vez: baja el modelo offline (~40 MB). Después solo lo carga (1-2 s). */
    private fun prepararOido() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!oido.modeloInstalado) oido.instalarModelo { pct ->
                        main.post {
                            notificar("Descargando la voz offline (solo la primera vez): $pct%")
                            alCambiarEstado?.invoke("Descargando la voz offline: $pct%")
                        }
                    }
                    oido.cargar()
                }
                oidoListo = true
                if (estado == Estado.ESPERA) esperarNombre()
            } catch (e: Exception) {
                Log.e(TAG, "Modelo", e)
                val msg = "No pude preparar la escucha offline (${e.message?.take(60)}). Revisá internet y reiniciá ${prefs.nombre}."
                notificar(msg); alCambiarEstado?.invoke(msg)
            }
        }
    }

    /** Modo espera: escucha continua offline. No hace bip ni pausa tu música. */
    private fun esperarNombre() {
        estado = Estado.ESPERA
        if (!oidoListo) return   // prepararOido() lo arranca cuando termine
        notificar("Decí \"${prefs.nombre}\" para hablarme")
        alCambiarEstado?.invoke("Esperando \"${prefs.nombre}\"…")
        oido.escuchar(
            alDetectar = { main.post { if (estado == Estado.ESPERA) escucharPedido() } },
            alError = { msg ->
                main.post {
                    Log.w(TAG, "Escucha: $msg")
                    main.postDelayed({ if (estado == Estado.ESPERA) esperarNombre() }, 2000)
                }
            })
    }

    /** Escucha el pedido con el reconocedor de Google (más preciso). Su bip es la señal de "te escucho". */
    private fun escucharPedido() {
        estado = Estado.ESCUCHA
        oido.detener()   // libera el micrófono
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notificar("Este celular no tiene reconocimiento de voz (instalá la app de Google)")
            esperarNombre(); return
        }
        notificar("Te escucho…")
        alCambiarEstado?.invoke("Te escucho…")
        val r = nuevoReconocedor()
        r.setRecognitionListener(object : Escucha() {
            override fun onPartialResults(partialResults: Bundle?) {
                textos(partialResults).firstOrNull()?.let { alCambiarEstado?.invoke("Vos: $it") }
            }
            override fun onResults(results: Bundle?) {
                if (estado != Estado.ESCUCHA) return
                val t = textos(results).firstOrNull().orEmpty()
                if (t.isBlank()) esperarNombre() else procesar(quitarNombre(t))
            }
            override fun onError(error: Int) { if (estado == Estado.ESCUCHA) esperarNombre() }
        })
        r.startListening(intentReco())
    }

    // ------------------------------------------------------------ PENSAR
    private fun procesar(pedido: String) {
        frenarReconocimiento()
        estado = Estado.PENSANDO
        alCambiarEstado?.invoke("Vos: $pedido")
        val p = normalizar(pedido).trim(' ', '.', '!', '?')
        if ("cuanto uso" in p || "limite de uso" in p) { decir(cerebro.resumenUso(), false); return }  // gratis
        when (p) {
            "nada", "chau", "gracias", "listo", "cancelar", "eso es todo" -> { decir("Dale, cualquier cosa me llamás.", false); return }
            "olvida todo", "nueva conversacion", "empecemos de nuevo" -> { cerebro.olvidar(); decir("Listo, empezamos de cero.", false); return }
            "callate", "silencio", "para" -> { tts?.stop(); esperarNombre(); return }
        }
        scope.launch {
            // 1) Atajo instantáneo (sin internet ni cuota). 2) Si no aplica, Gemini.
            val rapido = withContext(Dispatchers.IO) { runCatching { comandos.intentar(pedido) }.getOrNull() }
            if (rapido != null) { decir(rapido, seguir = false); return@launch }
            notificar("Pensando…")
            alCambiarEstado?.invoke("Pensando…")
            val respuesta = withContext(Dispatchers.IO) {
                try { cerebro.pensar(pedido) }
                catch (e: Exception) { Log.e(TAG, "Error", e); "Uy, tuve un problema: ${e.message?.take(80)}. ¿Probamos de nuevo?" }
            }
            decir(respuesta, seguir = respuesta.trimEnd().endsWith("?"))
        }
    }

    // ------------------------------------------------------------ UTILIDADES
    private fun textos(b: Bundle?): List<String> =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() } ?: emptyList()

    /** Si el pedido empieza con el nombre ("Nova, poné..."), lo saca. */
    private fun quitarNombre(t: String): String {
        val palabras = t.trim().split(Regex("\\s+"))
        val primera = normalizar(palabras.first()).trim(',', '.')
        return if (palabras.size > 1 && primera == normalizar(prefs.nombre)) palabras.drop(1).joinToString(" ") else t
    }

    private fun crearCanal() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, prefs.nombre, NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hablar = PendingIntent.getService(this, 1,
            Intent(this, AsistenteService::class.java).setAction(ACCION_ESCUCHAR), PendingIntent.FLAG_IMMUTABLE)
        val detener = PendingIntent.getService(this, 2,
            Intent(this, AsistenteService::class.java).setAction(ACCION_DETENER), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(prefs.nombre)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Hablar", hablar).build())
            .addAction(Notification.Action.Builder(null, "Detener", detener).build())
            .build()
    }

    private fun notificar(texto: String) {
        getSystemService(NotificationManager::class.java).notify(1, notificacion(texto))
    }

    /** RecognitionListener con métodos vacíos por defecto. */
    private abstract class Escucha : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
