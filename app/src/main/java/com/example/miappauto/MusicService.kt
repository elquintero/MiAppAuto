package com.example.miappauto

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
                    Biblioteca.carpeta("cola", "Cola", "${Biblioteca.queue.size} vídeos"),
                    Biblioteca.carpeta("ahora", "Sonando ahora"),
                    Biblioteca.carpeta("lib", "Biblioteca", "${Biblioteca.lib.size} vídeos")
                )
                parentId == "cola" ->
                    if (Biblioteca.queue.isEmpty())
                        listOf(Biblioteca.aviso("La cola está vacía. Añade vídeos desde la app del móvil."))
                    else Biblioteca.itemsDeCola(conContexto = true)
                parentId == "ahora" -> {
                    val p = session.player
                    if (p.mediaItemCount == 0) {
                        listOf(Biblioteca.aviso("No hay nada sonando todavía."))
                    } else {
                        (0 until p.mediaItemCount).map {
                            val m = p.getMediaItemAt(it)
                            m.buildUpon().setMediaId(Biblioteca.idContexto("a", m.mediaId)).build()
                        }
                    }
                }
                parentId == "lib" -> Biblioteca.lib.values
                    .groupBy { it.perf }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    .map { (perf, vs) ->
                        Biblioteca.carpeta("perf:$perf", perf, "${vs.size} vídeos", Caratulas.uri(perf))
                    }
                parentId.startsWith("perf:") -> {
                    val perf = parentId.removePrefix("perf:")
                    Biblioteca.delInterprete(perf)
                        .map { Biblioteca.itemDe(it, Biblioteca.idContexto("p", it.k, perf)) }
                }
                else -> emptyList()
            }
            val pagina = if (pageSize > 0 && pageSize < Int.MAX_VALUE)
                hijos.drop(page * pageSize).take(pageSize) else hijos
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(pagina), params)
            )
        }

        // Cuando alguien (Android Auto o la pantalla del móvil) elige una pista,
        // se monta la lista de reproducción de la lista de origen y se empieza por la elegida.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resultado: MediaSession.MediaItemsWithStartPosition =
                if (mediaItems.size == 1) {
                    elegida(mediaSession.player, mediaItems[0])
                        ?: MediaSession.MediaItemsWithStartPosition(
                            mediaItems, startIndex, startPositionMs
                        )
                } else {
                    playlistEsCola = true
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems.map { m ->
                            Biblioteca.lib[m.mediaId]?.let { Biblioteca.itemDe(it) } ?: m
                        },
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

    /** Decide qué lista reproducir según de dónde viene la pista pulsada (cola, sonando ahora o intérprete). */
    private fun elegida(player: Player, pedido: MediaItem): MediaSession.MediaItemsWithStartPosition? {
        val partes = pedido.mediaId.split(Biblioteca.SEP, limit = 3)
        val ctx = if (partes.size >= 2 && partes[0] in listOf("q", "a", "p")) partes[0] else ""
        val perf = if (ctx == "p" && partes.size == 3) partes[1] else null
        val k = when (ctx) {
            "p" -> partes.getOrNull(2) ?: return null
            "q", "a" -> partes[1]
            else -> pedido.mediaId
        }
        val video = Biblioteca.lib[k]

        if (ctx == "a") {
            val actuales = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
            val i = actuales.indexOfFirst { it.mediaId == k }
            if (i >= 0) return MediaSession.MediaItemsWithStartPosition(actuales, i, 0L)
        }
        if (ctx == "p" && perf != null && video != null) {
            playlistEsCola = false
            return grupo(perf, k)
        }
        // Cola ("q") o identificador sin contexto
        val posCola = Biblioteca.queue.indexOf(k)
        if (posCola >= 0) {
            playlistEsCola = true
            return MediaSession.MediaItemsWithStartPosition(Biblioteca.itemsDeCola(), posCola, 0L)
        }
        if (video != null) {
            playlistEsCola = false
            return grupo(video.perf, k)
        }
        return null
    }

    private fun grupo(perf: String, k: String): MediaSession.MediaItemsWithStartPosition {
        val g = Biblioteca.delInterprete(perf)
        return MediaSession.MediaItemsWithStartPosition(
            g.map { Biblioteca.itemDe(it) },
            g.indexOfFirst { it.k == k }.coerceAtLeast(0),
            0L
        )
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Biblioteca.cargar(applicationContext)

        // Más margen de red: tiempos de espera largos y buffer amplio (útil con cobertura irregular en carretera)
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val carga = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 180_000, 5_000, 10_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .setLoadControl(carga)
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

    /** La web cambió la biblioteca, la cola o una carátula: se ajusta el reproductor sin cortar la pista actual. */
    fun alCambiarBiblioteca(perf: String? = null) {
        val s = session ?: return
        val p = s.player
        p.shuffleModeEnabled = Biblioteca.shuffle
        p.repeatMode = if (Biblioteca.repeat) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        if (playlistEsCola && p.mediaItemCount > 0) reconciliar(p)
        runCatching {
            s.notifyChildrenChanged("raiz", 3, null)
            s.notifyChildrenChanged("cola", Biblioteca.queue.size, null)
            s.notifyChildrenChanged("lib", Biblioteca.lib.size, null)
            if (perf != null) {
                s.notifyChildrenChanged("perf:$perf", Biblioteca.delInterprete(perf).size, null)
            }
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
