package com.thousandlabs.tonedial.data

/**
 * Regroupe les openings et endings par anime, d'après le nom des titres
 * (« One Piece Opening 25 - The Peak » → One Piece, opening 25). Rien n'est modifié dans les fichiers.
 */
object AnimeGroups {

    data class Group(val name: String, val songs: List<Song>, val openings: Int, val endings: Int)

    private data class Info(val anime: String, val ending: Boolean, val number: Int)

    private val pattern = Regex(
        """^(.*?)\s*(?:-\s*)?\b(opening|op|ending|ed)\s*(\d{1,3})\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun info(song: Song): Info? {
        val fileName = song.path.substringAfterLast('/').substringBeforeLast('.')
        val m = pattern.find(TagCleaner.prepare(song.title)) ?: pattern.find(TagCleaner.prepare(fileName)) ?: return null
        val anime = m.groupValues[1].trim(' ', '-', '–', '—', '_', ':', '|', '.').trim()
        if (anime.isBlank()) return null
        val ending = m.groupValues[2].lowercase().startsWith("e")
        return Info(anime, ending, m.groupValues[3].toIntOrNull() ?: 0)
    }

    /** Nom de l'anime d'un titre, ou null si ce n'est pas un opening ou un ending. */
    fun animeOf(song: Song): String? = info(song)?.anime

    /** Tous les animes de la bibliothèque, du plus fourni au moins fourni. */
    fun groups(songs: List<Song>): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<Pair<Song, Info>>>()
        songs.forEach { s ->
            val i = info(s) ?: return@forEach
            val key = CoverSearch.norm(i.anime).ifBlank { i.anime.lowercase() }
            byKey.getOrPut(key) { ArrayList() } += s to i
        }
        return byKey.values.map { list ->
            // l'écriture la plus fréquente sert de nom affiché
            val name = list.groupingBy { it.second.anime }.eachCount().maxByOrNull { it.value }!!.key
            val sorted = list.sortedWith(compareBy({ it.second.ending }, { it.second.number }, { it.first.title.lowercase() }))
            Group(
                name = name,
                songs = sorted.map { it.first },
                openings = list.count { !it.second.ending },
                endings = list.count { it.second.ending },
            )
        }.sortedWith(compareByDescending<Group> { it.songs.size }.thenBy { it.name.lowercase() })
    }

    fun group(songs: List<Song>, name: String): Group? {
        val key = CoverSearch.norm(name).ifBlank { name.lowercase() }
        return groups(songs).firstOrNull { (CoverSearch.norm(it.name).ifBlank { it.name.lowercase() }) == key }
    }

    fun describe(g: Group): String = listOfNotNull(
        g.openings.takeIf { it > 0 }?.let { if (it == 1) "1 opening" else "$it openings" },
        g.endings.takeIf { it > 0 }?.let { if (it == 1) "1 ending" else "$it endings" },
    ).joinToString(" · ")
}
