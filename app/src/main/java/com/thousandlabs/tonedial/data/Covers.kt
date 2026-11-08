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

    fun stamp(songId: Long, albumId: Long, artist: String? = null): Long =
        (stamps[songName(songId)] ?: 0L) + (stamps[albumName(albumId)] ?: 0L) +
            (artist?.let { stamps[artistFileName(it)] } ?: 0L)

    // ---------- Images des artistes ----------
    private fun artistFileName(name: String): String {
        val md = java.security.MessageDigest.getInstance("MD5").digest(name.trim().lowercase().toByteArray())
        return "artist_" + md.joinToString("") { "%02x".format(it) }.take(16) + ".jpg"
    }

    fun artistFile(name: String): File? {
        if (!::dir.isInitialized || name == "Artiste inconnu") return null
        val n = artistFileName(name)
        if (!stamps.containsKey(n)) return null
        return File(dir, n).takeIf { it.exists() }
    }

    fun artistBytes(name: String): ByteArray? = artistFile(name)?.let { runCatching { it.readBytes() }.getOrNull() }

    private val artistFailed: MutableSet<String> by lazy {
        val set = ConcurrentHashMap.newKeySet<String>()
        runCatching {
            val a = org.json.JSONArray(UserStore.getString("artist_img_failed") ?: "[]")
            for (i in 0 until a.length()) set += a.getString(i)
        }
        set
    }

    private fun saveArtistFailed() {
        UserStore.putString("artist_img_failed", org.json.JSONArray(artistFailed.toList()).toString())
    }

    /** Cherche et enregistre l'image d'un artiste ou d'un groupe. */
    suspend fun fetchArtistImage(name: String, isAnime: Boolean = false, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized || name == "Artiste inconnu" || name.length < 2) return@withContext false
        if (artistFile(name) != null) return@withContext true
        val key = name.trim().lowercase()
        if (!force && key in artistFailed) return@withContext false
        val url = runCatching { CoverSearch.artistImage(name, isAnime) }.getOrNull()
        val bmp = url?.let { CoverSearch.download(it) }?.let { ArtBytes.decode(it, 600) }
        if (bmp == null) {
            artistFailed += key
            saveArtistFailed()
            return@withContext false
        }
        save(artistFileName(name), bmp)
        artistFailed -= key
        true
    }

    /** Une seule fois : oublie les photos d'anime venues d'un artiste homonyme, et les échecs passés. */
    fun resetAnimeArtistImages(animeNames: List<String>) {
        if (!::dir.isInitialized) return
        animeNames.forEach { n ->
            val f = artistFileName(n)
            File(dir, f).delete()
            stamps.remove(f)
        }
        artistFailed.clear()
        saveArtistFailed()
        _version.value++
    }

    private var artistJob: Job? = null

    /** Complète en arrière-plan les images manquantes d'une liste d'artistes. */
    fun fetchArtistImages(artists: List<Pair<String, Boolean>>, force: Boolean = false) {
        if (!autoEnabled() && !force) return
        if (artistJob?.isActive == true) return
        artistJob = scope.launch {
            artists.distinctBy { it.first.lowercase() }.forEach { (name, anime) ->
                if (!isActive) return@launch
                if (artistFile(name) == null && (force || name.trim().lowercase() !in artistFailed)) {
                    fetchArtistImage(name, anime, force)
                    delay(300)
                }
            }
        }
    }

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

    /** Enregistre comme pochette du titre une image trouvée en ligne. */
    suspend fun saveSongImage(songId: Long, url: String): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        val bmp = CoverSearch.download(url)?.let { ArtBytes.decode(it, 600) } ?: return@withContext false
        save(songName(songId), bmp)
        true
    }

    // ---------- Sauvegarde ----------
    /** Toutes les images gardées par l'appli (titres, albums, artistes, playlists). */
    fun allFiles(): List<File> =
        if (!::dir.isInitialized) emptyList() else dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") && it.length() > 0 }.orEmpty()

    /** Restauration d'une image ; une image déjà présente ici n'est pas remplacée. Renvoie true si ajoutée. */
    fun restoreFile(name: String, bytes: ByteArray): Boolean {
        if (!::dir.isInitialized || !Regex("^(song|album|artist|playlist)_[A-Za-z0-9-]+\\.jpg$").matches(name) || bytes.isEmpty()) return false
        val f = File(dir, name)
        if (f.exists() && stamps.containsKey(name)) return false
        f.writeBytes(bytes)
        stamps[name] = System.currentTimeMillis()
        _version.value++
        return true
    }

    // ---------- Images des playlists ----------
    private fun playlistName(id: Long) = "playlist_$id.jpg"

    /** Image choisie pour une playlist, ou null. */
    fun playlistFile(id: Long): File? {
        if (!::dir.isInitialized || !stamps.containsKey(playlistName(id))) return null
        return File(dir, playlistName(id)).takeIf { it.exists() }
    }

    suspend fun setPlaylistImage(context: Context, id: Long, image: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(image)?.use { it.readBytes() } ?: return@runCatching false
            val bmp = ArtBytes.decode(bytes, 800) ?: return@runCatching false
            save(playlistName(id), bmp)
            true
        }.getOrDefault(false)
    }

    fun removePlaylistImage(id: Long) {
        if (!::dir.isInitialized) return
        File(dir, playlistName(id)).delete()
        stamps.remove(playlistName(id))
        _version.value++
    }

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
            // image de l'artiste, utilisée quand un titre n'a pas de pochette
            song.artists.filter { it != "Artiste inconnu" }.take(2).forEach { a ->
                if (artistFile(a) == null) fetchArtistImage(a, isAnime = looksAnime(song))
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
            // puis les images des artistes (nouvel essai pour ceux qui avaient échoué)
            artistFailed.clear()
            val artists = songs.flatMap { s -> s.artists.filter { it != "Artiste inconnu" }.map { it to looksAnime(s) } }
            artists.distinctBy { it.first.lowercase() }.forEach { (name, anime) ->
                if (!isActive) return@launch
                if (artistFile(name) == null) {
                    fetchArtistImage(name, anime, force = true)
                    delay(300)
                }
            }
        }
    }

    private val animeWords = Regex("""\b(opening|op|ending|ed)\s*\d{1,3}\b""", RegexOption.IGNORE_CASE)

    /** Le titre est-il un opening ou un ending d'anime ? */
    fun looksAnime(song: Song): Boolean =
        animeWords.containsMatchIn(song.title) || animeWords.containsMatchIn(song.path.substringAfterLast('/'))

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
