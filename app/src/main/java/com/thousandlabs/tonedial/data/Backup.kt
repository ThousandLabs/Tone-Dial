package com.thousandlabs.tonedial.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sauvegarde et restauration des données personnelles dans un fichier :
 * playlists, favoris, écoutes, paroles, corrections, dossiers exclus et réglages.
 * Les titres sont retrouvés par leur emplacement, sinon par titre et artiste,
 * ce qui permet de restaurer sur un autre téléphone.
 */
object Backup {

    private val intKeys = listOf("eq_preset", "bass", "virtualizer", "min_duration_sec", "queue_repeat") +
        (0 until 10).map { "eq_band_$it" }
    private val boolKeys = listOf("eq_enabled", "smart_tags", "covers_auto", "update_auto")

    fun suggestedName(): String = "ToneDial-sauvegarde-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"

    suspend fun export(context: Context, target: Uri, library: List<Song>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val byId = library.associateBy { it.id }
            val referenced = HashSet<Long>()
            val playlists = JSONArray()
            UserStore.playlists.value.forEach { p ->
                referenced += p.songIds
                playlists.put(JSONObject().apply {
                    put("name", p.name)
                    put("songs", JSONArray(p.songIds))
                    put("added", JSONObject(p.added.mapKeys { it.key.toString() }))
                    put("sort", UserStore.playlistSort(p.id).name)
                })
            }
            val favorites = UserStore.favorites.value
            referenced += favorites
            val counts = UserStore.playCounts.value
            referenced += counts.keys
            val lyrics = UserStore.allLyrics()
            referenced += lyrics.keys
            val overrides = LibraryPrefs.overrides.value
            referenced += overrides.keys

            val songs = JSONObject()
            referenced.forEach { id ->
                val s = byId[id] ?: return@forEach
                songs.put(id.toString(), JSONObject().apply {
                    put("p", s.path)
                    put("t", s.title)
                    put("a", s.artist)
                    put("d", s.duration)
                })
            }

            val root = JSONObject().apply {
                put("app", "Tone Dial")
                put("format", 1)
                put("date", System.currentTimeMillis())
                put("songs", songs)
                put("playlists", playlists)
                put("favorites", JSONArray(favorites.toList()))
                put("favAdded", JSONObject(favorites.associateWith { UserStore.favoriteAddedAt(it) }.mapKeys { it.key.toString() }))
                put("playCounts", JSONObject(counts.mapKeys { it.key.toString() }))
                put("lyrics", JSONObject().apply {
                    lyrics.forEach { (id, v) -> put(id.toString(), JSONObject().apply { put("text", v.first); v.second?.let { put("src", it) } }) }
                })
                put("overrides", JSONObject().apply {
                    overrides.forEach { (id, o) ->
                        put(id.toString(), JSONObject().apply {
                            o.title?.let { put("t", it) }; o.artist?.let { put("a", it) }; o.album?.let { put("al", it) }
                        })
                    }
                })
                put("excluded", JSONArray(LibraryPrefs.excluded.value.toList()))
                put("sortSongs", UserStore.sort.value.name)
                put("favSort", UserStore.favoritesSort().name)
                put("settings", JSONObject().apply {
                    intKeys.forEach { k -> put(k, UserStore.getInt(k, Int.MIN_VALUE)) }
                    boolKeys.forEach { k -> put(k, UserStore.getBool(k, true)) }
                })
            }
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(root.toString(2).toByteArray()) }
                ?: error("Fichier inaccessible")
            UserStore.playlists.value.size
        }
    }

    data class ImportResult(val playlists: Int, val matched: Int, val missing: Int)

    private fun key(title: String, artist: String) = (title.trim() + "|" + artist.trim()).lowercase()

    suspend fun import(context: Context, source: Uri, library: List<Song>): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Fichier illisible")
            val root = JSONObject(text)
            require(root.optString("app") == "Tone Dial") { "Ce fichier n'est pas une sauvegarde Tone Dial" }

            // correspondance ancien identifiant → titre actuel
            val byPath = library.associateBy { it.path }
            val byKey = library.groupBy { key(it.title, it.artist) }
            val byFile = library.groupBy { it.path.substringAfterLast('/').lowercase() }
            val songs = root.optJSONObject("songs") ?: JSONObject()
            val idMap = HashMap<Long, Long>()
            var missing = 0
            songs.keys().forEach { k ->
                val o = songs.getJSONObject(k)
                val path = o.optString("p")
                val match = byPath[path]
                    ?: byKey[key(o.optString("t"), o.optString("a"))]?.firstOrNull()
                    ?: byFile[path.substringAfterLast('/').lowercase()]?.firstOrNull()
                if (match != null) idMap[k.toLong()] = match.id else missing++
            }
            fun mapIds(arr: JSONArray?): List<Long> =
                if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { idMap[arr.getLong(it)] }

            // playlists : fusion avec celles du même nom
            val current = UserStore.playlists.value.toMutableList()
            var plCount = 0
            root.optJSONArray("playlists")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name").ifBlank { "Playlist restaurée" }
                    val ids = mapIds(o.optJSONArray("songs")).distinct()
                    val addedJson = o.optJSONObject("added")
                    val added = HashMap<Long, Long>()
                    addedJson?.let { aj -> aj.keys().forEach { k -> idMap[k.toLong()]?.let { added[it] = aj.getLong(k) } } }
                    val existing = current.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                    if (existing >= 0) {
                        val p = current[existing]
                        val fresh = ids.filterNot { it in p.songIds }
                        current[existing] = p.copy(songIds = p.songIds + fresh, added = p.added + added.filterKeys { it in fresh })
                    } else {
                        val newId = System.currentTimeMillis() + i
                        current += Playlist(newId, name, ids, added)
                        runCatching { UserStore.setPlaylistSort(newId, PlaylistSort.valueOf(o.optString("sort", "CUSTOM"))) }
                    }
                    plCount++
                }
            }
            UserStore.replacePlaylists(current)

            // favoris
            val favs = UserStore.favorites.value + mapIds(root.optJSONArray("favorites"))
            val favAddedJson = root.optJSONObject("favAdded")
            val favAdded = HashMap<Long, Long>()
            favs.forEach { favAdded[it] = UserStore.favoriteAddedAt(it) }
            favAddedJson?.let { fj -> fj.keys().forEach { k -> idMap[k.toLong()]?.let { favAdded[it] = fj.getLong(k) } } }
            UserStore.replaceFavorites(favs, favAdded)

            // nombre d'écoutes : seulement pour les titres jamais écoutés ici
            root.optJSONObject("playCounts")?.let { pc ->
                val extra = HashMap<Long, Int>()
                pc.keys().forEach { k ->
                    val id = idMap[k.toLong()] ?: return@forEach
                    if ((UserStore.playCounts.value[id] ?: 0) == 0) extra[id] = pc.getInt(k)
                }
                UserStore.addPlayCounts(extra)
            }

            // paroles et corrections
            root.optJSONObject("lyrics")?.let { ly ->
                ly.keys().forEach { k ->
                    val id = idMap[k.toLong()] ?: return@forEach
                    if (UserStore.customLyrics(id) == null) {
                        val o = ly.getJSONObject(k)
                        UserStore.setCustomLyrics(id, o.optString("text"), o.optString("src").ifBlank { null })
                    }
                }
            }
            root.optJSONObject("overrides")?.let { ov ->
                val map = LibraryPrefs.overrides.value.toMutableMap()
                ov.keys().forEach { k ->
                    val id = idMap[k.toLong()] ?: return@forEach
                    val o = ov.getJSONObject(k)
                    map[id] = LibraryPrefs.Override(o.optString("t").ifBlank { null }, o.optString("a").ifBlank { null }, o.optString("al").ifBlank { null })
                }
                LibraryPrefs.replaceOverrides(map)
            }
            root.optJSONArray("excluded")?.let { ex ->
                LibraryPrefs.setExcluded(LibraryPrefs.excluded.value + (0 until ex.length()).map { ex.getString(it) })
            }

            // réglages
            root.optJSONObject("settings")?.let { st ->
                intKeys.forEach { k -> if (st.has(k) && st.getInt(k) != Int.MIN_VALUE) UserStore.putInt(k, st.getInt(k)) }
                boolKeys.forEach { k -> if (st.has(k)) UserStore.putBool(k, st.getBoolean(k)) }
            }
            runCatching { UserStore.setSort(SongSort.valueOf(root.optString("sortSongs"))) }
            runCatching { UserStore.setFavoritesSort(PlaylistSort.valueOf(root.optString("favSort"))) }

            ImportResult(plCount, idMap.size, missing)
        }
    }
}
