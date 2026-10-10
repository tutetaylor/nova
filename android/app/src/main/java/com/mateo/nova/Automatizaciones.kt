package com.mateo.nova

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * El motor de automatizaciones: escucha lo que pasa en el celular (auriculares, Bluetooth, cargador, batería,
 * lugares, mensajes) y, si hay una regla para eso, le pide a Nova que haga lo que dice la regla.
 */
class Automatizaciones(private val ctx: Context, private val ejecutar: (Libreta.Regla, String) -> Unit) {

    private val libreta = Libreta.de(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var bateriaAvisada = false
    /** Evita disparar dos veces lo mismo seguido (algunos dispositivos conectan y reconectan). */
    private val ultimoDisparo = mutableMapOf<Int, Long>()

    companion object {
        /** El servicio de Nova se suscribe para las reglas de lugares (que llegan por el receptor de geovallas). */
        var alLugar: ((String, Boolean) -> Unit)? = null

        fun tienePermisoUbicacion(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun tieneUbicacionSiempre(ctx: Context) = tienePermisoUbicacion(ctx) && (Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

        private fun intentGeovalla(ctx: Context) = PendingIntent.getBroadcast(ctx, 77,
            Intent(ctx, LugarReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

        /** Registra en Google Play Services las geovallas de los lugares que tienen reglas. */
        @SuppressLint("MissingPermission")
        fun actualizarGeovallas(ctx: Context): String {
            val lib = Libreta.de(ctx)
            val cliente = LocationServices.getGeofencingClient(ctx)
            cliente.removeGeofences(intentGeovalla(ctx))
            val usados = lib.reglas().filter { it.disparador == "llegar" || it.disparador == "salir" }.map { normalizar(it.valor) }.toSet()
            val vallas = lib.lugares().filter { normalizar(it.nombre) in usados }.map {
                Geofence.Builder().setRequestId(it.nombre)
                    .setCircularRegion(it.lat, it.lng, it.radio)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            if (vallas.isEmpty()) return ""
            if (!tieneUbicacionSiempre(ctx)) return " Para que funcione falta permitir la ubicación «todo el tiempo» en los ajustes de Nova."
            cliente.addGeofences(GeofencingRequest.Builder()
                .setInitialTrigger(0).addGeofences(vallas).build(), intentGeovalla(ctx))
            return ""
        }

        /** Ubicación actual (para "guardá este lugar como casa"). Bloqueante. */
        @SuppressLint("MissingPermission")
        fun ubicacionActual(ctx: Context): android.location.Location? {
            if (!tienePermisoUbicacion(ctx)) return null
            return try {
                Tasks.await(LocationServices.getFusedLocationProviderClient(ctx)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null), 20, TimeUnit.SECONDS)
            } catch (e: Exception) { null }
        }
    }

    // ---------------------------------------------------------------- DISPARAR
    private fun disparar(tipo: String, valor: String = "", contexto: String = "") {
        val ahora = System.currentTimeMillis()
        for (r in libreta.reglas()) {
            if (r.disparador != tipo) continue
            if (r.valor.isNotBlank() && valor.isNotBlank() && !normalizar(valor).contains(normalizar(r.valor))) continue
            if (ahora - (ultimoDisparo[r.id] ?: 0) < 60_000) continue
            ultimoDisparo[r.id] = ahora
            main.post { ejecutar(r, contexto) }
        }
    }

    /** "modo dormir", "activá modo gym", "rutina estudio": devuelve la regla si lo dicho la nombra. */
    fun reglaDeVoz(dicho: String): Libreta.Regla? {
        val p = normalizar(dicho).replace(Regex("^(activa|activar|pone|poner|arranca|inicia|empeza|rutina|modo)\\s+"), "")
            .trim(' ', '.', '!', '¡')
        return libreta.reglas().firstOrNull { r ->
            r.disparador == "voz" && normalizar(r.valor).let { v -> p == v || p == v.removePrefix("modo ").trim() || "modo $p" == v }
        }
    }

    fun mensajeRecibido(quien: String, app: String, texto: String) {
        disparar("mensaje", quien, "Llegó un mensaje de $quien por $app: \"$texto\".")
    }

    // ---------------------------------------------------------------- ESCUCHAR EL CELULAR
    private val tiposAuriculares = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET)
    /** Lo que ya estaba conectado: Android lo vuelve a avisar al registrarse y no tiene que disparar nada. */
    private val conectados = mutableSetOf<Int>()

    private val auriculares = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(agregados: Array<out AudioDeviceInfo>) {
            val nuevos = agregados.filter { it.isSink && it.type in tiposAuriculares && it.id !in conectados }
            nuevos.forEach { conectados.add(it.id) }
            if (nuevos.isNotEmpty()) disparar("auriculares")
        }
        override fun onAudioDevicesRemoved(quitados: Array<out AudioDeviceInfo>) {
            quitados.forEach { conectados.remove(it.id) }
        }
    }

    private val receptor = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> disparar("cargador")
                Intent.ACTION_POWER_DISCONNECTED -> { disparar("desconectar_cargador"); bateriaAvisada = false }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val umbrales = libreta.reglas().filter { it.disparador == "bateria_baja" }.mapNotNull { it.valor.toIntOrNull() }
                    if (!bateriaAvisada && umbrales.any { nivel in 1..it }) {
                        bateriaAvisada = true
                        disparar("bateria_baja", contexto = "La batería está en $nivel%.")
                    }
                    if (umbrales.none { nivel <= it }) bateriaAvisada = false
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val disp: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val nombre = try { disp?.name } catch (e: SecurityException) { null } ?: ""
                    disparar("bluetooth", nombre, "Se conectó el Bluetooth «$nombre».")
                }
            }
        }
    }

    fun iniciar() {
        conectados.addAll(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id })
        audio.registerAudioDeviceCallback(auriculares, main)
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        ContextCompat.registerReceiver(ctx, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
        alLugar = { lugar, llegando -> disparar(if (llegando) "llegar" else "salir", lugar,
            if (llegando) "Acabás de llegar a $lugar." else "Acabás de salir de $lugar.") }
    }

    fun detener() {
        audio.unregisterAudioDeviceCallback(auriculares)
        runCatching { ctx.unregisterReceiver(receptor) }
        alLugar = null
    }
}

/** Llega o sale de un lugar guardado. Si Nova está encendida, ejecuta la regla; si no, deja una notificación. */
class LugarReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val evento = GeofencingEvent.fromIntent(intent) ?: return
        if (evento.hasError()) return
        val llegando = evento.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        for (g in evento.triggeringGeofences ?: emptyList()) {
            val escucha = Automatizaciones.alLugar
            if (escucha != null) { escucha(g.requestId, llegando); continue }
            val reglas = Libreta.de(ctx).reglas().filter {
                it.disparador == (if (llegando) "llegar" else "salir") && normalizar(it.valor) == normalizar(g.requestId)
            }
            if (reglas.isEmpty()) continue
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("nova_lugares", "Lugares de Nova", NotificationManager.IMPORTANCE_DEFAULT))
            val abrir = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(7000 + g.requestId.hashCode() % 1000, NotificationCompat.Builder(ctx, "nova_lugares")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(if (llegando) "Llegaste a ${g.requestId}" else "Saliste de ${g.requestId}")
                .setContentText("Nova está apagada. Encendela para: " + reglas.joinToString("; ") { it.hacer })
                .setContentIntent(abrir).setAutoCancel(true).build())
        }
    }
}
