package com.example.miappauto

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

    companion object {
        @Volatile
        var instancia: MusicService? = null
    }

    private var session: MediaLibrarySession? = null
    private var playlistEsCola = false
    private var fallos = 0

    private val callback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(Biblioteca.carpeta("raiz", "Biblioteca Chopin"), params)
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val hijos: List<MediaItem> = when {
                parentId == "raiz" -> listOf(
                    Biblioteca.carpeta("cola", "Cola de reproducción", "${Biblioteca.queue.size} vídeos"),
                    Biblioteca.carpeta("lib", "Biblioteca", "${Biblioteca.lib.size} vídeos")
                )
                parentId == "cola" -> Biblioteca.itemsDeCola()
                parentId == "lib" -> Biblioteca.lib.values
                    .groupBy { it.perf }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { (perf, vs) -> Biblioteca.carpeta("perf:$perf", perf, "${vs.size} vídeos") }
                parentId.startsWith("perf:") ->
                    Biblioteca.delInterprete(parentId.removePrefix("perf:")).map { Biblioteca.itemDe(it) }
                else -> emptyList()
            }
            val pagina = if (pageSize > 0 && pageSize < Int.MAX_VALUE)
                hijos.drop(page * pageSize).take(pageSize) else hijos
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(pagina), params)
            )
        }

        // Cuando alguien (Android Auto o la pantalla del móvil) elige una pista,
        // se monta la lista de reproducción completa y se empieza por la elegida.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resultado: MediaSession.MediaItemsWithStartPosition
            if (mediaItems.size == 1) {
                val id = mediaItems[0].mediaId
                val posCola = Biblioteca.queue.indexOf(id)
                val video = Biblioteca.lib[id]
                if (posCola >= 0) {
                    playlistEsCola = true
                    resultado = MediaSession.MediaItemsWithStartPosition(
                        Biblioteca.itemsDeCola(), posCola, 0L
                    )
                } else if (video != null) {
                    playlistEsCola = false
                    val grupo = Biblioteca.delInterprete(video.perf)
                    resultado = MediaSession.MediaItemsWithStartPosition(
                        grupo.map { Biblioteca.itemDe(it) },
                        grupo.indexOfFirst { it.k == id }.coerceAtLeast(0),
                        0L
                    )
                } else {
                    resultado = MediaSession.MediaItemsWithStartPosition(
                        mediaItems, startIndex, startPositionMs
                    )
                }
            } else {
                playlistEsCola = true
                resultado = MediaSession.MediaItemsWithStartPosition(
                    mediaItems.map { m -> Biblioteca.lib[m.mediaId]?.let { Biblioteca.itemDe(it) } ?: m },
                    startIndex,
                    startPositionMs
                )
            }
            return Futures.immediateFuture(resultado)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resueltos = mediaItems.map { m ->
                Biblioteca.lib[m.mediaId]?.let { Biblioteca.itemDe(it) } ?: m
            }.toMutableList()
            return Futures.immediateFuture(resueltos)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Biblioteca.cargar(applicationContext)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Solo sonido: se desactiva la pista de vídeo
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        player.shuffleModeEnabled = Biblioteca.shuffle
        player.repeatMode =
            if (Biblioteca.repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) fallos = 0
            }

            // Enlace caducado o caído: pasa al siguiente, y se detiene tras tres fallos seguidos
            override fun onPlayerError(error: PlaybackException) {
                fallos++
                if (fallos < 3 && player.mediaItemCount > 1) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        session = MediaLibrarySession.Builder(this, player, callback).build()
    }

    /** La web cambió la biblioteca o la cola: se ajusta el reproductor sin cortar la pista actual. */
    fun alCambiarBiblioteca() {
        val s = session ?: return
        val p = s.player
        p.shuffleModeEnabled = Biblioteca.shuffle
        p.repeatMode = if (Biblioteca.repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        if (playlistEsCola && p.mediaItemCount > 0) reconciliar(p)
        runCatching {
            s.notifyChildrenChanged("cola", Biblioteca.queue.size, null)
            s.notifyChildrenChanged("lib", Biblioteca.lib.size, null)
        }
    }

    private fun reconciliar(p: Player) {
        val ids = Biblioteca.queue
        val enReproductor = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        if (enReproductor == ids) return

        val actual = p.currentMediaItem?.mediaId
        val nuevaPos = ids.indexOf(actual)
        if (actual == null || nuevaPos < 0) {
            // La pista que sonaba ya no está en la cola
            p.stop()
            p.clearMediaItems()
            return
        }
        val i = p.currentMediaItemIndex
        if (i + 1 < p.mediaItemCount) p.removeMediaItems(i + 1, p.mediaItemCount)
        if (i > 0) p.removeMediaItems(0, i)
        val items = Biblioteca.itemsDeCola()
        p.addMediaItems(0, items.subList(0, nuevaPos))
        p.addMediaItems(items.subList(nuevaPos + 1, items.size))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        instancia = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
