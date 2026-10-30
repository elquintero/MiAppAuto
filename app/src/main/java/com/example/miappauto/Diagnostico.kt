package com.example.miappauto

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recoge datos de red y reproducción para entender por qué se corta el sonido. */
object Diagnostico {

    private class Acum {
        var cargas = 0
        var bytes = 0L
        var msTotal = 0L
        var msMax = 0L
        var formato = ""
        var contenidoMs = 0L
    }

    private val acumulado = LinkedHashMap<String, Acum>()
    private val eventos = ArrayDeque<String>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var errores = 0
    private var ultimoError = ""

    /** Dirección pegada a mano en la prueba de audio. */
    @Volatile
    var urlPrueba: String? = null

    @Synchronized
    fun evento(texto: String) {
        eventos.addLast("${hora.format(Date())} $texto")
        while (eventos.size > 14) eventos.removeFirst()
    }

    fun nombreEstado(s: Int): String = when (s) {
        Player.STATE_IDLE -> "parado"
        Player.STATE_BUFFERING -> "CARGANDO"
        Player.STATE_READY -> "listo"
        Player.STATE_ENDED -> "terminado"
        else -> "?$s"
    }

    private fun nombreTipo(t: Int): String = when (t) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_VIDEO -> "vídeo"
        C.TRACK_TYPE_DEFAULT -> "mixto/sin tipo"
        C.TRACK_TYPE_TEXT -> "texto"
        else -> "otro($t)"
    }

    private fun nombreDato(d: Int): String = when (d) {
        C.DATA_TYPE_MEDIA -> "segmentos"
        C.DATA_TYPE_MANIFEST -> "listas"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "inicio"
        else -> "otros($d)"
    }

    @Synchronized
    fun carga(tipoPista: Int, tipoDato: Int, bytes: Long, ms: Long, formato: String?, contenidoMs: Long = 0L) {
        val clave = "${nombreDato(tipoDato)} · ${nombreTipo(tipoPista)}"
        val a = acumulado.getOrPut(clave) { Acum() }
        a.cargas++
        a.bytes += bytes
        a.msTotal += ms
        a.contenidoMs += contenidoMs
        if (ms > a.msMax) a.msMax = ms
        if (!formato.isNullOrEmpty()) a.formato = formato
    }

    @Synchronized
    fun errorDeCarga(texto: String) {
        errores++
        ultimoError = texto
        evento("error de red: $texto")
    }

    @Synchronized
    fun reiniciar() {
        acumulado.clear()
        errores = 0
        ultimoError = ""
        evento("contadores a cero")
    }

    /** Estado actual del reproductor. Debe llamarse desde el hilo principal. */
    @Synchronized
    fun estado(p: Player): String {
        val sb = StringBuilder()
        sb.append("== DIAGNÓSTICO ==\n")
        sb.append("Estado: ${nombreEstado(p.playbackState)}  ·  quiere reproducir: ${p.playWhenReady}\n")
        sb.append("Buffer por delante: ${p.totalBufferedDuration / 1000.0} s\n")
        sb.append("Pistas en la lista: ${p.mediaItemCount}\n")
        sb.append("Calidad elegida: ${Calidad.elegida}  ·  no existentes: ${Calidad.inexistentes()}\n")
        sb.append("Sin conexión: ${Descargas.resumen()}\n")

        sb.append("\nPistas que ofrece el stream:\n")
        var hay = false
        for (g in p.currentTracks.groups) {
            for (i in 0 until g.length) {
                hay = true
                val f = g.getTrackFormat(i)
                sb.append(" - ${nombreTipo(g.type)}")
                sb.append(if (g.isTrackSelected(i)) " [SELECCIONADA]" else " [no]")
                sb.append(": ${f.sampleMimeType ?: "?"} ${f.codecs ?: ""} ")
                sb.append(if (f.bitrate > 0) "${f.bitrate / 1000} kbps " else "")
                sb.append(if (f.width > 0) "${f.width}x${f.height}" else "")
                sb.append("\n")
            }
        }
        if (!hay) sb.append(" (ninguna todavía)\n")

        sb.append("\nDescargas desde que arrancó el servicio o se puso a cero:\n")
        if (acumulado.isEmpty()) sb.append(" (ninguna)\n")
        for ((clave, a) in acumulado) {
            val mb = a.bytes / 1_048_576.0
            val media = if (a.cargas > 0) a.msTotal / a.cargas else 0
            val kbps = if (a.msTotal > 0) a.bytes * 8 / a.msTotal else 0
            sb.append(" - $clave: ${a.cargas} cargas, ${"%.2f".format(mb)} MB, ")
            sb.append("media $media ms, máx ${a.msMax} ms, ~$kbps kbps efectivos\n")
            if (a.contenidoMs > 0 && clave.startsWith("segmentos")) {
                val kb = a.bytes * 8 / a.contenidoMs
                sb.append("     contenido: ${a.contenidoMs / 1000} s de música -> ~$kb kbps, ~${"%.1f".format(kb * 0.0075)} MB por minuto\n")
            }
            if (a.formato.isNotEmpty()) sb.append("     formato: ${a.formato}\n")
        }
        val conVideo = acumulado.any { it.key.contains("segmentos") && it.key.contains("vídeo") && it.value.cargas > 0 }
        val conMixto = acumulado.any { it.key.contains("segmentos") && it.key.contains("mixto") && it.value.cargas > 0 }
        val soloAudio = acumulado.any { it.key.contains("segmentos") && it.key.contains("audio") && it.value.cargas > 0 }
        sb.append("RESUMEN:\n")
        if (conVideo) sb.append(" - hay descargas de VÍDEO aparte del audio\n")
        if (conMixto) sb.append(" - hay trozos con audio y vídeo juntos (no se pueden separar)\n")
        if (soloAudio) sb.append(" - hay descargas de SOLO AUDIO\n")
        if (!conVideo && !conMixto && !soloAudio) sb.append(" - todavía no hay descargas que analizar\n")
        sb.append("Errores de carga: $errores")
        if (ultimoError.isNotEmpty()) sb.append(" (último: $ultimoError)")
        sb.append("\n\nEventos recientes:\n")
        for (e in eventos) sb.append(" $e\n")
        return sb.toString()
    }

    /** Descarga la lista principal, detecta si hay audio independiente y comprueba calidades. Fuera del hilo principal. */
    fun listas(url: String?): String {
        if (url == null) return "(no hay ningún vídeo en la biblioteca para probar)"
        val sb = StringBuilder()
        val principal = bajar(url)
        val texto = principal.first
        val esMaestra = texto.contains("#EXT-X-STREAM-INF") || texto.contains("#EXT-X-MEDIA")
        sb.append("== LISTA PRINCIPAL (${principal.second} ms) ==\n$url\n")
        sb.append(if (esMaestra) "Tipo: LISTA MAESTRA (varias calidades)\n" else "Tipo: lista de una sola calidad\n")
        AudioDirecto.derivar(url)?.let { sb.append("Audio directo que usa la app: $it\n") }
        sb.append("\n")
        if (esMaestra) {
            sb.append(texto.take(4500))
        } else {
            sb.append(texto.take(600)).append("\n...\n").append(texto.takeLast(200))
        }
        sb.append("\n")

        if (esMaestra) {
            val audios = texto.lines()
                .filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=AUDIO") }
                .mapNotNull { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            sb.append("\n== AUDIO INDEPENDIENTE: ")
            sb.append(if (audios.isEmpty()) "NO HAY ==\n" else "SÍ (${audios.size} pistas) ==\n")
            for (a in audios.take(2)) {
                val abs = runCatching { URL(URL(url), a).toString() }.getOrNull() ?: continue
                val r = bajar(abs)
                sb.append("\n$abs\n(${r.second} ms)\n")
                sb.append(r.first.take(800)).append("\n...\n").append(r.first.takeLast(200)).append("\n")
            }
        }

        if (Calidad.nivelDe(url) != null) {
            sb.append("\n== ¿QUÉ CALIDADES EXISTEN? ==\n")
            for (n in Calidad.niveles) {
                val u = Calidad.con(url, n) ?: break
                sb.append("$n: ${sondear(u)}\n")
            }
            val base = url.substringBeforeLast('/') + "/"
            sb.append("\n== ¿HAY LISTA MAESTRA? ==\n")
            for (nombre in listOf("playlist.m3u8", "master.m3u8", "index.m3u8", "hls.m3u8", "main.m3u8", "audio.m3u8")) {
                sb.append("$nombre: ${sondear(base + nombre)}\n")
            }
        }
        return sb.toString()
    }

    private fun sondear(u: String): String {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            val codigo = c.responseCode
            val ms = SystemClock.elapsedRealtime() - t0
            if (codigo == 200) {
                val cabecera = c.inputStream.use { s ->
                    val b = ByteArray(2_500)
                    var n = 0
                    while (n < b.size) {
                        val r = s.read(b, n, b.size - n)
                        if (r < 0) break
                        n += r
                    }
                    String(b, 0, n, Charsets.UTF_8)
                }
                val extras = if (cabecera.startsWith("#EXTM3U"))
                    cabecera.lines()
                        .filter { it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-MEDIA") || it.contains(".m3u8") }
                        .take(12)
                        .joinToString("\n") { "     $it" }
                else ""
                if (extras.isEmpty()) "OK ($ms ms)" else "OK ($ms ms)\n$extras"
            } else {
                "HTTP $codigo ($ms ms)"
            }
        } catch (e: Exception) {
            "error: ${e.javaClass.simpleName}"
        }
    }

    private fun bajar(u: String): Pair<String, Long> {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            val texto = c.inputStream.use { s ->
                val b = ByteArray(200_000)
                var n = 0
                while (n < b.size) {
                    val r = s.read(b, n, b.size - n)
                    if (r < 0) break
                    n += r
                }
                String(b, 0, n, Charsets.UTF_8)
            }
            Pair(texto, SystemClock.elapsedRealtime() - t0)
        } catch (e: Exception) {
            Pair("ERROR: $e", SystemClock.elapsedRealtime() - t0)
        }
    }
}
