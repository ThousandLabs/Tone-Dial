package com.thousandlabs.tonedial.data

import android.content.Context
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
        job = scope.launch {
            _progress.value = Progress(running = true, total = songs.size)
            var found = 0
            songs.forEachIndexed { i, song ->
                if (!isActive) return@launch
                val has = runCatching { LyricsLoader.load(app, song) }.getOrNull()?.lines?.isNotEmpty() == true
                if (!has) {
                    val text = LyricsOnline.find(song)
                    if (text != null) {
                        UserStore.setCustomLyrics(song.id, text, source = "lrclib")
                        found++
                    }
                    delay(350)
                }
                _progress.value = Progress(running = true, done = i + 1, total = songs.size, found = found)
            }
            _progress.value = Progress(done = songs.size, total = songs.size, found = found, finished = true)
        }
    }

    /**
     * Remplace les paroles en écriture japonaise déjà enregistrées (trouvées sur LRCLIB)
     * par une version romaji quand elle existe. Les paroles collées à la main ne sont pas touchées.
     */
    fun startRomaji(songs: List<Song>) {
        if (job?.isActive == true) return
        val list = songs.filter { s ->
            UserStore.customLyricsSource(s.id) == "lrclib" && UserStore.customLyrics(s.id)?.let { LyricsOnline.isJapanese(it) } == true
        }
        job = scope.launch {
            _progress.value = Progress(running = true, total = list.size)
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
            _progress.value = Progress(done = list.size, total = list.size, found = found, finished = true)
        }
    }

    fun stop() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false, finished = true)
    }
}
