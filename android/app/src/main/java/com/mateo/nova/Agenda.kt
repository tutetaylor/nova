package com.mateo.nova

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** La agenda del celular (la de Google Calendar sincronizada), sin conectar cuentas. */
class Agenda(private val ctx: Context) {

    private val formato = SimpleDateFormat("EEEE d/M HH:mm", Locale("es", "AR"))
    private fun permiso(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Eventos desde [desdeDias] (0 = hoy) durante [dias] días. */
    fun ver(desdeDias: Int, dias: Int): String {
        if (!permiso(Manifest.permission.READ_CALENDAR)) return "Falta el permiso de calendario. Pedile al usuario que lo active en los ajustes de Nova."
        val ini = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, desdeDias); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val fin = (ini.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, dias.coerceIn(1, 31)) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, ini.timeInMillis)
            android.content.ContentUris.appendId(it, fin.timeInMillis)
        }.build()
        val eventos = mutableListOf<String>()
        ctx.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && eventos.size < 40) {
                val titulo = c.getString(0) ?: "(sin título)"
                val inicio = c.getLong(1)
                val todoElDia = c.getInt(2) == 1
                val lugar = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " en $it" } ?: ""
                val cuando = if (todoElDia) SimpleDateFormat("EEEE d/M", Locale("es", "AR")).format(Date(inicio)) + " (todo el día)"
                             else formato.format(Date(inicio))
                eventos.add("$cuando: $titulo$lugar")
            }
        }
        return if (eventos.isEmpty()) "No hay nada agendado en ese período." else eventos.joinToString("\n")
    }

    /** Crea un evento en el calendario principal. [inicio] en formato "AAAA-MM-DD HH:MM". */
    fun crear(titulo: String, inicio: String, minutos: Int): String {
        val t = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(inicio.trim().replace("T", " "))?.time }.getOrNull()
            ?: return "Fecha inválida, usá AAAA-MM-DD HH:MM."
        if (!permiso(Manifest.permission.WRITE_CALENDAR)) return "Falta el permiso de calendario."
        val idCal = calendarioPrincipal() ?: return "No encontré un calendario donde guardar."
        val valores = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, idCal)
            put(CalendarContract.Events.TITLE, titulo)
            put(CalendarContract.Events.DTSTART, t)
            put(CalendarContract.Events.DTEND, t + minutos.coerceAtLeast(5) * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, valores) ?: return "No pude crear el evento."
        return "Agendado: $titulo, ${formato.format(Date(t))}."
    }

    private fun calendarioPrincipal(): Long? {
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null, null, null)?.use { c ->
            var primero: Long? = null
            while (c.moveToNext()) {
                if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue   // solo lectura (feriados, etc.)
                if (primero == null) primero = c.getLong(0)
                if (c.getInt(1) == 1) return c.getLong(0)
            }
            return primero
        }
        return null
    }
}
