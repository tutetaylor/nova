package com.mateo.nova

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** "¿Dónde está mi celular?": suena fuerte (aunque esté en silencio) hasta que lo encontrás. */
object Sonar {
    private var reproductor: MediaPlayer? = null
    private var volumenAntes = -1
    private val main = Handler(Looper.getMainLooper())

    fun iniciar(ctx: Context): String {
        parar(ctx)
        val audio = ctx.getSystemService(AudioManager::class.java)
        volumenAntes = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val tono = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        reproductor = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(ctx, tono)
            isLooping = true
            prepare(); start()
        }
        main.postDelayed({ parar(ctx) }, 60_000)   // como mucho un minuto

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova_buscar", "Buscar el celular", NotificationManager.IMPORTANCE_HIGH))
        val parar = PendingIntent.getService(ctx, 9, Intent(ctx, AsistenteService::class.java)
            .setAction(AsistenteService.ACCION_PARAR_SONIDO), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(9000, NotificationCompat.Builder(ctx, "nova_buscar")
            .setSmallIcon(R.drawable.ic_notif).setColor(0xFFFFD27A.toInt())
            .setContentTitle("¡Acá estoy!").setContentText("Tocá para dejar de sonar")
            .setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
            .setContentIntent(parar).addAction(0, "Lo encontré", parar).build())
        return "Hice sonar el celular."
    }

    fun parar(ctx: Context) {
        reproductor?.let { runCatching { it.stop() }; runCatching { it.release() } }
        reproductor = null
        main.removeCallbacksAndMessages(null)
        if (volumenAntes >= 0) {
            ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volumenAntes, 0)
            volumenAntes = -1
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(9000)
    }
}

/**
 * "Prendé la pantalla": la enciende y pide quitar el bloqueo. Si Extend Unlock la tiene desbloqueada, queda abierta;
 * si no, Android muestra el pedido de huella, cara o PIN. Nova nunca saltea el bloqueo.
 */
class PantallaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissCancelled() { finish() }
                override fun onDismissError() { finish() }
            })
        } else finish()
    }
}

/** Busca versiones nuevas en tu repo de GitHub (privado, con token de solo lectura) y las instala. */
object Actualizador {
    data class Novedad(val version: Int, val urlDescarga: String)

    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    fun versionActual(ctx: Context): Int =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()

    /** Devuelve la novedad si hay una versión más nueva, o un texto explicando por qué no. Bloqueante. */
    fun revisar(ctx: Context): Pair<Novedad?, String> {
        val prefs = Prefs(ctx)
        prefs.ultimaRevision = System.currentTimeMillis()
        if (prefs.repo.isBlank() || prefs.tokenGithub.isBlank())
            return null to "Para buscar actualizaciones cargá tu repo y tu token de GitHub en Ajustes › Conexiones."
        val req = Request.Builder().url("https://api.github.com/repos/${prefs.repo.trim()}/releases/tags/ultima")
            .header("Authorization", "Bearer ${prefs.tokenGithub.trim()}")
            .header("Accept", "application/vnd.github+json").build()
        val (codigo, cuerpo) = try {
            http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: Exception) { return null to "No pude conectarme con GitHub." }
        if (codigo == 401 || codigo == 403) return null to "GitHub rechazó el token. Revisá que tenga permiso de lectura del repo."
        if (codigo == 404) return null to "No encontré el release. Revisá el nombre del repo (usuario/repo)."
        if (codigo !in 200..299) return null to "GitHub respondió con error $codigo."
        val j = JSONObject(cuerpo)
        val version = Regex("Versión 1\\.(\\d+)").find(j.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
            ?: return null to "No pude leer la versión del release."
        val activos = j.optJSONArray("assets") ?: return null to "El release no tiene el APK."
        val apk = (0 until activos.length()).map { activos.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
            ?: return null to "El release no tiene el APK."
        val actual = versionActual(ctx)
        if (version <= actual) return null to "Ya tenés la última versión (1.$actual)."
        return Novedad(version, apk.getString("url")) to "Hay una versión nueva: 1.$version (tenés la 1.$actual)."
    }

    /** Descarga el APK y abre el instalador de Android. Bloqueante: llamar en segundo plano. */
    fun descargarEInstalar(ctx: Context, n: Novedad): String {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Primero permití que Nova instale actualizaciones (te abrí el ajuste) y volvé a intentar."
        }
        val prefs = Prefs(ctx)
        val archivo = File(ctx.cacheDir, "nova-actualizacion.apk")
        val req = Request.Builder().url(n.urlDescarga)
            .header("Authorization", "Bearer ${prefs.tokenGithub.trim()}")
            .header("Accept", "application/octet-stream").build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return "No pude descargar la actualización (error ${r.code})."
                archivo.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
            }
        } catch (e: Exception) { return "Se cortó la descarga. Probá de nuevo." }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", archivo)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Descargué la versión 1.${n.version}. Tocá «Actualizar» en la pantalla de Android."
    }
}
