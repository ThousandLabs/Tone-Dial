package com.thousandlabs.tonedial.data

import android.content.Context
import com.thousandlabs.tonedial.playback.BatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Identification de tous les openings et endings, hors de l'écran de l'appli :
 * elle continue quand on quitte Tone Dial (service avec notification) et reprend
 * au lancement suivant si elle a été interrompue.
 */
object OpeningsBatch {

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val found: Int = 0, val finished: Boolean = false)

    /** Résultat mémorisé pour un titre : artiste trouvé, ou null si rien n'a été trouvé. */
    data class Status(val artist: String?)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _status = MutableStateFlow<Map<Long, Status>>(emptyMap())
    val status: StateFlow<Map<Long, Status>> = _status.asStateFlow()

    /** Titre dont l'artiste vient d'être appliqué (l'écran met alors sa liste à jour). */
    private val _applied = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val applied: SharedFlow<Long> = _applied.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val raw = UserStore.getString("openings_status", null) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        _status.value = o.keys().asSequence().mapNotNull { k ->
            k.toLongOrNull()?.let { id -> id to Status(o.optString(k).ifBlank { null }) }
        }.toMap()
    }

    private fun saveStatus() {
        val o = JSONObject()
        _status.value.forEach { (id, s) -> o.put(id.toString(), s.artist.orEmpty()) }
        UserStore.putString("openings_status", o.toString())
    }

    fun statusMap(): Map<Long, Status> {
        load()
        return _status.value
    }

    /** Enregistre le résultat d'une identification (groupée ou titre par titre). */
    fun mark(songId: Long, artist: String?) {
        load()
        _status.value = _status.value + (songId to Status(artist?.ifBlank { null }))
        saveStatus()
    }

    private fun pending(): List<Long> =
        UserStore.getString("openings_pending", null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    private fun setPending(ids: List<Long>) =
        UserStore.putString("openings_pending", ids.joinToString(","))

    /** Lance l'identification de tous les openings et endings de [songs]. */
    fun start(context: Context, songs: List<Song>) {
        run(context, songs.filter { Covers.looksAnime(it) })
    }

    /** Reprend une identification interrompue (appli fermée de force, téléphone redémarré…). */
    fun resumeIfNeeded(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        val ids = pending().toSet()
        if (ids.isEmpty()) return
        val list = songs.filter { it.id in ids }
        if (list.isEmpty()) setPending(emptyList()) else run(context, list)
    }

    private fun run(context: Context, list: List<Song>) {
        if (job?.isActive == true) return
        load()
        val app = context.applicationContext
        setPending(list.map { it.id })
        _progress.value = Progress(running = true, total = list.size)
        BatchService.start(app)
        job = scope.launch {
            var found = 0
            val remaining = list.map { it.id }.toMutableList()
            list.forEachIndexed { i, song ->
                if (!isActive) return@launch
                val r = runCatching { CoverSearch.identify(song) }.getOrNull()
                if (r != null && r.artist.isNotBlank()) {
                    apply(song, r)
                    found++
                } else {
                    mark(song.id, null)
                }
                remaining.remove(song.id)
                setPending(remaining)
                _progress.value = Progress(running = true, done = i + 1, total = list.size, found = found)
                delay(700) // MyAnimeList limite le nombre de requêtes
            }
            setPending(emptyList())
            _progress.value = Progress(done = list.size, total = list.size, found = found, finished = true)
        }
    }

    /** Artiste (le titre reste tel quel), pochette et paroles. */
    private suspend fun apply(song: Song, id: CoverSearch.Identity) {
        LibraryPrefs.setOverride(song.id, LibraryPrefs.Override(null, id.artist, null))
        mark(song.id, id.artist)
        _applied.tryEmit(song.id)
        id.image?.let { if (!Covers.hasCustom(song.id)) runCatching { Covers.saveSongImage(song.id, it) } }
        Covers.markResolved(song.id)
        if (UserStore.customLyrics(song.id) == null) {
            val text = runCatching { LyricsOnline.find(LibraryPrefs.apply(song)) }.getOrNull()
            if (text != null) UserStore.setCustomLyrics(song.id, text, source = "lrclib")
        }
        _applied.tryEmit(song.id)
    }

    fun stop() {
        job?.cancel()
        setPending(emptyList())
        _progress.value = _progress.value.copy(running = false, finished = true)
    }
}
