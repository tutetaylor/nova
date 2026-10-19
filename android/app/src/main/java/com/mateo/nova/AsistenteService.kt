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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
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
        const val ACCION_PARAR_SONIDO = "com.mateo.nova.PARAR_SONIDO"

        private const val RUTINA_BUEN_DIA = "Rutina de buen día: saludame según la hora, contame el clima de hoy, mi agenda de hoy, " +
            "mis avisos de hoy y si tengo algo en la lista de pendientes, y cerrá con una frase linda. Todo en 4 a 6 oraciones, " +
            "natural, como un amigo. Usá clima, agenda_ver, avisos_ver y lista_ver."
        private val PARA_BUEN_DIA = setOf("buen dia", "buenos dias", "buen dia nova", "arranquemos el dia", "arrancamos el dia",
            "como arranca el dia", "que tengo hoy", "resumen del dia")
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
    private lateinit var automatizaciones: Automatizaciones
    private lateinit var vozNatural: VozNatural
    private var turnoVoz = 0          // para ignorar audios viejos si lo interrumpiste
    private var vinculo: Vinculo? = null
    private var oidoListo = false
    private var reco: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var estado = Estado.ESPERA
    private var seguirDespuesDeHablar = false
    private var modoCharla = false
    private val pendientes = mutableListOf<Notificaciones.Mensaje>()
    private var anunciado = ""          // lo último que te leyó, para entender "respondele que sí"
    private var anunciadoEn = 0L
    private val anunciar = Runnable { anunciarMensajes() }
    private var silencios = 0

    // ------------------------------------------------------------ CICLO DE VIDA
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val herramientas = Herramientas(this, prefs)
        comandos = Comandos(herramientas)
        cerebro = Cerebro(prefs, herramientas).apply {
            memoria = { Libreta.de(this@AsistenteService).memoriaParaCerebro() }
            alUsarHerramienta = { n ->
                main.post { notificar("Usando $n…") }
                Bus.estado(Bus.Estado.PENSANDO, descripcionHerramienta(n))
            }
        }
        oido = PalabraClave(this, prefs.nombre)
        vozNatural = VozNatural(this)
        Pomodoro.alHablar = { t -> main.post { if (estado == Estado.ESPERA) decir(t, seguir = false) else Bus.mensaje(false, t) } }
        EnvioMasivo.alHablar = { t -> main.post { if (estado == Estado.ESPERA) decir(t, seguir = false) } }
        Medios.alHablar = { t -> main.post { if (estado == Estado.ESPERA) decir(t, seguir = false) } }
        Herramientas.alModoEstudio = { tema -> cerebro.estudio = tema; main.post { ponerCharla(true) } }
        if (Vinculo.codigoValido(prefs.codigoVinculo)) {
            vinculo = Vinculo(prefs.codigoVinculo) { m -> main.post { recibirDePC(m) } }.also { it.iniciar() }
        }
        Herramientas.vinculo = vinculo
        Avisos.alSonar = { av -> main.post { sonoAviso(av) } }
        automatizaciones = Automatizaciones(this) { regla, contexto -> ejecutarRegla(regla, contexto, 0) }
        automatizaciones.iniciar()
        Notificaciones.alLlegar = { m -> main.post {
            automatizaciones.mensajeRecibido(m.quien, m.app, m.texto)
            pendientes.add(m); main.removeCallbacks(anunciar); main.postDelayed(anunciar, 2500)
        } }
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
            ACCION_ESCUCHAR -> { prefs.quiereEncendido = true; main.post { if (estado != Estado.PENSANDO) { callar(); escucharPedido() } } }
            ACCION_TEXTO -> { prefs.quiereEncendido = true; intent?.getStringExtra(EXTRA_TEXTO)?.let { t -> main.post { procesar(t) } } }
            ACCION_PARAR_SONIDO -> { Sonar.parar(this); return if (prefs.quiereEncendido) START_STICKY else START_NOT_STICKY }
            ACCION_CHARLA -> {
                prefs.quiereEncendido = true
                main.post {
                    if (modoCharla) { ponerCharla(false); callar(); frenarReconocimiento(); esperarNombre() }
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
        Notificaciones.alLlegar = null
        Avisos.alSonar = null
        Pomodoro.alHablar = null
        EnvioMasivo.alHablar = null
        Medios.alHablar = null
        vinculo?.detener(); Herramientas.vinculo = null
        Herramientas.alModoEstudio = null
        callar()
        automatizaciones.detener()
        main.removeCallbacks(anunciar)
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
            override fun onDone(id: String?) { main.post { terminoFrase() } }
            @Deprecated("") override fun onError(id: String?) { main.post { terminoFrase() } }
        })
        ttsListo = true
    }

    private fun decir(texto: String, seguir: Boolean) {
        if (texto.isBlank()) {   // acción hecha, no hace falta hablar
            Bus.mensaje(deVos = false, texto = "Listo.")
            if (seguir) escucharPedido() else esperarNombre()
            return
        }
        notificar(texto)
        Bus.mensaje(deVos = false, texto = texto)
        empezarAHablar(seguir).apply { agregar(texto); cerrar() }
    }

    /** Corta lo anterior, pasa a "hablando" y devuelve una tanda nueva para ir agregándole frases. */
    private fun empezarAHablar(seguir: Boolean): Tanda {
        callar()
        estado = Estado.HABLANDO
        seguirDespuesDeHablar = seguir
        frenarReconocimiento()
        oido.detener()          // que no se escuche a sí mismo
        Bus.estado(Bus.Estado.HABLANDO)
        return Tanda(turnoVoz).also { tanda = it }
    }

    private var tanda: Tanda? = null
    /** Lo que hay que hacer cuando la voz del sistema termina la frase actual. */
    private var alTerminarFrase: (() -> Unit)? = null

    private fun terminoFrase() {
        val f = alTerminarFrase ?: return
        alTerminarFrase = null
        f()
    }

    /**
     * Frases que se dicen a medida que llegan (mientras Gemini todavía está escribiendo).
     * Con la voz natural, genera el audio de la frase siguiente mientras dice la actual.
     */
    private inner class Tanda(val turno: Int) {
        private val frases = Channel<String>(Channel.UNLIMITED)
        private val audios = Channel<Pair<String, File?>>(2)
        private val natural = prefs.voz != "sistema"
        var dijoAlgo = false; private set
        private val generador: Job = scope.launch(Dispatchers.IO) {
            var usarNatural = natural
            for (f in frases) {
                val mp3 = if (usarNatural) runCatching { vozNatural.sintetizar(f, prefs.voz) }.getOrNull() else null
                if (usarNatural && mp3 == null) usarNatural = false      // sin internet: el resto con la voz del sistema
                audios.send(f to mp3)
            }
            audios.close()
        }
        private val reproductor: Job = scope.launch {
            for ((texto, mp3) in audios) {
                if (turno != turnoVoz) { mp3?.let { borrarAudio(it) }; continue }
                decirFrase(texto, mp3)
            }
            if (turno == turnoVoz) terminoDeHablar()
        }

        val vigente get() = turno == turnoVoz

        fun agregar(f: String) {
            if (!vigente || f.isBlank()) return
            dijoAlgo = true
            frases.trySend(f.trim())
        }

        fun cerrar() { frases.close() }

        fun cancelar() { frases.close(); generador.cancel(); reproductor.cancel() }
    }

    private fun borrarAudio(f: File) { if (f.parentFile?.name != "voz_cache") f.delete() }

    /** Dice una frase y vuelve cuando terminó de sonar. */
    private suspend fun decirFrase(texto: String, mp3: File?) = suspendCancellableCoroutine<Unit> { cont ->
        val listo: () -> Unit = { if (cont.isActive) cont.resume(Unit) }
        when {
            mp3 != null -> vozNatural.reproducir(mp3) { listo() }
            ttsListo -> {
                alTerminarFrase = listo
                tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, Bundle(), "f${System.nanoTime()}")
            }
            else -> main.postDelayed({ listo() }, 300)
        }
    }

    /** Corta lo que esté diciendo (cualquiera de las dos voces). */
    private fun callar() {
        turnoVoz++
        tanda?.cancelar(); tanda = null
        alTerminarFrase = null
        tts?.stop()
        if (::vozNatural.isInitialized) vozNatural.parar()
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
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
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
        cerebro.calentar()      // mientras hablás, ya abre la conexión con Google
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

    /** Te lee los mensajes que llegaron (juntando los que llegan seguidos) y te escucha por si querés responder. */
    private fun anunciarMensajes() {
        val lote = pendientes.toList(); pendientes.clear()
        if (lote.isEmpty() || !prefs.leerMensajes) return
        // No interrumpir: si está ocupada, charlando, en una llamada o en "No molestar", no lee
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val enLlamada = audio.mode == android.media.AudioManager.MODE_IN_CALL ||
            audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        val noMolestar = getSystemService(NotificationManager::class.java).currentInterruptionFilter !=
            NotificationManager.INTERRUPTION_FILTER_ALL
        if (estado != Estado.ESPERA || modoCharla || enLlamada || noMolestar) return

        val chats = lote.groupBy { "${it.quien}|${it.app}" }
        val texto = if (chats.size == 1) {
            val ms = chats.values.first(); val m = ms.last()
            if (ms.size == 1) "${m.quien} te escribió por ${m.app}: ${m.texto}"
            else "${m.quien} te mandó ${ms.size} mensajes por ${m.app}. El último dice: ${m.texto}"
        } else {
            val nombres = chats.values.map { it.last().quien }.distinct()
            "Tenés ${lote.size} mensajes nuevos, de " +
                (if (nombres.size > 1) nombres.dropLast(1).joinToString(", ") + " y " + nombres.last() else nombres.first()) +
                ". ¿Querés que te los lea?"
        }
        anunciado = lote.joinToString(" | ") { "${it.quien} por ${it.app}: ${it.texto}" }.take(1500)
        anunciadoEn = System.currentTimeMillis()
        decir(texto, seguir = true)
    }

    /** Llegó la hora de un aviso programado. Si está ocupada, queda la notificación. */
    private fun sonoAviso(av: Libreta.Aviso) {
        if (estado != Estado.ESPERA) { Avisos.mostrarNotificacion(this, av); return }
        when {
            av.texto.startsWith("@") -> procesar(RUTINA_BUEN_DIA)
            av.texto.startsWith("!") -> ejecutarRegla(Libreta.Regla(0, "Aviso programado", "hora", "", av.texto.drop(1)), "", 0)
            else -> decir("Te aviso: ${av.texto}", seguir = false)
        }
    }

    /** Ejecuta una automatización. Si Nova está ocupada, reintenta en unos segundos (hasta 3 veces). */
    private fun ejecutarRegla(r: Libreta.Regla, contexto: String, intento: Int) {
        if (estado != Estado.ESPERA) {
            if (intento < 3) main.postDelayed({ ejecutarRegla(r, contexto, intento + 1) }, 8000)
            return
        }
        frenarReconocimiento()
        estado = Estado.PENSANDO
        Bus.mensaje(deVos = false, texto = "Automatización: ${r.nombre}")
        Bus.estado(Bus.Estado.PENSANDO, "Haciendo «${r.nombre}»…")
        val orden = "(Automatización que el usuario creó: hacelo directamente, sin preguntar, salvo pagar o borrar. " +
            "${contexto.trim()}) ${r.hacer}. Al terminar, confirmá en una oración corta qué hiciste."
        scope.launch {
            val respuesta = withContext(Dispatchers.IO) {
                try { cerebro.pensar(orden) } catch (e: Exception) { "No pude hacer «${r.nombre}»: ${e.message?.take(60)}" }
            }
            decir(respuesta, seguir = false)
        }
    }

    /** Llegó algo desde la PC: hacer sonar el celular, o una orden para ejecutar y responder. */
    private fun recibirDePC(m: org.json.JSONObject) {
        val v = vinculo ?: return
        when (m.optString("tipo")) {
            "sonar" -> {
                val r = Sonar.iniciar(this)
                scope.launch(Dispatchers.IO) { runCatching { v.enviar("respuesta", r, m.optString("id")) } }
            }
            "orden" -> {
                val texto = m.optString("texto")
                Bus.mensaje(deVos = true, texto = "Desde la PC: $texto")
                scope.launch {
                    val respuesta = withContext(Dispatchers.IO) {
                        try {
                            cerebro.pensar("(Orden que te mandó ${prefs.usuario} desde su PC; él no está mirando el celular, " +
                                "así que respondé con el resultado concreto.) $texto")
                        } catch (e: Exception) { "No pude hacerlo: ${e.message?.take(60)}" }
                    }
                    Bus.mensaje(deVos = false, texto = respuesta)
                    withContext(Dispatchers.IO) { runCatching { v.enviar("respuesta", respuesta, m.optString("id")) } }
                }
            }
        }
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
        if (!activa) cerebro.estudio = null
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
    private fun procesar(dicho: String) {
        frenarReconocimiento()
        estado = Estado.PENSANDO
        Bus.parcial("")
        Bus.mensaje(deVos = true, texto = if (dicho == RUTINA_BUEN_DIA) "Rutina de buen día" else dicho)
        // "Buen día" dispara la rutina completa
        val pedido = if (normalizar(dicho).trim(' ', '.', '!', ',', '¡') in PARA_BUEN_DIA) RUTINA_BUEN_DIA else dicho
        Bus.estado(Bus.Estado.PENSANDO)
        val p = normalizar(pedido).trim(' ', '.', '!', '?', ',', '¿', '¡')
        if ("cuanto uso" in p || "limite de uso" in p) { decir(cerebro.resumenUso(), modoCharla); return }  // gratis
        if (modoCharla && p in PARA_TERMINAR) { ponerCharla(false); decir("¡Qué linda charla! Cuando quieras seguimos.", false); return }
        if (p in PARA_CHARLAR) { ponerCharla(true); decir(apertura(), seguir = true); return }
        automatizaciones.reglaDeVoz(pedido)?.let { estado = Estado.ESPERA; ejecutarRegla(it, "Lo pidió por voz.", 0); return }
        when (p) {
            "nada", "chau", "gracias", "listo", "cancelar", "eso es todo" -> { decir("Dale, cualquier cosa me llamás.", false); return }
            "olvida todo", "nueva conversacion", "empecemos de nuevo" -> { cerebro.olvidar(); decir("Listo, empezamos de cero.", false); return }
            "callate", "silencio", "para" -> { ponerCharla(false); callar(); esperarNombre(); return }
            "" -> { esperarNombre(); return }
        }
        scope.launch {
            // 1) Atajo instantáneo (sin internet ni cuota). 2) Si no aplica, Gemini.
            val rapido = withContext(Dispatchers.IO) { runCatching { comandos.intentar(pedido) }.getOrNull() }
            if (rapido != null) { decir(rapido, seguir = modoCharla); return@launch }
            notificar("Pensando…")
            // Si recién te leyó mensajes, el cerebro sabe a qué te referís con "respondele que sí"
            val conContexto = if (System.currentTimeMillis() - anunciadoEn < 90_000 && anunciado.isNotEmpty())
                "(Contexto: recién le leí al usuario estos mensajes nuevos: $anunciado. Si quiere responder, usá responder_mensaje; " +
                "si quiere escucharlos, usá mensajes_sin_leer.)\n$pedido" else pedido
            // Empieza a hablar con la primera frase que escribe Gemini, sin esperar la respuesta entera
            var enVivo: Tanda? = null
            val turnoPedido = turnoVoz
            val alTexto: (String) -> Unit = { frase -> main.post {
                if (enVivo == null && estado == Estado.PENSANDO && turnoVoz == turnoPedido)
                    enVivo = empezarAHablar(seguir = modoCharla)
                enVivo?.agregar(frase)
            } }
            val respuesta = withContext(Dispatchers.IO) {
                try { cerebro.pensar(conContexto, alTexto) }
                catch (e: Exception) { Log.e(TAG, "Error", e); "Uy, tuve un problema: ${e.message?.take(80)}. ¿Probamos de nuevo?" }
            }
            val seguir = modoCharla || respuesta.trimEnd().endsWith("?")
            val t = enVivo
            when {
                t == null -> if (estado == Estado.PENSANDO) decir(respuesta, seguir) else Bus.mensaje(deVos = false, texto = respuesta)
                t.vigente && t.dijoAlgo -> {
                    seguirDespuesDeHablar = seguir
                    notificar(respuesta)
                    Bus.mensaje(deVos = false, texto = respuesta + (cerebro.ultimoLibre?.let { "\n\n— respondió el cerebro libre ($it)" } ?: ""))
                    t.cerrar()
                }
                else -> Bus.mensaje(deVos = false, texto = respuesta)   // la interrumpiste mientras hablaba
            }
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
        "envio_masivo_preparar" -> "Leyendo los números de las capturas…"
        "envio_masivo_enviar" -> "Arrancando el envío…"
        "documento_guardar" -> "Abriendo tus archivos…"
        "generar_imagen" -> "Pintando la imagen…"
        "generar_video" -> "Arrancando el video…"
        "media_enviar" -> "Mandándola…"
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
