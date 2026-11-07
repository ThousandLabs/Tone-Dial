package com.thousandlabs.tonedial.data

/**
 * Identification des artistes et nettoyage des titres, sans modifier les fichiers.
 *
 * - « Oliver Tree - Life Goes On » sans artiste renseigné → artiste « Oliver Tree », titre « Life Goes On »
 * - retire les mentions parasites : (Official Video), [Lyrics], (Audio), noms de sites, numéros de piste…
 * - repère les artistes invités : « A feat. B », « A ft. B », « A, B », « A & B », « Titre (feat. B) »
 */
object TagCleaner {

    data class Result(val title: String, val artist: String?)

    private val dashSplit = Regex("""\s+[-–—]{1,2}\s+""")
    private val junkBrackets = Regex(
        """\s*[(\[{【][^)\]}】]*?(official|officiel|video|vidéo|audio|lyric|paroles|clip|visuali[sz]er|kbps|\bhd\b|\bhq\b|\b4k\b|mp3|m4a|full song|music video|\bmv\b|free download|prod\.?)[^)\]}】]*[)\]}】]""",
        RegexOption.IGNORE_CASE,
    )
    private val junkSuffix = Regex(
        """\s*[-|]\s*(official (music )?video|official audio|lyrics?|paroles|audio|clip officiel)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val sitePrefix = Regex("""^\s*(?:\[[^\]]*]\s*)?[\w.-]+\.(?:com|net|cc|io|org|to|me|app|biz|info|ws)\s*[-_|:]\s*""", RegexOption.IGNORE_CASE)
    private val siteSuffix = Regex("""(?:\s*[-_|(\[]\s*|\s+)[\w.-]+\.(?:com|net|cc|io|org|to|me|app|biz|info|ws)\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val trackNumber = Regex("""^\s*\d{1,3}\s*[-._)]\s*(?=\S)""")
    private val spaces = Regex("""\s{2,}""")

    fun isUnknown(artist: String?): Boolean =
        artist.isNullOrBlank() || artist == "<unknown>" || artist.equals("unknown", true) ||
            artist.equals("Artiste inconnu", true) || artist.equals("Unknown artist", true)

    private val trailingNumber = Regex("""[_\s]+\d{1,5}$""")
    private val camelBreak = Regex("""(?<=[a-z0-9'’])(?=[A-Z])|(?<=[A-Za-z0-9])(?=[(\[])|(?<=[)\]])(?=[A-Za-z0-9])""")
    private val gluedDash = Regex("""(?<=\S)-(?=\S)""")

    /**
     * Remet des espaces dans les noms collés des téléchargeurs :
     * « AJR-World'sSmallestViolin(OfficialVideo)_567 » → « AJR - World's Smallest Violin (Official Video) ».
     * Ne touche qu'aux noms sans aucun espace.
     */
    fun prepare(raw: String): String {
        var t = raw.trim()
        t = trailingNumber.replace(t, "").ifBlank { t }
        if (!t.contains(' ')) {
            if (t.contains('_')) {
                t = t.replace('_', ' ')
            } else {
                t = camelBreak.replace(t, " ")
                t = gluedDash.replaceFirst(t, " - ")
            }
        }
        return t
    }

    /** Artiste et titre à utiliser pour les recherches en ligne, quel que soit le réglage d'affichage. */
    fun searchKey(song: Song): Result {
        val fromTitle = clean(song.title, song.artist.takeUnless { it == "Artiste inconnu" })
        if (fromTitle.artist != null) return fromTitle
        // sinon, essayer avec le nom du fichier
        val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
        val fromFile = clean(fileName, null)
        return if (fromFile.artist != null) fromFile else fromTitle
    }

    fun clean(rawTitle: String, rawArtist: String?): Result {
        var title = prepare(rawTitle)
        title = sitePrefix.replace(title, "")
        title = siteSuffix.replace(title, "")
        title = junkBrackets.replace(title, "")
        title = junkSuffix.replace(title, "")
        title = title.trim()
        trackNumber.replace(title, "").let { if (it.isNotBlank()) title = it }

        var artist = rawArtist?.trim()?.takeUnless { isUnknown(it) }

        val parts = dashSplit.split(title, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val left = trackNumber.replace(parts[0], "").trim()
            val right = parts[1].trim()
            when {
                // pas d'artiste renseigné : « Artiste - Titre »
                artist == null && left.length <= 60 && left.isNotBlank() -> {
                    artist = left
                    title = right
                }
                // artiste déjà renseigné et répété au début du titre
                artist != null && left.equals(artist, ignoreCase = true) -> title = right
            }
        }

        title = spaces.replace(title, " ").trim().trim('-', '–', '_', ' ')
        if (title.isBlank()) title = rawTitle.trim()
        return Result(title, artist?.let { spaces.replace(it, " ").trim() }?.takeIf { it.isNotBlank() })
    }

    private val featSplit = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+|\s*,\s*|\s+&\s+(?!the\b)|\s+x\s+|\s*;\s*|\s+/\s+""", RegexOption.IGNORE_CASE)

    /** Noms de groupes qui contiennent une virgule ou « & » et ne doivent pas être coupés. */
    private val protectedNames = listOf(
        "Tyler, The Creator", "Earth, Wind & Fire", "Simon & Garfunkel", "Hall & Oates",
        "Mumford & Sons", "Crosby, Stills, Nash & Young", "Peter, Paul and Mary", "Ike & Tina Turner",
        "Sam & Dave", "Brooks & Dunn", "Chase & Status", "Above & Beyond", "Krewella & Co",
    )

    private fun splitArtists(text: String): List<String> {
        var t = text
        val kept = ArrayList<String>()
        protectedNames.forEach { name ->
            val idx = t.indexOf(name, ignoreCase = true)
            if (idx >= 0) {
                kept += t.substring(idx, idx + name.length)
                t = t.replaceRange(idx, idx + name.length, "\u0000${kept.size - 1}\u0000")
            }
        }
        return featSplit.split(t).map { part ->
            Regex("""\u0000(\d+)\u0000""").replace(part) { kept[it.groupValues[1].toInt()] }.trim()
        }.filter { it.isNotBlank() && !it.contains('\u0000') }
    }
    private val featInTitle = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring)\s+([^)\]]+)[)\]]""", RegexOption.IGNORE_CASE)

    /** Liste des artistes d'un titre : artiste principal puis invités. */
    fun artists(artist: String, title: String): List<String> {
        val list = ArrayList<String>()
        list += splitArtists(artist)
        featInTitle.findAll(title).forEach { m -> list += splitArtists(m.groupValues[1]) }
        return list.distinctBy { it.lowercase() }.ifEmpty { listOf(artist) }
    }
}
