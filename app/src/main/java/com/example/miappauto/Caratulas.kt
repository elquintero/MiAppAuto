package com.example.miappauto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Carátulas por intérprete. Se elige una imagen desde el móvil, se recorta en cuadrado,
 * se reduce a 512x512 y se guarda; Android Auto la lee a través de CaratulasProvider.
 */
object Caratulas {

    const val AUTORIDAD = "com.example.miappauto.caratulas"
    private const val ARCHIVO = "caratulas.json"

    private val mapa = LinkedHashMap<String, String>() // intérprete -> nombre de archivo
    private var cargado = false

    fun carpeta(ctx: Context): File = File(ctx.filesDir, "caratulas").apply { mkdirs() }

    @Synchronized
    fun cargar(ctx: Context) {
        if (cargado) return
        cargado = true
        val f = File(ctx.filesDir, ARCHIVO)
        if (f.exists()) runCatching {
            val o = JSONObject(f.readText())
            val ks = o.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                mapa[k] = o.getString(k)
            }
        }
    }

    private fun persistir(ctx: Context) {
        runCatching { File(ctx.filesDir, ARCHIVO).writeText(JSONObject(mapa as Map<*, *>).toString()) }
    }

    private fun idDe(perf: String): String =
        MessageDigest.getInstance("SHA-1").digest(perf.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

    /** Dirección que se entrega a Android Auto. Cambia con cada imagen nueva para evitar cachés viejas. */
    @Synchronized
    fun uri(perf: String): Uri? =
        mapa[perf]?.let { Uri.parse("content://$AUTORIDAD/$it") }

    @Synchronized
    fun guardar(ctx: Context, perf: String, origen: Uri): Boolean {
        cargar(ctx)
        return try {
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(origen)?.use {
                BitmapFactory.decodeStream(it, null, limites)
            }
            var muestra = 1
            while (limites.outWidth / muestra > 1200 || limites.outHeight / muestra > 1200) muestra *= 2

            val opciones = BitmapFactory.Options().apply { inSampleSize = muestra }
            val bmp = ctx.contentResolver.openInputStream(origen)?.use {
                BitmapFactory.decodeStream(it, null, opciones)
            } ?: return false

            val lado = minOf(bmp.width, bmp.height)
            val cuadrada = Bitmap.createBitmap(
                bmp, (bmp.width - lado) / 2, (bmp.height - lado) / 2, lado, lado
            )
            val recortada = Bitmap.createScaledBitmap(cuadrada, 512, 512, true)

            val nombre = "${idDe(perf)}-${System.currentTimeMillis()}.jpg"
            File(carpeta(ctx), nombre).outputStream().use {
                recortada.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            mapa[perf]?.let { File(carpeta(ctx), it).delete() }
            mapa[perf] = nombre
            persistir(ctx)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun quitar(ctx: Context, perf: String) {
        cargar(ctx)
        mapa.remove(perf)?.let { File(carpeta(ctx), it).delete() }
        persistir(ctx)
    }

    /** Miniatura de 96 px como data-URL, para mostrarla en la pantalla web del móvil. */
    @Synchronized
    fun miniatura(ctx: Context, perf: String): String? {
        cargar(ctx)
        val n = mapa[perf] ?: return null
        val bmp = BitmapFactory.decodeFile(File(carpeta(ctx), n).path) ?: return null
        val peq = Bitmap.createScaledBitmap(bmp, 96, 96, true)
        val sal = ByteArrayOutputStream()
        peq.compress(Bitmap.CompressFormat.JPEG, 80, sal)
        return "data:image/jpeg;base64," + Base64.encodeToString(sal.toByteArray(), Base64.NO_WRAP)
    }

    @Synchronized
    fun todasMiniaturas(ctx: Context): String {
        cargar(ctx)
        val o = JSONObject()
        for (perf in mapa.keys.toList()) {
            miniatura(ctx, perf)?.let { o.put(perf, it) }
        }
        return o.toString()
    }
}
