package com.thousandlabs.tonedial.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Pochettes ajoutées par l'appli : image choisie dans la galerie, ou pochette officielle
 * trouvée en ligne (catalogue public iTunes). Rangées dans le stockage privé de l'appli.
 */
object Covers {

    private lateinit var dir: File
    private val stamps = ConcurrentHashMap<String, Long>()

    private val _version = MutableStateFlow(0)
    /** Change à chaque nouvelle pochette, pour rafraîchir l'affichage. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "covers").apply { mkdirs() }
        dir.listFiles()?.forEach { stamps[it.name] = it.lastModified() }
        // liste des titres sans pochette de la dernière recherche
        runCatching {
            val a = org.json.JSONArray(UserStore.getString("covers_missing") ?: "[]")
            val ids = (0 until a.length()).map { a.getLong(it) }
            if (ids.isNotEmpty()) _progress.value = Progress(finished = true, missing = ids)
        }
    }

    private fun songName(id: Long) = "song_$id.jpg"
    private fun albumName(id: Long) = "album_$id.jpg"

    fun stamp(songId: Long, albumId: Long): Long =
        (stamps[songName(songId)] ?: 0L) + (stamps[albumName(albumId)] ?: 0L)

    /** Image ajoutée par l'appli pour ce titre ou son album, si elle existe. */
    fun localBytes(songId: Long, albumId: Long): ByteArray? {
        if (!::dir.isInitialized) return null
        for (name in listOf(songName(songId), albumName(albumId))) {
            if (stamps.containsKey(name)) {
                val f = File(dir, name)
                if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
            }
        }
        return null
    }

    fun hasCustom(songId: Long): Boolean = stamps.containsKey(songName(songId))

    private fun save(name: String, bmp: Bitmap) {
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        stamps[name] = System.currentTimeMillis()
        _version.value++
    }

    /** Pochette choisie par l'utilisateur dans sa galerie. */
    suspend fun setCustom(context: Context, songId: Long, image: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(image)?.use { it.readBytes() } ?: return@runCatching false
            val bmp = ArtBytes.decode(bytes, 800) ?: return@runCatching false
            save(songName(songId), bmp)
            true
        }.getOrDefault(false)
    }

    fun removeCustom(songId: Long) {
        if (!::dir.isInitialized) return
        File(dir, songName(songId)).delete()
        stamps.remove(songName(songId))
        _version.value++
    }

    /** Le titre a-t-il déjà une image (ajoutée, d'album ou intégrée au fichier) ? */
    fun hasAnyArt(context: Context, song: Song): Boolean =
        localBytes(song.id, song.albumId) != null || ArtBytes.load(context, song.id, song.albumId, includeLocal = false) != null

    // ------------------------------------------------------------------
    // Vrais albums et faux albums
    // ------------------------------------------------------------------

    private val fakeAlbumWords = listOf(
        "download", "youtube", "www", ".com", ".net", ".cc", "telegram", "whatsapp", "music", "musique",
        "unknown", "inconnu", "mp3", "audio", "songs", "single", "tubidy", "y2mate", "savefrom", "snaptube",
    )

    /**
     * Un groupe de titres est un vrai album s'il a un nom plausible et peu d'artistes différents.
     * Les téléchargeurs mettent souvent le même faux album (nom du site, du dossier…) sur tous leurs fichiers.
     */
    fun isRealAlbum(albumSongs: List<Song>): Boolean {
        val first = albumSongs.firstOrNull() ?: return false
        val name = first.album.lowercase().trim()
        if (name.isBlank() || first.album == "Album inconnu") return false
        if (fakeAlbumWords.any { name.contains(it) }) return false
        if (albumSongs.any { it.folder.equals(first.album, ignoreCase = true) }) return false
        if (albumSongs.size > 40) return false
        val artists = albumSongs.mapNotNull { it.artists.firstOrNull()?.lowercase() }.filter { it != "artiste inconnu" }.distinct()
        return artists.size <= 2
    }

    /** Retire les pochettes d'album enregistrées pour de faux albums (ancienne version de la recherche). */
    fun cleanupFakeAlbums(library: List<Song>) {
        if (!::dir.isInitialized) return
        val byAlbum = library.groupBy { it.albumId }
        var removed = 0
        stamps.keys.filter { it.startsWith("album_") }.forEach { name ->
            val id = name.removePrefix("album_").removeSuffix(".jpg").toLongOrNull() ?: return@forEach
            val songs = byAlbum[id] ?: return@forEach
            if (!isRealAlbum(songs)) {
                File(dir, name).delete()
                stamps.remove(name)
                removed++
            }
        }
        if (removed > 0) _version.value++
    }

    // ------------------------------------------------------------------
    // Recherche en ligne
    // ------------------------------------------------------------------

    /** Cherche et enregistre la pochette d'un titre. Renvoie true si une image a été trouvée. */
    suspend fun fetchOnline(song: Song, realAlbum: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        val found = runCatching { CoverSearch.find(song) }.getOrNull() ?: return@withContext false
        val bytes = CoverSearch.download(found.imageUrl) ?: return@withContext false
        val bmp = ArtBytes.decode(bytes, 600) ?: return@withContext false
        save(if (realAlbum) albumName(song.albumId) else songName(song.id), bmp)
        true
    }

    // ------------------------------------------------------------------
    // Recherche groupée pour toute la bibliothèque
    // ------------------------------------------------------------------

    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val found: Int = 0,
        val finished: Boolean = false,
        val missing: List<Long> = emptyList(),
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val autoTried = ConcurrentHashMap.newKeySet<Long>()

    fun autoEnabled(): Boolean = UserStore.getBool("covers_auto", true)
    fun setAutoEnabled(on: Boolean) = UserStore.putBool("covers_auto", on)

    /** Pendant la lecture : cherche la pochette du titre en cours s'il n'en a pas. */
    fun autoFetch(context: Context, song: Song, library: List<Song> = emptyList()) {
        if (!autoEnabled() || !autoTried.add(song.id)) return
        scope.launch {
            if (!hasAnyArt(context, song)) {
                val albumSongs = library.filter { it.albumId == song.albumId }
                fetchOnline(song, realAlbum = albumSongs.isNotEmpty() && isRealAlbum(albumSongs))
            }
        }
    }

    fun startBatch(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            cleanupFakeAlbums(songs)
            // un représentant par vrai album, chaque titre sinon
            val byAlbum = songs.groupBy { it.albumId }
            val tasks = ArrayList<Pair<Song, Boolean>>()
            byAlbum.values.forEach { list ->
                if (isRealAlbum(list)) tasks += list.first() to true
                else list.forEach { tasks += it to false }
            }
            _progress.value = Progress(running = true, total = tasks.size)
            var found = 0
            val missing = ArrayList<Long>()
            tasks.forEachIndexed { i, (song, real) ->
                if (!isActive) return@launch
                val members = if (real) byAlbum[song.albumId].orEmpty() else listOf(song)
                if (members.none { hasAnyArt(app, it) }) {
                    if (fetchOnline(song, realAlbum = real)) found++ else missing += members.map { it.id }
                }
                _progress.value = Progress(running = true, done = i + 1, total = tasks.size, found = found, missing = missing.toList())
            }
            _progress.value = Progress(running = false, done = tasks.size, total = tasks.size, found = found, finished = true, missing = missing.toList())
            saveMissing(missing)
        }
    }

    fun stopBatch() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false, finished = true)
    }

    /** Retire un titre de la liste « sans pochette » (après en avoir ajouté une). */
    fun markResolved(songId: Long) {
        val p = _progress.value
        if (songId in p.missing) {
            _progress.value = p.copy(missing = p.missing - songId)
            saveMissing(_progress.value.missing)
        }
    }

    private fun saveMissing(ids: List<Long>) {
        UserStore.putString("covers_missing", org.json.JSONArray(ids).toString())
    }
}
