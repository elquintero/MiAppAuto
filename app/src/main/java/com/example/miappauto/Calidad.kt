package com.example.miappauto

import android.content.Context

/**
 * Los vídeos del concurso se guardan en varias calidades con nombres como ".../hls/2160p.m3u8".
 * Como solo interesa el sonido, se cambia el nombre de la calidad en la dirección para pedir una versión
 * mucho más ligera (la 2160p pesa unos 18 Mbps; la 360p, bastante menos de 1 Mbps).
 */
object Calidad {

    val niveles = listOf("360p", "480p", "720p", "1080p", "2160p")
    const val ORIGINAL = "original"

    private val patron = Regex("/(\\d{3,4}p)\\.m3u8")
    private val noExisten = HashSet<String>()

    @Volatile
    var elegida: String = "360p"
        private set

    fun cargar(ctx: Context) {
        val g = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getString("calidad", null)
        if (g != null && (g == ORIGINAL || g in niveles)) elegida = g
    }

    fun guardar(ctx: Context, nivel: String) {
        if (nivel != ORIGINAL && nivel !in niveles) return
        elegida = nivel
        synchronized(noExisten) { noExisten.clear() }
        ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
            .edit().putString("calidad", nivel).apply()
    }

    fun nivelDe(url: String): String? = patron.find(url)?.groupValues?.get(1)

    fun con(url: String, nivel: String): String? =
        if (patron.containsMatchIn(url)) patron.replace(url) { "/$nivel.m3u8" } else null

    fun marcarInexistente(nivel: String) {
        synchronized(noExisten) { noExisten.add(nivel) }
    }

    fun inexistentes(): String =
        synchronized(noExisten) { noExisten.joinToString(", ").ifEmpty { "ninguna" } }

    /** Devuelve la dirección con la calidad elegida (o la más cercana por encima que exista). */
    fun aplicar(url: String): String {
        if (elegida == ORIGINAL) return url
        val orig = nivelDe(url) ?: return url
        val iOrig = niveles.indexOf(orig)
        if (iOrig < 0) return url
        val desde = niveles.indexOf(elegida).coerceAtLeast(0)
        val nivel = synchronized(noExisten) {
            niveles.drop(desde).firstOrNull { it !in noExisten && niveles.indexOf(it) <= iOrig }
        } ?: orig
        return if (nivel == orig) url else (con(url, nivel) ?: url)
    }
}
