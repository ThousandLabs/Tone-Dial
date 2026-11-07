package com.thousandlabs.tonedial.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Dossiers ignorés et corrections manuelles des informations des titres. */
object LibraryPrefs {

    // ---------- Dossiers exclus ----------
    private val _excluded = MutableStateFlow<Set<String>>(emptySet())
    val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    // ---------- Corrections (titre, artiste, album) ----------
    data class Override(val title: String?, val artist: String?, val album: String?)

    private val _overrides = MutableStateFlow<Map<Long, Override>>(emptyMap())
    val overrides: StateFlow<Map<Long, Override>> = _overrides.asStateFlow()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        _excluded.value = runCatching {
            val a = JSONArray(UserStore.getString("excluded_folders") ?: "[]")
            (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        _overrides.value = runCatching {
            val o = JSONObject(UserStore.getString("tag_overrides") ?: "{}")
            o.keys().asSequence().associate { k ->
                val v = o.getJSONObject(k)
                k.toLong() to Override(
                    v.optString("t").ifBlank { null },
                    v.optString("a").ifBlank { null },
                    v.optString("al").ifBlank { null },
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun setExcluded(paths: Set<String>) {
        _excluded.value = paths
        UserStore.putString("excluded_folders", JSONArray(paths.toList()).toString())
    }

    fun toggleExcluded(path: String) {
        setExcluded(if (path in _excluded.value) _excluded.value - path else _excluded.value + path)
    }

    fun isExcluded(folderPath: String): Boolean {
        val set = _excluded.value
        if (set.isEmpty()) return false
        // un dossier exclu masque aussi ses sous-dossiers
        return set.any { folderPath == it || folderPath.startsWith("$it/") }
    }

    fun setOverride(songId: Long, o: Override?) {
        val map = _overrides.value.toMutableMap()
        if (o == null || (o.title == null && o.artist == null && o.album == null)) map.remove(songId) else map[songId] = o
        saveOverrides(map)
    }

    fun replaceOverrides(map: Map<Long, Override>) = saveOverrides(map)

    private fun saveOverrides(map: Map<Long, Override>) {
        _overrides.value = map
        val o = JSONObject()
        map.forEach { (id, v) ->
            o.put(id.toString(), JSONObject().apply {
                v.title?.let { put("t", it) }
                v.artist?.let { put("a", it) }
                v.album?.let { put("al", it) }
            })
        }
        UserStore.putString("tag_overrides", o.toString())
    }

    /** Applique la correction manuelle d'un titre, si elle existe. */
    fun apply(song: Song): Song {
        val o = _overrides.value[song.id] ?: return song
        val title = o.title ?: song.title
        val artist = o.artist ?: song.artist
        return song.copy(
            title = title,
            artist = artist,
            album = o.album ?: song.album,
            artists = when {
                o.artist == null && o.title == null -> song.artists
                // un opening identifié reste aussi rangé avec son anime
                o.artist != null && Covers.looksAnime(song) ->
                    (TagCleaner.artists(artist, title) + song.artists.filter { it != "Artiste inconnu" }).distinctBy { it.lowercase() }
                else -> TagCleaner.artists(artist, title)
            },
        )
    }
}
