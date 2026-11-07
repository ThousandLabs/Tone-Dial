package com.thousandlabs.tonedial.data

/**
 * Openings connus, intégrés à l'appli : identification sûre et hors ligne,
 * sans dépendre de MyAnimeList (souvent saturé quand on identifie beaucoup de titres d'un coup).
 * Pour les autres animes, la recherche en ligne prend le relais.
 */
object AnimeThemesLocal {

    data class Theme(val title: String, val artist: String)

    private class Anime(val names: List<String>, val openings: List<Theme>)

    private val catalog = listOf(
        Anime(
            listOf("one piece", "onepiece"),
            listOf(
                Theme("We Are!", "Hiroshi Kitadani"),
                Theme("Believe", "Folder5"),
                Theme("Hikari e", "The Babystars"),
                Theme("Bon Voyage!", "Bon-Bon Blanco"),
                Theme("Kokoro no Chizu", "BOYSTYLE"),
                Theme("Brand New World", "D-51"),
                Theme("We Are! (Straw Hat Pirates)", "Straw Hat Pirates"),
                Theme("Crazy Rainbow", "Tackey & Tsubasa"),
                Theme("Jungle P", "5050"),
                Theme("We Are!", "TVXQ"),
                Theme("Share the World", "TVXQ"),
                Theme("Kaze wo Sagashite", "Mari Yaguchi with Straw Hat"),
                Theme("One Day", "The ROOTLESS"),
                Theme("Fight Together", "Namie Amuro"),
                Theme("We Go!", "Hiroshi Kitadani"),
                Theme("Hands Up!", "Kota Shinzato"),
                Theme("Wake Up!", "AAA"),
                Theme("Hard Knock Days", "GENERATIONS from EXILE TRIBE"),
                Theme("We Can!", "Kishidan & Hiroshi Kitadani"),
                Theme("Hope", "Namie Amuro"),
                Theme("Super Powers", "V6"),
                Theme("Over the Top", "Hiroshi Kitadani"),
                Theme("Dreamin' On", "Da-iCE"),
                Theme("PAINT", "I Don't Like Mondays."),
                Theme("The Peak", "SEKAI NO OWARI"),
                Theme("Aaa!", "Hiroshi Kitadani"),
            ),
        ),
        Anime(
            listOf("black clover", "blackclover"),
            listOf(
                Theme("Haruka Mirai", "Kankaku Piero"),
                Theme("PAiNT it BLACK", "BiSH"),
                Theme("Black Rover", "Vickeblanka"),
                Theme("Guess Who Is Back", "Koda Kumi"),
                Theme("Gamushara", "Miyuna"),
                Theme("Rakugaki Page", "Kankaku Piero"),
                Theme("JUSTadICE", "Seiko Oomori"),
                Theme("Sky & Blue", "GIRLFRIEND"),
                Theme("Right Now", "EMPiRE"),
                Theme("Black Catcher", "Vickeblanka"),
                Theme("Stories", "Snow Man"),
                Theme("Everlasting Shine", "TOMORROW X TOGETHER"),
                Theme("Grandeur", "Snow Man"),
            ),
        ),
    )

    private fun words(s: String): Set<String> =
        CoverSearch.norm(s).split(' ').filter { it.isNotBlank() }.toSet()

    /**
     * Opening n° [number] de [animeName]. Si l'utilisateur a écrit le titre de la chanson
     * (« … Opening 25 - The Peak »), il départage en cas de numérotation différente.
     */
    fun find(animeName: String, isOpening: Boolean, number: Int, knownTitle: String?): Theme? {
        if (!isOpening) return null
        val key = CoverSearch.norm(animeName)
        val anime = catalog.firstOrNull { a -> a.names.any { it == key || it == key.replace(" ", "") } } ?: return null
        val byNumber = anime.openings.getOrNull(number - 1)
        val known = knownTitle?.let { words(it) }.orEmpty()
        if (known.isEmpty()) return byNumber
        fun matches(t: Theme): Boolean {
            val w = words(t.title.substringBefore(" ("))
            return w.isNotEmpty() && w.count { it in known }.toDouble() / w.size >= 0.75
        }
        // numéro et titre concordent : sûr ; sinon le titre écrit l'emporte (« We Are! » existe deux fois)
        if (byNumber != null && matches(byNumber)) return byNumber
        return anime.openings.firstOrNull { matches(it) } ?: byNumber
    }
}
