package com.example.miappauto

import android.net.Uri

/**
 * Las direcciones de la emisión en directo (redcdn.pl) con horas de inicio y fin tienen una pista de
 * SOLO AUDIO aparte. Si una pista de la biblioteca es de ese tipo, se pide directamente esa pista de
 * audio en lugar de la lista con vídeo. Si el servidor la rechaza, se vuelve a la dirección original.
 */
object AudioDirecto {

    private val fallidos = HashSet<String>()

    @Synchronized
    fun marcarFallo(k: String) {
        fallidos.add(k)
    }

    @Synchronized
    fun urlPara(v: Biblioteca.Video): String? =
        if (v.k in fallidos) null else derivar(v.url)

    /** Devuelve la dirección de la pista de audio, o null si esta dirección no es de ese tipo. */
    fun derivar(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith("redcdn.pl") || !url.contains("/livehls/")) return null
        if (uri.getQueryParameter("audioId") != null) return null // ya es la pista de audio
        val ini = uri.getQueryParameter("startTime") ?: return null
        val fin = uri.getQueryParameter("stopTime") ?: return null
        val lang = uri.getQueryParameter("lang") ?: "pol"
        val base = url.substringBefore('?')
        return "$base?bitrate=320000&audioId=1&lang=$lang&startTime=$ini&stopTime=$fin&renditions"
    }
}
