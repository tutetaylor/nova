package com.mateo.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * La libreta de Nova: todo lo que guarda de vos, en un archivo dentro del celular.
 *  - memoria: cosas que le contaste ("acordate que...")
 *  - listas:  compras, pendientes, pelis…
 *  - gastos:  lo que vas gastando
 *  - avisos:  recordatorios programados (una vez o repetidos)
 * Nada sale del celular salvo lo que Nova le manda a Gemini para responderte.
 */
class Libreta private constructor(ctx: Context) {

    companion object {
        @Volatile private var unica: Libreta? = null
        fun de(ctx: Context) = unica ?: synchronized(this) { unica ?: Libreta(ctx.applicationContext).also { unica = it } }
        private val fechaCorta = SimpleDateFormat("dd/MM/yyyy", Locale("es", "AR"))
        private val fechaHora = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    private val archivo = File(ctx.filesDir, "libreta.json")
    private val datos: JSONObject = try { JSONObject(archivo.readText()) } catch (e: Exception) { JSONObject() }

    private fun arr(nombre: String): JSONArray = datos.optJSONArray(nombre) ?: JSONArray().also { datos.put(nombre, it) }
    private fun obj(nombre: String): JSONObject = datos.optJSONObject(nombre) ?: JSONObject().also { datos.put(nombre, it) }
    private fun guardar() { archivo.writeText(datos.toString()) }
    private fun hoy() = fechaCorta.format(Date())

    // ---------------------------------------------------------------- MEMORIA
    @Synchronized fun recordar(dato: String): String {
        val m = arr("memoria")
        for (i in 0 until m.length()) if (normalizar(m.getJSONObject(i).getString("dato")) == normalizar(dato)) return "Eso ya lo sabía."
        m.put(JSONObject().put("dato", dato.trim()).put("fecha", hoy()))
        guardar()
        return "Anotado en mi memoria."
    }

    @Synchronized fun olvidar(algo: String): String {
        val m = arr("memoria"); val q = normalizar(algo); var borrados = 0
        for (i in m.length() - 1 downTo 0) if (normalizar(m.getJSONObject(i).getString("dato")).contains(q)) { m.remove(i); borrados++ }
        guardar()
        return if (borrados == 0) "No tenía nada sobre eso." else "Listo, me olvidé $borrados cosa(s)."
    }

    /** Lo que sabe de vos, para que el cerebro lo tenga siempre presente. */
    @Synchronized fun memoriaParaCerebro(): String {
        val m = arr("memoria")
        if (m.length() == 0) return ""
        return (maxOf(0, m.length() - 60) until m.length()).joinToString("\n") { "- " + m.getJSONObject(it).getString("dato") }
    }

    @Synchronized fun verMemoria(): String = memoriaParaCerebro().ifBlank { "Todavía no me contaste nada para recordar." }

    // ---------------------------------------------------------------- LISTAS
    @Synchronized fun listaAgregar(lista: String, items: List<String>): String {
        val l = obj("listas"); val nombre = normalizar(lista)
        val a = l.optJSONArray(nombre) ?: JSONArray().also { l.put(nombre, it) }
        val existentes = (0 until a.length()).map { normalizar(a.getString(it)) }
        val nuevos = items.map { it.trim() }.filter { it.isNotEmpty() && normalizar(it) !in existentes }
        nuevos.forEach { a.put(it) }
        guardar()
        return if (nuevos.isEmpty()) "Ya estaba en la lista." else "Agregué ${nuevos.joinToString(", ")} a $lista."
    }

    @Synchronized fun listaQuitar(lista: String, item: String): String {
        val a = obj("listas").optJSONArray(normalizar(lista)) ?: return "No tengo una lista $lista."
        val q = normalizar(item)
        for (i in a.length() - 1 downTo 0) if (normalizar(a.getString(i)).contains(q)) { val t = a.getString(i); a.remove(i); guardar(); return "Saqué $t." }
        return "No encontré $item en $lista."
    }

    @Synchronized fun listaVer(lista: String?): String {
        val l = obj("listas")
        if (lista.isNullOrBlank()) {
            val nombres = l.keys().asSequence().toList()
            if (nombres.isEmpty()) return "No tenés listas."
            return nombres.joinToString("\n") { n -> val a = l.getJSONArray(n); "$n (${a.length()}): " + (0 until a.length()).joinToString(", ") { a.getString(it) } }
        }
        val a = l.optJSONArray(normalizar(lista)) ?: return "La lista $lista está vacía."
        if (a.length() == 0) return "La lista $lista está vacía."
        return "$lista: " + (0 until a.length()).joinToString(", ") { a.getString(it) }
    }

    @Synchronized fun listaBorrar(lista: String): String {
        obj("listas").remove(normalizar(lista)); guardar(); return "Borré la lista $lista."
    }

    // ---------------------------------------------------------------- GASTOS
    @Synchronized fun gastoAnotar(monto: Double, descripcion: String, categoria: String?): String {
        val cat = categoria?.trim()?.lowercase()?.ifBlank { null } ?: "otros"
        arr("gastos").put(JSONObject().put("monto", monto).put("desc", descripcion.trim()).put("cat", cat)
            .put("t", System.currentTimeMillis()))
        guardar()
        return "Anotado: $${"%,.0f".format(Locale("es", "AR"), monto)} en $cat. Este mes llevás $${"%,.0f".format(Locale("es", "AR"), totalDesde(inicioMes()))}."
    }

    private fun inicioMes() = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
    }.timeInMillis

    private fun totalDesde(desde: Long, categoria: String? = null): Double {
        val g = arr("gastos"); var total = 0.0
        for (i in 0 until g.length()) {
            val x = g.getJSONObject(i)
            if (x.getLong("t") >= desde && (categoria == null || x.getString("cat").contains(categoria))) total += x.getDouble("monto")
        }
        return total
    }

    @Synchronized fun gastosResumen(periodo: String, categoria: String?): String {
        val ahora = Calendar.getInstance()
        val desde = when (normalizar(periodo)) {
            "hoy" -> ahora.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            "semana" -> System.currentTimeMillis() - 7L * 86_400_000
            "año", "ano" -> ahora.apply { set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
            else -> inicioMes()
        }
        val cat = categoria?.lowercase()?.ifBlank { null }
        val g = arr("gastos")
        val porCat = sortedMapOf<String, Double>()
        val ultimos = mutableListOf<String>()
        for (i in 0 until g.length()) {
            val x = g.getJSONObject(i)
            if (x.getLong("t") < desde || (cat != null && !x.getString("cat").contains(cat))) continue
            porCat[x.getString("cat")] = (porCat[x.getString("cat")] ?: 0.0) + x.getDouble("monto")
            ultimos.add("${fechaCorta.format(Date(x.getLong("t")))} ${x.getString("desc")}: $${"%.0f".format(x.getDouble("monto"))}")
        }
        if (porCat.isEmpty()) return "No hay gastos anotados en ese período."
        return "Total: $${"%.0f".format(porCat.values.sum())}\nPor categoría: " +
            porCat.entries.joinToString(", ") { "${it.key} $${"%.0f".format(it.value)}" } +
            "\nÚltimos: " + ultimos.takeLast(8).joinToString("; ")
    }

    @Synchronized fun gastoBorrarUltimo(): String {
        val g = arr("gastos"); if (g.length() == 0) return "No hay gastos."
        val x = g.getJSONObject(g.length() - 1); g.remove(g.length() - 1); guardar()
        return "Borré el último gasto: ${x.getString("desc")} $${"%.0f".format(x.getDouble("monto"))}."
    }

    // ---------------------------------------------------------------- AUTOMATIZACIONES
    /** disparador: voz | auriculares | bluetooth | cargador | desconectar_cargador | bateria_baja | llegar | salir | mensaje */
    data class Regla(val id: Int, val nombre: String, val disparador: String, val valor: String, val hacer: String)

    @Synchronized fun reglas(): List<Regla> {
        val a = arr("reglas")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Regla(it.getInt("id"), it.getString("nombre"), it.getString("disparador"), it.optString("valor"), it.getString("hacer"))
        }
    }

    @Synchronized fun reglaNueva(nombre: String, disparador: String, valor: String, hacer: String): Regla {
        val id = (reglas().maxOfOrNull { it.id } ?: 0) + 1
        arr("reglas").put(JSONObject().put("id", id).put("nombre", nombre).put("disparador", disparador)
            .put("valor", valor).put("hacer", hacer))
        guardar()
        return Regla(id, nombre, disparador, valor, hacer)
    }

    @Synchronized fun reglaBorrar(id: Int): Boolean {
        val a = arr("reglas")
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).getInt("id") == id) { a.remove(i); guardar(); return true }
        return false
    }

    fun describir(r: Regla): String {
        val cuando = when (r.disparador) {
            "voz" -> "cuando digas «${r.valor}»"
            "auriculares" -> "al conectar auriculares"
            "bluetooth" -> "al conectar el Bluetooth «${r.valor}»"
            "cargador" -> "al enchufar el cargador"
            "desconectar_cargador" -> "al desenchufar el cargador"
            "bateria_baja" -> "cuando la batería baje de ${r.valor}%"
            "llegar" -> "al llegar a ${r.valor}"
            "salir" -> "al salir de ${r.valor}"
            "mensaje" -> "cuando te escriba ${r.valor}"
            else -> r.disparador
        }
        return "[${r.id}] ${r.nombre}: $cuando → ${r.hacer}"
    }

    // ---------------------------------------------------------------- LUGARES
    data class Lugar(val nombre: String, val lat: Double, val lng: Double, val radio: Float)

    @Synchronized fun lugares(): List<Lugar> {
        val a = arr("lugares")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Lugar(it.getString("nombre"), it.getDouble("lat"), it.getDouble("lng"), it.optDouble("radio", 150.0).toFloat()) }
    }

    @Synchronized fun lugarGuardar(nombre: String, lat: Double, lng: Double, radio: Float = 150f) {
        val a = arr("lugares"); val n = normalizar(nombre)
        for (i in a.length() - 1 downTo 0) if (normalizar(a.getJSONObject(i).getString("nombre")) == n) a.remove(i)
        a.put(JSONObject().put("nombre", nombre.trim()).put("lat", lat).put("lng", lng).put("radio", radio.toDouble()))
        guardar()
    }

    // ---------------------------------------------------------------- ESTUDIO
    @Synchronized fun estudioRegistrar(minutos: Int, tema: String) {
        arr("estudio").put(JSONObject().put("min", minutos).put("tema", tema).put("t", System.currentTimeMillis()))
        guardar()
    }

    @Synchronized fun estudioResumen(periodo: String): String {
        val desde = when (normalizar(periodo)) {
            "hoy" -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            "mes" -> inicioMes()
            else -> System.currentTimeMillis() - 7L * 86_400_000
        }
        val e = arr("estudio"); val porTema = sortedMapOf<String, Int>()
        for (i in 0 until e.length()) {
            val x = e.getJSONObject(i)
            if (x.getLong("t") >= desde) porTema[x.optString("tema", "general")] = (porTema[x.optString("tema", "general")] ?: 0) + x.getInt("min")
        }
        if (porTema.isEmpty()) return "No hay tiempo de estudio registrado en ese período."
        val total = porTema.values.sum()
        return "Estudiaste ${total / 60} h ${total % 60} min. " + porTema.entries.joinToString(", ") { "${it.key}: ${it.value} min" }
    }

    // ---------------------------------------------------------------- PRECIOS Y ENVÍOS
    data class Producto(val nombre: String, val url: String, val precio: Double)

    @Synchronized fun productos(): List<Producto> {
        val a = arr("productos")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Producto(it.getString("nombre"), it.getString("url"), it.optDouble("precio", 0.0)) }
    }

    @Synchronized fun productoGuardar(nombre: String, url: String, precio: Double) {
        val a = arr("productos")
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).getString("url") == url) a.remove(i)
        a.put(JSONObject().put("nombre", nombre).put("url", url).put("precio", precio))
        guardar()
    }

    @Synchronized fun productoBorrar(nombre: String): Boolean {
        val a = arr("productos"); val q = normalizar(nombre)
        for (i in a.length() - 1 downTo 0) if (normalizar(a.getJSONObject(i).getString("nombre")).contains(q)) { a.remove(i); guardar(); return true }
        return false
    }

    @Synchronized fun envioGuardar(nombre: String, codigo: String, empresa: String) {
        arr("envios").put(JSONObject().put("nombre", nombre).put("codigo", codigo).put("empresa", empresa).put("t", System.currentTimeMillis()))
        guardar()
    }

    @Synchronized fun envios(): String {
        val a = arr("envios")
        if (a.length() == 0) return "No tenés envíos guardados."
        return (0 until a.length()).map { a.getJSONObject(it) }.joinToString("\n") {
            "${it.getString("nombre")} (${it.getString("empresa")}): código ${it.getString("codigo")}"
        }
    }

    @Synchronized fun envioBorrar(nombre: String): Boolean {
        val a = arr("envios"); val q = normalizar(nombre)
        for (i in a.length() - 1 downTo 0) if (normalizar(a.getJSONObject(i).getString("nombre")).contains(q)) { a.remove(i); guardar(); return true }
        return false
    }

    // ---------------------------------------------------------------- AVISOS
    data class Aviso(val id: Int, val texto: String, val cuando: Long, val repetir: String)

    @Synchronized fun avisos(): List<Aviso> {
        val a = arr("avisos")
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Aviso(it.getInt("id"), it.getString("texto"), it.getLong("cuando"), it.optString("repetir", "no")) }
    }

    @Synchronized fun avisoNuevo(texto: String, cuando: Long, repetir: String): Aviso {
        val id = (avisos().maxOfOrNull { it.id } ?: 0) + 1
        val av = Aviso(id, texto, cuando, repetir)
        arr("avisos").put(JSONObject().put("id", id).put("texto", texto).put("cuando", cuando).put("repetir", repetir))
        guardar()
        return av
    }

    @Synchronized fun avisoActualizar(id: Int, cuando: Long?) {
        val a = arr("avisos")
        for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).getInt("id") == id) {
            if (cuando == null) a.remove(i) else a.getJSONObject(i).put("cuando", cuando)
        }
        guardar()
    }

    fun describir(av: Aviso): String {
        val rep = when (av.repetir) { "diario" -> ", todos los días"; "semanal" -> ", todas las semanas"; "laborales" -> ", de lunes a viernes"; else -> "" }
        val texto = if (av.texto.startsWith("@")) "rutina de buen día" else av.texto
        return "[${av.id}] ${fechaHora.format(Date(av.cuando))}$rep: $texto"
    }

    // ---------------------------------------------------------------- DOCUMENTOS, PLANTILLAS Y ENVÍOS MASIVOS
    data class Documento(val nombre: String, val uri: String, val archivo: String)

    @Synchronized fun documentoGuardar(nombre: String, uri: String, archivo: String) {
        val d = arr("documentos")
        for (i in d.length() - 1 downTo 0) if (normalizar(d.getJSONObject(i).getString("nombre")) == normalizar(nombre)) d.remove(i)
        d.put(JSONObject().put("nombre", nombre.trim()).put("uri", uri).put("archivo", archivo))
        guardar()
    }

    @Synchronized fun documentos(): List<Documento> {
        val d = arr("documentos")
        return (0 until d.length()).map { d.getJSONObject(it) }
            .map { Documento(it.getString("nombre"), it.getString("uri"), it.optString("archivo", it.getString("nombre"))) }
    }

    /** Busca un documento guardado por nombre aproximado ("mi CV de chef" encuentra "CV Mateo Taylor CHEF"). */
    @Synchronized fun documentoBuscar(q: String): Documento? {
        val lista = documentos()
        if (lista.isEmpty()) return null
        if (q.isBlank()) return lista.singleOrNull()
        val buscado = normalizar(q)
        lista.firstOrNull { normalizar(it.nombre) == buscado || normalizar(it.archivo) == buscado }?.let { return it }
        val palabras = buscado.split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in setOf("mi", "el", "la", "de", "documento", "archivo") }
        return lista.map { d -> d to palabras.count { normalizar("${d.nombre} ${d.archivo}").contains(it) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    @Synchronized fun documentoBorrar(q: String): Boolean {
        val doc = documentoBuscar(q) ?: return false
        val d = arr("documentos")
        for (i in d.length() - 1 downTo 0) if (d.getJSONObject(i).getString("uri") == doc.uri) d.remove(i)
        guardar(); return true
    }

    @Synchronized fun plantillaGuardar(nombre: String, texto: String) {
        obj("plantillas").put(normalizar(nombre).ifBlank { "predeterminado" }, texto.trim()); guardar()
    }

    @Synchronized fun plantillas(): Map<String, String> {
        val p = obj("plantillas")
        return p.keys().asSequence().associateWith { p.getString(it) }
    }

    /** Si [q] es el nombre de una plantilla devuelve su texto; si está vacío, la predeterminada (o la única). */
    @Synchronized fun plantillaTexto(q: String): String? {
        val todas = plantillas()
        if (q.isBlank()) return todas["predeterminado"] ?: todas["predeterminada"] ?: todas.values.singleOrNull()
        val n = normalizar(q)
        return todas[n] ?: todas.entries.firstOrNull { n.contains(it.key) || it.key.contains(n) }?.value
            ?: if (n.contains("predetermin")) todas["predeterminado"] ?: todas.values.singleOrNull() else null
    }

    /** Cuándo se mandó [clave] (un documento o mensaje) a [numero], o null si nunca. */
    @Synchronized fun enviadoA(clave: String, numero: String): Long? =
        obj("enviados").optJSONObject(normalizar(clave))?.optLong(numero, 0L)?.takeIf { it > 0 }

    @Synchronized fun marcarEnviado(clave: String, numero: String) {
        val e = obj("enviados")
        val c = e.optJSONObject(normalizar(clave)) ?: JSONObject().also { e.put(normalizar(clave), it) }
        c.put(numero, System.currentTimeMillis()); guardar()
    }

    @Synchronized fun cuantosEnviados(clave: String): Int = obj("enviados").optJSONObject(normalizar(clave))?.length() ?: 0
}
