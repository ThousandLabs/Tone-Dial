package com.thousandlabs.tonedial.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Statistiques d'écoute, gardées sur le téléphone :
 * temps d'écoute et nombre d'écoutes par jour (120 derniers jours) et au total.
 */
object ListenStats {

    private const val KEY_DAYS = "stats_days"
    private const val KEY_TOTAL = "stats_total_ms"
    private const val KEY_SONG_TIME = "stats_song_ms"
    private const val KEEP_DAYS = 120

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private class Day(var ms: Long = 0, val songMs: MutableMap<Long, Long> = HashMap(), val plays: MutableMap<Long, Int> = HashMap())

    private var days: MutableMap<String, Day>? = null
    private var totalMs = 0L
    private var songTime: MutableMap<Long, Long> = HashMap()
    private var dirty = 0

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun ensure(): MutableMap<String, Day> {
        days?.let { return it }
        val map = HashMap<String, Day>()
        runCatching {
            val o = JSONObject(UserStore.getString(KEY_DAYS) ?: "{}")
            o.keys().forEach { k ->
                val d = o.getJSONObject(k)
                val day = Day(d.optLong("ms"))
                d.optJSONObject("s")?.let { s -> s.keys().forEach { id -> day.songMs[id.toLong()] = s.getLong(id) } }
                d.optJSONObject("p")?.let { p -> p.keys().forEach { id -> day.plays[id.toLong()] = p.getInt(id) } }
                map[k] = day
            }
        }
        totalMs = UserStore.getLong(KEY_TOTAL, 0L)
        runCatching {
            val o = JSONObject(UserStore.getString(KEY_SONG_TIME) ?: "{}")
            o.keys().forEach { songTime[it.toLong()] = o.getLong(it) }
        }
        days = map
        return map
    }

    private fun today(): String = dayFmt.format(Date())

    /** Ajoute du temps d'écoute pour un titre. */
    @Synchronized
    fun addListening(songId: Long, ms: Long) {
        if (ms <= 0) return
        val map = ensure()
        val d = map.getOrPut(today()) { Day() }
        d.ms += ms
        d.songMs[songId] = (d.songMs[songId] ?: 0L) + ms
        totalMs += ms
        songTime[songId] = (songTime[songId] ?: 0L) + ms
        if (++dirty >= 3) save()
    }

    @Synchronized
    fun recordPlay(songId: Long) {
        val d = ensure().getOrPut(today()) { Day() }
        d.plays[songId] = (d.plays[songId] ?: 0) + 1
        save()
    }

    @Synchronized
    fun save() {
        val map = ensure()
        dirty = 0
        // ne garder que les derniers jours
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -KEEP_DAYS) }
        val limit = dayFmt.format(cal.time)
        map.keys.filter { it < limit }.forEach { map.remove(it) }
        val o = JSONObject()
        map.forEach { (k, d) ->
            o.put(k, JSONObject().apply {
                put("ms", d.ms)
                put("s", JSONObject(d.songMs.mapKeys { it.key.toString() }))
                put("p", JSONObject(d.plays.mapKeys { it.key.toString() }))
            })
        }
        UserStore.putString(KEY_DAYS, o.toString())
        UserStore.putLong(KEY_TOTAL, totalMs)
        UserStore.putString(KEY_SONG_TIME, JSONObject(songTime.mapKeys { it.key.toString() }).toString())
        _version.value++
    }

    data class Summary(
        val totalMs: Long,
        val plays: Map<Long, Int>,
        val timeBySong: Map<Long, Long>,
        val activeDays: Int,
        val byDay: List<Pair<String, Long>>,
    )

    /** Résumé sur les `days` derniers jours, ou depuis toujours si null. */
    @Synchronized
    fun summary(days: Int?): Summary {
        val map = ensure()
        if (days == null) {
            val byDay = map.entries.sortedBy { it.key }.takeLast(30).map { it.key to it.value.ms }
            return Summary(totalMs, UserStore.playCounts.value, HashMap(songTime), map.count { it.value.ms > 0 }, byDay)
        }
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }
        val from = dayFmt.format(cal.time)
        val selected = map.filterKeys { it >= from }
        val plays = HashMap<Long, Int>()
        val time = HashMap<Long, Long>()
        var total = 0L
        selected.values.forEach { d ->
            total += d.ms
            d.plays.forEach { (id, n) -> plays[id] = (plays[id] ?: 0) + n }
            d.songMs.forEach { (id, ms) -> time[id] = (time[id] ?: 0L) + ms }
        }
        // une barre par jour, jours sans écoute compris
        val byDay = (0 until days).map { i ->
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1 - i)) }
            val k = dayFmt.format(c.time)
            k to (map[k]?.ms ?: 0L)
        }
        return Summary(total, plays, time, selected.count { it.value.ms > 0 }, byDay)
    }
}
