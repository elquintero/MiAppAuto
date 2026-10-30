package com.example.miappauto

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Descarga pistas con Wi-Fi para oírlas después sin gastar datos.
 * - Las descargas solo avanzan con una red sin límite de datos (Wi-Fi).
 * - Al reproducir, lo descargado se lee del almacenamiento; lo que no está descargado se pide a internet,
 *   salvo que esté activado el "modo sin datos".
 */
object Descargas {

    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null
    private var http: DefaultHttpDataSource.Factory? = null

    @Volatile
    var modoSinDatos: Boolean = false
        private set

    /** Se avisa (en el hilo principal) cuando cambia el estado de alguna descarga. */
    var alCambiar: (() -> Unit)? = null

    /** Llamar desde el hilo principal. */
    @Synchronized
    fun iniciar(ctx: Context) {
        if (manager != null) return
        val app = ctx.applicationContext
        modoSinDatos = app.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
            .getBoolean("sinDatos", false)

        val fabricaHttp = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        http = fabricaHttp

        val db = StandaloneDatabaseProvider(app)
        val c = SimpleCache(File(app.filesDir, "descargas"), NoOpCacheEvictor(), db)
        cache = c

        val m = DownloadManager(app, db, c, fabricaHttp, Executors.newFixedThreadPool(2))
        m.maxParallelDownloads = 2
        m.requirements = Requirements(Requirements.NETWORK_UNMETERED)
        m.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                alCambiar?.invoke()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                alCambiar?.invoke()
            }
        })
        manager = m
        m.resumeDownloads()
    }

    /** Fuente de datos para reproducir: primero lo descargado; solo lectura sobre el almacenamiento. */
    fun fabricaReproduccion(): DataSource.Factory {
        val c = cache
        val h = http ?: DefaultHttpDataSource.Factory()
        if (c == null) return h
        val aguasArriba = ResolvingDataSource.Factory(
            h,
            ResolvingDataSource.Resolver { spec ->
                if (modoSinDatos) throw IOException("Modo sin datos: esta pista no está descargada")
                spec
            }
        )
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(aguasArriba)
            .setCacheWriteDataSinkFactory(null)
    }

    fun pedir(videos: List<Biblioteca.Video>): Int {
        val m = manager ?: return 0
        var n = 0
        for (v in videos) {
            val url = Biblioteca.urlReproduccion(v)
            val peticion = DownloadRequest.Builder(v.k, Uri.parse(url))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            m.addDownload(peticion)
            n++
        }
        m.resumeDownloads()
        return n
    }

    fun borrarTodo() {
        manager?.removeAllDownloads()
    }

    fun fijarModoSinDatos(ctx: Context, valor: Boolean) {
        modoSinDatos = valor
        ctx.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
            .edit().putBoolean("sinDatos", valor).apply()
    }

    fun resumen(): String {
        val m = manager ?: return "Descargas no disponibles todavía"
        var listas = 0
        var pendientes = 0
        var falladas = 0
        var bytes = 0L
        try {
            m.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    bytes += d.bytesDownloaded
                    when (d.state) {
                        Download.STATE_COMPLETED -> listas++
                        Download.STATE_FAILED -> falladas++
                        else -> pendientes++
                    }
                }
            }
        } catch (e: Exception) {
            return "No se pudo leer el estado de las descargas"
        }
        val mb = bytes / 1_048_576
        return "Descargadas: $listas · pendientes: $pendientes · fallidas: $falladas · $mb MB" +
            if (modoSinDatos) " · MODO SIN DATOS" else ""
    }
}
