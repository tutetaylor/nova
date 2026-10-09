package com.mateo.nova

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Todo lo que Nova puede hacer en el celular. Cada herramienta tiene un esquema (para que Claude
 * sepa que existe) y una implementación en [ejecutar].
 */
class Herramientas(private val ctx: Context, private val prefs: Prefs) {

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val manos get() = ControlAccesibilidad.instancia

    // ---------------------------------------------------------------- ESQUEMAS
    private fun h(nombre: String, desc: String, vararg params: Triple<String, String, String>): JSONObject {
        val props = JSONObject(); val req = JSONArray()
        for ((n, tipo, d) in params) {
            props.put(n, JSONObject().put("type", tipo).put("description", d.removePrefix("?")))
            if (!d.startsWith("?")) req.put(n)
        }
        return JSONObject().put("name", nombre).put("description", desc)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", req))
    }
    private fun p(n: String, tipo: String, d: String) = Triple(n, tipo, d)

    val esquemas: JSONArray = JSONArray(listOf(
        h("fecha_hora", "Fecha y hora actuales."),
        h("bateria", "Nivel de batería y si está cargando."),
        h("clima", "Clima actual y pronóstico de 4 días.", p("ciudad", "string", "?Ciudad; por defecto la del usuario")),
        h("abrir_app", "Abre una app instalada por su nombre (WhatsApp, Instagram, Spotify, cámara, ajustes...).",
            p("nombre", "string", "Nombre de la app")),
        h("buscar_web", "Abre una búsqueda o página web.", p("consulta", "string", "Texto a buscar o URL"),
            p("sitio", "string", "?google | youtube | maps | mercadolibre")),
        h("reproducir", "Busca y reproduce música o videos.", p("consulta", "string", "Canción, artista o video"),
            p("app", "string", "?spotify | youtube | youtube_music. Por defecto spotify")),
        h("navegar", "Abre Google Maps con indicaciones hacia un destino.", p("destino", "string", "Lugar o dirección"),
            p("modo", "string", "?d (auto) | w (caminando) | b (bici) | r (transporte). Por defecto d")),
        h("buscar_contacto", "Busca un contacto y devuelve sus números.", p("nombre", "string", "Nombre del contacto")),
        h("llamar", "Llama por teléfono. Confirmá con el usuario antes.", p("destino", "string", "Nombre de contacto o número")),
        h("enviar_sms", "Envía un SMS. Confirmá el texto con el usuario antes.",
            p("destino", "string", "Nombre de contacto o número"), p("mensaje", "string", "Texto")),
        h("enviar_whatsapp", "Envía un WhatsApp a un contacto. Confirmá el texto con el usuario antes.",
            p("destino", "string", "Nombre de contacto o número"), p("mensaje", "string", "Texto")),
        h("alarma", "Pone una alarma.", p("hora", "integer", "Hora 0-23"), p("minuto", "integer", "Minuto 0-59"),
            p("etiqueta", "string", "?Nombre de la alarma")),
        h("temporizador", "Pone un temporizador.", p("segundos", "integer", "Duración en segundos"),
            p("etiqueta", "string", "?Nombre")),
        h("recordatorio_calendario", "Crea un evento en el calendario.", p("titulo", "string", "Título"),
            p("inicio", "string", "Fecha y hora 'AAAA-MM-DD HH:MM'"), p("minutos", "integer", "?Duración, por defecto 60")),
        h("linterna", "Prende o apaga la linterna.", p("encender", "boolean", "true para prender")),
        h("volumen", "Ajusta el volumen multimedia.", p("accion", "string", "subir | bajar | silenciar | fijar"),
            p("porcentaje", "integer", "?0-100, solo para fijar")),
        h("multimedia", "Controla lo que está sonando.", p("accion", "string", "play_pausa | siguiente | anterior")),
        h("ajustes", "Abre un panel de ajustes del sistema.",
            p("panel", "string", "wifi | bluetooth | datos | ubicacion | pantalla | sonido | bateria | no_molestar | apps | general")),
        h("leer_pantalla", "Lee lo que hay en la pantalla ahora: elementos numerados con su texto y si son tocables o editables. Usala antes de tocar y después de cada acción para ver el resultado."),
        h("tocar", "Toca un elemento de la última lectura de pantalla por su número.",
            p("indice", "integer", "Número del elemento"), p("mantener", "boolean", "?true para toque largo")),
        h("tocar_texto", "Toca el primer elemento visible que contenga ese texto.", p("texto", "string", "Texto del botón o elemento")),
        h("escribir_texto", "Escribe en el campo con foco o en el elemento indicado.", p("texto", "string", "Texto"),
            p("indice", "integer", "?Número del campo editable")),
        h("deslizar", "Desliza la pantalla. 'arriba' muestra lo que está más abajo.", p("direccion", "string", "arriba | abajo | izquierda | derecha")),
        h("boton_sistema", "Botones del sistema.",
            p("accion", "string", "atras | inicio | recientes | notificaciones | ajustes_rapidos | bloquear | captura | apagado")),
        h("esperar", "Espera a que cargue algo.", p("segundos", "number", "1 a 8")),
    ))

    // ---------------------------------------------------------------- EJECUCIÓN
    fun ejecutar(nombre: String, e: JSONObject): String = try {
        when (nombre) {
            "fecha_hora" -> SimpleDateFormat("EEEE d 'de' MMMM yyyy, HH:mm", Locale("es", "AR")).format(Date())
            "bateria" -> bateria()
            "clima" -> clima(e.optString("ciudad").ifBlank { prefs.ciudad })
            "abrir_app" -> abrirApp(e.getString("nombre"))
            "buscar_web" -> buscarWeb(e.getString("consulta"), e.optString("sitio", "google"))
            "reproducir" -> reproducir(e.getString("consulta"), e.optString("app", "spotify"))
            "navegar" -> lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(
                "google.navigation:q=${enc(e.getString("destino"))}&mode=${e.optString("modo", "d")}")), "Navegando")
            "buscar_contacto" -> buscarContactos(e.getString("nombre")).let {
                if (it.isEmpty()) "No encontré ese contacto" else it.joinToString("; ") { (n, t) -> "$n: $t" } }
            "llamar" -> llamar(e.getString("destino"))
            "enviar_sms" -> sms(e.getString("destino"), e.getString("mensaje"))
            "enviar_whatsapp" -> whatsapp(e.getString("destino"), e.getString("mensaje"))
            "alarma" -> lanzar(Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, e.getInt("hora")).putExtra(AlarmClock.EXTRA_MINUTES, e.getInt("minuto"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, e.optString("etiqueta", prefs.nombre))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Alarma puesta")
            "temporizador" -> lanzar(Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, e.getInt("segundos"))
                .putExtra(AlarmClock.EXTRA_MESSAGE, e.optString("etiqueta", prefs.nombre))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Temporizador puesto")
            "recordatorio_calendario" -> evento(e.getString("titulo"), e.getString("inicio"), e.optInt("minutos", 60))
            "linterna" -> linterna(e.getBoolean("encender"))
            "volumen" -> volumen(e.getString("accion"), e.optInt("porcentaje", 50))
            "multimedia" -> multimedia(e.getString("accion"))
            "ajustes" -> ajustes(e.getString("panel"))
            "leer_pantalla" -> manos?.leerPantalla() ?: SIN_MANOS
            "tocar" -> manos?.tocar(e.getInt("indice"), e.optBoolean("mantener")) ?: SIN_MANOS
            "tocar_texto" -> manos?.tocarTexto(e.getString("texto")) ?: SIN_MANOS
            "escribir_texto" -> manos?.escribir(e.getString("texto"),
                if (e.has("indice")) e.getInt("indice") else null) ?: SIN_MANOS
            "deslizar" -> manos?.deslizar(e.getString("direccion")) ?: SIN_MANOS
            "boton_sistema" -> manos?.botonSistema(e.getString("accion")) ?: SIN_MANOS
            "esperar" -> { Thread.sleep((e.optDouble("segundos", 2.0).coerceIn(0.5, 8.0) * 1000).toLong()); "Listo" }
            else -> "Herramienta desconocida"
        }
    } catch (ex: Exception) {
        "ERROR: ${ex.javaClass.simpleName}: ${ex.message}"
    }

    // ---------------------------------------------------------------- IMPLEMENTACIONES
    private val SIN_MANOS = "El control de pantalla está apagado. Pedile al usuario que active Nova en Ajustes > Accesibilidad."

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Lanza actividades desde el servicio de accesibilidad si está activo (Android permite abrir apps desde ahí). */
    private fun lanzar(i: Intent, ok: String): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (manos ?: ctx).startActivity(i)
        return ok
    }

    private fun tienePermiso(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun bateria(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val nivel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "$nivel%" + if (bm.isCharging) ", cargando" else ", sin cargar"
    }

    private fun clima(ciudad: String): String {
        fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name=${enc(ciudad)}"))
        val r = geo.optJSONArray("results")?.optJSONObject(0) ?: return "No encontré $ciudad"
        val d = get("https://api.open-meteo.com/v1/forecast?latitude=${r.getDouble("latitude")}&longitude=${r.getDouble("longitude")}" +
            "&timezone=auto&forecast_days=4&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code")
        return "Lugar: ${r.getString("name")}. Datos (weather_code es código WMO): $d"
    }

    private fun apps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }

    private fun abrirApp(nombre: String): String {
        val q = normalizar(nombre)
        val todas = apps()
        val app = todas.firstOrNull { normalizar(it.first) == q }
            ?: todas.firstOrNull { normalizar(it.first).startsWith(q) }
            ?: todas.firstOrNull { normalizar(it.first).contains(q) || it.second.contains(q.replace(" ", "")) }
            ?: return "No encontré la app \"$nombre\". Algunas instaladas: " + todas.map { it.first }.sorted().joinToString(", ").take(1500)
        val i = ctx.packageManager.getLaunchIntentForPackage(app.second) ?: return "No se puede abrir ${app.first}"
        return lanzar(i, "Abierta ${app.first}")
    }

    private fun buscarWeb(consulta: String, sitio: String): String {
        val url = if (consulta.startsWith("http")) consulta else when (sitio) {
            "youtube" -> "https://www.youtube.com/results?search_query=${enc(consulta)}"
            "maps" -> "geo:0,0?q=${enc(consulta)}"
            "mercadolibre" -> "https://listado.mercadolibre.com.ar/${enc(consulta)}"
            else -> "https://www.google.com/search?q=${enc(consulta)}"
        }
        return lanzar(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Abierto")
    }

    private fun reproducir(consulta: String, app: String): String {
        val i = when (app) {
            "youtube" -> Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", consulta)
            "youtube_music" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=${enc(consulta)}"))
            else -> Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${enc(consulta)}"))
        }
        return try { lanzar(i, "Buscado \"$consulta\" en $app. Si querés que empiece a sonar, leé la pantalla y tocá el primer resultado.") }
        catch (ex: Exception) { buscarWeb(consulta, "youtube") }
    }

    private fun buscarContactos(nombre: String): List<Pair<String, String>> {
        if (!tienePermiso(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$nombre%"), null
        )?.use { c ->
            while (c.moveToNext() && out.size < 6) out.add(c.getString(0) to c.getString(1))
        }
        return out.distinctBy { it.second.filter(Char::isDigit) }
    }

    /** Si [destino] ya es un número lo usa; si no, lo busca en contactos. */
    private fun resolverNumero(destino: String): String? {
        val digitos = destino.filter { it.isDigit() || it == '+' }
        if (digitos.count { it.isDigit() } >= 6) return digitos
        return buscarContactos(destino).firstOrNull()?.second
    }

    @SuppressLint("MissingPermission")
    private fun llamar(destino: String): String {
        val num = resolverNumero(destino) ?: return "No encontré el número de $destino"
        val accion = if (tienePermiso(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return lanzar(Intent(accion, Uri.parse("tel:${Uri.encode(num)}")), "Llamando a $destino")
    }

    private fun sms(destino: String, mensaje: String): String {
        val num = resolverNumero(destino) ?: return "No encontré el número de $destino"
        if (!tienePermiso(Manifest.permission.SEND_SMS)) return "Falta el permiso de SMS"
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                 else @Suppress("DEPRECATION") SmsManager.getDefault()
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(mensaje), null, null)
        return "SMS enviado a $destino"
    }

    /** Número en formato internacional para WhatsApp. En Argentina los celulares llevan 549 + área + número. */
    private fun formatoInternacional(num: String): String {
        var d = num.filter { it.isDigit() }
        if (num.trim().startsWith("+") || d.startsWith("00")) return d.removePrefix("00")
        val pais = prefs.codigoPais
        if (d.startsWith(pais) && d.length > 10) return d
        d = d.removePrefix("0")
        if (pais == "54") {
            // 11 15 2345 6789 -> 11 2345 6789 (se saca el 15 después del código de área)
            for (largoArea in 2..4) {
                if (d.length == 12 && d.substring(largoArea, largoArea + 2) == "15") {
                    d = d.removeRange(largoArea, largoArea + 2); break
                }
            }
            return "549$d"
        }
        return pais + d
    }

    private fun whatsapp(destino: String, mensaje: String): String {
        val num = resolverNumero(destino) ?: return "No encontré el número de $destino"
        val intl = formatoInternacional(num)
        lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$intl&text=${enc(mensaje)}")), "")
        val m = manos ?: return "Abrí el chat con el mensaje escrito; falta tocar Enviar (activá la accesibilidad para que lo haga solo)."
        for (intento in 1..6) {
            Thread.sleep(1200)
            if (m.appActual().startsWith("com.whatsapp")) {
                for (etiqueta in listOf("Enviar", "Send")) {
                    val r = m.tocarTexto(etiqueta)
                    if (r.startsWith("Tocado")) return "WhatsApp enviado a $destino ($intl)"
                }
            }
        }
        return "Abrí el chat de WhatsApp pero no encontré el botón Enviar. Leé la pantalla para terminar."
    }

    private fun evento(titulo: String, inicio: String, minutos: Int): String {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(inicio)?.time ?: return "Fecha inválida"
        return lanzar(Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.Events.TITLE, titulo)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, t)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, t + minutos * 60_000L),
            "Abrí el calendario con el evento cargado. Leé la pantalla y tocá Guardar.")
    }

    private fun linterna(encender: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este celular no tiene linterna"
        cm.setTorchMode(id, encender)
        return if (encender) "Linterna prendida" else "Linterna apagada"
    }

    private fun volumen(accion: String, porcentaje: Int): String {
        val s = AudioManager.STREAM_MUSIC
        when (accion) {
            "subir" -> repeat(2) { audio.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "bajar" -> repeat(2) { audio.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "silenciar" -> audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "fijar" -> audio.setStreamVolume(s, audio.getStreamMaxVolume(s) * porcentaje.coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
        }
        return "Volumen: ${audio.getStreamVolume(s) * 100 / audio.getStreamMaxVolume(s)}%"
    }

    private fun multimedia(accion: String): String {
        val code = when (accion) {
            "siguiente" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Hecho"
    }

    private fun ajustes(panel: String): String {
        val accion = when (panel) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "datos" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_DATA_ROAMING_SETTINGS
            "ubicacion" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "pantalla" -> Settings.ACTION_DISPLAY_SETTINGS
            "sonido" -> Settings.ACTION_SOUND_SETTINGS
            "bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "no_molestar" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return lanzar(Intent(accion), "Abrí ajustes de $panel. Leé la pantalla para cambiar lo que haga falta.")
    }
}
