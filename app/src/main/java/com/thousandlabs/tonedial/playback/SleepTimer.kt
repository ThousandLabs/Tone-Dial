package com.thousandlabs.tonedial.playback

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minuterie de sommeil : met la musique en pause après un délai ou à la fin du morceau. */
object SleepTimer {

    data class State(val endAt: Long? = null, val endOfTrack: Boolean = false) {
        val active: Boolean get() = endAt != null || endOfTrack
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        PlaybackService.instance?.player?.pause()
        _state.value = State()
    }

    fun start(minutes: Int) {
        cancel()
        val delay = minutes * 60_000L
        handler.postDelayed(stopRunnable, delay)
        _state.value = State(endAt = System.currentTimeMillis() + delay)
    }

    fun endOfTrack() {
        cancel()
        PlaybackService.instance?.player?.pauseAtEndOfMediaItems = true
        _state.value = State(endOfTrack = true)
    }

    fun cancel() {
        handler.removeCallbacks(stopRunnable)
        PlaybackService.instance?.player?.pauseAtEndOfMediaItems = false
        _state.value = State()
    }

    internal fun onPausedAtEndOfTrack() {
        if (_state.value.endOfTrack) cancel()
    }
}
