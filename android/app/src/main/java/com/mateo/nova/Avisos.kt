package com.mateo.nova

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/** Programa los avisos con el reloj de Android, para que suenen aunque Nova esté apagada o el celular dormido. */
object Avisos {
    private const val CANAL = "nova_avisos"
    const val EXTRA_ID = "aviso_id"

    /** El servicio de Nova se suscribe para decir el aviso en voz alta (o hacer la rutina). */
    var alSonar: ((Libreta.Aviso) -> Unit)? = null

    private fun intent(ctx: Context, id: Int) = PendingIntent.getBroadcast(ctx, 1000 + id,
        Intent(ctx, AvisoReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun programar(ctx: Context, av: Libreta.Aviso) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intent(ctx, av.id)
        val exacto = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exacto) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, av.cuando, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, av.cuando, pi)   // puede atrasarse unos minutos
    }

    fun cancelar(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, id))
    }

    /** Después de reiniciar el celular, Android borra las alarmas: las volvemos a poner. */
    fun reprogramarTodos(ctx: Context) {
        val lib = Libreta.de(ctx)
        for (av in lib.avisos()) {
            val cuando = if (av.cuando < System.currentTimeMillis() && av.repetir != "no") siguiente(av.cuando, av.repetir) else av.cuando
            if (cuando < System.currentTimeMillis() - 60_000) { lib.avisoActualizar(av.id, null); continue }
            if (cuando != av.cuando) lib.avisoActualizar(av.id, cuando)
            programar(ctx, av.copy(cuando = cuando))
        }
    }

    /** Próxima vez que toca un aviso repetido, siempre en el futuro. */
    fun siguiente(desde: Long, repetir: String): Long {
        val c = Calendar.getInstance().apply { timeInMillis = desde }
        do {
            when (repetir) {
                "semanal" -> c.add(Calendar.DAY_OF_YEAR, 7)
                "laborales" -> do { c.add(Calendar.DAY_OF_YEAR, 1) } while (c.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY))
                else -> c.add(Calendar.DAY_OF_YEAR, 1)
            }
        } while (c.timeInMillis <= System.currentTimeMillis())
        return c.timeInMillis
    }

    fun mostrarNotificacion(ctx: Context, av: Libreta.Aviso) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Avisos de Nova", NotificationManager.IMPORTANCE_HIGH))
        val abrir = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val texto = if (av.texto.startsWith("@")) "¡Buen día! Tocá para escuchar tu resumen." else av.texto
        nm.notify(5000 + av.id, NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFFFD27A.toInt())
            .setContentTitle(Prefs(ctx).nombre)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build())
    }
}

/** Suena un aviso: lo dice Nova (si está encendida) y además queda la notificación. */
class AvisoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Avisos.EXTRA_ID, -1)
        val lib = Libreta.de(ctx)
        val av = lib.avisos().firstOrNull { it.id == id } ?: return
        val escucha = Avisos.alSonar
        if (escucha != null) escucha(av) else Avisos.mostrarNotificacion(ctx, av)
        if (av.repetir == "no") lib.avisoActualizar(av.id, null)
        else {
            val prox = Avisos.siguiente(av.cuando, av.repetir)
            lib.avisoActualizar(av.id, prox)
            Avisos.programar(ctx, av.copy(cuando = prox))
        }
    }
}

/** Al prender el celular: vuelve a poner los avisos y, si la dejaste encendida, avisa que Nova está lista. */
class InicioReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            Avisos.reprogramarTodos(ctx)
    }
}
