package com.mateo.nova

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Canal entre el celular y la PC, desde cualquier lugar, por ntfy.sh (gratis).
 * El canal tiene un nombre secreto y todo va cifrado (AES-GCM) con una clave que sale del código de vinculación:
 * nadie más puede leer ni mandar órdenes. Mensajes viejos (más de 2 minutos) se descartan.
 *
 * Formato (igual en la PC): {"de": "celular"|"pc", "tipo": "orden"|"respuesta"|"sonar", "id": "...", "texto": "...", "t": ms}
 */
class Vinculo(private val codigo: String, private val alRecibir: (JSONObject) -> Unit) {

    companion object {
        private const val SERVIDOR = "https://ntfy.sh"
        fun sha(texto: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray())
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun codigoValido(c: String) = c.trim().length >= 16
    }

    private val canal = "nova-" + hex(sha("nova-canal:" + codigo.trim())).take(28)
    private val clave = SecretKeySpec(sha("nova-clave:" + codigo.trim()), "AES")
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val esperando = ConcurrentHashMap<String, Pair<CountDownLatch, Array<String?>>>()
    @Volatile private var activo = false
    private var hilo: Thread? = null

    private fun cifrar(texto: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(128, iv)) }
        return Base64.encodeToString(iv + c.doFinal(texto.toByteArray()), Base64.NO_WRAP)
    }

    private fun descifrar(b64: String): String? = try {
        val datos = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(128, datos.copyOfRange(0, 12)))
        }
        String(c.doFinal(datos.copyOfRange(12, datos.size)))
    } catch (e: Exception) { null }   // no es nuestro o fue alterado: se ignora

    fun enviar(tipo: String, texto: String, id: String = UUID.randomUUID().toString().take(12)): String {
        val m = JSONObject().put("de", "celular").put("tipo", tipo).put("id", id).put("texto", texto.take(2500)).put("t", System.currentTimeMillis())
        http.newCall(Request.Builder().url("$SERVIDOR/$canal")
            .post(cifrar(m.toString()).toRequestBody("text/plain".toMediaType())).build()).execute().close()
        return id
    }

    /** Manda una orden a la PC y espera su respuesta (hasta [segundos]). Bloqueante. */
    fun ordenarPC(texto: String, segundos: Long = 60): String {
        val id = UUID.randomUUID().toString().take(12)
        val latch = CountDownLatch(1); val caja = arrayOfNulls<String>(1)
        esperando[id] = latch to caja
        return try {
            enviar("orden", texto, id)
            if (latch.await(segundos, TimeUnit.SECONDS)) caja[0] ?: "Listo." else "La PC no respondió. ¿Está prendida y con Nova abierta?"
        } catch (e: Exception) {
            "No pude comunicarme con la PC (${e.message?.take(40)})."
        } finally { esperando.remove(id) }
    }

    fun iniciar() {
        if (activo) return
        activo = true
        hilo = Thread {
            var desde = "${System.currentTimeMillis() / 1000}"
            while (activo) {
                try {
                    val req = Request.Builder().url("$SERVIDOR/$canal/json?since=$desde").build()
                    http.newCall(req).execute().use { r ->
                        val fuente = r.body?.source() ?: return@use
                        while (activo) {
                            val linea = fuente.readUtf8Line() ?: break
                            val evento = runCatching { JSONObject(linea) }.getOrNull() ?: continue
                            if (evento.optString("event") != "message") continue
                            desde = evento.optString("id", desde)
                            val claro = descifrar(evento.optString("message")) ?: continue
                            val m = runCatching { JSONObject(claro) }.getOrNull() ?: continue
                            if (m.optString("de") == "celular") continue                      // lo mandé yo
                            if (System.currentTimeMillis() - m.optLong("t") > 120_000) continue   // viejo: posible repetición
                            if (m.optString("tipo") == "respuesta") {
                                esperando[m.optString("id")]?.let { (latch, caja) -> caja[0] = m.optString("texto"); latch.countDown() }
                            } else alRecibir(m)
                        }
                    }
                } catch (e: Exception) {
                    if (activo) Thread.sleep(5000)   // sin internet: reintenta
                }
            }
        }.apply { isDaemon = true; name = "nova-vinculo"; start() }
    }

    fun detener() {
        activo = false
        hilo?.interrupt()
        http.dispatcher.cancelAll()
    }
}
