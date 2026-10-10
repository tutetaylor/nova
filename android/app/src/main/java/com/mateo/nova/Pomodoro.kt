package com.mateo.nova

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Pomodoro: bloques de foco con descansos. Durante el foco activa "No molestar" (si tiene permiso),
 * avisa por voz cada cambio y anota el tiempo estudiado en la libreta.
 */
object Pomodoro {
    private val main = Handler(Looper.getMainLooper())
    /** El servicio de Nova lo conecta a su voz. */
    var alHablar: ((String) -> Unit)? = null

    private var tema = ""
    private var foco = 25
    private var descanso = 5
    private var ciclos = 4
    private var ciclo = 0
    private var enFoco = false
    private var finActual = 0L
    private var ctxApp: Context? = null
    val activo get() = ciclo > 0

    private val siguiente = Runnable { avanzar() }

    fun iniciar(ctx: Context, minutosFoco: Int, minutosDescanso: Int, cantidad: Int, sobre: String): String {
        parar(ctx, anotar = false)
        ctxApp = ctx.applicationContext
        foco = minutosFoco.coerceIn(5, 120); descanso = minutosDescanso.coerceIn(1, 30)
        ciclos = cantidad.coerceIn(1, 12); tema = sobre.ifBlank { "general" }
        ciclo = 1
        empezarFoco()
        return "Arrancamos: $ciclos bloques de $foco minutos con $descanso de descanso. Activé No molestar si tenía permiso."
    }

    private fun empezarFoco() {
        enFoco = true
        noMolestar(true)
        programar(foco)
    }

    private fun programar(minutos: Int) {
        finActual = System.currentTimeMillis() + minutos * 60_000L
        main.removeCallbacks(siguiente)
        main.postDelayed(siguiente, minutos * 60_000L)
    }

    private fun avanzar() {
        val ctx = ctxApp ?: return
        if (enFoco) {
            Libreta.de(ctx).estudioRegistrar(foco, tema)
            if (ciclo >= ciclos) {
                noMolestar(false)
                alHablar?.invoke("¡Terminaste los $ciclos bloques! Son ${ciclos * foco} minutos de $tema. Muy bien.")
                ciclo = 0
                return
            }
            enFoco = false
            noMolestar(false)
            val largo = if (ciclo % 4 == 0) descanso * 3 else descanso
            alHablar?.invoke("¡Descanso de $largo minutos! Levantate, tomá agua y estirá un poco.")
            programar(largo)
        } else {
            ciclo++
            alHablar?.invoke("Volvemos al foco. Bloque $ciclo de $ciclos.")
            empezarFoco()
        }
    }

    fun estado(): String {
        if (!activo) return "No hay un pomodoro en curso."
        val faltan = ((finActual - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        return if (enFoco) "Bloque $ciclo de $ciclos, faltan $faltan minutos de foco." else "Estás en descanso, faltan $faltan minutos."
    }

    fun parar(ctx: Context, anotar: Boolean = true): String {
        if (!activo) return "No había un pomodoro en curso."
        main.removeCallbacks(siguiente)
        if (anotar && enFoco) {
            val hechos = foco - ((finActual - System.currentTimeMillis()) / 60_000).toInt()
            if (hechos > 0) Libreta.de(ctx).estudioRegistrar(hechos, tema)
        }
        noMolestar(false)
        ciclo = 0
        return "Pomodoro terminado."
    }

    private fun noMolestar(activar: Boolean) {
        val nm = ctxApp?.getSystemService(NotificationManager::class.java) ?: return
        if (nm.isNotificationPolicyAccessGranted)
            nm.setInterruptionFilter(if (activar) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
    }
}
