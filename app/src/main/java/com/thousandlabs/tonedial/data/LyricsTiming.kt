package com.thousandlabs.tonedial.data

/**
 * Outils de minutage des paroles synchronisées (format .lrc).
 * Les décalages s'appliquent à une ligne et à toutes celles qui la suivent.
 */
object LyricsTiming {

    private val lineTag = Regex("""^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun format(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val min = t / 60_000
        val sec = (t % 60_000) / 1000
        val cs = (t % 1000) / 10
        return "[%02d:%02d.%02d]".format(min, sec, cs)
    }

    /** Reconstruit un texte .lrc à partir de paroles déjà lues (fichier, LRCLIB…). */
    fun toLrc(lyrics: Lyrics): String =
        if (!lyrics.synced) lyrics.plainText
        else lyrics.lines.joinToString("\n") { l -> format(l.timeMs ?: 0L) + l.text }

    /** Heure de chaque ligne minutée du texte (null pour les lignes sans minutage). */
    private fun times(text: String): List<Long?> =
        text.replace("\r\n", "\n").split('\n').map { line ->
            lineTag.find(line)?.let { m ->
                val frac = m.groupValues[3].let { f -> if (f.isEmpty()) 0L else f.padEnd(3, '0').take(3).toLong() }
                m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac
            }
        }

    /** Décale la ligne `fromLine` (index parmi les lignes minutées) et toutes les suivantes. */
    fun shift(text: String, fromLine: Int, deltaMs: Long): String {
        var timedIndex = -1
        return text.replace("\r\n", "\n").split('\n').joinToString("\n") { line ->
            val m = lineTag.find(line) ?: return@joinToString line
            timedIndex++
            if (timedIndex < fromLine) return@joinToString line
            val frac = m.groupValues[3].let { f -> if (f.isEmpty()) 0L else f.padEnd(3, '0').take(3).toLong() }
            val t = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac
            format(t + deltaMs) + line.substring(m.range.last + 1)
        }
    }

    /**
     * Après une modification manuelle : si l'utilisateur a changé l'heure d'une ligne
     * sans toucher aux suivantes, les suivantes sont décalées du même écart.
     */
    fun propagateEdit(before: String, after: String): String {
        val old = times(before).filterNotNull()
        val new = times(after).filterNotNull()
        if (old.isEmpty() || old.size != new.size) return after
        val first = old.indices.firstOrNull { old[it] != new[it] } ?: return after
        val delta = new[first] - old[first]
        // les lignes suivantes n'ont pas été modifiées à la main : on les décale aussi
        val restUntouched = (first + 1 until old.size).all { old[it] == new[it] }
        if (!restUntouched || first == old.size - 1) return after
        return shift(after, first + 1, delta)
    }
}
