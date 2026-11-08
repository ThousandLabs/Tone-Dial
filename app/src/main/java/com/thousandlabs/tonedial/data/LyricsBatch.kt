package com.thousandlabs.tonedial.data

import android.content.Context
import com.thousandlabs.tonedial.playback.BatchService
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

/** Recherche des paroles manquantes pour toute la bibliothèque, en arrière-plan. */
object LyricsBatch {

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val found: Int = 0, val finished: Boolean = false)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun autoEnabled(): Boolean = UserStore.getBool("lyrics_auto", true)
    fun setAutoEnabled(on: Boolean) = UserStore.putBool("lyrics_auto", on)

    fun start(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        UserStore.putString("lyrics_batch_pending", "missing")
        _progress.value = Progress(running = true, total = songs.size)
        BatchService.start(app)
        job = scope.launch {
            var found = 0
            val missing = LinkedHashSet(missingIds())
            songs.forEachIndexed { i, song ->
                if (!isActive) return@launch
                val has = runCatching { LyricsLoader.load(app, song) }.getOrNull()?.lines?.isNotEmpty() == true
                if (!has) {
                    val text = LyricsOnline.find(song)
                    if (text != null) {
                        UserStore.setCustomLyrics(song.id, text, source = "lrclib")
                        missing -= song.id
                        found++
                    } else missing += song.id
                    delay(350)
                } else missing -= song.id
                _progress.value = Progress(running = true, done = i + 1, total = songs.size, found = found)
                if ((i + 1) % 20 == 0) saveMissing(missing)
            }
            saveMissing(missing)
            UserStore.putString("lyrics_batch_pending", "")
            _progress.value = Progress(done = songs.size, total = songs.size, found = found, finished = true)
        }
    }

    /**
     * Remplace les paroles en écriture japonaise déjà enregistrées (trouvées sur LRCLIB)
     * par une version romaji quand elle existe. Les paroles collées à la main ne sont pas touchées.
     */
    fun startRomaji(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        UserStore.putString("lyrics_batch_pending", "romaji")
        val list = songs.filter { s ->
            UserStore.customLyricsSource(s.id) == "lrclib" && UserStore.customLyrics(s.id)?.let { LyricsOnline.isJapanese(it) } == true
        }
        _progress.value = Progress(running = true, total = list.size)
        BatchService.start(context.applicationContext)
        job = scope.launch {
            var found = 0
            list.forEachIndexed { i, song ->
                if (!isActive) return@launch
                val text = LyricsOnline.find(song)
                if (text != null && LyricsOnline.looksRomaji(text)) {
                    UserStore.setCustomLyrics(song.id, text, source = "lrclib")
                    found++
                }
                delay(350)
                _progress.value = Progress(running = true, done = i + 1, total = list.size, found = found)
            }
            UserStore.putString("lyrics_batch_pending", "")
            _progress.value = Progress(done = list.size, total = list.size, found = found, finished = true)
        }
    }

    /** Reprend une recherche interrompue ; les titres déjà traités sont sautés d'eux-mêmes. */
    fun resumeIfNeeded(context: Context, songs: List<Song>) {
        if (job?.isActive == true) return
        when (UserStore.getString("lyrics_batch_pending")) {
            "missing" -> start(context, songs)
            "romaji" -> startRomaji(context, songs)
        }
    }

    /** Titres pour lesquels aucune parole n'a été trouvée en ligne (dernières recherches). */
    fun missingIds(): List<Long> = runCatching {
        val a = org.json.JSONArray(UserStore.getString("lyrics_missing") ?: "[]")
        (0 until a.length()).map { a.getLong(it) }
    }.getOrDefault(emptyList())

    private fun saveMissing(ids: Collection<Long>) = UserStore.putString("lyrics_missing", org.json.JSONArray(ids.toList()).toString())

    fun stop() {
        job?.cancel()
        UserStore.putString("lyrics_batch_pending", "")
        _progress.value = _progress.value.copy(running = false, finished = true)
    }
}
