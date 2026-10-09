package com.mateo.nova

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Las "manos" de Nova. Con este servicio puede ver lo que hay en pantalla en cualquier app
 * y tocar, escribir, deslizar o apretar los botones del sistema.
 */
class ControlAccesibilidad : AccessibilityService() {

    companion object {
        @Volatile var instancia: ControlAccesibilidad? = null
        val activo get() = instancia != null
    }

    /** Elementos de la última lectura de pantalla, para poder tocarlos por número. */
    private var elementos: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instancia = this
        // El botón de accesibilidad (o el gesto de accesibilidad) sirve como "hablarle ahora".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        startService(Intent(this@ControlAccesibilidad, AsistenteService::class.java)
                            .setAction(AsistenteService.ACCION_ESCUCHAR))
                    }
                })
        }
    }

    override fun onDestroy() {
        instancia = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ------------------------------------------------------------ VER
    fun appActual(): String = rootInActiveWindow?.packageName?.toString() ?: "desconocida"

    /** Devuelve una descripción compacta de la pantalla con elementos numerados. */
    fun leerPantalla(): String {
        val raiz = rootInActiveWindow ?: return "No puedo leer la pantalla ahora (¿bloqueada?)."
        val lista = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder("App: ${raiz.packageName}\n")
        recorrer(raiz, lista, 0)
        elementos = lista
        lista.forEachIndexed { i, n ->
            val texto = (n.text ?: n.contentDescription ?: n.hintText ?: "").toString()
                .replace("\n", " ").take(80)
            val id = n.viewIdResourceName?.substringAfterLast("/") ?: ""
            val tipo = n.className?.toString()?.substringAfterLast(".") ?: ""
            val flags = buildList {
                if (n.isClickable) add("tocable")
                if (n.isEditable) add("editable")
                if (n.isScrollable) add("desplazable")
                if (n.isCheckable) add(if (n.isChecked) "activado" else "desactivado")
                if (n.isFocused) add("con foco")
            }.joinToString(",")
            sb.append("[$i] $tipo \"$texto\"")
            if (id.isNotEmpty()) sb.append(" id=$id")
            if (flags.isNotEmpty()) sb.append(" ($flags)")
            sb.append('\n')
        }
        if (lista.isEmpty()) sb.append("(pantalla sin elementos legibles)")
        return sb.toString().take(9000)
    }

    private fun recorrer(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, prof: Int) {
        if (n == null || prof > 40 || out.size >= 150) return
        if (!n.isVisibleToUser) return
        val tieneTexto = !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
        if (tieneTexto || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable) out.add(n)
        for (i in 0 until n.childCount) recorrer(n.getChild(i), out, prof + 1)
    }

    // ------------------------------------------------------------ ACTUAR
    fun tocar(indice: Int, largo: Boolean = false): String {
        val n = elementos.getOrNull(indice) ?: return "No existe el elemento $indice. Volvé a leer la pantalla."
        val accion = if (largo) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var actual: AccessibilityNodeInfo? = n
        while (actual != null) {
            if ((if (largo) actual.isLongClickable else actual.isClickable) && actual.performAction(accion))
                return "Tocado [$indice]"
            actual = actual.parent
        }
        val r = Rect().also { n.getBoundsInScreen(it) }
        return if (tocarEn(r.exactCenterX(), r.exactCenterY(), if (largo) 700 else 60))
            "Tocado [$indice] por coordenadas" else "No pude tocar [$indice]"
    }

    fun tocarTexto(texto: String): String {
        val raiz = rootInActiveWindow ?: return "No puedo leer la pantalla"
        val q = normalizar(texto)
        val candidatos = raiz.findAccessibilityNodeInfosByText(texto).ifEmpty {
            val lista = mutableListOf<AccessibilityNodeInfo>()
            recorrer(raiz, lista, 0)
            lista.filter { normalizar("${it.text ?: ""} ${it.contentDescription ?: ""}").contains(q) }
        }
        val n = candidatos.firstOrNull { it.isVisibleToUser } ?: return "No encontré \"$texto\" en pantalla"
        elementos = listOf(n)
        return tocar(0).replace("[0]", "\"$texto\"")
    }

    fun escribir(texto: String, indice: Int? = null): String {
        val destino = indice?.let { elementos.getOrNull(it) }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: elementos.firstOrNull { it.isEditable }
            ?: return "No hay ningún campo de texto para escribir. Tocá primero el campo."
        destino.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
        }
        return if (destino.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Escrito" else "No pude escribir"
    }

    fun deslizar(direccion: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat(); val h = m.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direccion) {
            "arriba" -> listOf(w / 2, h * 0.75f, w / 2, h * 0.25f)     // contenido sube = ver más abajo
            "abajo" -> listOf(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "izquierda" -> listOf(w * 0.85f, h / 2, w * 0.15f, h / 2)
            "derecha" -> listOf(w * 0.15f, h / 2, w * 0.85f, h / 2)
            else -> return "Dirección inválida"
        }
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return if (gesto(GestureDescription.StrokeDescription(p, 0, 350))) "Deslizado hacia $direccion" else "No pude deslizar"
    }

    fun tocarEn(x: Float, y: Float, duracion: Long = 60): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesto(GestureDescription.StrokeDescription(p, 0, duracion))
    }

    private fun gesto(trazo: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        dispatchGesture(GestureDescription.Builder().addStroke(trazo).build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun botonSistema(accion: String): String {
        val a = when (accion) {
            "atras" -> GLOBAL_ACTION_BACK
            "inicio" -> GLOBAL_ACTION_HOME
            "recientes" -> GLOBAL_ACTION_RECENTS
            "notificaciones" -> GLOBAL_ACTION_NOTIFICATIONS
            "ajustes_rapidos" -> GLOBAL_ACTION_QUICK_SETTINGS
            "apagado" -> GLOBAL_ACTION_POWER_DIALOG
            "bloquear" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "Necesita Android 9"
            "captura" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return "Necesita Android 9"
            else -> return "Acción desconocida"
        }
        return if (performGlobalAction(a)) "Hecho: $accion" else "No pude: $accion"
    }
}
