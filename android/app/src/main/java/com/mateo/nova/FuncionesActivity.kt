package com.mateo.nova

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.json.JSONArray

/**
 * «Qué puedo hacer»: todas las funciones de Nova, por categoría, con frases de ejemplo.
 * Tocar un ejemplo lo copia en el cuadro de texto de la pantalla principal (para que lo revises antes de mandarlo).
 * La lista sale de assets/funciones.json, la misma que usa la PC.
 */
class FuncionesActivity : AppCompatActivity() {

    companion object { const val FRASE = "frase" }

    data class Funcion(val nombre: String, val donde: String, val detalle: String, val ejemplos: List<String>)
    data class Categoria(val titulo: String, val resumen: String, val funciones: List<Funcion>)

    private lateinit var categorias: List<Categoria>
    private lateinit var lista: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_funciones)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.contenido)) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(top = barras.top, bottom = maxOf(barras.bottom, teclado.bottom))
            insets
        }
        findViewById<View>(R.id.atras).setOnClickListener { finish() }
        lista = findViewById(R.id.lista)
        categorias = cargar()
        findViewById<EditText>(R.id.buscar).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                dibujar(s?.toString().orEmpty())
                findViewById<ScrollView>(R.id.scroll).scrollTo(0, 0)
            }
        })
        dibujar("")
    }

    private fun cargar(): List<Categoria> = try {
        val arr = JSONArray(assets.open("funciones.json").bufferedReader().use { it.readText() })
        (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val fs = c.getJSONArray("funciones")
            Categoria(c.getString("titulo"), c.optString("resumen"), (0 until fs.length()).map { j ->
                val f = fs.getJSONObject(j)
                val ej = f.optJSONArray("ejemplos") ?: JSONArray()
                Funcion(f.getString("nombre"), f.optString("donde"), f.optString("detalle"),
                    (0 until ej.length()).map { ej.getString(it) })
            })
        }
    } catch (e: Exception) { emptyList() }

    private fun dibujar(consulta: String) {
        lista.removeAllViews()
        val q = normalizar(consulta)
        var mostradas = 0
        for (cat in categorias) {
            val fs = if (q.isBlank()) cat.funciones else cat.funciones.filter { f ->
                normalizar("${cat.titulo} ${f.nombre} ${f.detalle} ${f.ejemplos.joinToString(" ")}").contains(q)
            }
            if (fs.isEmpty()) continue
            mostradas += fs.size
            lista.addView(texto(cat.titulo, 15f, R.color.bruma, negrita = true).apply {
                layoutParams = margenes(top = 24, bottom = 2, start = 6)
            })
            if (cat.resumen.isNotBlank()) lista.addView(texto(cat.resumen, 13f, R.color.bruma).apply {
                layoutParams = margenes(bottom = 10, start = 6); alpha = 0.8f
            })
            val grupo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_grupo)
                setPadding(dp(18), dp(6), dp(18), dp(6))
            }
            fs.forEachIndexed { i, f ->
                if (i > 0) grupo.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.linea))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
                grupo.addView(funcion(f))
            }
            lista.addView(grupo)
        }
        if (mostradas == 0) lista.addView(texto(
            if (categorias.isEmpty()) "No pude cargar la lista." else "No encontré nada con «$consulta». Igual podés pedírselo: si se puede hacer con la pantalla, lo intenta.",
            15f, R.color.bruma).apply { layoutParams = margenes(top = 32, start = 6); gravity = Gravity.START })
    }

    private fun funcion(f: Funcion): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        val cabeza = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cabeza.addView(texto(f.nombre, 16f, R.color.texto, negrita = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (f.donde.isNotBlank()) cabeza.addView(texto(f.donde, 11f, R.color.bruma).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_pastilla)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }, margenes(start = 8))
        addView(cabeza)
        if (f.detalle.isNotBlank()) addView(texto(f.detalle, 14f, R.color.bruma).apply {
            setLineSpacing(0f, 1.2f); layoutParams = margenes(top = 4)
        })
        for (ej in f.ejemplos) addView(texto("«$ej»", 14f, R.color.estrella).apply {
            layoutParams = margenes(top = 6)
            setPadding(0, dp(4), 0, dp(4))
            background = ContextCompat.getDrawable(context, R.drawable.bg_tocable_texto)
            contentDescription = "Probar: $ej"
            setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(FRASE, ej.replace("{nombre}", "").trim()))
                finish()
            }
        })
    }

    private fun texto(t: String, sp: Float, color: Int, negrita: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp
        setTextColor(ContextCompat.getColor(context, color))
        if (negrita) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun margenes(top: Int = 0, bottom: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
