package com.mateo.nova

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

/**
 * Envíos masivos por WhatsApp: "escaneá los números de las últimas 10 capturas y mandales mi CV con el mensaje
 * predeterminado".
 *
 * 1. [preparar] lee las capturas con visión, arma la lista de números y la deja lista (NO manda nada).
 * 2. El cerebro le muestra la lista al usuario y le pregunta. Con su sí, [enviar] manda de a uno, con pausas,
 *    tocando Enviar en WhatsApp. Se puede cortar en cualquier momento con [parar].
 * Lleva registro de a quién le mandó cada documento, para no repetir.
 */
object EnvioMasivo {

    const val MAXIMO = 40

    data class Destino(val numero: String, val nombre: String)
    data class Lote(val destinos: MutableList<Destino>, val mensaje: String, val documento: Libreta.Documento?,
                    val dudosos: List<String>, val repetidos: List<Destino>)

    @Volatile var lote: Lote? = null; private set
    @Volatile private var hilo: Thread? = null
    @Volatile private var cortar = false
    @Volatile var progreso = ""; private set
    /** Lo pone el servicio: para avisar en voz alta cuando termina. */
    var alHablar: ((String) -> Unit)? = null

    val enviando get() = hilo?.isAlive == true

    // ---------------------------------------------------------------- NÚMEROS
    /**
     * Pasa un teléfono al formato de WhatsApp (solo dígitos, con código de país). En Argentina los celulares
     * van como 549 + área + número, sin el 0 ni el 15. Devuelve null si el número está incompleto.
     */
    fun numeroWhatsApp(crudo: String, pais: String): String? {
        val digitos = crudo.filter { it.isDigit() }
        val conMas = crudo.trim().startsWith("+") || digitos.startsWith("00")
        var d = digitos.removePrefix("00")
        val traePais = conMas || (d.startsWith(pais) && d.length >= 11)
        if (traePais && !d.startsWith("54")) return d.takeIf { it.length in 8..15 }      // otro país
        if (!traePais && pais != "54") return (pais + d.removePrefix("0")).takeIf { it.length in 8..15 }
        // Argentina
        if (traePais) d = d.drop(2).removePrefix("9")
        d = d.removePrefix("0")
        if (d.length == 12) {                              // 11 15 2345 6789 → 11 2345 6789
            for (largoArea in 2..4) if (d.substring(largoArea, largoArea + 2) == "15") {
                d = d.removeRange(largoArea, largoArea + 2); break
            }
        }
        if (d.startsWith("1") && !d.startsWith("11")) return null   // "15 2345 6789": le falta la característica
        return if (d.length == 10) "549$d" else null
    }

    /** "5491123456789" → "+54 9 11 2345-6789" (para leerlo cómodo). */
    fun lindo(n: String): String =
        if (n.startsWith("549") && n.length == 13) "+54 9 ${n.substring(3, 5)} ${n.substring(5, 9)}-${n.substring(9)}" else "+$n"

    // ---------------------------------------------------------------- IMÁGENES
    /** Las últimas [cantidad] capturas de pantalla (o imágenes cualquiera si [soloCapturas] es false). */
    fun ultimasImagenes(ctx: Context, cantidad: Int, soloCapturas: Boolean): List<Uri> {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val (sel, args) = when {
            !soloCapturas -> null to null
            Build.VERSION.SDK_INT >= 29 ->
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? " +
                    "OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)" to arrayOf("%screenshot%", "%screenshot%", "%captura%")
            else -> @Suppress("DEPRECATION")
                "(${MediaStore.Images.Media.DATA} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?)" to arrayOf("%screenshot%", "%captura%")
        }
        val out = mutableListOf<Uri>()
        ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), sel, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < cantidad) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
        }
        return out
    }

    private fun jpegDe(ctx: Context, uri: Uri): ByteArray? = try {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, op) }
        var muestra = 1
        while (maxOf(op.outWidth, op.outHeight) / (muestra * 2) >= 1600) muestra *= 2
        val b = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
        }
        if (b == null) null else Vision.aJpeg(b, 1600, 80)
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- PREPARAR
    /**
     * Arma el lote sin mandar nada. [capturas] = cuántas capturas leer (0 = ninguna); [numeros] = números dichos
     * a mano, separados por coma. [mensaje] = texto, nombre de plantilla o vacío (la predeterminada).
     */
    fun preparar(ctx: Context, prefs: Prefs, capturas: Int, soloCapturas: Boolean, numeros: String,
                 mensaje: String, plantilla: String, documento: String, repetir: Boolean): String {
        if (enviando) return "Ya hay un envío en curso: $progreso. Para cortarlo usá envio_masivo_parar."
        val libreta = Libreta.de(ctx)

        val texto: String? = when {
            mensaje.isNotBlank() -> mensaje.trim()
            plantilla.isNotBlank() -> libreta.plantillaTexto(plantilla)
                ?: return "No encontré la plantilla «$plantilla». Guardadas: ${libreta.plantillas().keys.joinToString().ifBlank { "ninguna" }}."
            else -> libreta.plantillaTexto("")
        }
        val doc = if (documento.isBlank()) null else libreta.documentoBuscar(documento)
            ?: return "No tengo guardado el documento «$documento». Usá documento_guardar para que el usuario lo elija " +
                "una sola vez desde sus archivos (queda guardado para siempre) y después volvé a preparar el envío."
        if (texto == null && doc == null)
            return "No hay mensaje predeterminado guardado. Preguntale qué mensaje quiere mandar y guardalo con plantilla_guardar."

        val crudos = mutableListOf<Vision.Telefono>()
        numeros.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { crudos.add(Vision.Telefono(it, "")) }

        var leidas = 0
        if (capturas > 0) {
            val permiso = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
                          else android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, permiso) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                return "Falta el permiso de Fotos. Pedile al usuario que lo active en Ajustes de Nova (paso «Fotos y capturas») eligiendo «Permitir todo»."
            val uris = ultimasImagenes(ctx, capturas.coerceIn(1, 30), soloCapturas)
            if (uris.isEmpty()) return if (soloCapturas) "No encontré capturas de pantalla en el celular." else "No encontré imágenes."
            val vision = Vision(prefs)
            for (grupo in uris.chunked(5)) {
                val jpegs = grupo.mapNotNull { jpegDe(ctx, it) }
                if (jpegs.isEmpty()) continue
                val (tels, error) = vision.telefonosEn(jpegs)
                if (tels == null) return "Leí $leidas capturas y paré: $error"
                crudos.addAll(tels); leidas += jpegs.size
            }
        }
        if (crudos.isEmpty()) return if (capturas > 0) "Leí $leidas capturas y no encontré ningún número de teléfono." else "No me pasaste números."

        val vistos = mutableMapOf<String, Destino>()
        val dudosos = mutableListOf<String>()
        for (t in crudos) {
            val n = numeroWhatsApp(t.numero, prefs.codigoPais)
            if (n == null) { dudosos.add(t.numero); continue }
            val previo = vistos[n]
            if (previo == null || previo.nombre.isBlank()) vistos[n] = Destino(n, t.nombre.ifBlank { previo?.nombre ?: "" })
        }
        val clave = doc?.nombre ?: texto!!.take(40)
        val (repetidos, nuevos) = vistos.values.partition { !repetir && libreta.enviadoA(clave, it.numero) != null }
        val destinos = nuevos.take(MAXIMO).toMutableList()
        lote = Lote(destinos, texto ?: "", doc, dudosos.distinct(), repetidos)
        return resumen(leidas, nuevos.size > MAXIMO)
    }

    fun resumen(leidas: Int = -1, recortado: Boolean = false): String {
        val l = lote ?: return "No hay ningún envío preparado."
        val sb = StringBuilder()
        if (leidas >= 0) sb.append("Leí $leidas capturas. ")
        if (l.destinos.isEmpty()) sb.append("No quedó ningún número para mandar.\n")
        else {
            sb.append("Listos para mandar: ${l.destinos.size}\n")
            l.destinos.forEachIndexed { i, d -> sb.append("${i + 1}. ${lindo(d.numero)}${if (d.nombre.isNotBlank()) " (${d.nombre})" else ""}\n") }
        }
        if (recortado) sb.append("Recorté a $MAXIMO por tanda para cuidar tu cuenta de WhatsApp; el resto en otra tanda.\n")
        if (l.repetidos.isNotEmpty()) sb.append("Ya les mandaste esto antes, los salteo: ${l.repetidos.joinToString { lindo(it.numero) }}\n")
        if (l.dudosos.isNotEmpty()) sb.append("Números incompletos o raros, no los mando: ${l.dudosos.joinToString()}\n")
        if (l.mensaje.isNotBlank()) sb.append("Mensaje: «${l.mensaje.take(300)}»\n")
        l.documento?.let { sb.append("Documento: ${it.archivo}\n") }
        if (l.destinos.isNotEmpty()) {
            sb.append("Tarda más o menos ${(l.destinos.size * 25 + 59) / 60} minutos.\n")
            sb.append("NO MANDES NADA TODAVÍA: decile cuántos son y los nombres que haya (no leas los números en voz alta), " +
                "y preguntale si lo mando. Recién con su sí usá envio_masivo_enviar. Si quiere sacar alguno, envio_masivo_quitar.")
        }
        return sb.toString().trim()
    }

    fun quitar(cual: String): String {
        val l = lote ?: return "No hay ningún envío preparado."
        val q = cual.trim()
        val idx = q.toIntOrNull()?.minus(1)
        val dig = q.filter { it.isDigit() }
        val sacado: Destino = if (idx != null && idx in l.destinos.indices) l.destinos.removeAt(idx)
        else l.destinos.firstOrNull { d ->
            (dig.length >= 4 && d.numero.endsWith(dig)) || (d.nombre.isNotBlank() && normalizar(d.nombre).contains(normalizar(q)))
        }?.also { l.destinos.remove(it) } ?: return "No encontré «$cual» en la lista."
        return "Saqué ${lindo(sacado.numero)}${if (sacado.nombre.isNotBlank()) " (${sacado.nombre})" else ""}. Quedan ${l.destinos.size}."
    }

    // ---------------------------------------------------------------- ENVIAR
    private fun paqueteWhatsApp(ctx: Context): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    private fun desbloqueado(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val kg = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !kg.isKeyguardLocked
    }

    /** Copia el documento al caché con su nombre real, para compartirlo con WhatsApp. */
    private fun copiaParaCompartir(ctx: Context, doc: Libreta.Documento): Pair<Uri, String>? {
        try {
            val origen = Uri.parse(doc.uri)
            val carpeta = File(ctx.cacheDir, "compartir").apply { mkdirs() }
            val destino = File(carpeta, doc.archivo.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val entrada = ctx.contentResolver.openInputStream(origen) ?: return null
            entrada.use { ent -> destino.outputStream().use { ent.copyTo(it) } }
            val tipo = ctx.contentResolver.getType(origen) ?: "application/pdf"
            return FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", destino) to tipo
        } catch (e: Exception) {
            return null
        }
    }

    /** Arranca el envío en segundo plano. Vuelve enseguida. */
    fun enviar(ctx: Context): String {
        val l = lote ?: return "No hay ningún envío preparado. Primero usá envio_masivo_preparar."
        if (enviando) return "Ya estoy mandando: $progreso."
        if (l.destinos.isEmpty()) return "La lista está vacía."
        val manos = ControlAccesibilidad.instancia
            ?: return "Para mandar solo necesito el control de pantalla (Ajustes › Accesibilidad › Nova)."
        val pkg = paqueteWhatsApp(ctx) ?: return "No encontré WhatsApp instalado."
        val archivo = l.documento?.let { copiaParaCompartir(ctx, it)
            ?: return "No pude abrir «${it.archivo}». Puede que se haya movido o borrado: volvé a elegirlo con documento_guardar." }
        if (!desbloqueado(ctx)) {
            manos.startActivity(Intent(ctx, PantallaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "El celular está bloqueado. Desbloquealo y decime «dale» para arrancar (mientras manda no lo uses)."
        }
        cortar = false
        val app = ctx.applicationContext
        hilo = Thread { try {
            val libreta = Libreta.de(app)
            val clave = l.documento?.nombre ?: l.mensaje.take(40)
            val ok = mutableListOf<Destino>(); val sinWa = mutableListOf<Destino>(); val fallos = mutableListOf<Destino>()
            val total = l.destinos.size
            for ((i, d) in l.destinos.toList().withIndex()) {
                if (cortar) break
                progreso = "mandando ${i + 1} de $total"
                if (!desbloqueado(app)) { cortar = true; progreso = "frenado porque se bloqueó el celular"; break }
                val mensaje = l.mensaje.replace("{nombre}", d.nombre).replace(Regex(" +([,.!?])"), "$1").replace(Regex("  +"), " ").trim()
                val r = runCatching { mandarUno(app, manos, pkg, d.numero, mensaje, archivo) }.getOrDefault(Resultado.FALLO)
                when (r) {
                    Resultado.OK -> { ok.add(d); libreta.marcarEnviado(clave, d.numero) }
                    Resultado.SIN_WHATSAPP -> sinWa.add(d)
                    Resultado.FALLO -> fallos.add(d)
                }
                if (i < total - 1 && !cortar) dormir((9_000L..16_000L).random())   // pausa "humana" entre contactos
            }
            manos.botonSistema("inicio")
            l.destinos.removeAll(ok.toSet() + sinWa.toSet())
            val partes = mutableListOf("Listo el envío: mandé a ${ok.size} de $total")
            if (cortar && ok.size + sinWa.size + fallos.size < total) partes[0] = "Corté el envío: mandé a ${ok.size} de $total"
            if (sinWa.isNotEmpty()) partes.add("${sinWa.size} no tienen WhatsApp")
            if (fallos.isNotEmpty()) partes.add("con ${fallos.size} no pude (${fallos.joinToString { it.nombre.ifBlank { lindo(it.numero) } }}); si querés los reintento")
            val final = partes.joinToString(", ") + "."
            progreso = final
            if (l.destinos.isEmpty()) lote = null
            Bus.mensaje(false, final)
            alHablar?.invoke(final)
        } catch (e: Exception) {
            progreso = "El envío se cortó por un error: ${e.message}"
            Bus.mensaje(false, progreso); alHablar?.invoke("El envío se cortó por un error.")
        } }.apply { isDaemon = true; start() }
        val min = (l.destinos.size * 25 + 59) / 60
        return "Arranqué a mandar a ${l.destinos.size} contactos, uno por uno. Tarda unos $min minutos; " +
            "mientras tanto no uses el celular. Te aviso cuando termine. Si querés cortarlo, decime «pará el envío»."
    }

    fun parar(): String {
        if (!enviando) return "No hay ningún envío en curso."
        cortar = true
        return "Corto después del que estoy mandando."
    }

    fun estado(): String = when {
        enviando -> "Envío en curso: $progreso."
        lote != null -> resumen()
        progreso.isNotBlank() -> progreso
        else -> "No hay envíos en curso ni preparados."
    }

    private enum class Resultado { OK, SIN_WHATSAPP, FALLO }

    private fun dormir(ms: Long) {
        var falta = ms
        while (falta > 0 && !cortar) { Thread.sleep(minOf(falta, 500)); falta -= 500 }
    }

    private val avisoSinWhatsApp = arrayOf("no está en WhatsApp", "no esta en whatsapp", "isn't on WhatsApp", "is not on WhatsApp",
        "no es válido", "not valid", "no tiene WhatsApp")

    /** Abre el chat, escribe y toca Enviar; después comparte el documento al mismo chat. */
    private fun mandarUno(ctx: Context, m: ControlAccesibilidad, pkg: String, numero: String, mensaje: String,
                          archivo: Pair<Uri, String>?): Resultado {
        if (mensaje.isNotBlank()) {
            val url = "whatsapp://send?phone=$numero&text=${URLEncoder.encode(mensaje, "UTF-8").replace("+", "%20")}"
            m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            when (tocarHastaEnviar(m, pkg)) {
                Resultado.SIN_WHATSAPP -> { m.botonSistema("atras"); return Resultado.SIN_WHATSAPP }
                Resultado.FALLO -> return Resultado.FALLO
                Resultado.OK -> {}
            }
        }
        if (archivo != null) {
            dormir(1500)
            val (uri, tipo) = archivo
            m.startActivity(Intent(Intent.ACTION_SEND).setPackage(pkg).setType(tipo)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra("jid", "$numero@s.whatsapp.net")
                .apply { clipData = ClipData.newRawUri("", uri) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            val r = tocarHastaEnviar(m, pkg)
            if (r != Resultado.OK) {
                if (r == Resultado.SIN_WHATSAPP) { m.botonSistema("atras"); return Resultado.SIN_WHATSAPP }
                return Resultado.FALLO
            }
        }
        return Resultado.OK
    }

    /**
     * Espera a que WhatsApp muestre el chat (o la vista previa del archivo) y toca Enviar hasta que el botón
     * desaparece (el cartel "¿Enviar a…?" y la vista previa piden dos toques).
     */
    private fun tocarHastaEnviar(m: ControlAccesibilidad, pkg: String): Resultado {
        var toques = 0; var vecesSinBoton = 0
        val limite = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < limite && !cortar) {
            Thread.sleep(800)
            if (!m.appActual().startsWith(pkg)) continue
            if (m.hayTexto(*avisoSinWhatsApp)) return Resultado.SIN_WHATSAPP
            if (toques < 3 && m.tocarEnviar()) { toques++; vecesSinBoton = 0; Thread.sleep(1200); continue }
            if (toques > 0 && ++vecesSinBoton >= 2) return Resultado.OK
        }
        return if (toques > 0) Resultado.OK else Resultado.FALLO
    }
}

/**
 * Abre el selector de archivos para que elijas un documento (tu CV, por ejemplo) y lo guarda con un nombre.
 * Android solo deja que Nova lo lea si lo elegís vos una vez; después queda guardado para siempre.
 */
class DocumentoActivity : Activity() {
    companion object { const val NOMBRE = "nombre" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*", "*/*")), 1)
    }

    @Deprecated("API vieja, alcanza")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { Bus.mensaje(false, "No elegiste ningún documento."); finish(); return }
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val archivo = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null } ?: "documento"
        val nombre = intent.getStringExtra(NOMBRE)?.takeIf { it.isNotBlank() } ?: archivo.substringBeforeLast(".")
        Libreta.de(this).documentoGuardar(nombre, uri.toString(), archivo)
        Bus.mensaje(false, "Guardé «$archivo» como «$nombre». Ya lo puedo mandar cuando quieras.")
        EnvioMasivo.alHablar?.invoke("Listo, guardé $nombre.")
        finish()
    }
}
