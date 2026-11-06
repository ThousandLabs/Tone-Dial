package com.thousandlabs.tonedial.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Données personnelles de l'utilisateur, stockées uniquement sur le téléphone :
 * favoris, compteurs d'écoute, playlists, paroles, réglages, dernière file de lecture.
 */
object UserStore {

    private lateinit var prefs: SharedPreferences

    private val _favorites = MutableStateFlow<Set<Long>>(emptySet())
    val favorites: StateFlow<Set<Long>> = _favorites.asStateFlow()

    private val _playCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val playCounts: StateFlow<Map<Long, Int>> = _playCounts.asStateFlow()

    private val _lastPlayed = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val lastPlayed: StateFlow<Map<Long, Long>> = _lastPlayed.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _sort = MutableStateFlow(SongSort.TITLE)
    val sort: StateFlow<SongSort> = _sort.asStateFlow()

    private val _minDurationSec = MutableStateFlow(30)
    val minDurationSec: StateFlow<Int> = _minDurationSec.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("tone_dial", Context.MODE_PRIVATE)
        _favorites.value = readLongSet("favorites")
        _playCounts.value = readLongIntMap("play_counts")
        _lastPlayed.value = readLongLongMap("last_played")
        _playlists.value = readPlaylists()
        _sort.value = runCatching { SongSort.valueOf(prefs.getString("sort", "TITLE")!!) }.getOrDefault(SongSort.TITLE)
        _minDurationSec.value = prefs.getInt("min_duration_sec", 30)
    }

    // ---------- Favoris ----------
    fun isFavorite(id: Long) = id in _favorites.value

    fun toggleFavorite(id: Long) {
        val set = _favorites.value.toMutableSet()
        if (!set.add(id)) set.remove(id)
        _favorites.value = set
        prefs.edit().putString("favorites", JSONArray(set.toList()).toString()).apply()
    }

    fun setFavorites(ids: List<Long>, favorite: Boolean) {
        val set = _favorites.value.toMutableSet()
        if (favorite) set.addAll(ids) else set.removeAll(ids.toSet())
        _favorites.value = set
        prefs.edit().putString("favorites", JSONArray(set.toList()).toString()).apply()
    }

    // ---------- Statistiques d'écoute ----------
    fun recordPlay(id: Long) {
        val counts = _playCounts.value.toMutableMap()
        counts[id] = (counts[id] ?: 0) + 1
        _playCounts.value = counts
        val last = _lastPlayed.value.toMutableMap()
        last[id] = System.currentTimeMillis()
        _lastPlayed.value = last
        prefs.edit()
            .putString("play_counts", JSONObject(counts.mapKeys { it.key.toString() }).toString())
            .putString("last_played", JSONObject(last.mapKeys { it.key.toString() }).toString())
            .apply()
    }

    // ---------- Playlists ----------
    fun createPlaylist(name: String, songIds: List<Long> = emptyList()): Playlist {
        val p = Playlist(System.currentTimeMillis(), name.trim().ifBlank { "Nouvelle playlist" }, songIds.distinct())
        savePlaylists(_playlists.value + p)
        return p
    }

    fun renamePlaylist(id: Long, name: String) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })

    fun deletePlaylist(id: Long) = savePlaylists(_playlists.value.filterNot { it.id == id })

    fun addToPlaylist(id: Long, songIds: List<Long>) =
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id) p.copy(songIds = (p.songIds + songIds).distinct()) else p
        })

    fun removeFromPlaylist(id: Long, songIds: List<Long>) {
        val remove = songIds.toSet()
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id) p.copy(songIds = p.songIds.filterNot { it in remove }) else p
        })
    }

    fun movePlaylistSong(id: Long, from: Int, to: Int) =
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id && from in p.songIds.indices && to in p.songIds.indices) {
                val l = p.songIds.toMutableList()
                val item = l.removeAt(from)
                l.add(to, item)
                p.copy(songIds = l)
            } else p
        })

    private fun savePlaylists(list: List<Playlist>) {
        _playlists.value = list
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("songs", JSONArray(p.songIds))
            })
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    private fun readPlaylists(): List<Playlist> = runCatching {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val songs = o.getJSONArray("songs")
            Playlist(o.getLong("id"), o.getString("name"), (0 until songs.length()).map { songs.getLong(it) })
        }
    }.getOrDefault(emptyList())

    // ---------- Paroles saisies par l'utilisateur ----------
    fun customLyrics(songId: Long): String? = prefs.getString("lyrics_$songId", null)

    fun setCustomLyrics(songId: Long, text: String?) {
        if (text.isNullOrBlank()) prefs.edit().remove("lyrics_$songId").apply()
        else prefs.edit().putString("lyrics_$songId", text).apply()
    }

    // ---------- Réglages ----------
    fun setSort(sort: SongSort) {
        _sort.value = sort
        prefs.edit().putString("sort", sort.name).apply()
    }

    fun setMinDurationSec(sec: Int) {
        _minDurationSec.value = sec
        prefs.edit().putInt("min_duration_sec", sec).apply()
    }

    fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun getBool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    // ---------- Reprise de la lecture ----------
    fun saveQueue(ids: List<Long>, index: Int, positionMs: Long) {
        prefs.edit()
            .putString("queue_ids", JSONArray(ids).toString())
            .putInt("queue_index", index)
            .putLong("queue_pos", positionMs)
            .apply()
    }

    fun savePosition(index: Int, positionMs: Long) {
        prefs.edit().putInt("queue_index", index).putLong("queue_pos", positionMs).apply()
    }

    data class SavedQueue(val ids: List<Long>, val index: Int, val positionMs: Long)

    fun savedQueue(): SavedQueue? = runCatching {
        val arr = JSONArray(prefs.getString("queue_ids", "[]"))
        val ids = (0 until arr.length()).map { arr.getLong(it) }
        if (ids.isEmpty()) null
        else SavedQueue(ids, prefs.getInt("queue_index", 0), prefs.getLong("queue_pos", 0L))
    }.getOrNull()

    // ---------- Lecture JSON ----------
    private fun readLongSet(key: String): Set<Long> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).map { arr.getLong(it) }.toSet()
    }.getOrDefault(emptySet())

    private fun readLongIntMap(key: String): Map<Long, Int> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}")!!)
        o.keys().asSequence().associate { it.toLong() to o.getInt(it) }
    }.getOrDefault(emptyMap())

    private fun readLongLongMap(key: String): Map<Long, Long> = runCatching {
        val o = JSONObject(prefs.getString(key, "{}")!!)
        o.keys().asSequence().associate { it.toLong() to o.getLong(it) }
    }.getOrDefault(emptyMap())
}
