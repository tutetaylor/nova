package com.mateo.nova

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Los oídos para los mensajes: ve las notificaciones que llegan (WhatsApp, Instagram, Telegram, SMS…),
 * se las pasa a Nova para que te las lea, y permite responder desde la notificación sin abrir la app.
 */
class Notificaciones : NotificationListenerService() {

    data class Mensaje(
        val clave: String,
        val app: String,
        val quien: String,
        val texto: String,
        val cuando: Long,
        val responder: Notification.Action?,
    )

    companion object {
        @Volatile var instancia: Notificaciones? = null
        val activo get() = instancia != null

        /** El servicio de Nova se suscribe para anunciar los mensajes nuevos. */
        var alLlegar: ((Mensaje) -> Unit)? = null

        /** Apps de mensajería que vale la pena leer en voz alta. */
        private val MENSAJERIA = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram", "org.telegram.messenger" to "Telegram",
            "com.facebook.orca" to "Messenger", "com.google.android.apps.messaging" to "Mensajes",
            "com.samsung.android.messaging" to "Mensajes", "com.discord" to "Discord",
            "com.google.android.gm" to "Gmail", "com.snapchat.android" to "Snapchat")

        fun nombreApp(paquete: String) = MENSAJERIA[paquete]
    }

    /** Últimos mensajes vistos, el más nuevo al final. */
    private val recientes = ArrayDeque<Mensaje>()

    override fun onListenerConnected() { instancia = this }
    override fun onListenerDisconnected() { instancia = null }
    override fun onDestroy() { instancia = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val m = convertir(sbn) ?: return
        // WhatsApp vuelve a publicar la misma notificación al actualizarla: evitamos repetir
        synchronized(recientes) {
            if (recientes.any { it.clave == m.clave && it.texto == m.texto }) return
            recientes.addLast(m)
            while (recientes.size > 60) recientes.removeFirst()
        }
        alLlegar?.invoke(m)
    }

    private fun convertir(sbn: StatusBarNotification): Mensaje? {
        if (sbn.packageName == packageName) return null
        val app = nombreApp(sbn.packageName) ?: return null
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null     // el resumen "5 mensajes de 3 chats"
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null     // llamadas en curso, subidas, etc.
        val extras = n.extras
        val titulo = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val texto = ultimoMensaje(extras) ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        if (texto.isBlank()) return null
        val responder = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        return Mensaje(sbn.key, app, titulo, texto.take(500), sbn.postTime, responder)
    }

    /** En chats, Android guarda la lista de mensajes: tomamos el último con su autor ("Juan: hola"). */
    private fun ultimoMensaje(extras: Bundle): String? {
        @Suppress("DEPRECATION")
        val lista = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val ultimo = lista.lastOrNull() as? Bundle ?: return null
        val texto = ultimo.getCharSequence("text")?.toString() ?: return null
        val autor = ultimo.getCharSequence("sender")?.toString()
        val esGrupo = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return if (esGrupo && !autor.isNullOrBlank()) "$autor: $texto" else texto
    }

    /** Mensajes sin leer (los que siguen en la barra de notificaciones), agrupados por chat. */
    fun sinLeer(filtroApp: String? = null): String {
        val activos = try { activeNotifications } catch (e: Exception) { null } ?: return "No pude leer las notificaciones."
        val porChat = linkedMapOf<String, MutableList<String>>()
        for (sbn in activos.sortedBy { it.postTime }) {
            val app = nombreApp(sbn.packageName) ?: continue
            if (filtroApp != null && !normalizar(app).contains(normalizar(filtroApp))) continue
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val extras = n.extras
            val chat = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: continue
            @Suppress("DEPRECATION")
            val lista = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val lineas = lista?.mapNotNull { p: Parcelable ->
                val b = p as? Bundle ?: return@mapNotNull null
                val t = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val autor = b.getCharSequence("sender")?.toString()
                if (autor.isNullOrBlank()) t else "$autor: $t"
            } ?: listOfNotNull(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            porChat.getOrPut("$chat ($app)") { mutableListOf() }.addAll(lineas.map { it.take(300) })
        }
        if (porChat.isEmpty()) return "No hay mensajes sin leer."
        return porChat.entries.joinToString("\n\n") { (chat, lineas) ->
            "$chat — ${lineas.size} mensaje(s):\n" + lineas.takeLast(15).joinToString("\n") { "- $it" }
        }.take(8000)
    }

    /** Lista de los últimos mensajes, numerados, para poder responder uno. */
    fun ultimos(cantidad: Int = 10): String {
        val lista = synchronized(recientes) { recientes.toList() }
        if (lista.isEmpty()) return "Todavía no llegó ningún mensaje desde que Nova está encendida."
        return lista.takeLast(cantidad).reversed().mapIndexed { i, m ->
            "[$i] ${m.app} · ${m.quien}: ${m.texto}" + if (m.responder == null) " (no se puede responder desde la notificación)" else ""
        }.joinToString("\n")
    }

    /** Responde un mensaje desde su notificación. [indice] 0 = el más reciente; o buscá por nombre de chat. */
    fun responder(texto: String, indice: Int? = null, chat: String? = null): String {
        val lista = synchronized(recientes) { recientes.toList().reversed() }
        val m = when {
            !chat.isNullOrBlank() -> lista.firstOrNull { normalizar(it.quien).contains(normalizar(chat)) }
            indice != null -> lista.getOrNull(indice)
            else -> lista.firstOrNull()
        } ?: return "No encontré ese mensaje."
        val accion = m.responder ?: return "${m.app} no permite responder desde la notificación. Abrí el chat con la pantalla."
        val entradas = accion.remoteInputs ?: return "No se puede responder ese mensaje."
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val datos = Bundle().apply { entradas.forEach { putCharSequence(it.resultKey, texto) } }
        RemoteInput.addResultsToIntent(entradas, intent, datos)
        return try {
            accion.actionIntent.send(this, 0, intent)
            "Respondido a ${m.quien} por ${m.app}"
        } catch (e: PendingIntent.CanceledException) {
            "Ese mensaje ya no se puede responder (la notificación se cerró)."
        }
    }
}
