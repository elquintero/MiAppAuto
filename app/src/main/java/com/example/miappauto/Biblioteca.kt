package com.example.miappauto

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import java.io.File

/**
 * Guarda la biblioteca y la cola que gestiona la página web (WebView).
 * La web llama a actualizar() cada vez que cambia algo; Android Auto la lee desde aquí,
 * incluso cuando la pantalla de la app no está abierta (se guarda en un archivo).
 */
object Biblioteca {

    /** Separador para los identificadores de las listas de Android Auto (indican de qué lista viene cada pista). */
    const val SEP = "§"

    data class Video(
        val k: String,
        val url: String,
        val perf: String,
        val title: String,
        val stage: String,
        val edition: String,
        val s: Long
    )

    @Volatile
    var lib: Map<String, Video> = emptyMap()
        private set

    @Volatile
    var queue: List<String> = emptyList()
        private set

    @Volatile
    var shuffle: Boolean = false
        private set

    @Volatile
    var repeat: Boolean = true
        private set

    @Volatile
    var cur: Int = -1
        private set

    private const val ARCHIVO = "biblioteca.json"
    private var cargado = false

    @Synchronized
    fun cargar(ctx: Context) {
        Caratulas.cargar(ctx)
        Calidad.cargar(ctx)
        if (cargado) return
        cargado = true
        val f = File(ctx.filesDir, ARCHIVO)
        if (f.exists()) runCatching { interpretar(f.readText()) }
    }

    @Synchronized
    fun actualizar(ctx: Context, json: String) {
        cargado = true
        interpretar(json)
        runCatching { File(ctx.filesDir, ARCHIVO).writeText(json) }
    }

    private fun interpretar(json: String) {
        val o = JSONObject(json)
        val nueva = LinkedHashMap<String, Video>()
        val libJ = o.optJSONObject("lib")
        if (libJ != null) {
            val claves = libJ.keys()
            while (claves.hasNext()) {
                val k = claves.next()
                val v = libJ.optJSONObject(k) ?: continue
                val url = v.optString("url")
                if (url.isEmpty()) continue
                nueva[k] = Video(
                    k = k,
                    url = url,
                    perf = v.optString("perf", "Sin intérprete"),
                    title = v.optString("title", "Vídeo"),
                    stage = v.optString("stage"),
                    edition = v.optString("edition"),
                    s = v.optLong("s")
                )
            }
        }
        val cola = ArrayList<String>()
        val qJ = o.optJSONArray("queue")
        if (qJ != null) {
            for (i in 0 until qJ.length()) {
                val k = qJ.optString(i)
                if (nueva.containsKey(k)) cola.add(k)
            }
        }
        lib = nueva
        queue = cola
        shuffle = o.optBoolean("shuffle", false)
        repeat = o.optBoolean("repeat", true)
        cur = o.optInt("cur", -1)
    }

    fun delInterprete(perf: String): List<Video> =
        lib.values.filter { it.perf == perf }.sortedWith(compareBy({ it.edition }, { it.s }))

    /** Cola de la web, con identificadores "q§clave" para que Android Auto sepa de qué lista viene. */
    fun itemsDeCola(conContexto: Boolean = false): List<MediaItem> =
        queue.mapNotNull { lib[it] }.map { itemDe(it, if (conContexto) idContexto("q", it.k) else it.k) }

    fun idContexto(ctx: String, k: String, perf: String? = null): String =
        if (perf == null) "$ctx$SEP$k" else "$ctx$SEP$perf$SEP$k"

    /** Dirección que se reproduce (o se descarga) para una pista, según audio directo y calidad elegida. */
    fun urlReproduccion(v: Video): String = AudioDirecto.urlPara(v) ?: Calidad.aplicar(v.url)

    fun itemDe(v: Video, id: String = v.k, url: String = urlReproduccion(v)): MediaItem {
        val album = listOf(v.stage, v.edition).filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { "Concurso Chopin" }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(v.title)
                    .setArtist(v.perf)
                    .setAlbumTitle(album)
                    .setArtworkUri(Caratulas.uri(v.perf))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            ).build()
    }

    fun carpeta(id: String, titulo: String, subtitulo: String? = null, imagen: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(titulo)
                    .setSubtitle(subtitulo)
                    .setArtworkUri(imagen)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            ).build()

    /** Elemento informativo (ni carpeta ni pista), para listas vacías. */
    fun aviso(texto: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("aviso")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(texto)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build()
            ).build()
}
