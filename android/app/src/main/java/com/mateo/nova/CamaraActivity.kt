package com.mateo.nova

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Puente entre la herramienta (que espera la foto) y la pantalla de cámara. */
object Camara {
    @Volatile private var latch: CountDownLatch? = null
    @Volatile private var foto: ByteArray? = null

    /** Abre la cámara, espera la foto (hasta 40 s) y la devuelve en JPEG. Bloqueante. */
    fun sacar(ctx: Context): ByteArray? {
        foto = null
        val l = CountDownLatch(1)
        latch = l
        val i = Intent(ctx, CamaraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (ControlAccesibilidad.instancia ?: ctx).startActivity(i)
        l.await(40, TimeUnit.SECONDS)
        return foto
    }

    fun entregar(jpeg: ByteArray?) {
        foto = jpeg
        latch?.countDown()
    }
}

/** Muestra lo que apunta la cámara y saca la foto sola a los 2 segundos (o al tocar). */
class CamaraActivity : AppCompatActivity() {

    private lateinit var vista: PreviewView
    private lateinit var aviso: TextView
    private var captura: ImageCapture? = null
    private var listo = false
    private var entregado = false

    private val pedirPermiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) iniciar() else terminar(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se puede usar con el celular bloqueado: muestra solo esta pantalla, no desbloquea nada
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }

        vista = PreviewView(this)
        aviso = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(32, 32, 32, 96)
            text = "Apuntá a lo que querés que mire"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(vista, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(aviso, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { sacarFoto() }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) iniciar()
        else pedirPermiso.launch(Manifest.permission.CAMERA)
    }

    private fun iniciar() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            val proveedor = futuro.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(vista.surfaceProvider) }
            val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            captura = cap
            try {
                proveedor.unbindAll()
                proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
            } catch (e: Exception) {
                terminar(null); return@addListener
            }
            listo = true
            cuentaRegresiva(2)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cuentaRegresiva(n: Int) {
        if (isFinishing) return
        if (n <= 0) { sacarFoto(); return }
        aviso.text = "Saco la foto en $n… (o tocá la pantalla)"
        aviso.postDelayed({ cuentaRegresiva(n - 1) }, 1000)
    }

    private fun sacarFoto() {
        val cap = captura ?: return
        if (!listo) return
        listo = false
        aviso.text = "Mirando…"
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imagen: ImageProxy) {
                val giro = imagen.imageInfo.rotationDegrees
                var bmp: Bitmap = imagen.toBitmap()
                imagen.close()
                if (giro != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(giro.toFloat()) }, true)
                terminar(Vision.aJpeg(bmp))
            }
            override fun onError(e: ImageCaptureException) { terminar(null) }
        })
    }

    private fun terminar(jpeg: ByteArray?) {
        if (!entregado) { entregado = true; Camara.entregar(jpeg) }
        finish()
    }

    override fun onDestroy() {
        // Si la cerrás sin sacar foto, Nova no se queda esperando
        if (!isChangingConfigurations && !entregado) { entregado = true; Camara.entregar(null) }
        super.onDestroy()
    }
}
