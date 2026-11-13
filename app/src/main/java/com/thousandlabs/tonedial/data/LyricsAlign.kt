package com.thousandlabs.tonedial.data

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Minutage automatique des paroles collées sans heures.
 * On reprend les heures d'une version synchronisée (LRCLIB) et on les place sur le texte
 * de l'utilisateur, ligne par ligne : son texte reste le sien, seules les heures sont ajoutées.
 */
object LyricsAlign {

    data class Result(val lrc: String, val approximate: Boolean)

    /** Le texte contient-il déjà des minutages [mm:ss.xx] ? */
    fun hasTiming(text: String): Boolean = LyricsLoader.parse(text, "").synced

    private fun clean(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .filter { it.isLetterOrDigit() }

    private fun bigrams(s: String): List<String> =
        if (s.length < 2) listOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }

    /** Ressemblance entre deux lignes, de 0 (rien à voir) à 1 (identiques). */
    private fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty() || a[0].isEmpty() || b[0].isEmpty()) return 0.0
        val counts = HashMap<String, Int>()
        b.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var common = 0
        a.forEach { g ->
            val c = counts[g] ?: 0
            if (c > 0) { common++; counts[g] = c - 1 }
        }
        return 2.0 * common / (a.size + b.size)
    }

    /**
     * Place les heures de `reference` sur le texte simple `plain`.
     * Renvoie null si les deux versions ne se correspondent pas assez.
     */
    fun transfer(plain: String, reference: Lyrics): Result? {
        val mine = plain.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val ref = reference.lines.filter { it.timeMs != null && it.text.isNotBlank() }
        if (mine.isEmpty() || ref.size < 2) return null
        val n = mine.size
        val m = ref.size
        val a = mine.map { bigrams(clean(it)) }
        val b = ref.map { bigrams(clean(it.text)) }

        // alignement des deux listes de lignes, dans l'ordre (les refrains répétés restent à leur place)
        val threshold = 0.35
        val sim = Array(n) { i -> DoubleArray(m) { j -> similarity(a[i], b[j]) } }
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            val s = sim[i - 1][j - 1]
            val diag = if (s >= threshold) dp[i - 1][j - 1] + s else Double.NEGATIVE_INFINITY
            dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1], diag)
        }
        val match = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val s = sim[i - 1][j - 1]
            when {
                s >= threshold && dp[i][j] == dp[i - 1][j - 1] + s -> { match[i - 1] = j - 1; i--; j-- }
                dp[i][j] == dp[i - 1][j] -> i--
                else -> j--
            }
        }
        val matched = match.count { it >= 0 }
        val times: LongArray
        val approximate: Boolean
        if (matched >= max(2, (n * 0.5).roundToInt())) {
            times = fillGaps(match, ref)
            approximate = matched < n * 0.8
        } else if (abs(n - m) <= max(1, m / 10)) {
            // textes trop différents pour être comparés (écriture japonaise contre romaji, traduction…) :
            // même nombre de lignes à peu près, on répartit dans l'ordre
            times = LongArray(n) { k -> ref[if (n == 1) 0 else (k.toDouble() * (m - 1) / (n - 1)).roundToInt()].timeMs!! }
            approximate = n != m
        } else return null

        // heures toujours croissantes
        for (k in 1 until n) if (times[k] <= times[k - 1]) times[k] = times[k - 1] + 300
        val lrc = mine.indices.joinToString("\n") { LyricsTiming.format(times[it]) + mine[it] }
        return Result(lrc, approximate)
    }

    /** Lignes sans correspondance : heure estimée entre celles des lignes voisines reconnues. */
    private fun fillGaps(match: IntArray, ref: List<LyricLine>): LongArray {
        val n = match.size
        val t = LongArray(n) { if (match[it] >= 0) ref[match[it]].timeMs!! else -1L }
        val known = (0 until n).filter { match[it] >= 0 }
        val first = known.first()
        val last = known.last()
        // avant la première ligne reconnue
        if (first > 0) {
            val end = t[first]
            val start = if (match[first] > 0) ref[0].timeMs!! else max(0L, end - first * 2500L)
            for (k in 0 until first) t[k] = start + (end - start) * k / first
        }
        // après la dernière ligne reconnue
        if (last < n - 1) {
            val start = t[last]
            val refEnd = ref.last().timeMs!!
            val end = if (match[last] < ref.size - 1) max(refEnd, start + 1000) else start + (n - 1 - last) * 3000L
            val count = n - last
            for (k in last + 1 until n) t[k] = start + (end - start) * (k - last) / count
        }
        // entre deux lignes reconnues
        for (w in 0 until known.size - 1) {
            val p = known[w]
            val q = known[w + 1]
            if (q - p > 1) for (k in p + 1 until q) t[k] = t[p] + (t[q] - t[p]) * (k - p) / (q - p)
        }
        return t
    }

    /**
     * Synchronisation à la main : `stamps` contient l'heure des premières lignes.
     * Les lignes restantes sont réparties entre la dernière heure notée et la fin du morceau.
     */
    fun fromStamps(lines: List<String>, stamps: List<Long>, durationMs: Long): String {
        val times = LongArray(lines.size)
        stamps.forEachIndexed { k, v -> if (k < lines.size) times[k] = v }
        val done = min(stamps.size, lines.size)
        if (done < lines.size) {
            val start = if (done > 0) times[done - 1] else 0L
            val remaining = lines.size - done
            val step = if (durationMs > start) min(4000L, (durationMs - start) / (remaining + 1)) else 3000L
            for (k in done until lines.size) times[k] = start + step * (k - done + 1)
        }
        for (k in 1 until lines.size) if (times[k] <= times[k - 1]) times[k] = times[k - 1] + 300
        return lines.indices.joinToString("\n") { LyricsTiming.format(times[it]) + lines[it] }
    }
}
