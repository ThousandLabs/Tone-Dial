package com.thousandlabs.tonedial.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.thousandlabs.tonedial.MainActivity
import com.thousandlabs.tonedial.R
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.UserStore
import com.thousandlabs.tonedial.widget.PlayerWidget

/**
 * Service de lecture : continue la musique en arrière-plan, affiche la notification
 * et les contrôles sur l'écran verrouillé, gère les écouteurs et le Bluetooth.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        @Volatile
        var instance: PlaybackService? = null
            private set
    }

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private val saveLoop = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                UserStore.savePosition(playPosition(), player.currentPosition)
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        UserStore.init(this)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        AudioFx.attach(player.audioSessionId)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                if (id != null && (player.playWhenReady || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) {
                    UserStore.recordPlay(id)
                }
                UserStore.savePosition(playPosition(), 0L)
                PlayerWidget.updateAll(this@PlaybackService)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) UserStore.savePosition(playPosition(), player.currentPosition)
                PlayerWidget.updateAll(this@PlaybackService)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    SleepTimer.onPausedAtEndOfTrack()
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueueNow()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                saveQueueNow()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                UserStore.putInt("queue_repeat", repeatMode)
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .setBitmapLoader(ArtBitmapLoader(this))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        instance = this
        handler.post(saveLoop)
        PlayerWidget.updateAll(this)
    }

    /** Ordre de lecture réel (mélangé si l'aléatoire est actif). */
    private fun playOrder(): List<Int> {
        val tl = player.currentTimeline
        if (tl.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val out = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    private fun playPosition(): Int =
        if (!player.shuffleModeEnabled) player.currentMediaItemIndex
        else playOrder().indexOf(player.currentMediaItemIndex).coerceAtLeast(0)

    /**
     * Mémorise la file dans l'ordre affiché (y compris un ordre aléatoire réorganisé à la main),
     * pour la retrouver telle quelle au prochain lancement.
     */
    fun saveQueueNow() {
        val order = playOrder()
        val ids = order.mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        UserStore.saveQueue(ids, order.indexOf(player.currentMediaItemIndex).coerceAtLeast(0), player.currentPosition)
        UserStore.putBool("queue_shuffle", player.shuffleModeEnabled)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveLoop)
        if (::player.isInitialized && player.mediaItemCount > 0) {
            UserStore.savePosition(playPosition(), player.currentPosition)
        }
        SleepTimer.cancel()
        instance = null
        AudioFx.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        PlayerWidget.updateAll(this)
        super.onDestroy()
    }

    /** Reconstruit l'adresse du fichier à partir de l'identifiant du morceau. */
    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val id = item.mediaId.toLongOrNull()
                if (id != null) item.buildUpon().setUri(Song.songUri(id)).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
