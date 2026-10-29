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
    }

    private val acumulado = LinkedHashMap<String, Acum>()
    private val eventos = ArrayDeque<String>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var errores = 0
    private var ultimoError = ""

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
    fun carga(tipoPista: Int, tipoDato: Int, bytes: Long, ms: Long, formato: String?) {
        val clave = "${nombreDato(tipoDato)} · ${nombreTipo(tipoPista)}"
        val a = acumulado.getOrPut(clave) { Acum() }
        a.cargas++
        a.bytes += bytes
        a.msTotal += ms
        if (ms > a.msMax) a.msMax = ms
        if (!formato.isNullOrEmpty()) a.formato = formato
    }

    @Synchronized
    fun errorDeCarga(texto: String) {
        errores++
        ultimoError = texto
        evento("error de red: $texto")
    }

    /** Estado actual del reproductor. Debe llamarse desde el hilo principal. */
    @Synchronized
    fun estado(p: Player): String {
        val sb = StringBuilder()
        sb.append("== DIAGNÓSTICO ==\n")
        sb.append("Estado: ${nombreEstado(p.playbackState)}  ·  quiere reproducir: ${p.playWhenReady}\n")
        sb.append("Buffer por delante: ${p.totalBufferedDuration / 1000.0} s\n")
        sb.append("Pistas en la lista: ${p.mediaItemCount}\n")

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

        sb.append("\nDescargas desde que arrancó el servicio:\n")
        if (acumulado.isEmpty()) sb.append(" (ninguna)\n")
        for ((clave, a) in acumulado) {
            val mb = a.bytes / 1_048_576.0
            val media = if (a.cargas > 0) a.msTotal / a.cargas else 0
            val kbps = if (a.msTotal > 0) a.bytes * 8 / a.msTotal else 0
            sb.append(" - $clave: ${a.cargas} cargas, ${"%.2f".format(mb)} MB, ")
            sb.append("media $media ms, máx ${a.msMax} ms, ~$kbps kbps efectivos\n")
            if (a.formato.isNotEmpty()) sb.append("     formato: ${a.formato}\n")
        }
        sb.append("Errores de carga: $errores")
        if (ultimoError.isNotEmpty()) sb.append(" (último: $ultimoError)")
        sb.append("\n\nEventos recientes:\n")
        for (e in eventos) sb.append(" $e\n")
        return sb.toString()
    }

    /** Descarga la lista principal y las enlazadas y devuelve su texto. Llamar fuera del hilo principal. */
    fun listas(url: String?): String {
        if (url == null) return "(no hay ningún vídeo en la biblioteca para probar)"
        val sb = StringBuilder()
        val principal = bajar(url)
        sb.append("== LISTA PRINCIPAL (${principal.second} ms) ==\n$url\n\n")
        sb.append(principal.first.take(3500))

        val enlaces = principal.first.lines().flatMap { l ->
            val t = l.trim()
            when {
                t.isEmpty() -> emptyList()
                t.startsWith("#") ->
                    Regex("URI=\"([^\"]+)\"").find(t)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList()
                else -> listOf(t)
            }
        }.distinct().take(4)

        for (e in enlaces) {
            val abs = runCatching { URL(URL(url), e).toString() }.getOrNull() ?: continue
            val r = bajar(abs)
            sb.append("\n\n== LISTA ENLAZADA (${r.second} ms) ==\n$abs\n\n")
            sb.append(r.first.take(1500))
        }
        return sb.toString()
    }

    private fun bajar(u: String): Pair<String, Long> {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            val texto = c.inputStream.use { s ->
                val b = ByteArray(30_000)
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
