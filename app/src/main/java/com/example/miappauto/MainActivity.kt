package com.example.miappauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var futuro: ListenableFuture<MediaController>? = null
    private var control: MediaController? = null
    private var pendiente: Int? = null
    private var perfPendiente: String? = null

    // Selector de imágenes del móvil para las carátulas
    private val selector = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val perf = perfPendiente
        perfPendiente = null
        if (uri != null && perf != null) {
            Thread {
                val ok = Caratulas.guardar(applicationContext, perf, uri)
                runOnUiThread {
                    if (ok) {
                        MusicService.instancia?.alCambiarBiblioteca(perf)
                        avisarCaratula(perf)
                        Toast.makeText(this, "Carátula guardada", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "No se pudo usar esa imagen", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Biblioteca.cargar(applicationContext)
        Descargas.iniciar(applicationContext)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                // La web usa confirm() (por ejemplo al vaciar la biblioteca)
                override fun onJsConfirm(
                    view: WebView, url: String, message: String, result: JsResult
                ): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message)
                        .setPositiveButton("Aceptar") { _, _ -> result.confirm() }
                        .setNegativeButton("Cancelar") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }
                        .show()
                    return true
                }
            }
            addJavascriptInterface(Puente(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
        Descargas.alCambiar = { notificarDescargas() }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        futuro = f
        f.addListener({
            try {
                val c = f.get()
                control = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.containsAny(
                                Player.EVENT_MEDIA_ITEM_TRANSITION,
                                Player.EVENT_IS_PLAYING_CHANGED,
                                Player.EVENT_PLAYBACK_STATE_CHANGED
                            )
                        ) avisarWeb(player)
                    }
                })
                avisarWeb(c)
                pendiente?.let { pendiente = null; reproducir(it) }
            } catch (e: Exception) {
                control = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        futuro?.let { MediaController.releaseFuture(it) }
        futuro = null
        control = null
        super.onStop()
    }

    override fun onDestroy() {
        Descargas.alCambiar = null
        web.destroy()
        super.onDestroy()
    }

    // Avisa a la página de qué pista de SU cola suena y si está en marcha (para que su interfaz no se desfase)
    private fun avisarWeb(p: Player) {
        val id = p.currentMediaItem?.mediaId
        val i = if (id == null) -1 else Biblioteca.queue.indexOf(id)
        web.evaluateJavascript("window.__nativeState&&__nativeState($i,${p.isPlaying})", null)
    }

    // Avisa a la página de la miniatura de carátula de un intérprete ("" = sin carátula)
    private fun avisarCaratula(perf: String) {
        val mini = Caratulas.miniatura(applicationContext, perf) ?: ""
        web.evaluateJavascript(
            "window.__cover&&__cover(${JSONObject.quote(perf)},${JSONObject.quote(mini)})", null
        )
    }

    // Muestra en la página el estado de las descargas
    private fun notificarDescargas() {
        web.evaluateJavascript("window.__dl&&__dl(${JSONObject.quote(Descargas.resumen())})", null)
    }

    private fun reproducir(i: Int) {
        val c = control ?: run { pendiente = i; return }
        val ids = Biblioteca.queue
        if (ids.isEmpty()) return
        val idx = i.coerceIn(0, ids.size - 1)
        val enReproductor = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        if (enReproductor == ids) {
            c.seekToDefaultPosition(idx)
        } else {
            c.setMediaItems(ids.map { MediaItem.Builder().setMediaId(it).build() }, idx, 0L)
        }
        c.prepare()
        c.play()
    }

    private fun alternar() {
        val c = control ?: return
        if (c.mediaItemCount == 0) {
            reproducir(Biblioteca.cur.coerceAtLeast(0))
            return
        }
        if (c.playWhenReady) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    /** Funciones que la página puede llamar como AndroidBridge.xxx() */
    inner class Puente {
        @JavascriptInterface
        fun sync(json: String) {
            Biblioteca.actualizar(applicationContext, json)
            runOnUiThread { MusicService.instancia?.alCambiarBiblioteca() }
        }

        @JavascriptInterface
        fun playIndex(i: Int) {
            runOnUiThread { reproducir(i) }
        }

        @JavascriptInterface
        fun toggle() {
            runOnUiThread { alternar() }
        }

        @JavascriptInterface
        fun pause() {
            runOnUiThread { control?.pause() }
        }

        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                control?.let {
                    it.stop()
                    it.clearMediaItems()
                }
            }
        }

        @JavascriptInterface
        fun pickCover(perf: String) {
            runOnUiThread {
                perfPendiente = perf
                selector.launch("image/*")
            }
        }

        @JavascriptInterface
        fun removeCover(perf: String) {
            Caratulas.quitar(applicationContext, perf)
            runOnUiThread {
                MusicService.instancia?.alCambiarBiblioteca(perf)
                avisarCaratula(perf)
            }
        }

        @JavascriptInterface
        fun getCovers(): String = Caratulas.todasMiniaturas(applicationContext)

        // Prueba de audio: reproduce una dirección pegada a mano, sin pasar por la biblioteca
        @JavascriptInterface
        fun playUrl(url: String) {
            runOnUiThread {
                val c = control
                if (c == null) {
                    Toast.makeText(this@MainActivity, "El reproductor no está listo, inténtalo de nuevo", Toast.LENGTH_SHORT).show()
                } else {
                    Diagnostico.urlPrueba = url.trim()
                    val item = MediaItem.Builder()
                        .setMediaId("prueba")
                        .setUri(url.trim())
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Prueba de audio")
                                .setArtist("Dirección pegada a mano")
                                .build()
                        )
                        .build()
                    c.setMediaItem(item)
                    c.prepare()
                    c.play()
                }
            }
        }

        // Descargas para oír sin datos
        @JavascriptInterface
        fun downloadQueue() {
            runOnUiThread {
                val n = Descargas.pedir(Biblioteca.queue.mapNotNull { Biblioteca.lib[it] })
                Toast.makeText(this@MainActivity, "Descargando $n pistas cuando haya Wi-Fi", Toast.LENGTH_SHORT).show()
                notificarDescargas()
            }
        }

        @JavascriptInterface
        fun downloadPerf(perf: String) {
            runOnUiThread {
                val n = Descargas.pedir(Biblioteca.delInterprete(perf))
                Toast.makeText(this@MainActivity, "Descargando $n pistas cuando haya Wi-Fi", Toast.LENGTH_SHORT).show()
                notificarDescargas()
            }
        }

        @JavascriptInterface
        fun clearDownloads() {
            runOnUiThread {
                Descargas.borrarTodo()
                notificarDescargas()
            }
        }

        @JavascriptInterface
        fun downloadStatus() {
            runOnUiThread { notificarDescargas() }
        }

        @JavascriptInterface
        fun getOfflineMode(): Boolean = Descargas.modoSinDatos

        @JavascriptInterface
        fun setOfflineMode(valor: Boolean) {
            Descargas.fijarModoSinDatos(applicationContext, valor)
            runOnUiThread { notificarDescargas() }
        }

        @JavascriptInterface
        fun getQuality(): String = Calidad.elegida

        @JavascriptInterface
        fun setQuality(nivel: String) {
            Calidad.guardar(applicationContext, nivel)
            Diagnostico.reiniciar()
            runOnUiThread { MusicService.instancia?.aplicarCalidad() }
        }

        // Diagnóstico: reúne el estado del reproductor y descarga las listas del stream actual
        @JavascriptInterface
        fun diag() {
            runOnUiThread {
                val estado = MusicService.instancia?.textoDiagnostico()
                    ?: "El servicio de reproducción no está iniciado. Reproduce algo y vuelve a probar."
                val id = control?.currentMediaItem?.mediaId ?: Biblioteca.queue.firstOrNull()
                val url = if (id == "prueba") Diagnostico.urlPrueba
                else (id?.let { Biblioteca.lib[it]?.url } ?: Biblioteca.lib.values.firstOrNull()?.url)
                Thread {
                    val texto = estado + "\n\n" + Diagnostico.listas(url)
                    runOnUiThread {
                        web.evaluateJavascript("window.__diag(${JSONObject.quote(texto)})", null)
                    }
                }.start()
            }
        }
    }
}
