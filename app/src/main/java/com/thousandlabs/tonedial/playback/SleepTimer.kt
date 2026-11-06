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
    // Fondu de 6 secondes avant la pause, pour ne pas couper la musique d'un coup
    private const val FADE_STEPS = 30
    private const val FADE_STEP_MS = 200L
    private var fadeStep = 0

    private val fadeRunnable = object : Runnable {
        override fun run() {
            val p = PlaybackService.instance?.player
            if (p == null) {
                _state.value = State(); return
            }
            fadeStep++
            val v = 1f - fadeStep.toFloat() / FADE_STEPS
            if (v <= 0f || !p.isPlaying) {
                p.pause()
                p.volume = 1f
                _state.value = State()
            } else {
                p.volume = v
                handler.postDelayed(this, FADE_STEP_MS)
            }
        }
    }

    private val stopRunnable = Runnable {
        fadeStep = 0
        handler.post(fadeRunnable)
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
        handler.removeCallbacks(fadeRunnable)
        PlaybackService.instance?.player?.let {
            it.pauseAtEndOfMediaItems = false
            it.volume = 1f
        }
        _state.value = State()
    }

    internal fun onPausedAtEndOfTrack() {
        if (_state.value.endOfTrack) cancel()
    }
}
