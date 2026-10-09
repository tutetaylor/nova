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
        const val ACCION_ENCENDER = "com.mateo.nova.ENCENDER"
        const val ACCION_ESCUCHAR = "com.mateo.nova.ESCUCHAR"
        const val ACCION_DETENER = "com.mateo.nova.DETENER"
        const val ACCION_TEXTO = "com.mateo.nova.TEXTO"
        const val ACCION_CHARLA = "com.mateo.nova.CHARLA"

        private val PARA_CHARLAR = setOf("charlemos", "hablemos", "modo charla", "quiero charlar", "charlemos un rato",
            "hablemos un rato", "quiero hablar", "quiero hablar con vos", "charlemos un poco", "hablemos un poco")
        private val PARA_TERMINAR = setOf("nada", "chau", "gracias", "listo", "cancelar", "eso es todo", "terminamos",
            "termina la charla", "basta", "fin de la charla", "nos vemos", "hasta luego", "me voy", "despues seguimos")
        const val EXTRA_TEXTO = "texto"
        private const val CANAL = "nova"
        private const val TAG = "Nova"

        @Volatile var corriendo = false
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
    private var modoCharla = false
    private var silencios = 0

    // ------------------------------------------------------------ CICLO DE VIDA
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val herramientas = Herramientas(this, prefs)
        comandos = Comandos(herramientas)
        cerebro = Cerebro(prefs, herramientas).apply {
            alUsarHerramienta = { n ->
                main.post { notificar("Usando $n…") }
                Bus.estado(Bus.Estado.PENSANDO, descripcionHerramienta(n))
            }
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
            Bus.estado(Bus.Estado.PREPARANDO, "Preparando la escucha…")
            prepararOido()
        } catch (e: Exception) {
            // Android 14+ no deja usar el micrófono si el servicio arranca solo desde segundo plano
            Log.e(TAG, "No se pudo iniciar en primer plano", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null: Android lo reinició por su cuenta. Solo sigue si vos lo habías dejado encendido.
        if (intent == null && !prefs.quiereEncendido) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACCION_DETENER -> { prefs.quiereEncendido = false; stopSelf(); return START_NOT_STICKY }
            // Todo lo que hacés vos (encender, tocar la estrella, escribir) la deja encendida
            ACCION_ESCUCHAR -> { prefs.quiereEncendido = true; main.post { if (estado != Estado.PENSANDO) { tts?.stop(); escucharPedido() } } }
            ACCION_TEXTO -> { prefs.quiereEncendido = true; intent.getStringExtra(EXTRA_TEXTO)?.let { t -> main.post { procesar(t) } } }
            ACCION_CHARLA -> {
                prefs.quiereEncendido = true
                main.post {
                    if (modoCharla) { ponerCharla(false); tts?.stop(); frenarReconocimiento(); esperarNombre() }
                    else { ponerCharla(true); decir(apertura(), seguir = true) }
                }
            }
            ACCION_ENCENDER -> { prefs.quiereEncendido = true; main.post { if (estado == Estado.ESPERA) esperarNombre() } }
            else -> main.post { if (estado == Estado.ESPERA) esperarNombre() }   // reinicio de Android (ya verificado arriba)
        }
        return if (prefs.quiereEncendido) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        corriendo = false
        oido.liberar()
        reco?.destroy(); reco = null
        tts?.shutdown()
        scope.cancel()
        Bus.estado(Bus.Estado.APAGADO)
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
        if (texto.isBlank()) {   // acción hecha, no hace falta hablar
            Bus.mensaje(deVos = false, texto = "Listo.")
            if (seguir) escucharPedido() else esperarNombre()
            return
        }
        estado = Estado.HABLANDO
        seguirDespuesDeHablar = seguir
        frenarReconocimiento()
        oido.detener()          // que no se escuche a sí mismo
        notificar(texto)
        Bus.mensaje(deVos = false, texto = texto)
        Bus.estado(Bus.Estado.HABLANDO)
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
                            Bus.estado(Bus.Estado.PREPARANDO, "Bajando la voz offline · $pct%")
                        }
                    }
                    oido.cargar()
                }
                oidoListo = true
                if (estado == Estado.ESPERA) esperarNombre()
            } catch (e: Exception) {
                Log.e(TAG, "Modelo", e)
                val msg = "No pude preparar la escucha offline (${e.message?.take(60)}). Revisá internet y reiniciá ${prefs.nombre}."
                notificar(msg); Bus.estado(Bus.Estado.PREPARANDO, msg)
            }
        }
    }

    /** Modo espera: escucha continua offline. No hace bip ni pausa tu música. */
    private fun esperarNombre() {
        estado = Estado.ESPERA
        if (!oidoListo) {   // prepararOido() lo arranca cuando termine
            Bus.estado(Bus.Estado.PREPARANDO, "Preparando la escucha…"); return
        }
        notificar("Decí \"${prefs.nombre}\" para hablarme")
        Bus.estado(Bus.Estado.ESPERA)
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
        Bus.estado(Bus.Estado.ESCUCHA)
        val r = nuevoReconocedor()
        r.setRecognitionListener(object : Escucha() {
            override fun onPartialResults(partialResults: Bundle?) {
                textos(partialResults).firstOrNull()?.let { Bus.parcial(it) }
            }
            override fun onRmsChanged(rmsdB: Float) { Bus.nivel(rmsdB) }
            override fun onResults(results: Bundle?) {
                if (estado != Estado.ESCUCHA) return
                val t = textos(results).firstOrNull().orEmpty()
                if (t.isBlank()) sinRespuesta() else { silencios = 0; procesar(quitarNombre(t)) }
            }
            override fun onError(error: Int) { if (estado == Estado.ESCUCHA) sinRespuesta() }
        })
        r.startListening(intentReco())
    }

    /** No dijiste nada. En modo charla te espera un poco antes de irse. */
    private fun sinRespuesta() {
        if (!modoCharla) { esperarNombre(); return }
        silencios++
        if (silencios >= 3) {
            ponerCharla(false)
            decir("Te dejo tranquilo. Cuando quieras seguimos charlando.", seguir = false)
        } else main.postDelayed({ if (estado == Estado.ESCUCHA && modoCharla) escucharPedido() }, 300)
    }

    private fun ponerCharla(activa: Boolean) {
        modoCharla = activa
        silencios = 0
        cerebro.charla = activa
        Bus.ponerCharla(activa)
    }

    private fun apertura() = listOf(
        "¡Dale, charlemos! ¿Cómo venís hoy, ${prefs.usuario}?",
        "¡Me encanta! Contame, ¿qué estuviste haciendo hoy?",
        "¡De una! ¿De qué tenés ganas de hablar?",
        "Acá estoy. ¿Cómo te trató el día?").random()

    // ------------------------------------------------------------ PENSAR
    private fun procesar(pedido: String) {
        frenarReconocimiento()
        estado = Estado.PENSANDO
        Bus.parcial("")
        Bus.mensaje(deVos = true, texto = pedido)
        Bus.estado(Bus.Estado.PENSANDO)
        val p = normalizar(pedido).trim(' ', '.', '!', '?', ',', '¿', '¡')
        if ("cuanto uso" in p || "limite de uso" in p) { decir(cerebro.resumenUso(), modoCharla); return }  // gratis
        if (modoCharla && p in PARA_TERMINAR) { ponerCharla(false); decir("¡Qué linda charla! Cuando quieras seguimos.", false); return }
        if (p in PARA_CHARLAR) { ponerCharla(true); decir(apertura(), seguir = true); return }
        when (p) {
            "nada", "chau", "gracias", "listo", "cancelar", "eso es todo" -> { decir("Dale, cualquier cosa me llamás.", false); return }
            "olvida todo", "nueva conversacion", "empecemos de nuevo" -> { cerebro.olvidar(); decir("Listo, empezamos de cero.", false); return }
            "callate", "silencio", "para" -> { ponerCharla(false); tts?.stop(); esperarNombre(); return }
            "" -> { esperarNombre(); return }
        }
        scope.launch {
            // 1) Atajo instantáneo (sin internet ni cuota). 2) Si no aplica, Gemini.
            val rapido = withContext(Dispatchers.IO) { runCatching { comandos.intentar(pedido) }.getOrNull() }
            if (rapido != null) { decir(rapido, seguir = modoCharla); return@launch }
            notificar("Pensando…")
            val respuesta = withContext(Dispatchers.IO) {
                try { cerebro.pensar(pedido) }
                catch (e: Exception) { Log.e(TAG, "Error", e); "Uy, tuve un problema: ${e.message?.take(80)}. ¿Probamos de nuevo?" }
            }
            decir(respuesta, seguir = modoCharla || respuesta.trimEnd().endsWith("?"))
        }
    }

    // ------------------------------------------------------------ UTILIDADES
    private fun descripcionHerramienta(n: String) = when (n) {
        "leer_pantalla" -> "Mirando la pantalla…"
        "tocar", "tocar_texto" -> "Tocando…"
        "escribir_texto" -> "Escribiendo…"
        "deslizar" -> "Deslizando…"
        "abrir_app" -> "Abriendo la app…"
        "enviar_whatsapp" -> "Mandando el WhatsApp…"
        "enviar_sms" -> "Mandando el SMS…"
        "llamar" -> "Llamando…"
        "clima" -> "Mirando el clima…"
        "buscar_contacto" -> "Buscando el contacto…"
        "esperar" -> "Esperando que cargue…"
        else -> "Trabajando…"
    }

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
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFFFD27A.toInt())
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
