package com.mateo.nova

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * La estrella de Nova. Cambia con lo que está haciendo el asistente:
 *  - ESPERA:    respira lento, dorada y tranquila
 *  - ESCUCHA:   se agranda con tu voz y lanza ondas
 *  - PENSANDO:  la rodea una nebulosa violeta que gira
 *  - HABLANDO:  late y suelta chispas
 *  - APAGADO:   apagada, casi sin brillo
 */
class Estrella @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val oro = Color.parseColor("#FFD27A")
    private val oroClaro = Color.parseColor("#FFF4D6")
    private val violeta = Color.parseColor("#8F7DFF")
    private val ceniza = Color.parseColor("#5A5590")
    private val argb = ArgbEvaluator()

    var estado: Bus.Estado = Bus.Estado.APAGADO
        set(v) { if (field != v) { field = v; desde = colorActual; tCambio = ahora() } }

    /** 0..1, volumen de tu voz mientras escucha. */
    var nivel = 0f
        set(v) { field = v.coerceIn(0f, 1f) }
    private var nivelSuave = 0f

    private var colorActual = ceniza
    private var desde = ceniza
    private var tCambio = 0L
    private val inicio = System.nanoTime()
    private fun ahora() = (System.nanoTime() - inicio) / 1_000_000L

    private val animar = ValueAnimator.areAnimatorsEnabled()   // respeta "quitar animaciones" del sistema

    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pNucleo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pOnda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pArco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pChispa = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // necesario para el desenfoque del halo
        pHalo.maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
        contentDescription = "Tocá para hablar"
    }

    private fun destino() = when (estado) {
        Bus.Estado.APAGADO -> ceniza
        Bus.Estado.PREPARANDO -> argb.evaluate(0.5f, ceniza, oro) as Int
        Bus.Estado.PENSANDO -> argb.evaluate(0.55f, oro, violeta) as Int
        else -> oro
    }

    override fun onDraw(c: Canvas) {
        val t = if (animar) ahora() / 1000f else 0f
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.36f     // radio del núcleo

        // Transición suave de color entre estados (400 ms)
        val avance = ((ahora() - tCambio) / 400f).coerceIn(0f, 1f)
        colorActual = argb.evaluate(avance, desde, destino()) as Int
        nivelSuave += (nivel - nivelSuave) * 0.25f

        val escala = when (estado) {
            Bus.Estado.ESPERA -> 1f + 0.035f * sin(t * 1.6f)                // respiración
            Bus.Estado.ESCUCHA -> 1f + 0.06f + nivelSuave * 0.22f           // crece con tu voz
            Bus.Estado.HABLANDO -> 1f + 0.05f * sin(t * 9f) * sin(t * 3.1f) + 0.03f
            Bus.Estado.PENSANDO -> 0.94f + 0.02f * sin(t * 4f)
            Bus.Estado.PREPARANDO -> 0.92f + 0.03f * sin(t * 2.2f)
            Bus.Estado.APAGADO -> 0.88f
        }
        val r = base * escala
        val apagado = estado == Bus.Estado.APAGADO

        // 1) Halo difuso
        pHalo.color = colorActual
        pHalo.alpha = if (apagado) 40 else (110 + 50 * sin(t * 1.3f)).toInt()
        c.drawCircle(cx, cy, r * 1.55f, pHalo)

        // 2) Ondas al escuchar (salen del centro y se desvanecen)
        if (estado == Bus.Estado.ESCUCHA) {
            for (i in 0 until 3) {
                val fase = ((t * 0.7f + i / 3f) % 1f)
                pOnda.color = oro
                pOnda.alpha = ((1f - fase) * 150).toInt()
                pOnda.strokeWidth = 3f + 4f * (1f - fase)
                c.drawCircle(cx, cy, r * (1.05f + fase * 0.9f), pOnda)
            }
        }

        // 3) Nebulosa girando al pensar
        if (estado == Bus.Estado.PENSANDO) {
            for (i in 0 until 2) {
                val rr = r * (1.28f + i * 0.2f)
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                pArco.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, violeta, oroClaro, Color.TRANSPARENT), null)
                pArco.strokeWidth = if (i == 0) 6f else 3f
                c.save()
                c.rotate((t * (if (i == 0) 140f else -95f)) % 360f, cx, cy)
                c.drawArc(rect, 0f, 250f, false, pArco)
                c.restore()
            }
        }

        // 4) Destello de cuatro puntas (como el logo): anchas en la base, afiladas en la punta
        if (!apagado) {
            val pulso = 1f + 0.08f * sin(t * 2f) + (if (estado == Bus.Estado.ESCUCHA) nivelSuave * 0.35f else 0f)
            val giro = 0.05f * sin(t * 0.4f)
            for (k in 0 until 4) rayo(c, cx, cy, (k * PI / 2 - PI / 2).toFloat() + giro, r * 2.25f * pulso, r * 0.2f, 0.95f)
            for (k in 0 until 4) rayo(c, cx, cy, (k * PI / 2 - PI / 4).toFloat() + giro, r * 1.45f * pulso, r * 0.09f, 0.55f)
        }

        // 5) Núcleo: centro casi blanco que se funde en el color del estado
        pNucleo.shader = RadialGradient(cx - r * 0.18f, cy - r * 0.22f, r * 1.15f,
            intArrayOf(if (apagado) ceniza else Color.WHITE, if (apagado) ceniza else oroClaro, colorActual,
                argb.evaluate(0.6f, colorActual, Color.parseColor("#121033")) as Int),
            floatArrayOf(0f, 0.25f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, pNucleo)

        // 6) Chispas al hablar
        if (estado == Bus.Estado.HABLANDO) {
            val rnd = Random(7)
            for (i in 0 until 7) {
                val vel = 0.25f + rnd.nextFloat() * 0.35f
                val fase = ((t * vel + rnd.nextFloat()) % 1f)
                val ang = rnd.nextFloat() * 2f * PI.toFloat()
                val d = r * (1.1f + fase * 1.1f)
                pChispa.color = oroClaro
                pChispa.alpha = ((1f - fase) * 230).toInt()
                c.drawCircle(cx + cos(ang) * d, cy + sin(ang) * d, 3f + 3f * (1f - fase), pChispa)
            }
        }

        if (animar && isShown) postInvalidateOnAnimation()
    }

    private val pRayoForma = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camino = android.graphics.Path()

    private fun rayo(c: Canvas, cx: Float, cy: Float, a: Float, largo: Float, ancho: Float, alfa: Float) {
        val px = cos(a + PI.toFloat() / 2) * ancho
        val py = sin(a + PI.toFloat() / 2) * ancho
        val tx = cx + cos(a) * largo
        val ty = cy + sin(a) * largo
        fun conAlfa(color: Int, f: Float) = Color.argb((f * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
        pRayoForma.shader = android.graphics.LinearGradient(cx, cy, tx, ty,
            intArrayOf(conAlfa(oroClaro, alfa), conAlfa(colorActual, alfa * 0.55f), conAlfa(colorActual, 0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        camino.reset()
        camino.moveTo(cx + px, cy + py); camino.lineTo(tx, ty); camino.lineTo(cx - px, cy - py); camino.close()
        c.drawPath(camino, pRayoForma)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val ancho = MeasureSpec.getSize(w)
        val alto = MeasureSpec.getSize(h)
        val lado = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) ancho else min(ancho, max(alto, 1))
        setMeasuredDimension(ancho, lado)
    }
}

/** Fondo de cielo nocturno con estrellas fijas muy tenues. Se dibuja una sola vez. */
class Cielo @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val fondo = Paint()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // Un poco más claro arriba, como el cielo después del atardecer
        fondo.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#1B1850"), Color.parseColor("#121033"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fondo)
        val rnd = Random(2026)
        val cantidad = (width * height / 9000).coerceAtMost(220)
        repeat(cantidad) {
            p.alpha = 25 + rnd.nextInt(80)
            val radio = if (rnd.nextInt(12) == 0) 2.2f else 1.1f
            c.drawCircle(rnd.nextFloat() * width, rnd.nextFloat() * height, radio, p)
        }
    }
}
