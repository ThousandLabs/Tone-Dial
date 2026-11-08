package com.thousandlabs.tonedial.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde complète dans un seul fichier .zip :
 * • tonedial.json : playlists, favoris, écoutes, dates d'écoute, statistiques, paroles,
 *   corrections, openings identifiés, dossiers et titres masqués, ordre des onglets,
 *   tris et tous les réglages ;
 * • covers/ : toutes les images gardées par l'appli (pochettes trouvées en ligne ou
 *   choisies dans la galerie, images d'albums, d'artistes et de playlists).
 * Les titres sont retrouvés par leur emplacement, sinon par titre et artiste,
 * ce qui permet de restaurer sur un autre téléphone.
 * Les anciennes sauvegardes (.json seul) restent lisibles.
 */
object Backup {

    /** Réglages numériques (la valeur n'est sauvegardée que si elle a été changée). */
    private val intKeys = listOf("eq_preset", "bass", "virtualizer", "min_duration_sec", "queue_repeat", "speed_pct", "lyrics_scale") +
        (0 until 10).map { "eq_band_$it" }
    /** Réglages oui / non (sauvegardés seulement s'ils ont été changés). */
    private val boolKeys = listOf(
        "eq_enabled", "smart_tags", "covers_auto", "update_auto", "tap_jump_queue", "lyrics_romaji", "notif_buttons",
        "lyrics_auto", "lyrics_karaoke", "resume_on_headset", "skip_silence",
    )
    private val stringKeys = listOf("artist_mode")
    private const val JSON_ENTRY = "tonedial.json"

    fun suggestedName(): String = "ToneDial-sauvegarde-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"

    data class ExportResult(val playlists: Int, val images: Int)

    suspend fun export(context: Context, target: Uri, library: List<Song>): Result<ExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val byId = library.associateBy { it.id }
            val referenced = HashSet<Long>()
            val playlists = JSONArray()
            UserStore.playlists.value.forEach { p ->
                referenced += p.songIds
                playlists.put(JSONObject().apply {
                    put("id", p.id)
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
            val lastPlayed = UserStore.lastPlayed.value
            referenced += lastPlayed.keys
            val lyrics = UserStore.allLyrics()
            referenced += lyrics.keys
            val overrides = LibraryPrefs.overrides.value
            referenced += overrides.keys
            val openings = OpeningsBatch.statusMap()
            referenced += openings.keys
            val stats = ListenStats.exportJson()
            stats.optJSONObject("songs")?.keys()?.forEach { k -> k.toLongOrNull()?.let { referenced += it } }
            referenced += LibraryPrefs.hidden.value

            // images : celles des titres et des albums sont reliées à un titre, pour être retrouvées ailleurs
            val images = Covers.allFiles()
            val albumRef = JSONObject()
            images.forEach { f ->
                val n = f.name.removeSuffix(".jpg")
                when {
                    n.startsWith("song_") -> n.removePrefix("song_").toLongOrNull()?.let { referenced += it }
                    n.startsWith("album_") -> n.removePrefix("album_").toLongOrNull()?.let { aid ->
                        library.firstOrNull { it.albumId == aid }?.let { s -> referenced += s.id; albumRef.put(aid.toString(), s.id) }
                    }
                }
            }

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
                put("format", 2)
                put("date", System.currentTimeMillis())
                put("songs", songs)
                put("playlists", playlists)
                put("favorites", JSONArray(favorites.toList()))
                put("favAdded", JSONObject(favorites.associateWith { UserStore.favoriteAddedAt(it) }.mapKeys { it.key.toString() }))
                put("playCounts", JSONObject(counts.mapKeys { it.key.toString() }))
                put("lastPlayed", JSONObject(lastPlayed.mapKeys { it.key.toString() }))
                put("stats", stats)
                put("openings", JSONObject().apply { openings.forEach { (id, st) -> put(id.toString(), st.artist.orEmpty()) } })
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
                put("hidden", JSONArray(LibraryPrefs.hidden.value.toList()))
                put("sortSongs", UserStore.sort.value.name)
                put("favSort", UserStore.favoritesSort().name)
                put("tabOrder", JSONArray(UserStore.tabOrder.value))
                put("albumRef", albumRef)
                put("settings", JSONObject().apply {
                    intKeys.forEach { k -> if (UserStore.has(k)) put(k, UserStore.getInt(k, 0)) }
                    boolKeys.forEach { k -> if (UserStore.has(k)) put(k, UserStore.getBool(k, false)) }
                    stringKeys.forEach { k -> UserStore.getString(k)?.let { put(k, it) } }
                })
            }
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Fichier inaccessible")
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(JSON_ENTRY))
                zip.write(root.toString(1).toByteArray())
                zip.closeEntry()
                images.forEach { f ->
                    zip.putNextEntry(ZipEntry("covers/" + f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            ExportResult(UserStore.playlists.value.size, images.size)
        }
    }

    data class ImportResult(val playlists: Int, val matched: Int, val missing: Int, val images: Int)

    private fun key(title: String, artist: String) = (title.trim() + "|" + artist.trim()).lowercase()

    suspend fun import(context: Context, source: Uri, library: List<Song>): Result<ImportResult> = withContext(Dispatchers.IO) {
        var tmp: File? = null
        runCatching {
            // copie locale du fichier choisi (.zip complet, ou .json des anciennes versions)
            tmp = File(context.cacheDir, "restore-" + System.currentTimeMillis())
            context.contentResolver.openInputStream(source)?.use { i -> tmp!!.outputStream().use { i.copyTo(it) } }
                ?: error("Fichier illisible")
            val isZip = tmp!!.inputStream().use { s -> val b = ByteArray(2); s.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
            val zip = if (isZip) ZipFile(tmp!!) else null
            try {
                val text = if (zip != null) {
                    val e = zip.getEntry(JSON_ENTRY) ?: error("Ce fichier n'est pas une sauvegarde Tone Dial")
                    zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                } else tmp!!.readText()
                val root = runCatching { JSONObject(text) }.getOrElse { error("Ce fichier n'est pas une sauvegarde Tone Dial") }
                require(root.optString("app") == "Tone Dial") { "Ce fichier n'est pas une sauvegarde Tone Dial" }
                restore(root, zip, library)
            } finally {
                zip?.close()
            }
        }.also { tmp?.delete() }
    }

    private fun restore(root: JSONObject, zip: ZipFile?, library: List<Song>): ImportResult {
        // correspondance ancien identifiant → titre actuel
        val byPath = library.associateBy { it.path }
        val byKey = library.groupBy { key(it.title, it.artist) }
        val byFile = library.groupBy { it.path.substringAfterLast('/').lowercase() }
        val byNewId = library.associateBy { it.id }
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
        val playlistIdMap = HashMap<Long, Long>()
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
                val finalId: Long
                if (existing >= 0) {
                    val p = current[existing]
                    val fresh = ids.filterNot { it in p.songIds }
                    current[existing] = p.copy(songIds = p.songIds + fresh, added = p.added + added.filterKeys { it in fresh })
                    finalId = p.id
                } else {
                    finalId = System.currentTimeMillis() + i
                    current += Playlist(finalId, name, ids, added)
                    runCatching { UserStore.setPlaylistSort(finalId, PlaylistSort.valueOf(o.optString("sort", "CUSTOM"))) }
                }
                if (o.has("id")) playlistIdMap[o.getLong("id")] = finalId
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
        // dates d'écoute, statistiques, openings identifiés
        root.optJSONObject("lastPlayed")?.let { lp ->
            val m = HashMap<Long, Long>()
            lp.keys().forEach { k -> idMap[k.toLong()]?.let { m[it] = lp.getLong(k) } }
            UserStore.mergeLastPlayed(m)
        }
        root.optJSONObject("stats")?.let { ListenStats.importJson(it) { old -> idMap[old] } }
        root.optJSONObject("openings")?.let { op ->
            val m = HashMap<Long, String>()
            op.keys().forEach { k -> idMap[k.toLong()]?.let { m[it] = op.optString(k) } }
            OpeningsBatch.mergeStatus(m)
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
        root.optJSONArray("hidden")?.let { h ->
            LibraryPrefs.setHidden(LibraryPrefs.hidden.value + (0 until h.length()).mapNotNull { idMap[h.getLong(it)] })
        }

        // réglages (format 1 : valeurs « non changées » marquées Int.MIN_VALUE)
        root.optJSONObject("settings")?.let { st ->
            intKeys.forEach { k -> if (st.has(k) && st.getInt(k) != Int.MIN_VALUE) UserStore.putInt(k, st.getInt(k)) }
            boolKeys.forEach { k -> if (st.has(k)) UserStore.putBool(k, st.getBoolean(k)) }
            stringKeys.forEach { k -> if (st.has(k)) UserStore.putString(k, st.getString(k)) }
        }
        runCatching { UserStore.setSort(SongSort.valueOf(root.optString("sortSongs"))) }
        runCatching { UserStore.setFavoritesSort(PlaylistSort.valueOf(root.optString("favSort"))) }
        root.optJSONArray("tabOrder")?.let { t -> UserStore.restoreTabOrder((0 until t.length()).map { t.getString(it) }) }

        // images
        var images = 0
        if (zip != null) {
            val albumRef = root.optJSONObject("albumRef") ?: JSONObject()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory || !e.name.startsWith("covers/")) continue
                val n = e.name.removePrefix("covers/").removeSuffix(".jpg")
                val target: String? = when {
                    n.startsWith("song_") -> n.removePrefix("song_").toLongOrNull()?.let { idMap[it] }?.let { "song_$it" }
                    n.startsWith("album_") -> n.removePrefix("album_").let { aid ->
                        if (albumRef.has(aid)) idMap[albumRef.getLong(aid)]?.let { byNewId[it] }?.let { "album_${it.albumId}" } else null
                    }
                    n.startsWith("playlist_") -> n.removePrefix("playlist_").toLongOrNull()?.let { playlistIdMap[it] }?.let { "playlist_$it" }
                    n.startsWith("artist_") -> n
                    else -> null
                }
                if (target != null && zip.getInputStream(e).use { Covers.restoreFile("$target.jpg", it.readBytes()) }) images++
            }
        }
        return ImportResult(plCount, idMap.size, missing, images)
    }
}
