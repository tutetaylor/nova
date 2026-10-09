package com.mateo.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
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
 *  ESPERA    -> escucha en loop buscando el nombre ("Nova")
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
    private lateinit var audio: AudioManager
    private var reco: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var estado = Estado.ESPERA
    private var seguirDespuesDeHablar = false
    private val silenciados = mutableListOf<Int>()

    // ------------------------------------------------------------ CICLO DE VIDA
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cerebro = Cerebro(prefs, Herramientas(this, prefs)).apply {
            alUsarHerramienta = { n -> main.post { notificar("Usando $n…") } }
        }
        tts = TextToSpeech(this, this)
        crearCanal()
        val notif = notificacion("Decí \"${prefs.nombre}\" para hablarme")
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, notif)
            corriendo = true
        } catch (e: Exception) {
            // Android 14+ no deja usar el micrófono si el servicio arranca solo desde segundo plano
            Log.e(TAG, "No se pudo iniciar en primer plano", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_DETENER -> { stopSelf(); return START_NOT_STICKY }
            ACCION_ESCUCHAR -> main.post { if (estado != Estado.PENSANDO) escucharPedido(conTono = true) }
            ACCION_TEXTO -> intent.getStringExtra(EXTRA_TEXTO)?.let { t -> main.post { procesar(t) } }
            else -> main.post { if (estado == Estado.ESPERA) esperarNombre() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        corriendo = false
        restaurarVolumen()
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
        estado = Estado.HABLANDO
        seguirDespuesDeHablar = seguir
        frenarReconocimiento()
        notificar(texto)
        alCambiarEstado?.invoke("${prefs.nombre}: $texto")
        if (!ttsListo) { main.postDelayed({ terminoDeHablar() }, 500); return }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, Bundle(), "r${System.currentTimeMillis()}")
    }

    private fun terminoDeHablar() {
        if (estado != Estado.HABLANDO) return
        if (seguirDespuesDeHablar) escucharPedido(conTono = false) else esperarNombre()
    }

    // ------------------------------------------------------------ OÍDO (STT)
    private fun intentReco(espera: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        if (espera) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // menos datos y batería en modo espera
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    private fun nuevoReconocedor(): SpeechRecognizer {
        reco?.destroy()
        return SpeechRecognizer.createSpeechRecognizer(this).also { reco = it }
    }

    private fun frenarReconocimiento() {
        reco?.cancel()
    }

    /** Modo espera: escucha en loop y solo reacciona si oye el nombre. */
    private fun esperarNombre() {
        estado = Estado.ESPERA
        notificar("Decí \"${prefs.nombre}\" para hablarme")
        alCambiarEstado?.invoke("Esperando \"${prefs.nombre}\"…")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            notificar("Este celular no tiene reconocimiento de voz (instalá la app de Google)")
            return
        }
        silenciarPitido()
        val r = nuevoReconocedor()
        r.setRecognitionListener(object : Escucha() {
            override fun onReadyForSpeech(params: Bundle?) { main.postDelayed({ restaurarVolumen() }, 400) }
            override fun onResults(results: Bundle?) {
                if (estado != Estado.ESPERA) return
                val t = textos(results).firstOrNull { contieneNombre(it) }
                if (t != null) activado(restoDespuesDelNombre(t)) else reintentarEspera()
            }
            override fun onError(error: Int) { if (estado == Estado.ESPERA) reintentarEspera(error) }
        })
        r.startListening(intentReco(espera = true))
    }

    private fun reintentarEspera(error: Int = 0) {
        restaurarVolumen()
        val demora = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> 1500L
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER -> 3000L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { notificar("Falta el permiso de micrófono"); return }
            else -> 250L
        }
        main.postDelayed({ if (estado == Estado.ESPERA) esperarNombre() }, demora)
    }

    private fun activado(pedidoYaDicho: String) {
        frenarReconocimiento()
        if (pedidoYaDicho.split(" ").size >= 2) procesar(pedidoYaDicho)   // "Nova poné una alarma a las 7"
        else escucharPedido(conTono = true)
    }

    /** Escucha un pedido completo (después del nombre, o para seguir la charla). */
    private fun escucharPedido(conTono: Boolean) {
        estado = Estado.ESCUCHA
        restaurarVolumen()
        if (conTono) runCatching {
            val tono = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tono.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tono.release() }, 400)
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
                if (t.isBlank()) esperarNombre() else procesar(t)
            }
            override fun onError(error: Int) { if (estado == Estado.ESCUCHA) esperarNombre() }
        })
        main.postDelayed({ if (estado == Estado.ESCUCHA) r.startListening(intentReco(espera = false)) }, if (conTono) 200 else 0)
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
        notificar("Pensando…")
        scope.launch {
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

    private fun contieneNombre(t: String) = normalizar(t).split(" ").any { it.startsWith(normalizar(prefs.nombre)) }

    private fun restoDespuesDelNombre(t: String): String {
        val palabras = t.trim().split(Regex("\\s+"))
        val i = palabras.indexOfFirst { normalizar(it).startsWith(normalizar(prefs.nombre)) }
        return if (i < 0) "" else palabras.drop(i + 1).joinToString(" ").trim(' ', ',', '.')
    }

    /** El reconocedor de Android hace "bip" cada vez que arranca; en modo espera lo silenciamos un instante. */
    private fun silenciarPitido() {
        if (!prefs.silenciarPitidos || silenciados.isNotEmpty()) return
        val streams = mutableListOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        if (!audio.isMusicActive) streams.add(AudioManager.STREAM_MUSIC)
        for (s in streams) runCatching {
            if (!audio.isStreamMute(s)) { audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); silenciados.add(s) }
        }
    }

    private fun restaurarVolumen() {
        for (s in silenciados) runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
        silenciados.clear()
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
