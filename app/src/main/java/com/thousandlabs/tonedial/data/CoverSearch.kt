package com.thousandlabs.tonedial.data

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Recherche de pochettes tolérante aux noms de fichiers abîmés.
 *
 * 1. Nettoyage : identifiants YouTube, suites de lettres et chiffres, (1), 320kbps, lyrics…
 * 2. Deezer d'abord (recherche souple), iTunes ensuite.
 * 3. Validation par les mots du titre, l'artiste et surtout la durée du morceau.
 * 4. Formulations de plus en plus courtes si rien n'est trouvé.
 * 5. Openings et endings d'anime : AnimeThemes retrouve la vraie chanson, et l'affiche sert de secours.
 */
object CoverSearch {

    data class Found(val imageUrl: String, val source: String)

    // ------------------------------------------------------------------
    // Nettoyage
    // ------------------------------------------------------------------

    private val junkWords = setOf(
        "lyrics", "lyric", "paroles", "audio", "official", "officiel", "video", "vidéo", "clip", "hd", "hq", "4k",
        "mp3", "m4a", "kbps", "320", "128", "256", "192", "copy", "copie", "visualizer", "visualiser", "mv",
        "full", "song", "download", "free", "www", "com", "remastered", "remaster", "explicit", "clean",
    )

    /** Un mot qui ressemble à un code généré (identifiant de vidéo, empreinte, suffixe aléatoire). */
    private fun looksRandom(token: String): Boolean {
        if (token.length < 5) return false
        val letters = token.count { it.isLetter() }
        val digits = token.count { it.isDigit() }
        val hasUpper = token.any { it.isUpperCase() }
        val hasLower = token.any { it.isLowerCase() }
        return when {
            // identifiant YouTube : 11 caractères, lettres + chiffres ou casse mélangée
            token.length == 11 && token.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                (digits > 0 || (hasUpper && hasLower && token.drop(1).any { it.isUpperCase() })) -> true
            // empreinte hexadécimale
            token.length >= 8 && token.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } && digits > 0 -> true
            // mélange lettres + chiffres (au moins 2 de chaque)
            letters >= 2 && digits >= 2 -> true
            else -> false
        }
    }

    /** Mots utiles pour la recherche, dans l'ordre. */
    fun searchWords(text: String): List<String> {
        val t = text
            .replace(Regex("""[_]+"""), " ")
            .replace(Regex("""[(\[{][^)\]}]*[)\]}]"""), " ") // contenu entre parenthèses
            .replace(Regex("""\b(?:feat|ft|featuring)\b\.?.*$""", RegexOption.IGNORE_CASE), " ")
        return t.split(Regex("""[\s\-–—|.,:;!?"«»]+"""))
            .map { it.trim('\'', '’') }
            .filter { it.isNotBlank() }
            .filterNot { looksRandom(it) }
            .filterNot { it.lowercase() in junkWords }
            .filterNot { it.length == 1 && it[0].isDigit() }
    }

    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[(\[].*?[)\]]"""), " ")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    private fun tokens(s: String): Set<String> = norm(s).split(' ').filter { it.length > 1 || it.firstOrNull()?.isDigit() == true }.toSet()

    // ------------------------------------------------------------------
    // Recherche
    // ------------------------------------------------------------------

    private data class Candidate(val title: String, val artist: String, val durationSec: Int, val image: String)

    private val animePattern = Regex(
        """^(.*?)\s*(?:-\s*)?\b(opening|op|ending|ed)\s*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Chanson officielle retrouvée : titre, artiste, durée et pochette. */
    data class Identity(val title: String, val artist: String, val durationSec: Int, val image: String?, val source: String)

    suspend fun find(song: Song): Found? =
        identify(song)?.let { id -> id.image?.let { Found(it, id.source) } }

    /**
     * Retrouve la chanson officielle derrière un nom de fichier approximatif.
     * Sert aux pochettes et à la recherche de paroles.
     */
    suspend fun identify(song: Song): Identity? {
        val artist = song.artist.takeUnless { it == "Artiste inconnu" }
        val full = listOfNotNull(artist, song.title).joinToString(" ")
        val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
        val durationSec = (song.duration / 1000).toInt()

        // Openings / endings d'anime (ex. « One Piece Opening 25 - The Peak »)
        val animeMatch = animePattern.find(full) ?: animePattern.find(fileName)
        if (animeMatch != null) {
            findAnime(animeMatch.groupValues[1], animeMatch.groupValues[2], animeMatch.groupValues[3].toInt(), song)?.let { return it }
        }

        // Mots de référence pour valider les résultats (titre, artiste et nom du fichier)
        val reference = tokens(full + " " + fileName)
        val titleWords = searchWords(song.title)
        val artistWords = artist?.let { searchWords(it) } ?: emptyList()

        val queries = LinkedHashSet<String>()
        if (artistWords.isNotEmpty() && titleWords.isNotEmpty()) queries += (artistWords + titleWords).joinToString(" ")
        if (titleWords.isNotEmpty()) queries += titleWords.joinToString(" ")
        // formulations raccourcies : on retire les derniers mots, souvent parasites
        var shortened = titleWords
        while (shortened.size > 2) {
            shortened = shortened.dropLast(1)
            queries += (artistWords + shortened).joinToString(" ")
        }
        if (titleWords.isEmpty()) queries += searchWords(fileName).joinToString(" ")

        for (q in queries.take(5)) {
            if (q.isBlank()) continue
            val best = pick(deezer(q), song, reference, durationSec)
            if (best != null) return Identity(best.title, best.artist, best.durationSec, best.image, "Deezer")
            delay(250)
        }
        // iTunes en dernier recours, une seule requête (limite de 20 par minute)
        queries.firstOrNull()?.let { q ->
            val best = pick(itunes(q), song, reference, durationSec)
            if (best != null) return Identity(best.title, best.artist, best.durationSec, best.image, "iTunes")
        }
        return null
    }

    /** Choisit le meilleur résultat, ou aucun si rien n'est assez sûr. */
    private fun pick(results: List<Candidate>, song: Song, reference: Set<String>, durationSec: Int, ignoreDuration: Boolean = false): Candidate? {
        var best: Candidate? = null
        var bestScore = 0.0
        for (c in results) {
            val ct = tokens(c.title).filterNot { it in setOf("feat", "ft", "remix", "version", "edit", "radio") }
            if (ct.isEmpty()) continue
            val titleRatio = ct.count { it in reference }.toDouble() / ct.size
            val artistTokens = tokens(c.artist)
            val artistOk = artistTokens.isNotEmpty() && artistTokens.count { it in reference } >= (artistTokens.size + 1) / 2
            val diff = if (durationSec > 0 && c.durationSec > 0) kotlin.math.abs(durationSec - c.durationSec) else -1
            val durationClose = diff in 0..4
            val durationFar = diff > 25 && !ignoreDuration

            // Un clip vidéo dure souvent plus longtemps que le titre officiel (intro, fin, sketch) :
            // avec le bon artiste et le titre complet, on accepte même si la durée est éloignée.
            val strong = titleRatio >= 0.99 && artistOk
            val accepted = strong || (!durationFar && (
                (titleRatio >= 0.75 && (artistOk || durationClose || ignoreDuration)) ||
                    (titleRatio >= 0.5 && artistOk && (durationClose || ignoreDuration))
                ))
            if (!accepted) continue
            val score = titleRatio * 4 + (if (artistOk) 3 else 0) + when {
                diff in 0..2 -> 3.0
                diff in 3..4 -> 2.0
                diff in 5..10 -> 1.0
                else -> 0.0
            }
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun deezer(q: String): List<Candidate> {
        val json = getJson("https://api.deezer.com/search?limit=12&q=" + enc(q)) ?: return emptyList()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        // limite de requêtes atteinte : on patiente puis on réessaie une fois
        if (o.optJSONObject("error")?.optInt("code") == 4) {
            delay(5000)
            return deezerOnce(q)
        }
        return parseDeezer(o)
    }

    private fun deezerOnce(q: String): List<Candidate> {
        val json = getJsonBlocking("https://api.deezer.com/search?limit=12&q=" + enc(q)) ?: return emptyList()
        return runCatching { parseDeezer(JSONObject(json)) }.getOrDefault(emptyList())
    }

    private fun parseDeezer(o: JSONObject): List<Candidate> {
        val data = o.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val r = data.getJSONObject(i)
            val album = r.optJSONObject("album") ?: return@mapNotNull null
            val img = album.optString("cover_xl").ifBlank { album.optString("cover_big") }
            if (img.isBlank()) return@mapNotNull null
            Candidate(
                r.optString("title_short").ifBlank { r.optString("title") },
                r.optJSONObject("artist")?.optString("name").orEmpty(),
                r.optInt("duration"),
                img,
            )
        }
    }

    private var lastItunes = 0L

    private suspend fun itunes(q: String): List<Candidate> {
        // au moins 3 secondes entre deux requêtes iTunes
        val wait = 3100 - (System.currentTimeMillis() - lastItunes)
        if (wait > 0) delay(wait)
        lastItunes = System.currentTimeMillis()
        var json = getJson("https://itunes.apple.com/search?media=music&entity=song&limit=10&term=" + enc(q))
        if (json == null) {
            delay(6000)
            lastItunes = System.currentTimeMillis()
            json = getJson("https://itunes.apple.com/search?media=music&entity=song&limit=10&term=" + enc(q)) ?: return emptyList()
        }
        val results = runCatching { JSONObject(json).getJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.getJSONObject(i)
            val art = r.optString("artworkUrl100")
            if (art.isBlank()) return@mapNotNull null
            Candidate(
                r.optString("trackName"),
                r.optString("artistName"),
                (r.optLong("trackTimeMillis") / 1000).toInt(),
                art.replace("100x100bb", "600x600bb"),
            )
        }
    }

    /** Openings et endings : la vraie chanson via AnimeThemes, puis sa pochette ou l'affiche de l'anime. */
    private suspend fun findAnime(animeName: String, kind: String, number: Int, song: Song): Identity? {
        val name = searchWords(animeName).joinToString(" ")
        if (name.isBlank()) return null
        val type = if (kind.lowercase().startsWith("o")) "OP" else "ED"
        // plusieurs formes d'adresse selon la version de l'API AnimeThemes
        val urls = listOf(
            "https://api.animethemes.moe/search?q=${enc(name)}&fields[search]=anime&include[anime]=images,animethemes.song.artists",
            "https://api.animethemes.moe/anime?q=${enc(name)}&include=images,animethemes.song.artists&page[size]=3",
            "https://api.animethemes.moe/api/anime?filter[search]=${enc(name)}&include=images,animethemes.song.artists,animethemes.song.performances.artist&page[size]=3",
        )
        var anime: JSONObject? = null
        for (u in urls) {
            val json = getJson(u) ?: continue
            val root = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val list = root.optJSONObject("search")?.optJSONArray("anime") ?: root.optJSONArray("anime")
            if (list != null && list.length() > 0) {
                anime = list.getJSONObject(0)
                break
            }
        }
        val animeObj = anime ?: return null

        var poster: String? = null
        animeObj.optJSONArray("images")?.let { imgs ->
            for (i in 0 until imgs.length()) {
                val im = imgs.getJSONObject(i)
                if (im.optString("facet").contains("Large", true)) poster = im.optString("link")
                if (poster == null) poster = im.optString("link").ifBlank { null }
            }
        }

        var songTitle: String? = null
        var songArtist: String? = null
        animeObj.optJSONArray("animethemes")?.let { themes ->
            for (i in 0 until themes.length()) {
                val t = themes.getJSONObject(i)
                val seq = t.optInt("sequence", 1).let { if (it == 0) 1 else it }
                if (t.optString("type").equals(type, true) && seq == number) {
                    val s = t.optJSONObject("song")
                    songTitle = s?.optString("title")
                    songArtist = s?.optJSONArray("artists")?.let { a -> if (a.length() > 0) a.getJSONObject(0).optString("name") else null }
                        ?: s?.optJSONArray("performances")?.let { a ->
                            if (a.length() > 0) a.getJSONObject(0).optJSONObject("artist")?.optString("name") else null
                        }
                    break
                }
            }
        }

        // pochette officielle de la chanson (la durée varie entre version TV et complète : on l'ignore)
        val st = songTitle
        val sa = songArtist
        if (!st.isNullOrBlank()) {
            val q = listOfNotNull(sa, st).joinToString(" ")
            val reference = tokens("$q ${song.title} ${song.artist}")
            pick(deezer(q), song, reference, 0, ignoreDuration = true)?.let {
                return Identity(st, sa ?: it.artist, it.durationSec, it.image, "Deezer")
            }
            return Identity(st, sa.orEmpty(), 0, poster, "AnimeThemes")
        }
        return poster?.let { Identity("", "", 0, it, "AnimeThemes") }
    }

    // ------------------------------------------------------------------
    // Réseau
    // ------------------------------------------------------------------

    private suspend fun getJson(url: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { getJsonBlocking(url) }

    private fun getJsonBlocking(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "ToneDial (https://github.com/ThousandLabs/Tone-Dial)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun download(url: String): ByteArray? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("User-Agent", "ToneDial (https://github.com/ThousandLabs/Tone-Dial)")
        }
        try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
