package com.thousandlabs.tonedial.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Recherche de paroles sur LRCLIB (lrclib.net), base de paroles libre et gratuite,
 * souvent avec les minutages pour l'affichage synchronisé.
 */
object LyricsOnline {

    private const val BASE = "https://lrclib.net/api"
    private val featPart = Regex("""\s*[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

    /** Renvoie les paroles trouvées (format .lrc si synchronisées), ou null. */
    suspend fun find(song: Song): String? = withContext(Dispatchers.IO) {
        val title = featPart.replace(song.title, "").trim()
        val artist = song.artists.firstOrNull()?.takeUnless { it == "Artiste inconnu" }
        val durationSec = song.duration / 1000.0

        val results = ArrayList<JSONObject>()
        if (artist != null) {
            results += search("track_name=${enc(title)}&artist_name=${enc(artist)}")
        }
        if (results.isEmpty()) {
            results += search("q=${enc(listOfNotNull(artist, title).joinToString(" "))}")
        }
        if (results.isEmpty()) return@withContext null

        fun score(o: JSONObject): Double {
            var s = 0.0
            if (!o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()) s += 10
            if (!o.isNull("plainLyrics") && o.optString("plainLyrics").isNotBlank()) s += 3
            val d = o.optDouble("duration", -1.0)
            if (d > 0 && durationSec > 0) s -= kotlin.math.min(10.0, kotlin.math.abs(d - durationSec) / 2)
            if (o.optBoolean("instrumental", false)) s -= 20
            return s
        }

        val best = results.maxByOrNull(::score) ?: return@withContext null
        val synced = best.optString("syncedLyrics").takeIf { !best.isNull("syncedLyrics") && it.isNotBlank() }
        val plain = best.optString("plainLyrics").takeIf { !best.isNull("plainLyrics") && it.isNotBlank() }
        synced ?: plain
    }

    private fun search(query: String): List<JSONObject> = runCatching {
        val conn = (URL("$BASE/search?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "ToneDial (https://github.com/ThousandLabs/Tone-Dial)")
        }
        try {
            if (conn.responseCode != 200) return@runCatching emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until minOf(arr.length(), 20)).map { arr.getJSONObject(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun googleUrl(song: Song): String {
        val artist = song.artists.firstOrNull()?.takeUnless { it == "Artiste inconnu" }
        return "https://www.google.com/search?q=" + enc(listOfNotNull(featPart.replace(song.title, ""), artist, "paroles").joinToString(" "))
    }
}
