package com.example.miappauto

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.concurrent.thread

class MusicService : MediaLibraryService() {

    companion object {

        private const val TAG = "MusicService"

        private const val ROOT = "root"
        private const val INTERPRETES = "interpretes"

        private const val CACHE_FILE =
            "biblioteca_cache.json"

        /*
         * TU URL RAW DE GITHUB
         */
        private const val JSON_URL =
            "https://raw.githubusercontent.com/USUARIO/REPOSITORIO/main/biblioteca.json"
    }

    private var session: MediaLibrarySession? = null

    private val client = OkHttpClient()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /*
     * Catálogo completo
     */
    private val catalogo =
        mutableMapOf<String, MediaItem>()

    /*
     * Índice por intérprete
     */
    private val catalogoPorInterprete =
        mutableMapOf<String, MutableList<MediaItem>>()

    override fun onCreate() {

        super.onCreate()

        cargarBiblioteca()

        val player =
            ExoPlayer.Builder(this)
                .build()

        session =
            MediaLibrarySession.Builder(
                this,
                player,
                callback
            ).build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? = session

    override fun onDestroy() {

        session?.run {
            player.release()
            release()
        }

        session = null

        super.onDestroy()
    }

    // -------------------------------------------------------
    // DESCARGA Y CACHE
    // -------------------------------------------------------

    private fun cargarBiblioteca() {

        thread {

            try {

                Log.d(TAG, "Descargando JSON...")

                val biblioteca = descargarDesdeGithub()

                guardarCache(biblioteca)

                construirCatalogo(biblioteca)

                Log.d(
                    TAG,
                    "Biblioteca cargada desde GitHub"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error descargando GitHub",
                    e
                )

                try {

                    val biblioteca =
                        cargarDesdeCache()

                    construirCatalogo(
                        biblioteca
                    )

                    Log.d(
                        TAG,
                        "Biblioteca cargada desde cache"
                    )

                } catch (cacheError: Exception) {

                    Log.e(
                        TAG,
                        "No existe cache válida",
                        cacheError
                    )
                }
            }
        }
    }

    private fun descargarDesdeGithub(): BibliotecaJson {

        val request =
            Request.Builder()
                .url(JSON_URL)
                .build()

        client.newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    error(
                        "HTTP ${response.code}"
                    )
                }

                val texto =
                    response.body?.string()
                        ?: error("JSON vacío")

                return json.decodeFromString(
                    texto
                )
            }
    }

    private fun guardarCache(
        biblioteca: BibliotecaJson
    ) {

        val archivo =
            File(filesDir, CACHE_FILE)

        archivo.writeText(
            json.encodeToString(
                BibliotecaJson.serializer(),
                biblioteca
            )
        )
    }

    private fun cargarDesdeCache():
            BibliotecaJson {

        val archivo =
            File(filesDir, CACHE_FILE)

        if (!archivo.exists()) {
            error("No existe cache")
        }

        return json.decodeFromString(
            archivo.readText()
        )
    }

    // -------------------------------------------------------
    // GENERACIÓN DEL CATÁLOGO
    // -------------------------------------------------------

    private fun construirCatalogo(
        biblioteca: BibliotecaJson
    ) {

        catalogo.clear()
        catalogoPorInterprete.clear()

        biblioteca.pistas.forEach { pista ->

            val item =
                MediaItem.Builder()
                    .setMediaId(pista.id)
                    .setUri(pista.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(
                                pista.titulo
                            )
                            .setArtist(
                                pista.interprete
                            )
                            .setAlbumTitle(
                                pista.edicion
                            )
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .build()
                    )
                    .build()

            catalogo[pista.id] = item

            val lista =
                catalogoPorInterprete
                    .getOrPut(
                        pista.interprete
                    ) {
                        mutableListOf()
                    }

            lista.add(item)
        }

        Log.d(
            TAG,
            "Pistas: ${catalogo.size}"
        )
    }

    private fun interpretes():
            List<String> {

        return catalogoPorInterprete.keys
            .sorted()
    }

    private fun pistasDe(
        interprete: String
    ): List<MediaItem> {

        return catalogoPorInterprete[
            interprete
        ] ?: emptyList()
    }

    // -------------------------------------------------------
    // MEDIA LIBRARY CALLBACK
    // -------------------------------------------------------

    private val callback =
        object : MediaLibrarySession.Callback {

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<
                    LibraryResult<MediaItem>
                    > {

                val root =
                    MediaItem.Builder()
                        .setMediaId(ROOT)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(
                                    "Biblioteca Chopin"
                                )
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()

                return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        root,
                        params
                    )
                )
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<
                    LibraryResult<
                            ImmutableList<MediaItem>
                            >
                    > {

                val resultado =
                    when {

                        parentId == ROOT -> {

                            listOf(
                                MediaItem.Builder()
                                    .setMediaId(
                                        INTERPRETES
                                    )
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(
                                                "Intérpretes"
                                            )
                                            .setIsBrowsable(
                                                true
                                            )
                                            .setIsPlayable(
                                                false
                                            )
                                            .build()
                                    )
                                    .build()
                            )
                        }

                        parentId == INTERPRETES -> {

                            interpretes().map {
                                artista ->

                                MediaItem.Builder()
                                    .setMediaId(
                                        "artist:$artista"
                                    )
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(
                                                artista
                                            )
                                            .setIsBrowsable(
                                                true
                                            )
                                            .setIsPlayable(
                                                false
                                            )
                                            .build()
                                    )
                                    .build()
                            }
                        }

                        parentId.startsWith(
                            "artist:"
                        ) -> {

                            val artista =
                                parentId.removePrefix(
                                    "artist:"
                                )

                            pistasDe(
                                artista
                            )
                        }

                        else -> emptyList()
                    }

                return Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        ImmutableList.copyOf(
                            resultado
                        ),
                        params
                    )
                )
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<
                    MutableList<MediaItem>
                    > {

                val items =
                    mediaItems.map {

                        catalogo[
                            it.mediaId
                        ] ?: it
                    }
                        .toMutableList()

                return Futures.immediateFuture(
                    items
                )
            }
        }
}

// -----------------------------------------------------------
// MODELOS JSON
// -----------------------------------------------------------

@Serializable
data class BibliotecaJson(
    val version: Int = 1,
    val nombre: String = "",
    val pistas: List<PistaJson> = emptyList()
)

@Serializable
data class PistaJson(
    val id: String,
    val titulo: String,
    val interprete: String,
    val edicion: String = "",
    val etapa: String = "",
    val url: String,
    val caratula: String = ""
)
