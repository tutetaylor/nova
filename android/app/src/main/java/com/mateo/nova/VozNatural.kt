package com.mateo.nova

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Voz neural (la misma de la PC: Elena o Tomás de Argentina), gratis, usando el servicio de lectura en voz alta
 * del navegador Edge. No es un servicio oficial: si algún día falla, Nova vuelve sola a la voz del sistema.
 */
class VozNatural(private val ctx: Context) {

    companion object {
        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val VERSION = "1-130.0.2849.68"
        private const val URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        val VOCES = linkedMapOf(
            "es-AR-ElenaNeural" to "Elena · Argentina", "es-AR-TomasNeural" to "Tomás · Argentina",
            "es-UY-ValentinaNeural" to "Valentina · Uruguay", "es-MX-DaliaNeural" to "Dalia · México",
            "es-ES-AlvaroNeural" to "Álvaro · España", "sistema" to "Voz del sistema (sin internet)")
    }

    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private var reproductor: MediaPlayer? = null

    /** Token anti-abuso que pide el servicio: cambia cada 5 minutos. */
    private fun token(): String {
        val segundos = System.currentTimeMillis() / 1000 + 11_644_473_600L
        val ticks = (segundos - segundos % 300) * 10_000_000L
        return MessageDigest.getInstance("SHA-256").digest("$ticks$TOKEN".toByteArray())
            .joinToString("") { "%02X".format(it) }
    }

    /** "es-AR-ElenaNeural" → "Microsoft Server Speech Text to Speech Voice (es-AR, ElenaNeural)" */
    private fun nombreLargo(v: String): String {
        val partes = v.split("-")
        return if (partes.size >= 3) "Microsoft Server Speech Text to Speech Voice (${partes[0]}-${partes[1]}, ${partes.drop(2).joinToString("-")})" else v
    }

    private fun escapar(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** Genera el MP3. Bloqueante (llamar en segundo plano). Devuelve null si algo falla.
     *  Las frases cortas ("Listo", "Dale") quedan guardadas: la próxima vez suenan al instante. */
    fun sintetizar(texto: String, voz: String, velocidad: String = "+8%"): File? {
        val guardada = if (texto.length <= 40) File(File(ctx.cacheDir, "voz_cache").apply { mkdirs() },
            MessageDigest.getInstance("MD5").digest("$voz|$velocidad|$texto".toByteArray())
                .joinToString("") { "%02x".format(it) } + ".mp3") else null
        if (guardada != null && guardada.length() > 0) return guardada
        val id = UUID.randomUUID().toString().replace("-", "")
        val req = Request.Builder()
            .url("$URL?TrustedClientToken=$TOKEN&Sec-MS-GEC=${token()}&Sec-MS-GEC-Version=$VERSION&ConnectionId=$id")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
            .build()
        val audio = ByteArrayOutputStream()
        val fin = CountDownLatch(1)
        var ok = false
        val fecha = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send("X-Timestamp:$fecha\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
                    "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='es-AR'>" +
                    "<voice name='${nombreLargo(voz)}'><prosody pitch='+0Hz' rate='$velocidad' volume='+0%'>${escapar(texto)}</prosody></voice></speak>"
                ws.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${fecha}Z\r\nPath:ssml\r\n\r\n$ssml")
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Cada mensaje binario: 2 bytes con el largo del encabezado, el encabezado y después el audio
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val largo = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                if (b.size > 2 + largo && String(b, 2, largo).contains("Path:audio")) audio.write(b, 2 + largo, b.size - 2 - largo)
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) { ok = audio.size() > 0; ws.close(1000, null); fin.countDown() }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { fin.countDown() }
        })
        fin.await(25, TimeUnit.SECONDS)
        ws.cancel()
        if (!ok) return null
        if (guardada != null) {
            val tmp = File(guardada.parentFile, "$id.tmp").apply { writeBytes(audio.toByteArray()) }
            return if (tmp.renameTo(guardada)) guardada else tmp
        }
        return File(ctx.cacheDir, "voz_$id.mp3").apply { writeBytes(audio.toByteArray()) }
    }

    /** Reproduce el MP3 y llama [alTerminar] al final (en el hilo principal). */
    fun reproducir(mp3: File, alTerminar: () -> Unit) {
        parar()
        try {
            reproductor = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(mp3.absolutePath)
                setOnCompletionListener { borrar(mp3); parar(); alTerminar() }
                setOnErrorListener { _, _, _ -> borrar(mp3); parar(); alTerminar(); true }
                prepare()
                start()
            }
        } catch (e: Exception) { borrar(mp3); parar(); alTerminar() }
    }

    /** Los audios de frases guardadas no se borran. */
    private fun borrar(mp3: File) { if (mp3.parentFile?.name != "voz_cache") mp3.delete() }

    fun hablando() = try { reproductor?.isPlaying == true } catch (e: Exception) { false }

    fun parar() {
        reproductor?.let { runCatching { it.stop() }; runCatching { it.release() } }
        reproductor = null
    }
}
