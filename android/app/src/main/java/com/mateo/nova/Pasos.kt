package com.mateo.nova

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Los pasos para que Nova funcione completo. Los usan el aviso del inicio y la pantalla de ajustes. */
object Pasos {

    enum class Tipo { CLAVE, MICROFONO, TELEFONO, NOTIFICACIONES, PANTALLA, MENSAJES, AGENDA, UBICACION, BLUETOOTH, FOTOS, BATERIA }

    data class Paso(val tipo: Tipo, val titulo: String, val descripcion: String, val esencial: Boolean)

    val todos = buildList {
        add(Paso(Tipo.CLAVE, "Clave gratis de Gemini", "Es el cerebro. Se carga más abajo, en «Cerebro gratis».", true))
        add(Paso(Tipo.MICROFONO, "Micrófono", "Para escucharte cuando decís su nombre.", true))
        add(Paso(Tipo.PANTALLA, "Control de pantalla", "Para tocar, escribir y deslizar por vos en cualquier app.", false))
        add(Paso(Tipo.MENSAJES, "Leer tus mensajes", "Para leerte lo que llega y responder sin abrir la app.", false))
        add(Paso(Tipo.AGENDA, "Agenda", "Para leer y crear eventos de tu calendario.", false))
        add(Paso(Tipo.UBICACION, "Ubicación todo el tiempo", "Para automatizaciones al llegar o salir de un lugar.", false))
        if (Build.VERSION.SDK_INT >= 31)
            add(Paso(Tipo.BLUETOOTH, "Dispositivos Bluetooth", "Para automatizaciones al conectar el auto o un parlante.", false))
        add(Paso(Tipo.FOTOS, "Fotos y capturas", "Para leer números de tus capturas y mandar envíos masivos. Elegí «Permitir todo».", false))
        add(Paso(Tipo.TELEFONO, "Contactos, llamadas y SMS", "Para llamar y mandar mensajes a tus contactos.", false))
        if (Build.VERSION.SDK_INT >= 33)
            add(Paso(Tipo.NOTIFICACIONES, "Notificaciones", "Para que veas cuando está escuchando.", false))
        add(Paso(Tipo.BATERIA, "Batería sin restricciones", "Para que Android no lo duerma en segundo plano.", false))
    }

    val permisosAgenda = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    val permisosFotos = arrayOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                                else Manifest.permission.READ_EXTERNAL_STORAGE)

    val permisosTelefono = arrayOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)

    private fun tiene(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hecho(ctx: Context, tipo: Tipo): Boolean = when (tipo) {
        Tipo.CLAVE -> Prefs(ctx).apiKey.isNotBlank()
        Tipo.MICROFONO -> tiene(ctx, Manifest.permission.RECORD_AUDIO)
        Tipo.TELEFONO -> permisosTelefono.all { tiene(ctx, it) }
        Tipo.AGENDA -> permisosAgenda.all { tiene(ctx, it) }
        Tipo.FOTOS -> permisosFotos.all { tiene(ctx, it) }
        Tipo.UBICACION -> Automatizaciones.tieneUbicacionSiempre(ctx)
        Tipo.BLUETOOTH -> Build.VERSION.SDK_INT < 31 || tiene(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        Tipo.NOTIFICACIONES -> Build.VERSION.SDK_INT < 33 || tiene(ctx, Manifest.permission.POST_NOTIFICATIONS)
        Tipo.PANTALLA -> ControlAccesibilidad.activo
        Tipo.MENSAJES -> androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        Tipo.BATERIA -> (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun pendientes(ctx: Context) = todos.filter { !hecho(ctx, it.tipo) }

    /** Lo mínimo para encender: clave y micrófono. */
    fun puedeEncender(ctx: Context) = hecho(ctx, Tipo.CLAVE) && hecho(ctx, Tipo.MICROFONO)

    fun intentBateria(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
}
