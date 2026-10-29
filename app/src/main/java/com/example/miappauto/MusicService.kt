package com.example.miappauto

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class MusicService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null

    // Catálogo de ejemplo: aquí es donde luego cargarás tus propias pistas
    private val catalogo = listOf(
        pista("1", "Canción de prueba 1", "SoundHelix",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
        pista("2", "Canción de prueba 2", "SoundHelix",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
        pista("3", "Canción de prueba 3", "SoundHelix",
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3")
    )

    private val callback = object : MediaLibrarySession.Callback {

        // Raíz del árbol que muestra Android Auto
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val raiz = MediaItem.Builder()
                .setMediaId("raiz")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                ).build()
            return Futures.immediateFuture(LibraryResult.ofItem(raiz, params))
        }

        // Lista de elementos que se muestran en pantalla
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(catalogo), params)
            )

        // Cuando el usuario toca una pista, se resuelve su URI real
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resueltos = mediaItems.map { pedido ->
                catalogo.firstOrNull { it.mediaId == pedido.mediaId } ?: pedido
            }.toMutableList()
            return Futures.immediateFuture(resueltos)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        session = MediaLibrarySession.Builder(this, player, callback).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

private fun pista(id: String, titulo: String, artista: String, url: String) =
    MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(titulo)
                .setArtist(artista)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        ).build()
