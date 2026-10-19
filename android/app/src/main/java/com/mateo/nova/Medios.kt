package com.mateo.nova

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Genera imágenes y videos con Pollinations (gen.pollinations.ai), los guarda en la galería
 * (Imágenes/Nova y Películas/Nova) y los abre. Las imágenes tardan unos segundos; los videos, un par de minutos,
 * así que se hacen en segundo plano y Nova avisa cuando están.
 *
 * Con clave (Ajustes › Cerebro gratis) usa la API oficial. Sin clave, las imágenes se intentan por el acceso
 * viejo sin clave (puede dejar de andar en cualquier momento) y los videos no se pueden.
 */
object Medios {

    data class Ultimo(val uri: Uri, val tipo: String, val descripcion: String)

    @Volatile var ultimo: Ultimo? = null; private set
    @Volatile private var haciendoVideo = false
    /** Lo pone el servicio: para avisar en voz alta cuando termina un video. */
    var alHablar: ((String) -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES).build()

    private fun tamano(formato: String, video: Boolean): Pair<Int, Int> = when (normalizar(formato)) {
        "vertical", "historia", "story", "retrato" -> if (video) 720 to 1280 else 768 to 1344
        "horizontal", "apaisado", "paisaje", "panoramico" -> if (video) 1280 to 720 else 1344 to 768
        else -> if (video) 1280 to 720 else 1024 to 1024
    }

    private class Fallo(msg: String) : Exception(msg)

    private fun descargar(url: String, clave: String): Pair<ByteArray, String> {
        val req = Request.Builder().url(url).apply { if (clave.isNotBlank()) header("Authorization", "Bearer $clave") }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Fallo(when (r.code) {
                401, 403 -> "La clave de Pollinations no es válida. Revisala en Ajustes."
                402 -> "Tu cuenta de Pollinations se quedó sin saldo. Podés cargar más en enter.pollinations.ai."
                429 -> "Pollinations me está frenando por pedir mucho seguido. Probá en un minuto."
                else -> "No pude generarlo (Pollinations respondió ${r.code})."
            })
            val tipo = r.header("Content-Type")?.substringBefore(";")?.trim().orEmpty()
            val bytes = r.body?.bytes() ?: throw Fallo("Pollinations no devolvió nada.")
            if (!tipo.startsWith("image/") && !tipo.startsWith("video/"))
                throw Fallo("Pollinations no devolvió una imagen ni un video. Probá describirlo de otra forma.")
            return bytes to tipo
        }
    }

    /** Guarda en la galería y devuelve la dirección para abrirlo o compartirlo. */
    private fun guardar(ctx: Context, bytes: ByteArray, tipo: String): Uri {
        val video = tipo.startsWith("video/")
        val ext = when (tipo) { "image/png" -> "png"; "image/webp" -> "webp"; "video/mp4" -> "mp4"; else -> if (video) "mp4" else "jpg" }
        val nombre = "nova_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".$ext"
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val coleccion = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, tipo)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Nova")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(coleccion, valores) ?: throw Fallo("No pude guardarlo en la galería.")
            (cr.openOutputStream(uri) ?: throw Fallo("No pude guardarlo en la galería.")).use { it.write(bytes) }
            valores.clear(); valores.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, valores, null, null)
            return uri
        }
        val carpeta = ctx.getExternalFilesDir(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
            ?: ctx.filesDir
        val archivo = File(carpeta, nombre).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", archivo)
    }

    private fun abrir(ctx: Context, uri: Uri, tipo: String) {
        runCatching {
            (ControlAccesibilidad.instancia ?: ctx).startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, tipo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    /** Bloqueante (unos segundos). [prompt] conviene en inglés y detallado. */
    fun imagen(ctx: Context, prefs: Prefs, prompt: String, formato: String, descripcion: String): String {
        val (w, h) = tamano(formato, false)
        val p = Uri.encode(prompt.trim().take(1500))
        val semilla = (1..999_999).random()
        val clave = prefs.clavePollinations
        val url = if (clave.isNotBlank()) "https://gen.pollinations.ai/image/$p?width=$w&height=$h&seed=$semilla&nologo=true"
                  else "https://image.pollinations.ai/prompt/$p?width=$w&height=$h&seed=$semilla&nologo=true"
        return try {
            val (bytes, tipo) = descargar(url, clave)
            val uri = guardar(ctx, bytes, tipo)
            ultimo = Ultimo(uri, tipo, descripcion.ifBlank { prompt.take(60) })
            abrir(ctx, uri, tipo)
            "Listo: generé la imagen, la abrí y quedó guardada en la galería (Imágenes › Nova)."
        } catch (e: Fallo) {
            e.message ?: "No pude generar la imagen."
        } catch (e: Exception) {
            if (clave.isBlank()) "No pude generar la imagen sin clave (el acceso gratis sin clave anda a veces). " +
                "Con una clave gratis de Pollinations en Ajustes anda siempre."
            else "No pude generar la imagen: ${e.message?.take(80)}"
        }
    }

    /** Vuelve enseguida: el video se hace en segundo plano y Nova avisa cuando está. */
    fun video(ctx: Context, prefs: Prefs, prompt: String, segundos: Int, formato: String, descripcion: String): String {
        val clave = prefs.clavePollinations
        if (clave.isBlank()) return "Para hacer videos necesito una clave gratis de Pollinations. Se saca en enter.pollinations.ai " +
            "entrando con GitHub, y se pega en Ajustes de Nova › Cerebro gratis."
        if (haciendoVideo) return "Ya estoy haciendo un video; cuando termine hago el otro."
        val (w, h) = tamano(formato, true)
        // Los modelos de video aceptan 4, 6 u 8 segundos
        val dur = listOf(4, 6, 8).minByOrNull { kotlin.math.abs(it - segundos) } ?: 4
        val url = "https://gen.pollinations.ai/video/${Uri.encode(prompt.trim().take(1500))}?duration=$dur&width=$w&height=$h&nologo=true"
        val app = ctx.applicationContext
        haciendoVideo = true
        Thread {
            val aviso = try {
                val (bytes, tipo) = descargar(url, clave)
                val uri = guardar(app, bytes, tipo)
                ultimo = Ultimo(uri, tipo, descripcion.ifBlank { prompt.take(60) })
                abrir(app, uri, tipo)
                "Listo tu video. Lo abrí y quedó guardado en la galería."
            } catch (e: Fallo) { e.message ?: "No pude hacer el video." }
            catch (e: Exception) { "No pude hacer el video: ${e.message?.take(80)}" }
            finally { haciendoVideo = false }
            Bus.mensaje(false, aviso)
            alHablar?.invoke(aviso)
        }.apply { isDaemon = true; start() }
        return "Arranqué a hacer el video de $dur segundos. Tarda uno o dos minutos; te aviso cuando esté."
    }

    /** Abre el menú de compartir de Android con lo último que generó (Instagram, Drive, lo que quieras). */
    fun compartir(ctx: Context): String {
        val u = ultimo ?: return "Todavía no generé ninguna imagen ni video."
        val i = Intent(Intent.ACTION_SEND).setType(u.tipo).putExtra(Intent.EXTRA_STREAM, u.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (ControlAccesibilidad.instancia ?: ctx).startActivity(Intent.createChooser(i, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Abrí el menú para compartir. Si me decís a qué app, la elijo con la pantalla."
    }
}
