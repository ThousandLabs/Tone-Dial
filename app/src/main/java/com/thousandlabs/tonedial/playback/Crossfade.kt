package com.thousandlabs.tonedial.playback

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.thousandlabs.tonedial.data.Features
import com.thousandlabs.tonedial.data.Song
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fondu enchaîné : pendant les dernières secondes d'un titre, le suivant démarre
 * sur un second lecteur et monte pendant que le premier baisse. Quand le titre se termine,
 * le lecteur principal reprend le suivant là où le second lecteur est arrivé, puis le second s'arrête.
 * En cas de doute (avance manuelle, recherche dans le titre, erreur), le fondu est annulé
 * et la lecture reste normale.
 */
@OptIn(UnstableApi::class)
class Crossfade(private val context: Context, private val main: ExoPlayer, private val handler: Handler) {

    private enum class Phase { IDLE, FADING, HANDOFF }

    private var phase = Phase.IDLE
    private var other: ExoPlayer? = null
    private var fromIndex = C.INDEX_UNSET
    private var toIndex = C.INDEX_UNSET
    private var fadeStartPos = 0L
    private var fadeLen = 1L
    private var handoffStart = 0L
    private var rampStart = 0L
    private var looping = false

    private val tick = object : Runnable {
        override fun run() {
            runCatching { step() }.onFailure { cancel() }
            if (looping) handler.postDelayed(this, 80)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        handler.post(tick)
    }

    private fun stopLoop() {
        looping = false
        handler.removeCallbacks(tick)
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            other?.let { if (phase == Phase.FADING && !it.isPlaying) it.play() }
            startLoop()
        } else if (!main.playWhenReady) {
            // pause demandée : le titre suivant attend aussi
            when (phase) {
                Phase.FADING -> other?.pause()
                Phase.HANDOFF -> finish()
                Phase.IDLE -> {}
            }
            if (phase == Phase.IDLE) stopLoop()
        }
        // sinon : simple chargement, le fondu continue
    }

    fun onTransition(reason: Int) {
        if (phase != Phase.FADING) return
        val o = other
        if (o != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && main.currentMediaItemIndex == toIndex) {
            // le lecteur principal reprend le titre suivant là où le second est arrivé
            main.volume = 0f
            phase = Phase.HANDOFF
            handoffStart = SystemClock.elapsedRealtime()
            rampStart = 0L
            main.seekTo(o.currentPosition + 120)
        } else {
            cancel()
        }
    }

    /** Recherche dans le titre pendant le fondu : on annule. */
    fun onSeek() {
        if (phase == Phase.FADING) cancel()
    }

    fun onError() = cancel()

    private fun step() {
        when (phase) {
            Phase.IDLE -> {
                if (!main.playWhenReady) { stopLoop(); return }
                maybeStart()
            }
            Phase.FADING -> {
                val o = other ?: return cancel()
                if (main.currentMediaItemIndex != fromIndex) return
                val p = ((main.currentPosition - fadeStartPos).toFloat() / fadeLen).coerceIn(0f, 1f)
                // fondu à puissance constante : pas de creux de volume au milieu
                main.volume = cos(p * PI / 2).toFloat()
                o.volume = sin(p * PI / 2).toFloat()
            }
            Phase.HANDOFF -> {
                val o = other ?: return finish()
                val now = SystemClock.elapsedRealtime()
                if (main.playbackState == Player.STATE_READY && main.isPlaying) {
                    if (rampStart == 0L) rampStart = now
                    val q = ((now - rampStart) / 300f).coerceIn(0f, 1f)
                    main.volume = q
                    o.volume = 1f - q
                    if (q >= 1f) finish()
                } else if (now - handoffStart > 4000) {
                    finish()
                }
            }
        }
    }

    private fun maybeStart() {
        if (!Features.crossfade.on || !main.isPlaying) return
        if (main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
        // minuterie « fin du morceau » : on laisse le titre se terminer seul
        if (SleepTimer.state.value.endOfTrack) return
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val len = Features.crossfadeSeconds() * 1000L
        if (duration < len * 3) return
        val remaining = duration - main.currentPosition
        if (remaining > len || remaining < 1200) return
        val next = main.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        val current = main.currentMediaItem ?: return
        val nextItem = main.getMediaItemAt(next)
        if (!main.shuffleModeEnabled && albumSequence(current, nextItem)) return
        val id = nextItem.mediaId.toLongOrNull() ?: return

        val o = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false,
            )
            .build()
        o.setMediaItem(MediaItem.fromUri(Song.songUri(id)))
        o.playbackParameters = main.playbackParameters
        o.volume = 0f
        o.prepare()
        o.play()
        other = o
        fromIndex = main.currentMediaItemIndex
        toIndex = next
        fadeStartPos = main.currentPosition
        fadeLen = remaining.coerceAtLeast(1L)
        phase = Phase.FADING
    }

    /** Titres qui se suivent dans un même album (pistes n et n+1) : enchaînement d'origine respecté. */
    private fun albumSequence(a: MediaItem, b: MediaItem): Boolean {
        val albumA = a.mediaMetadata.albumTitle?.toString().orEmpty()
        val albumB = b.mediaMetadata.albumTitle?.toString().orEmpty()
        val ta = a.mediaMetadata.trackNumber ?: return false
        val tb = b.mediaMetadata.trackNumber ?: return false
        return albumA.isNotBlank() && albumA == albumB && tb == ta + 1
    }

    /** Fin normale du passage de relais. */
    private fun finish() {
        other?.release()
        other = null
        main.volume = 1f
        phase = Phase.IDLE
    }

    /** Abandon du fondu : tout revient à la normale. */
    fun cancel() {
        val wasActive = phase != Phase.IDLE || other != null
        other?.release()
        other = null
        phase = Phase.IDLE
        if (wasActive) main.volume = 1f
    }

    fun release() {
        stopLoop()
        cancel()
    }
}
