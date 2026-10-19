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
 * Todo lo que Nova puede hacer en el celular. Cada herramienta tiene un esquema (para que el cerebro
 * sepa que existe) y una implementación en [ejecutar].
 */
class Herramientas(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        /** El servicio lo conecta para entrar al modo estudio (tutor con charla continua). */
        var alModoEstudio: ((String) -> Unit)? = null
        /** Canal con la PC (lo pone el servicio si hay código de vinculación). */
        var vinculo: Vinculo? = null
    }

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val manos get() = ControlAccesibilidad.instancia
    private val vision = Vision(prefs)
    private val libreta = Libreta.de(ctx)
    private val agenda = Agenda(ctx)

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
        // --- Ojos
        h("mirar_pantalla", "Saca una captura y la mira con visión: sirve para describir fotos o videos, entender pantallas " +
            "sin texto y ubicar íconos. Si pedís ubicar algo, devuelve su posición [y, x] de 0 a 1000 para usar con tocar_punto.",
            p("pregunta", "string", "Qué querés saber de la pantalla")),
        h("tocar_punto", "Toca un punto de la pantalla dado por mirar_pantalla, en coordenadas [y, x] de 0 a 1000.",
            p("y", "integer", "Vertical 0-1000"), p("x", "integer", "Horizontal 0-1000"), p("mantener", "boolean", "?true para toque largo")),
        h("mirar_camara", "Abre la cámara, saca una foto y la mira. Para '¿qué es esto?', leer un cartel, traducir un menú, etc.",
            p("pregunta", "string", "Qué querés saber de lo que se ve")),
        // --- Memoria y datos
        h("recordar", "Guarda para siempre algo sobre el usuario (gustos, gente, datos, pendientes). Usala cuando diga " +
            "'acordate que…' o cuente algo personal que convenga recordar.", p("dato", "string", "El dato, en una oración")),
        h("olvidar", "Borra de la memoria lo que coincida.", p("algo", "string", "Palabra o frase a olvidar")),
        h("ver_memoria", "Muestra todo lo que recordás del usuario."),
        h("lista_agregar", "Agrega cosas a una lista (compras, pendientes, pelis, regalos…). Se crea sola si no existe.",
            p("lista", "string", "Nombre de la lista"), p("items", "string", "Cosas separadas por coma")),
        h("lista_quitar", "Saca algo de una lista (ya comprado, ya hecho).", p("lista", "string", "Nombre"), p("item", "string", "Qué sacar")),
        h("lista_ver", "Lee una lista, o todas si no decís cuál.", p("lista", "string", "?Nombre de la lista")),
        h("lista_borrar", "Borra una lista entera.", p("lista", "string", "Nombre")),
        h("gasto_anotar", "Anota un gasto. 'Lucas' o 'mil' = miles: 5 lucas = 5000.", p("monto", "number", "En pesos"),
            p("descripcion", "string", "En qué"), p("categoria", "string", "?super, comida, transporte, salidas, servicios, ropa, salud, facu, otros")),
        h("gastos_resumen", "Cuánto gastó y en qué.", p("periodo", "string", "?hoy | semana | mes | año. Por defecto mes"),
            p("categoria", "string", "?Filtrar por categoría")),
        h("gasto_borrar_ultimo", "Borra el último gasto anotado (si se equivocó)."),
        h("agenda_ver", "Lee la agenda del celular (Google Calendar).", p("desde_dias", "integer", "?0 = hoy, 1 = mañana. Por defecto 0"),
            p("dias", "integer", "?Cuántos días. Por defecto 1")),
        h("aviso_programar", "Programa un aviso para que Nova te lo diga a esa hora, una vez o repetido. Para la rutina de buen día " +
            "automática usá el texto '@buen_dia'. Para que a esa hora Nova HAGA algo (no solo avisar), empezá el texto con '!', " +
            "por ejemplo '!activá no molestar y poné el brillo al 10%'.",
            p("texto", "string", "Qué avisar"), p("cuando", "string", "'AAAA-MM-DD HH:MM' o 'en N minutos' / 'en N horas'"),
            p("repetir", "string", "?no | diario | semanal | laborales. Por defecto no")),
        h("avisos_ver", "Lista los avisos programados."),
        h("aviso_borrar", "Borra un aviso programado.", p("id", "integer", "Número del aviso (de avisos_ver)")),
        // --- Automatizaciones
        h("automatizacion_crear", "Crea una automatización: cuando pase algo, Nova hace lo que diga 'hacer' (en palabras, con todas " +
            "sus herramientas). Disparadores: voz (una frase como 'modo dormir'), auriculares, bluetooth (valor = nombre del " +
            "dispositivo, ej. el del auto), cargador, desconectar_cargador, bateria_baja (valor = porcentaje), llegar / salir " +
            "(valor = nombre de un lugar guardado con lugar_guardar), mensaje (valor = nombre de la persona).",
            p("nombre", "string", "Nombre corto"), p("disparador", "string", "voz | auriculares | bluetooth | cargador | " +
                "desconectar_cargador | bateria_baja | llegar | salir | mensaje"),
            p("valor", "string", "?Frase, nombre del dispositivo, porcentaje, lugar o persona según el disparador"),
            p("hacer", "string", "Qué hacer, en palabras: 'activá no molestar, poné alarma 7:30 y bajá el brillo'")),
        h("automatizaciones_ver", "Lista las automatizaciones creadas."),
        h("automatizacion_borrar", "Borra una automatización.", p("id", "integer", "Número (de automatizaciones_ver)")),
        h("lugar_guardar", "Guarda la ubicación actual con un nombre (casa, facu, trabajo) para usar en automatizaciones de llegar o salir.",
            p("nombre", "string", "Nombre del lugar")),
        h("no_molestar", "Activa o desactiva el modo No molestar.", p("activar", "boolean", "true para activar")),
        h("brillo", "Cambia el brillo de la pantalla.", p("porcentaje", "integer", "0 a 100")),
        // --- Personalidad y foco
        h("cambiar_personalidad", "Cambia tu forma de ser: calida, graciosa, seria, motivadora, sarcastica, o propia (con descripción).",
            p("estilo", "string", "calida | graciosa | seria | motivadora | sarcastica | propia"),
            p("descripcion", "string", "?Solo para propia: cómo querés ser")),
        h("cambiar_voz", "Cambia tu voz. Opciones: " + VozNatural.VOCES.entries.joinToString { "${it.key} (${it.value})" },
            p("voz", "string", "Clave de la voz")),
        h("modo_estudio", "Entra en modo tutor sobre un tema: tomás examen oral, de a una pregunta, corregís y llevás la cuenta. " +
            "Usala para 'tomame examen de…', 'estudiemos…', 'preguntame de…'.", p("tema", "string", "Tema o materia")),
        h("pomodoro_iniciar", "Arranca bloques de estudio/trabajo con descansos. Silencia el celular durante el foco y avisa por voz.",
            p("minutos", "integer", "?Foco, por defecto 25"), p("descanso", "integer", "?Por defecto 5"),
            p("bloques", "integer", "?Cantidad, por defecto 4"), p("tema", "string", "?Qué estudia")),
        h("pomodoro_estado", "Cuánto falta del bloque actual."),
        h("pomodoro_parar", "Corta el pomodoro."),
        h("estudio_resumen", "Cuánto estudió, por tema.", p("periodo", "string", "?hoy | semana | mes. Por defecto semana")),
        h("noticias", "Titulares de noticias actuales (Argentina por defecto), opcionalmente de un tema. Sirve para resúmenes " +
            "y para armar un mini podcast.", p("tema", "string", "?Tema a buscar")),
        // --- Conexiones
        h("pc_ordenar", "Le manda una orden a Nova en la PC del usuario, esté donde esté (apagar, bloquear, poner música, abrir " +
            "programas, mirar la pantalla de la PC…). Devuelve lo que respondió la PC.", p("orden", "string", "Qué hacer en la PC, en palabras")),
        h("prender_pantalla", "Prende la pantalla y pide quitar el bloqueo (si Extend Unlock lo tiene desbloqueado, queda abierto; " +
            "si no, aparece el pedido de huella o PIN)."),
        h("buscar_actualizacion", "Busca si hay una versión nueva de Nova y, si hay, la descarga e instala."),
        // --- Servicios externos
        h("casa", "Para luces, enchufes, aire, TV y aparatos de la casa: abre la app de casa del usuario. Después seguí con " +
            "leer_pantalla / mirar_pantalla y tocar hasta hacer lo pedido.", p("pedido", "string", "Qué hay que hacer, ej. 'apagar la luz del cuarto'")),
        h("casa_configurar", "Guarda qué app usa el usuario para su casa (Google Home, Smart Life, Tuya, SmartThings…).",
            p("app", "string", "Nombre de la app")),
        h("modo_manejo", "Abre o cierra el modo manejo (pantalla grande para el auto, respuestas cortas).", p("activar", "boolean", "true para abrir")),
        h("precio_seguir", "Empieza a seguir el precio de un producto de Mercado Libre (link). Revisa una vez por día y avisa si baja.",
            p("url", "string", "Link del producto"), p("nombre", "string", "?Nombre corto")),
        h("precios_revisar", "Revisa ahora los precios de los productos que seguís y dice cuáles bajaron."),
        h("precio_dejar", "Deja de seguir un producto.", p("nombre", "string", "Nombre o parte")),
        h("envio_guardar", "Guarda un envío para seguirlo (código de seguimiento).", p("nombre", "string", "Qué es"),
            p("codigo", "string", "Código de seguimiento"), p("empresa", "string", "?Mercado Libre, Andreani, Correo Argentino, OCA…")),
        h("envios_ver", "Lista los envíos guardados. Para ver el estado, abrí la app de la empresa (o Mercado Libre › Mis compras) y leé la pantalla."),
        h("envio_borrar", "Borra un envío (ya llegó).", p("nombre", "string", "Nombre o parte")),
        // --- Mensajes
        h("mensajes_sin_leer", "Lee los mensajes sin leer de las notificaciones (WhatsApp, Instagram, Telegram, SMS…), agrupados por chat. " +
            "Usala para '¿qué me perdí?' o 'leeme mis mensajes' y después resumí.",
            p("app", "string", "?Filtrar por app, por ejemplo whatsapp")),
        h("ultimos_mensajes", "Lista los últimos mensajes que llegaron, numerados, para saber cuál responder."),
        h("responder_mensaje", "Responde un mensaje desde su notificación, sin abrir la app. Por defecto responde el más reciente.",
            p("texto", "string", "Qué responder"), p("chat", "string", "?Nombre del chat o persona"),
            p("indice", "integer", "?Número de ultimos_mensajes")),
        // --- Imágenes y videos
        h("generar_imagen", "Genera una imagen con IA a partir de una descripción, la guarda en la galería y la abre. " +
            "Para 'haceme una imagen de…', 'dibujame…', 'creá un logo…', 'un fondo de pantalla de…'. Tarda unos segundos.",
            p("prompt", "string", "Descripción para el generador, EN INGLÉS y detallada: sujeto, estilo, luz, encuadre, colores"),
            p("formato", "string", "?cuadrado | vertical (fondo de pantalla, historia) | horizontal. Por defecto cuadrado"),
            p("descripcion", "string", "?Resumen corto en español de qué es, para recordarla")),
        h("generar_video", "Genera un video corto con IA (4 a 8 segundos) a partir de una descripción. Se hace en segundo plano " +
            "(1 o 2 minutos) y Nova avisa cuando está; queda en la galería. Necesita la clave de Pollinations.",
            p("prompt", "string", "Descripción EN INGLÉS y detallada: qué pasa, movimiento de cámara, estilo, luz"),
            p("segundos", "integer", "?4, 6 u 8. Por defecto 4"),
            p("formato", "string", "?horizontal | vertical. Por defecto horizontal"),
            p("descripcion", "string", "?Resumen corto en español")),
        h("media_enviar", "Manda por WhatsApp la última imagen o video que generaste a un contacto o número.",
            p("destino", "string", "Nombre de contacto o número"), p("texto", "string", "?Mensaje para acompañar")),
        h("media_compartir", "Abre el menú de compartir de Android con la última imagen o video generado (Instagram, Drive, etc.)."),
        // --- Envíos masivos
        h("envio_masivo_preparar", "Prepara un envío masivo por WhatsApp SIN mandar nada: lee los teléfonos de las últimas " +
            "capturas de pantalla (o de números que te dicte) y arma la lista con el mensaje y/o un documento guardado. " +
            "Para 'escaneá los números de las últimas 10 capturas y mandales mi CV con el mensaje predeterminado'. " +
            "Si no dice qué mensaje, usa la plantilla predeterminada. Después mostrale la lista y preguntá antes de mandar.",
            p("capturas", "integer", "?Cuántas capturas leer (las más nuevas). 0 si solo dicta números"),
            p("todas_las_imagenes", "boolean", "?true para leer las últimas fotos/imágenes en general, no solo capturas"),
            p("numeros", "string", "?Números dictados, separados por coma"),
            p("mensaje", "string", "?Texto a mandar, si lo dice textual. Puede llevar {nombre}"),
            p("plantilla", "string", "?Nombre de una plantilla guardada (vacío = la predeterminada)"),
            p("documento", "string", "?Nombre del documento guardado a adjuntar, ej. 'CV Mateo Taylor CHEF'"),
            p("repetir", "boolean", "?true para mandar también a quienes ya se les mandó lo mismo antes")),
        h("envio_masivo_quitar", "Saca a alguien de la lista preparada.", p("cual", "string", "Número de la lista (1, 2…), nombre o últimos dígitos")),
        h("envio_masivo_enviar", "Manda el envío preparado, uno por uno con pausas, en segundo plano. SOLO después de que el usuario dijo que sí."),
        h("envio_masivo_estado", "Cómo va el envío masivo, o qué hay preparado."),
        h("envio_masivo_parar", "Corta el envío masivo en curso."),
        h("documento_guardar", "Abre el selector de archivos para que el usuario elija un documento (CV, PDF, foto) y lo guarda " +
            "con un nombre, para mandarlo después por voz. Se hace una sola vez por documento.",
            p("nombre", "string", "?Nombre para recordarlo, ej. 'CV chef'. Vacío = el nombre del archivo")),
        h("documentos_ver", "Lista los documentos guardados."),
        h("documento_borrar", "Borra un documento guardado (solo de Nova, no del celular).", p("nombre", "string", "Nombre o parte")),
        h("plantilla_guardar", "Guarda un mensaje para reusar (el 'mensaje predeterminado' se llama predeterminado). Puede llevar {nombre}, " +
            "que se reemplaza por el nombre de la persona o negocio si se conoce.",
            p("nombre", "string", "?Nombre de la plantilla. Vacío = predeterminado"), p("texto", "string", "El mensaje")),
        h("plantillas_ver", "Lista los mensajes guardados."),
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
            "recordatorio_calendario" -> agenda.crear(e.getString("titulo"), e.getString("inicio"), e.optInt("minutos", 60))
                .let { if (it.startsWith("Falta")) evento(e.getString("titulo"), e.getString("inicio"), e.optInt("minutos", 60)) else it }
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
            "recordar" -> libreta.recordar(e.getString("dato"))
            "olvidar" -> libreta.olvidar(e.getString("algo"))
            "ver_memoria" -> libreta.verMemoria()
            "lista_agregar" -> libreta.listaAgregar(e.getString("lista"), e.getString("items").split(",", " y "))
            "lista_quitar" -> libreta.listaQuitar(e.getString("lista"), e.getString("item"))
            "lista_ver" -> libreta.listaVer(e.optString("lista").ifBlank { null })
            "lista_borrar" -> libreta.listaBorrar(e.getString("lista"))
            "gasto_anotar" -> libreta.gastoAnotar(e.getDouble("monto"), e.getString("descripcion"), e.optString("categoria").ifBlank { null })
            "gastos_resumen" -> libreta.gastosResumen(e.optString("periodo", "mes"), e.optString("categoria").ifBlank { null })
            "gasto_borrar_ultimo" -> libreta.gastoBorrarUltimo()
            "agenda_ver" -> agenda.ver(e.optInt("desde_dias", 0), e.optInt("dias", 1))
            "aviso_programar" -> programarAviso(e.getString("texto"), e.getString("cuando"), e.optString("repetir", "no"))
            "avisos_ver" -> libreta.avisos().sortedBy { it.cuando }.joinToString("\n") { libreta.describir(it) }.ifBlank { "No hay avisos programados." }
            "aviso_borrar" -> { val id = e.getInt("id"); Avisos.cancelar(ctx, id); libreta.avisoActualizar(id, null); "Borré el aviso $id." }
            "automatizacion_crear" -> crearAutomatizacion(e.getString("nombre"), e.getString("disparador"),
                e.optString("valor"), e.getString("hacer"))
            "automatizaciones_ver" -> libreta.reglas().joinToString("\n") { libreta.describir(it) }.ifBlank { "No hay automatizaciones." }
            "automatizacion_borrar" -> if (libreta.reglaBorrar(e.getInt("id"))) {
                Automatizaciones.actualizarGeovallas(ctx); "Borrada." } else "No existe esa automatización."
            "lugar_guardar" -> guardarLugar(e.getString("nombre"))
            "no_molestar" -> noMolestar(e.getBoolean("activar"))
            "brillo" -> brillo(e.getInt("porcentaje"))
            "cambiar_personalidad" -> {
                val est = normalizar(e.getString("estilo"))
                val clave = Personalidad.ESTILOS.keys.firstOrNull { it == est } ?: Personalidad.buscar(est) ?: "calida"
                prefs.personalidad = clave
                if (clave == "propia") prefs.personalidadPropia = e.optString("descripcion")
                "Listo, desde ahora soy ${Personalidad.ESTILOS[clave]?.first?.lowercase()}."
            }
            "cambiar_voz" -> {
                val v = e.getString("voz").trim()
                val clave = VozNatural.VOCES.keys.firstOrNull { it.equals(v, true) }
                    ?: VozNatural.VOCES.entries.firstOrNull { normalizar(it.value).contains(normalizar(v)) }?.key
                if (clave == null) "No conozco esa voz." else { prefs.voz = clave; "Cambié a ${VozNatural.VOCES[clave]}." }
            }
            "modo_estudio" -> { alModoEstudio?.invoke(e.getString("tema")); "Modo estudio activado sobre ${e.getString("tema")}. Hacé la primera pregunta." }
            "pomodoro_iniciar" -> Pomodoro.iniciar(ctx, e.optInt("minutos", 25), e.optInt("descanso", 5), e.optInt("bloques", 4), e.optString("tema"))
            "pomodoro_estado" -> Pomodoro.estado()
            "pomodoro_parar" -> Pomodoro.parar(ctx)
            "estudio_resumen" -> libreta.estudioResumen(e.optString("periodo", "semana"))
            "noticias" -> noticias(e.optString("tema"))
            "pc_ordenar" -> vinculo?.ordenarPC(e.getString("orden"))
                ?: "No estás vinculado con la PC. En la PC: Ajustes › Conexiones › copiá el código y pegalo en Ajustes de Nova en el celular."
            "prender_pantalla" -> lanzar(Intent(ctx, PantallaActivity::class.java), "Prendí la pantalla.")
            "buscar_actualizacion" -> {
                val (novedad, texto) = Actualizador.revisar(ctx)
                if (novedad == null) texto else Actualizador.descargarEInstalar(ctx, novedad)
            }
            "casa" -> if (prefs.appCasa.isBlank()) "Todavía no sé qué app usás para la casa. Preguntale (Google Home, Smart Life, Tuya…) y usá casa_configurar."
                else abrirApp(prefs.appCasa).let { r -> if (r.startsWith("Abierta")) "$r. Ahora usá leer_pantalla y tocá lo necesario para: ${e.getString("pedido")}" else r }
            "casa_configurar" -> { prefs.appCasa = e.getString("app"); "Listo, para la casa uso ${prefs.appCasa}." }
            "modo_manejo" -> if (e.getBoolean("activar")) lanzar(Intent(ctx, ManejoActivity::class.java), "Modo manejo activado.")
                else { botonSistemaAtras(); "Modo manejo desactivado." }
            "precio_seguir" -> seguirPrecio(e.getString("url"), e.optString("nombre"))
            "precios_revisar" -> revisarPrecios()
            "precio_dejar" -> if (libreta.productoBorrar(e.getString("nombre"))) "Listo, ya no lo sigo." else "No encontré ese producto."
            "envio_guardar" -> { libreta.envioGuardar(e.getString("nombre"), e.getString("codigo"), e.optString("empresa", "Mercado Libre")); "Guardado." }
            "envios_ver" -> libreta.envios()
            "envio_borrar" -> if (libreta.envioBorrar(e.getString("nombre"))) "Borrado." else "No encontré ese envío."
            "mirar_pantalla" -> mirarPantalla(e.optString("pregunta"))
            "tocar_punto" -> manos?.tocarPunto(e.getInt("y"), e.getInt("x"), e.optBoolean("mantener")) ?: SIN_MANOS
            "mirar_camara" -> mirarCamara(e.optString("pregunta"))
            "mensajes_sin_leer" -> Notificaciones.instancia?.sinLeer(e.optString("app").ifBlank { null }) ?: SIN_NOTIF
            "ultimos_mensajes" -> Notificaciones.instancia?.ultimos() ?: SIN_NOTIF
            "responder_mensaje" -> Notificaciones.instancia?.responder(e.getString("texto"),
                if (e.has("indice")) e.getInt("indice") else null, e.optString("chat").ifBlank { null }) ?: SIN_NOTIF
            "envio_masivo_preparar" -> EnvioMasivo.preparar(ctx, prefs, e.optInt("capturas", 0), !e.optBoolean("todas_las_imagenes"),
                e.optString("numeros"), e.optString("mensaje"), e.optString("plantilla"), e.optString("documento"), e.optBoolean("repetir"))
            "generar_imagen" -> Medios.imagen(ctx, prefs, e.getString("prompt"), e.optString("formato"), e.optString("descripcion"))
            "generar_video" -> Medios.video(ctx, prefs, e.getString("prompt"), e.optInt("segundos", 4), e.optString("formato"), e.optString("descripcion"))
            "media_enviar" -> {
                val u = Medios.ultimo
                val num = resolverNumero(e.getString("destino"))
                when {
                    u == null -> "Todavía no generé ninguna imagen ni video."
                    num == null -> "No encontré el número de ${e.getString("destino")}."
                    else -> EnvioMasivo.mandarArchivo(ctx, formatoInternacional(num), u.uri, u.tipo, e.optString("texto"))
                }
            }
            "media_compartir" -> Medios.compartir(ctx)
            "envio_masivo_quitar" -> EnvioMasivo.quitar(e.getString("cual"))
            "envio_masivo_enviar" -> EnvioMasivo.enviar(ctx)
            "envio_masivo_estado" -> EnvioMasivo.estado()
            "envio_masivo_parar" -> EnvioMasivo.parar()
            "documento_guardar" -> lanzar(Intent(ctx, DocumentoActivity::class.java).putExtra(DocumentoActivity.NOMBRE, e.optString("nombre")),
                "Abrí tus archivos: que elija el documento. Cuando lo elija queda guardado y le aviso.")
            "documentos_ver" -> libreta.documentos().joinToString("\n") { "${it.nombre} (${it.archivo})" }.ifBlank { "No hay documentos guardados." }
            "documento_borrar" -> if (libreta.documentoBorrar(e.getString("nombre"))) "Listo, lo saqué de mis documentos." else "No encontré ese documento."
            "plantilla_guardar" -> { libreta.plantillaGuardar(e.optString("nombre").ifBlank { "predeterminado" }, e.getString("texto")); "Guardado el mensaje." }
            "plantillas_ver" -> libreta.plantillas().entries.joinToString("\n") { "${it.key}: ${it.value}" }.ifBlank { "No hay mensajes guardados." }
            "esperar" -> { Thread.sleep((e.optDouble("segundos", 2.0).coerceIn(0.5, 8.0) * 1000).toLong()); "Listo" }
            else -> "Herramienta desconocida"
        }
    } catch (ex: Exception) {
        "ERROR: ${ex.javaClass.simpleName}: ${ex.message}"
    }

    // ---------------------------------------------------------------- IMPLEMENTACIONES
    private fun botonSistemaAtras() { manos?.botonSistema("atras") }

    /** Precio actual de una publicación de Mercado Libre (lee la página como un navegador). */
    private fun leerPrecio(url: String): Pair<String, Double>? {
        val html = try {
            http.newCall(Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
                .header("Accept-Language", "es-AR").build()).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) { return null }
        val precio = (Regex("itemprop=\"price\" content=\"([\\d.]+)\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"price\"\\s*:\\s*([\\d.]+)").find(html)?.groupValues?.get(1))?.toDoubleOrNull() ?: return null
        val titulo = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.substringBefore(" | ")?.take(60) ?: "Producto"
        return titulo to precio
    }

    private fun seguirPrecio(url: String, nombre: String): String {
        val (titulo, precio) = leerPrecio(url.trim()) ?: return "No pude leer el precio de ese link. Probá con el link de la publicación de Mercado Libre."
        val n = nombre.ifBlank { titulo }
        libreta.productoGuardar(n, url.trim(), precio)
        // Revisión diaria automática (una sola, para todos los productos)
        if (libreta.avisos().none { it.texto.startsWith("!revisá los precios") }) {
            val manana = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 10); set(java.util.Calendar.MINUTE, 0) }
            Avisos.programar(ctx, libreta.avisoNuevo("!revisá los precios que sigo con precios_revisar y avisame solo si alguno bajó; si ninguno bajó, decí solo «Sin cambios en los precios».",
                manana.timeInMillis, "diario"))
        }
        return "Sigo $n: hoy está a $${"%,.0f".format(java.util.Locale("es", "AR"), precio)}. Te aviso si baja."
    }

    private fun revisarPrecios(): String {
        val lista = libreta.productos()
        if (lista.isEmpty()) return "No estás siguiendo ningún producto."
        val r = lista.map { p ->
            val ahora = leerPrecio(p.url)?.second
            when {
                ahora == null -> "${p.nombre}: no pude leer el precio"
                ahora < p.precio -> { libreta.productoGuardar(p.nombre, p.url, ahora)
                    "${p.nombre}: ¡BAJÓ! de $${"%.0f".format(p.precio)} a $${"%.0f".format(ahora)}" }
                ahora > p.precio -> { libreta.productoGuardar(p.nombre, p.url, ahora)
                    "${p.nombre}: subió a $${"%.0f".format(ahora)}" }
                else -> "${p.nombre}: sigue en $${"%.0f".format(ahora)}"
            }
        }
        return r.joinToString("\n")
    }

    private fun noticias(tema: String): String {
        val url = if (tema.isBlank()) "https://news.google.com/rss?hl=es-419&gl=AR&ceid=AR:es-419"
                  else "https://news.google.com/rss/search?q=${enc(tema)}&hl=es-419&gl=AR&ceid=AR:es-419"
        val xml = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
        val titulos = Regex("<item>.*?<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"").trim() }
            .take(12).toList()
        return if (titulos.isEmpty()) "No pude traer noticias ahora." else titulos.joinToString("\n") { "- $it" }
    }

    private fun crearAutomatizacion(nombre: String, disparador: String, valor: String, hacer: String): String {
        val d = normalizar(disparador).replace(" ", "_")
        val validos = listOf("voz", "auriculares", "bluetooth", "cargador", "desconectar_cargador", "bateria_baja", "llegar", "salir", "mensaje")
        if (d !in validos) return "Disparador inválido. Usá: ${validos.joinToString()}."
        if (d in listOf("llegar", "salir") && libreta.lugares().none { normalizar(it.nombre) == normalizar(valor) })
            return "Primero guardá el lugar \"$valor\" con lugar_guardar (estando ahí)."
        if (d == "bluetooth" && ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            && Build.VERSION.SDK_INT >= 31) return "Falta el permiso de Bluetooth: pedile al usuario que lo active en Ajustes de Nova › Para que funcione."
        val r = libreta.reglaNueva(nombre, d, valor, hacer)
        val extra = if (d in listOf("llegar", "salir")) Automatizaciones.actualizarGeovallas(ctx) else ""
        return "Creada: " + libreta.describir(r) + extra
    }

    private fun guardarLugar(nombre: String): String {
        if (!Automatizaciones.tienePermisoUbicacion(ctx)) return "Falta el permiso de ubicación: pedile al usuario que lo active en Ajustes de Nova."
        val u = Automatizaciones.ubicacionActual(ctx) ?: return "No pude obtener la ubicación. ¿Está prendido el GPS?"
        libreta.lugarGuardar(nombre, u.latitude, u.longitude)
        return "Guardé este lugar como $nombre." + Automatizaciones.actualizarGeovallas(ctx)
    }

    private fun noMolestar(activar: Boolean): String {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            lanzar(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "")
            return "Necesito permiso para manejar No molestar: te abrí el ajuste, activá Nova y pedímelo de nuevo."
        }
        nm.setInterruptionFilter(if (activar) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (activar) "No molestar activado." else "No molestar desactivado."
    }

    private fun brillo(porcentaje: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            lanzar(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")), "")
            return "Necesito permiso para cambiar el brillo: te abrí el ajuste, activalo y pedímelo de nuevo."
        }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (porcentaje.coerceIn(0, 100) * 255 / 100).coerceAtLeast(3))
        return "Brillo al $porcentaje%."
    }

    private fun programarAviso(texto: String, cuando: String, repetir: String): String {
        val c = normalizar(cuando)
        val momento = Regex("en (\\d+) (minuto|hora|segundo)").find(c)?.let {
            val n = it.groupValues[1].toLong()
            System.currentTimeMillis() + n * when (it.groupValues[2]) { "hora" -> 3_600_000L; "segundo" -> 1000L; else -> 60_000L }
        } ?: runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(cuando.trim().replace("T", " "))?.time }.getOrNull()
          ?: return "No entendí la hora. Usá 'AAAA-MM-DD HH:MM' o 'en N minutos'."
        if (momento < System.currentTimeMillis() - 60_000) return "Esa hora ya pasó."
        val rep = normalizar(repetir).let { if (it in listOf("diario", "semanal", "laborales")) it else "no" }
        // De lunes a viernes: si la primera vez cae en fin de semana, arranca el lunes
        val inicio = java.util.Calendar.getInstance().apply { timeInMillis = momento }
        while (rep == "laborales" && inicio.get(java.util.Calendar.DAY_OF_WEEK) in
            listOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)) inicio.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val av = libreta.avisoNuevo(texto, inicio.timeInMillis, rep)
        Avisos.programar(ctx, av)
        return "Listo: " + libreta.describir(av)
    }

    private val SIN_NOTIF = "El acceso a notificaciones está apagado. Pedile al usuario que lo active en los ajustes de Nova."

    private fun mirarPantalla(pregunta: String): String {
        val m = manos ?: return SIN_MANOS
        if (Build.VERSION.SDK_INT < 30) return "Mirar la pantalla necesita Android 11 o más. Usá leer_pantalla."
        val captura = m.capturar() ?: return "No pude sacar la captura. Probá de nuevo en un segundo."
        return vision.ver(Vision.aJpeg(captura), pregunta, esPantalla = true)
    }

    private fun mirarCamara(pregunta: String): String {
        val foto = Camara.sacar(ctx) ?: return "No se sacó la foto (se cerró la cámara o falta el permiso)."
        return vision.ver(foto, pregunta, esPantalla = false)
    }

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
    private fun formatoInternacional(num: String): String =
        EnvioMasivo.numeroWhatsApp(num, prefs.codigoPais) ?: num.filter { it.isDigit() }

    private fun whatsapp(destino: String, mensaje: String): String {
        val num = resolverNumero(destino) ?: return "No encontré el número de $destino"
        val intl = formatoInternacional(num)
        lanzar(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$intl&text=${enc(mensaje)}")), "")
        val m = manos ?: return "Abrí el chat con el mensaje escrito; falta tocar Enviar (activá la accesibilidad para que lo haga solo)."
        for (intento in 1..6) {
            Thread.sleep(1200)
            if (m.appActual().startsWith("com.whatsapp") && m.tocarEnviar()) return "WhatsApp enviado a $destino ($intl)"
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
