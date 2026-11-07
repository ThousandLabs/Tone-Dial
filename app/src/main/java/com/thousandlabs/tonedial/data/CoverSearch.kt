package com.thousandlabs.tonedial.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Recherche de pochettes tolérante aux noms de fichiers abîmés.
 *
 * 1. Nettoyage : identifiants YouTube, suites de lettres et chiffres, (1), 320kbps, lyrics…
 * 2. Deezer d'abord (recherche souple), iTunes ensuite.
 * 3. Validation par les mots du titre, l'artiste et surtout la durée du morceau.
 * 4. Formulations de plus en plus courtes si rien n'est trouvé.
 * 5. Openings et endings d'anime : liste intégrée (One Piece, Black Clover), puis MyAnimeList et
 *    AnimeThemes ; l'affiche de l'anime sert de secours.
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
    suspend fun identify(original: Song): Identity? {
        // recherche sur le nom nettoyé, même si l'affichage garde le nom d'origine
        val key = TagCleaner.searchKey(original)
        val song = original.copy(
            title = key.title,
            artist = key.artist ?: original.artist,
            artists = key.artist?.let { TagCleaner.artists(it, key.title) } ?: original.artists,
        )
        val artist = song.artist.takeUnless { it == "Artiste inconnu" }
        val full = listOfNotNull(artist, song.title).joinToString(" ")
        val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
        val durationSec = (song.duration / 1000).toInt()

        // Openings / endings d'anime (ex. « One Piece Opening 25 - The Peak »)
        // d'abord le nom tel que l'utilisateur l'a écrit (évite « One Piece One Piece Opening 25 »)
        val rawTitle = TagCleaner.prepare(original.title)
        val animeMatch = animePattern.find(rawTitle) ?: animePattern.find(TagCleaner.prepare(fileName)) ?: animePattern.find(full)
        if (animeMatch != null) {
            // titre de la chanson s'il est donné après le numéro (« … Opening 25 - The Peak »)
            val src = animeMatch.value.let { m -> listOf(rawTitle, full).firstOrNull { it.contains(m) } ?: rawTitle }
            val after = src.substringAfter(animeMatch.value, "").trim(' ', '-', '–', '—', ':', '|').ifBlank { null }
            findAnime(animeMatch.groupValues[1], animeMatch.groupValues[2], animeMatch.groupValues[3].toInt(), song, after)?.let { return it }
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
    // Images d'artistes
    // ------------------------------------------------------------------

    /** Photo d'un artiste ou d'un groupe (Deezer), ou affiche de l'anime pour les openings. */
    suspend fun artistImage(name: String, isAnime: Boolean): String? {
        val target = norm(name)
        if (target.isBlank()) return null
        // groupe d'openings (« One Piece ») : l'affiche de l'anime, pas un artiste homonyme
        if (isAnime) return animePosterAny(name)
        val json = getJson("https://api.deezer.com/search/artist?limit=5&q=" + enc(name))
        val data = json?.let { runCatching { JSONObject(it).optJSONArray("data") }.getOrNull() }
        if (data != null) {
            for (i in 0 until data.length()) {
                val a = data.getJSONObject(i)
                val n = norm(a.optString("name"))
                val match = n == target || (target.length >= 4 && (n.replace(" ", "") == target.replace(" ", "")))
                if (!match) continue
                val pic = a.optString("picture_xl").ifBlank { a.optString("picture_big") }
                // Deezer renvoie une silhouette vide quand l'artiste n'a pas de photo
                if (pic.isBlank() || pic.contains("/artist//")) continue
                return pic
            }
        }
        return null
    }

    /** Affiche d'un anime (AnimeThemes). */
    private suspend fun animePoster(animeName: String): String? {
        val name = searchWords(animeName).joinToString(" ").ifBlank { return null }
        val urls = listOf(
            "https://api.animethemes.moe/search?q=${enc(name)}&fields[search]=anime&include[anime]=images",
            "https://api.animethemes.moe/anime?q=${enc(name)}&include=images&page[size]=1",
            "https://api.animethemes.moe/api/anime?filter[search]=${enc(name)}&include=images&page[size]=1",
        )
        for (u in urls) {
            val json = getJson(u) ?: continue
            val root = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val list = root.optJSONObject("search")?.optJSONArray("anime") ?: root.optJSONArray("anime") ?: continue
            if (list.length() == 0) continue
            val imgs = list.getJSONObject(0).optJSONArray("images") ?: continue
            var any: String? = null
            for (i in 0 until imgs.length()) {
                val im = imgs.getJSONObject(i)
                val link = im.optString("link").ifBlank { null } ?: continue
                if (im.optString("facet").contains("Large", true)) return link
                if (any == null) any = link
            }
            if (any != null) return any
        }
        return null
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
    // ------------------------------------------------------------------
    // Openings et endings : MyAnimeList (Jikan) d'abord, AnimeThemes en secours
    // ------------------------------------------------------------------

    private data class AnimeInfo(val malId: Int, val poster: String?)

    // MyAnimeList (Jikan) accepte environ une requête par seconde : au-delà il répond « 429 » et
    // l'identification échoue. Les requêtes passent donc une par une, espacées, avec nouvel essai,
    // et les réponses sont gardées en mémoire (31 openings de One Piece = 2 requêtes, pas 62).
    private val jikanLock = Mutex()
    private var lastJikan = 0L
    private val animeCache = ConcurrentHashMap<String, AnimeInfo>()
    private val themesCache = ConcurrentHashMap<Int, String>()

    private suspend fun jikan(url: String): String? = jikanLock.withLock {
        var body: String? = null
        for (attempt in 0 until 3) {
            val wait = 1100 - (System.currentTimeMillis() - lastJikan)
            if (wait > 0) delay(wait)
            lastJikan = System.currentTimeMillis()
            val (code, text) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { getWithCode(url) }
            if (code == 200 && text != null) { body = text; break }
            // saturé, erreur serveur ou réseau : on patiente puis on réessaie ; autre erreur : abandon
            if (code != 429 && code !in 500..599 && code != -1) break
            delay(2500L * (attempt + 1))
        }
        body
    }

    /** L'anime le plus connu correspondant au nom (ex. « One Piece » → la série TV). */
    private suspend fun jikanAnime(name: String): AnimeInfo? {
        val cacheKey = norm(name)
        animeCache[cacheKey]?.let { return it }
        val json = jikan("https://api.jikan.moe/v4/anime?limit=6&order_by=members&sort=desc&q=" + enc(name)) ?: return null
        val data = runCatching { JSONObject(json).getJSONArray("data") }.getOrNull() ?: return null
        if (data.length() == 0) return null
        val target = norm(name)
        var chosen: JSONObject? = null
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i)
            val titles = listOf(a.optString("title"), a.optString("title_english")).map { norm(it) }
            if (titles.any { it == target }) { chosen = a; break }
        }
        val a = chosen ?: data.getJSONObject(0)
        val poster = a.optJSONObject("images")?.optJSONObject("jpg")?.let { j ->
            j.optString("large_image_url").ifBlank { j.optString("image_url") }
        }?.ifBlank { null }
        return AnimeInfo(a.optInt("mal_id"), poster).also { if (it.malId > 0) animeCache[cacheKey] = it }
    }

    private suspend fun jikanThemes(malId: Int): String? =
        themesCache[malId] ?: jikan("https://api.jikan.moe/v4/anime/$malId/themes")?.also { themesCache[malId] = it }

    // « 25: "The Peak" by Artiste (eps 1100-) » ; guillemets droits ou typographiques, numéro facultatif
    private val themeEntry = Regex("""^\s*(?:#?(\d+)\s*[:.)]\s*)?["“«]?(.+?)["”»]?\s+by\s+(.+?)\s*(?:\((?:eps?|episodes?|ep)\b.*)?$""", RegexOption.IGNORE_CASE)

    /** Retire la version japonaise entre parenthèses : « We Are! (ウィーアー!) » → « We Are! ». */
    private fun stripNative(s: String): String =
        s.replace(Regex("""\s*\([^)]*[^\x00-\x7F][^)]*\)"""), "").trim().ifBlank { s.trim() }

    private suspend fun findAnime(animeName: String, kind: String, number: Int, song: Song, knownTitle: String? = null): Identity? {
        val name = searchWords(animeName).joinToString(" ")
        if (name.isBlank()) return null
        val isOpening = kind.lowercase().startsWith("o")

        // 1. liste intégrée (One Piece, Black Clover…) : sûre et sans dépendre de MyAnimeList
        AnimeThemesLocal.find(name, isOpening, number, knownTitle)?.let { local ->
            val shortTitle = local.title.substringBefore(" (")
            val artistWords = tokens(local.artist)
            val reference = tokens("${local.title} ${local.artist} ${song.title} ${song.artist}")
            // pochette officielle, seulement si Deezer propose bien le même interprète
            val cover = runCatching {
                pick(deezer("${local.artist} $shortTitle").filter { c -> tokens(c.artist).any { it in artistWords } }, song, reference, 0, ignoreDuration = true)
            }.getOrNull()
            if (cover != null) return Identity(local.title, local.artist, cover.durationSec, cover.image, "liste intégrée, pochette Deezer")
            val poster = runCatching { jikanAnime(name)?.poster }.getOrNull()
            return Identity(local.title, local.artist, 0, poster, "liste intégrée")
        }

        // 2. MyAnimeList pour les autres animes
        val info = runCatching { jikanAnime(name) }.getOrNull()
        if (info != null && info.malId > 0) {
            val json = jikanThemes(info.malId)
            val list = json?.let { runCatching { JSONObject(it).getJSONObject("data").optJSONArray(if (isOpening) "openings" else "endings") }.getOrNull() }
            var title: String? = null
            var artist: String? = null
            if (list != null && list.length() > 0) {
                val entries = (0 until list.length()).map { list.getString(it) }
                val parsed = entries.mapNotNull { e -> themeEntry.find(e)?.let { m -> Triple(m.groupValues[1].toIntOrNull(), m.groupValues[2], m.groupValues[3]) } }
                // le titre écrit par l'utilisateur (« The Peak ») est le plus sûr, puis le numéro
                val known = knownTitle?.let { tokens(it) }.orEmpty()
                val byTitle = if (known.isEmpty()) null else parsed.firstOrNull { p ->
                    val t = tokens(stripNative(p.second))
                    t.isNotEmpty() && known.isNotEmpty() && (t.count { it in known }.toDouble() / t.size) >= 0.75
                }
                val pick = byTitle ?: parsed.firstOrNull { it.first == number } ?: parsed.getOrNull(number - 1)
                if (pick != null) {
                    title = stripNative(pick.second)
                    artist = stripNative(pick.third)
                }
            }
            val t = title
            if (!t.isNullOrBlank()) {
                val a = artist.orEmpty()
                val reference = tokens("$t $a ${song.title} ${song.artist}")
                for (q in listOf("$a $t".trim(), t)) {
                    pick(deezer(q), song, reference, 0, ignoreDuration = true)?.let {
                        return Identity(t, a.ifBlank { it.artist }, it.durationSec, it.image, "Deezer")
                    }
                    delay(250)
                }
                pick(itunes("$a $t".trim()), song, reference, 0, ignoreDuration = true)?.let {
                    return Identity(t, a.ifBlank { it.artist }, it.durationSec, it.image, "iTunes")
                }
                return Identity(t, a, 0, info.poster, "MyAnimeList")
            }
            if (info.poster != null) {
                // chanson introuvable : l'affiche de l'anime, mais on tente d'abord AnimeThemes
                findAnimeThemes(animeName, kind, number, song)?.let { return it }
                return Identity("", "", 0, info.poster, "MyAnimeList")
            }
        }
        return findAnimeThemes(animeName, kind, number, song)
    }

    /** Affiche d'un anime : MyAnimeList, sinon AnimeThemes. */
    suspend fun animePosterAny(name: String): String? =
        runCatching { jikanAnime(searchWords(name).joinToString(" "))?.poster }.getOrNull() ?: animePoster(name)

    private suspend fun findAnimeThemes(animeName: String, kind: String, number: Int, song: Song): Identity? {
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

    private fun getJsonBlocking(url: String): String? = getWithCode(url).let { (code, body) -> if (code == 200) body else null }

    /** Code de réponse HTTP (-1 si le réseau a échoué) et contenu. */
    private fun getWithCode(url: String): Pair<Int, String?> = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "ToneDial (https://github.com/ThousandLabs/Tone-Dial)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            code to (if (code != 200) null else c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrDefault(-1 to null)

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
