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

    // ---------- Romaji ----------

    /** Réglage : préférer les paroles japonaises transcrites en lettres latines (romaji). */
    fun preferRomaji(): Boolean = UserStore.getBool("lyrics_romaji", true)
    fun setPreferRomaji(on: Boolean) = UserStore.putBool("lyrics_romaji", on)

    private val timeTag = Regex("""\[[0-9:.]+\]|<[0-9:.]+>""")

    /** Paroles écrites en japonais (kana ou kanji) plutôt qu'en lettres latines ? */
    fun isJapanese(text: String): Boolean {
        val t = timeTag.replace(text, "")
        var jp = 0
        var latin = 0
        for (c in t) {
            when {
                c in '\u3040'..'\u30FF' || c in '\u4E00'..'\u9FFF' || c in '\uFF66'..'\uFF9F' -> jp++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        // un kana ou kanji vaut à peu près une syllabe, soit 2 à 3 lettres latines
        return jp > 0 && jp * 2.5 > latin
    }

    // petits mots typiques du japonais transcrit, quasi absents de l'anglais
    private val romajiMarkers = setOf("wa", "wo", "ga", "ni", "mo", "yo", "ne", "kimi", "boku", "ore", "watashi", "kara", "made", "dake", "nai", "shite", "kokoro", "sora", "yume", "naka", "hitotsu", "zutto", "itsumo", "koko", "doko")
    private val englishMarkers = setOf("the", "you", "and", "my", "is", "are", "of", "in", "me", "your", "it", "we", "be", "that", "this", "with")

    /** Paroles en lettres latines qui sont bien du japonais transcrit (et pas une traduction anglaise) ? */
    fun looksRomaji(text: String): Boolean {
        if (isJapanese(text)) return false
        val words = CoverSearch.norm(timeTag.replace(text, " ")).split(' ')
        val jp = words.count { it in romajiMarkers }
        val en = words.count { it in englishMarkers }
        return jp >= 6 && jp * 2 >= en
    }

    private val romajiWords = setOf("romanized", "romanised", "romaji", "romanization", "romanisation")

    private fun tokens(t: String): Set<String> =
        CoverSearch.norm(t).split(' ').filter { it.length > 1 || it.firstOrNull()?.isDigit() == true }.toSet()

    /**
     * Renvoie les paroles trouvées (format .lrc si synchronisées), ou null.
     * Essaie plusieurs formulations : nom nettoyé, chanson officielle retrouvée sur Deezer
     * (ou AnimeThemes pour les openings), puis titre raccourci.
     */
    suspend fun find(original: Song): String? = withContext(Dispatchers.IO) {
        val key = TagCleaner.searchKey(original)
        val song = original.copy(
            title = key.title,
            artist = key.artist ?: original.artist,
            artists = key.artist?.let { TagCleaner.artists(it, key.title) } ?: original.artists,
        )
        val durationSec = (song.duration / 1000).toInt()
        val artistRaw = song.artists.firstOrNull()?.takeUnless { it == "Artiste inconnu" }
        val artist = artistRaw?.let { CoverSearch.searchWords(it).joinToString(" ") }?.ifBlank { null }
        val titleWords = CoverSearch.searchWords(featPart.replace(song.title, ""))
        val title = titleWords.joinToString(" ")
        val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
        val reference = HashSet(tokens("${song.title} ${song.artist} $fileName"))

        val romaji = preferRomaji()
        fun tryResults(results: List<JSONObject>, extraRef: Set<String> = emptySet(), latinOnly: Boolean = false): String? {
            val ref = reference + extraRef
            var best: JSONObject? = null
            var bestScore = 0.0
            for (o in results) {
                if (o.optBoolean("instrumental", false)) continue
                val synced = o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") && it.isNotBlank() }
                val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() }
                if (synced == null && plain == null) continue
                val lyricsText = synced ?: plain.orEmpty()
                val japanese = isJapanese(lyricsText)
                val transcribed = romaji && !japanese && looksRomaji(lyricsText)
                if (latinOnly && !transcribed) continue
                val tt = tokens(o.optString("trackName")).filterNot { it in setOf("feat", "ft", "remix", "version", "edit", "radio") || it in romajiWords }
                if (tt.isEmpty()) continue
                val titleRatio = tt.count { it in ref }.toDouble() / tt.size
                val at = tokens(o.optString("artistName"))
                val artistOk = at.isNotEmpty() && at.count { it in ref } >= (at.size + 1) / 2
                if (titleRatio < 0.6 || (!artistOk && titleRatio < 0.99)) continue
                val d = o.optDouble("duration", -1.0)
                val diff = if (d > 0 && durationSec > 0) kotlin.math.abs(d - durationSec) else 99.0
                var score = titleRatio * 4 + (if (artistOk) 3 else 0) + (if (synced != null) 2 else 0)
                // version transcrite en romaji préférée à l'écriture japonaise
                if (transcribed) score += 4
                score += when {
                    diff <= 3 -> 3.0
                    diff <= 8 -> 2.0
                    diff <= 20 -> 1.0
                    else -> 0.0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = o
                }
            }
            val b = best ?: return null
            val synced = b.optString("syncedLyrics").takeIf { !b.isNull("syncedLyrics") && it.isNotBlank() }
            val plain = b.optString("plainLyrics").takeIf { !b.isNull("plainLyrics") && it.isNotBlank() }
            val d = b.optDouble("duration", -1.0)
            // paroles synchronisées seulement si la durée correspond (sinon le défilement serait décalé)
            val syncOk = d <= 0 || durationSec <= 0 || kotlin.math.abs(d - durationSec) <= 8
            return if (synced != null && (syncOk || plain == null)) synced else plain ?: synced
        }

        var identity: CoverSearch.Identity? = null
        val found: String? = run {
        // 1. titre et artiste nettoyés
        if (artist != null && title.isNotBlank()) {
            tryResults(search("track_name=${enc(title)}&artist_name=${enc(artist)}"))?.let { return@run it }
        }
        if (title.isNotBlank()) {
            tryResults(search("q=${enc(listOfNotNull(artist, title).joinToString(" "))}"))?.let { return@run it }
        }
        // 2. chanson officielle retrouvée (Deezer, iTunes ou AnimeThemes pour les openings)
        val id = runCatching { CoverSearch.identify(song) }.getOrNull()
        identity = id
        if (id != null && id.title.isNotBlank()) {
            val extra = tokens("${id.title} ${id.artist}")
            val idTitle = featPart.replace(id.title, "").trim()
            if (id.artist.isNotBlank()) {
                tryResults(search("track_name=${enc(idTitle)}&artist_name=${enc(id.artist)}"), extra)?.let { return@run it }
            }
            tryResults(search("q=${enc("${id.artist} $idTitle".trim())}"), extra)?.let { return@run it }
        }
        // 3. titre raccourci (les derniers mots sont souvent parasites)
        var shorter = titleWords
        while (shorter.size > 2) {
            shorter = shorter.dropLast(1)
            tryResults(search("q=${enc(listOfNotNull(artist, shorter.joinToString(" ")).joinToString(" "))}"))?.let { return@run it }
        }
        null
        }

        // 4. paroles en japonais : on cherche une version déjà transcrite en romaji sur LRCLIB
        if (found != null && romaji && isJapanese(found)) {
            val id = identity ?: runCatching { CoverSearch.identify(song) }.getOrNull()
            val extra = id?.let { tokens("${it.title} ${it.artist}") }.orEmpty()
            val bases = LinkedHashSet<String>()
            id?.takeIf { it.title.isNotBlank() }?.let { bases += "${it.artist} ${featPart.replace(it.title, "").trim()}".trim() }
            if (title.isNotBlank()) bases += listOfNotNull(artist, title).joinToString(" ")
            for (b in bases.take(2)) {
                for (suffix in listOf("", " romanized", " romaji")) {
                    tryResults(search("q=${enc(b + suffix)}"), extra, latinOnly = true)?.let { return@withContext it }
                }
            }
        }
        found
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

    fun googleUrl(original: Song): String {
        val key = TagCleaner.searchKey(original)
        val song = original.copy(title = key.title)
        val artist = key.artist ?: original.artists.firstOrNull()?.takeUnless { it == "Artiste inconnu" }
        return "https://www.google.com/search?q=" + enc(listOfNotNull(featPart.replace(song.title, ""), artist, "paroles").joinToString(" "))
    }
}
