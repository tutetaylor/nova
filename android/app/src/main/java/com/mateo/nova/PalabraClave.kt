package com.mateo.nova

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Escucha permanente de la palabra de activación, 100% offline (Vosk).
 *
 * A diferencia del reconocedor de Google:
 *  - no hace bip,
 *  - no pide el foco de audio, así que NO pausa música, videos ni audios de WhatsApp,
 *  - no se reinicia cada pocos segundos (escucha continua, reacciona al instante).
 */
class PalabraClave(private val ctx: Context, private val nombre: String) {

    companion object {
        private const val TAG = "Nova"
        private const val MODELO = "vosk-model-small-es-0.42"
        private const val URL = "https://alphacephei.com/vosk/models/$MODELO.zip"
        private const val TASA = 16000f
    }

    private val carpeta = File(ctx.filesDir, "vosk")
    private val dirModelo = File(carpeta, MODELO)
    private val marcaListo = File(carpeta, ".listo")
    private var modelo: Model? = null
    private var servicio: SpeechService? = null
    private var reconocedor: Recognizer? = null
    private val clave = normalizar(nombre)

    val modeloInstalado get() = marcaListo.exists() && dirModelo.isDirectory

    /** Descarga y descomprime el modelo (~40 MB) la primera vez. Bloqueante: llamar en segundo plano. */
    fun instalarModelo(progreso: (Int) -> Unit) {
        if (modeloInstalado) return
        carpeta.deleteRecursively(); carpeta.mkdirs()
        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("No pude bajar el modelo de voz (${r.code})")
            val cuerpo = r.body ?: throw IllegalStateException("Descarga vacía")
            val total = cuerpo.contentLength().takeIf { it > 0 } ?: 45_000_000L
            val raiz = carpeta.canonicalPath + File.separator
            var leidos = 0L
            var ultimo = -1
            ZipInputStream(object : java.io.FilterInputStream(cuerpo.byteStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        leidos += n
                        val pct = (leidos * 100 / total).toInt().coerceAtMost(99)
                        if (pct != ultimo) { ultimo = pct; progreso(pct) }
                    }
                    return n
                }
            }).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val destino = File(carpeta, e.name)
                    if (!destino.canonicalPath.startsWith(raiz)) continue   // protección zip-slip
                    if (e.isDirectory) destino.mkdirs()
                    else { destino.parentFile?.mkdirs(); destino.outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        marcaListo.writeText("ok")
        progreso(100)
    }

    /** Carga el modelo en memoria (tarda 1-2 s). Bloqueante: llamar en segundo plano. */
    fun cargar() {
        if (modelo != null) return
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        modelo = Model(dirModelo.absolutePath)
    }

    /** Empieza a escuchar. Llama [alDetectar] (en el hilo principal) cuando oye el nombre. */
    fun escuchar(alDetectar: () -> Unit, alError: (String) -> Unit) {
        val m = modelo ?: return alError("El modelo de voz no está cargado")
        detener()
        // Gramática cerrada: solo puede "oír" el nombre o ruido. Casi sin falsas activaciones.
        val gramatica = JSONArray(listOf(clave, "oye $clave", "hola $clave", "che $clave", "[unk]")).toString()
        try {
            val rec = Recognizer(m, TASA, gramatica)
            reconocedor = rec
            servicio = SpeechService(rec, TASA).also { s ->
                s.startListening(object : RecognitionListener {
                    private var disparado = false
                    private fun revisar(json: String?, campo: String) {
                        if (disparado || json == null) return
                        val texto = runCatching { JSONObject(json).optString(campo) }.getOrDefault("")
                        if (texto.split(" ").any { it == clave }) { disparado = true; alDetectar() }
                    }
                    override fun onPartialResult(h: String?) = revisar(h, "partial")
                    override fun onResult(h: String?) = revisar(h, "text")
                    override fun onFinalResult(h: String?) = revisar(h, "text")
                    override fun onError(e: Exception?) { alError(e?.message ?: "error de micrófono") }
                    override fun onTimeout() {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vosk", e)
            alError(e.message ?: "No pude abrir el micrófono")
        }
    }

    /** Libera el micrófono (hace falta antes de usar el reconocedor de Google para el pedido). */
    fun detener() {
        servicio?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        servicio = null
        reconocedor?.let { runCatching { it.close() } }
        reconocedor = null
    }

    fun liberar() {
        detener()
        modelo?.let { runCatching { it.close() } }
        modelo = null
    }
}
