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
    // Recherche en ligne
    // ------------------------------------------------------------------

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[(\[].*?[)\]]"""), "")
            .replace(Regex("""[^a-z0-9 ]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    /** Cherche et enregistre la pochette d'un titre. Renvoie true si une image a été trouvée. */
    suspend fun fetchOnline(song: Song): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        val artist = song.artists.firstOrNull()?.takeUnless { it == "Artiste inconnu" }
        val title = norm(song.title)
        if (title.isBlank()) return@withContext false
        val term = listOfNotNull(artist, song.title.substringBefore(" (").substringBefore(" [")).joinToString(" ")
        val url = "https://itunes.apple.com/search?media=music&entity=song&limit=8&term=" + URLEncoder.encode(term, "UTF-8")
        val json = httpText(url) ?: return@withContext false
        val results = runCatching { JSONObject(json).getJSONArray("results") }.getOrNull() ?: return@withContext false
        var best: String? = null
        var bestScore = 0
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val art = r.optString("artworkUrl100")
            if (art.isBlank()) continue
            val t = norm(r.optString("trackName"))
            val a = norm(r.optString("artistName"))
            var score = 0
            if (t == title) score += 4 else if (t.contains(title) || title.contains(t)) score += 2
            if (artist != null) {
                val na = norm(artist)
                if (a == na) score += 4 else if (a.contains(na) || na.contains(a)) score += 2
            }
            // il faut au moins un titre identique, ou un titre proche avec le bon artiste
            if (score >= 4 && score > bestScore) {
                bestScore = score
                best = art
            }
        }
        val artUrl = best?.replace("100x100bb", "600x600bb") ?: return@withContext false
        val bytes = httpBytes(artUrl) ?: return@withContext false
        val bmp = ArtBytes.decode(bytes, 600) ?: return@withContext false
        val name = if (song.album != "Album inconnu") albumName(song.albumId) else songName(song.id)
        save(name, bmp)
        true
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "ToneDial (https://github.com/ThousandLabs/Tone-Dial)")
        }

    private fun httpText(url: String): String? = runCatching {
        val c = open(url)
        try {
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun httpBytes(url: String): ByteArray? = runCatching {
        val c = open(url)
        try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Recherche groupée pour toute la bibliothèque
    // ------------------------------------------------------------------

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val found: Int = 0, val finished: Boolean = false)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val autoTried = ConcurrentHashMap.newKeySet<Long>()

    fun autoEnabled(): Boolean = UserStore.getBool("covers_auto", true)
    fun setAutoEnabled(on: Boolean) = UserStore.putBool("covers_auto", on)

    /** Pendant la lecture : cherche la pochette du titre en cours s'il n'en a pas. */
    fun autoFetch(context: Context, song: Song) {
        if (!autoEnabled() || !autoTried.add(song.id)) return
        scope.launch {
            if (!hasAnyArt(context, song)) fetchOnline(song)
        }
    }

    fun startBatch(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            // un représentant par album connu, chaque titre sinon
            val groups = songs.groupBy { if (it.album != "Album inconnu") "a${it.albumId}" else "s${it.id}" }.values.map { it.first() }
            _progress.value = Progress(running = true, total = groups.size)
            var found = 0
            groups.forEachIndexed { i, song ->
                if (!isActive) return@launch
                if (!hasAnyArt(app, song)) {
                    if (fetchOnline(song)) found++
                    delay(3100) // le catalogue limite à environ 20 recherches par minute
                }
                _progress.value = Progress(running = true, done = i + 1, total = groups.size, found = found)
            }
            _progress.value = Progress(running = false, done = groups.size, total = groups.size, found = found, finished = true)
        }
    }

    fun stopBatch() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false)
    }
}
