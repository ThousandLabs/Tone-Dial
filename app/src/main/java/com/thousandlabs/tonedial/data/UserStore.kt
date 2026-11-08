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
        if (!set.add(id)) set.remove(id) else markFavoriteAdded(listOf(id))
        _favorites.value = set
        prefs.edit().putString("favorites", JSONArray(set.toList()).toString()).apply()
    }

    private val favAdded: MutableMap<Long, Long> by lazy { readLongLongMap("fav_added").toMutableMap() }

    fun favoriteAddedAt(id: Long): Long = favAdded[id] ?: 0L

    private fun markFavoriteAdded(ids: List<Long>) {
        val now = System.currentTimeMillis()
        ids.forEachIndexed { i, id -> favAdded[id] = now + i }
        prefs.edit().putString("fav_added", JSONObject(favAdded.mapKeys { it.key.toString() }).toString()).apply()
    }

    fun setFavorites(ids: List<Long>, favorite: Boolean) {
        val set = _favorites.value.toMutableSet()
        if (favorite) {
            markFavoriteAdded(ids.filterNot { it in set })
            set.addAll(ids)
        } else set.removeAll(ids.toSet())
        _favorites.value = set
        prefs.edit().putString("favorites", JSONArray(set.toList()).toString()).apply()
    }

    // ---------- Statistiques d'écoute ----------
    fun recordPlay(id: Long) {
        ListenStats.recordPlay(id)
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
        val now = System.currentTimeMillis()
        val ids = songIds.distinct()
        val p = Playlist(now, name.trim().ifBlank { "Nouvelle playlist" }, ids, ids.mapIndexed { i, id -> id to now + i }.toMap())
        savePlaylists(_playlists.value + p)
        return p
    }

    fun renamePlaylist(id: Long, name: String) =
        savePlaylists(_playlists.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })

    fun deletePlaylist(id: Long) = savePlaylists(_playlists.value.filterNot { it.id == id })

    fun addToPlaylist(id: Long, songIds: List<Long>) {
        val now = System.currentTimeMillis()
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id) {
                val fresh = songIds.distinct().filterNot { it in p.songIds }
                p.copy(
                    songIds = p.songIds + fresh,
                    added = p.added + fresh.mapIndexed { i, sid -> sid to now + i },
                )
            } else p
        })
    }

    /** Enregistre un nouvel ordre personnalisé. */
    fun setPlaylistOrder(id: Long, order: List<Long>) =
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id) {
                val keep = order.filter { it in p.songIds }
                p.copy(songIds = keep + p.songIds.filterNot { it in keep })
            } else p
        })

    fun playlistSort(id: Long): PlaylistSort = runCatching {
        PlaylistSort.valueOf(prefs.getString("pl_sort_$id", "CUSTOM")!!)
    }.getOrDefault(PlaylistSort.CUSTOM)

    fun setPlaylistSort(id: Long, sort: PlaylistSort) {
        prefs.edit().putString("pl_sort_$id", sort.name).apply()
        _sortVersion.value++
    }

    fun favoritesSort(): PlaylistSort = runCatching {
        PlaylistSort.valueOf(prefs.getString("fav_sort", "TITLE")!!)
    }.getOrDefault(PlaylistSort.TITLE)

    fun setFavoritesSort(sort: PlaylistSort) {
        prefs.edit().putString("fav_sort", sort.name).apply()
        _sortVersion.value++
    }

    private val _sortVersion = MutableStateFlow(0)
    /** Change à chaque modification de tri, pour rafraîchir l'affichage. */
    val sortVersion: StateFlow<Int> = _sortVersion.asStateFlow()

    fun removeFromPlaylist(id: Long, songIds: List<Long>) {
        val remove = songIds.toSet()
        savePlaylists(_playlists.value.map { p ->
            if (p.id == id) p.copy(songIds = p.songIds.filterNot { it in remove }, added = p.added - remove) else p
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
                put("added", JSONObject(p.added.mapKeys { it.key.toString() }))
            })
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    private fun readPlaylists(): List<Playlist> = runCatching {
        val arr = JSONArray(prefs.getString("playlists", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val songs = o.getJSONArray("songs")
            val added = o.optJSONObject("added")
            Playlist(
                o.getLong("id"),
                o.getString("name"),
                (0 until songs.length()).map { songs.getLong(it) },
                added?.let { a -> a.keys().asSequence().associate { it.toLong() to a.getLong(it) } } ?: emptyMap(),
            )
        }
    }.getOrDefault(emptyList())

    // ---------- Paroles saisies par l'utilisateur ----------
    fun customLyrics(songId: Long): String? = prefs.getString("lyrics_$songId", null)

    fun customLyricsSource(songId: Long): String? = prefs.getString("lyrics_src_$songId", null)

    fun setCustomLyrics(songId: Long, text: String?, source: String? = null) {
        if (text.isNullOrBlank()) prefs.edit().remove("lyrics_$songId").remove("lyrics_src_$songId").apply()
        else prefs.edit().putString("lyrics_$songId", text).apply {
            if (source != null) putString("lyrics_src_$songId", source) else remove("lyrics_src_$songId")
        }.apply()
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

    // ---------- Ordre des onglets de l'accueil ----------
    private val _tabOrder = MutableStateFlow<List<String>>(emptyList())
    val tabOrder: StateFlow<List<String>> = _tabOrder.asStateFlow()

    fun loadTabOrder(defaults: List<String>) {
        val saved = prefs.getString("tab_order", null)?.split(',')?.filter { it in defaults } ?: emptyList()
        _tabOrder.value = saved + defaults.filterNot { it in saved }
    }

    fun setTabOrder(order: List<String>) {
        _tabOrder.value = order
        prefs.edit().putString("tab_order", order.joinToString(",")).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value).apply()
    }
    fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun allLyrics(): Map<Long, Pair<String, String?>> =
        prefs.all.keys.filter { it.startsWith("lyrics_") && !it.startsWith("lyrics_src_") }
            .mapNotNull { k ->
                val id = k.removePrefix("lyrics_").toLongOrNull() ?: return@mapNotNull null
                val text = prefs.getString(k, null) ?: return@mapNotNull null
                id to (text to prefs.getString("lyrics_src_$id", null))
            }.toMap()

    /** Remplace les données personnelles lors d'une restauration. */
    fun replacePlaylists(list: List<Playlist>) = savePlaylists(list)

    fun replaceFavorites(ids: Set<Long>, addedAt: Map<Long, Long>) {
        _favorites.value = ids
        favAdded.clear()
        favAdded.putAll(addedAt)
        prefs.edit()
            .putString("favorites", JSONArray(ids.toList()).toString())
            .putString("fav_added", JSONObject(favAdded.mapKeys { it.key.toString() }).toString())
            .apply()
    }

    fun addPlayCounts(extra: Map<Long, Int>) {
        if (extra.isEmpty()) return
        val counts = _playCounts.value.toMutableMap()
        extra.forEach { (id, n) -> counts[id] = (counts[id] ?: 0) + n }
        _playCounts.value = counts
        prefs.edit().putString("play_counts", JSONObject(counts.mapKeys { it.key.toString() }).toString()).apply()
    }

    fun has(key: String) = prefs.contains(key)

    /** Restauration : garde la date d'écoute la plus récente de chaque titre. */
    fun mergeLastPlayed(m: Map<Long, Long>) {
        if (m.isEmpty()) return
        val last = _lastPlayed.value.toMutableMap()
        m.forEach { (k, v) -> if (v > (last[k] ?: 0L)) last[k] = v }
        _lastPlayed.value = last
        prefs.edit().putString("last_played", JSONObject(last.mapKeys { it.key.toString() }).toString()).apply()
    }

    /** Restauration de l'ordre des onglets (les onglets inconnus sont ignorés, les nouveaux ajoutés à la fin). */
    fun restoreTabOrder(saved: List<String>) {
        if (saved.isEmpty()) return
        val cur = _tabOrder.value
        if (cur.isEmpty()) { prefs.edit().putString("tab_order", saved.joinToString(",")).apply(); return }
        val s = saved.filter { it in cur }
        setTabOrder(s + cur.filterNot { it in s })
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
