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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        /** Messages pour l'écran (fichier illisible…). */
        val events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

        private const val CMD_FAVORITE = "tonedial.FAVORITE"
        private const val CMD_SHUFFLE = "tonedial.SHUFFLE"

        // réglages lus par le service
        fun skipSilence(): Boolean = UserStore.getBool("skip_silence", false)
        fun resumeOnHeadset(): Boolean = UserStore.getBool("resume_on_headset", false)
        fun notifButtons(): Boolean = UserStore.getBool("notif_buttons", true)
    }

    private val scope = kotlinx.coroutines.MainScope()
    private var errorsInARow = 0
    private var audioManager: android.media.AudioManager? = null

    lateinit var player: ExoPlayer
        private set
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastTick = 0L

    /** Toutes les 10 secondes : position de reprise et temps d'écoute pour les statistiques. */
    private val saveLoop = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (::player.isInitialized && player.isPlaying) {
                UserStore.savePosition(playPosition(), player.currentPosition)
                val id = player.currentMediaItem?.mediaId?.toLongOrNull()
                if (id != null && lastTick > 0) {
                    val elapsed = ((now - lastTick) * player.playbackParameters.speed).toLong().coerceAtMost(15_000)
                    com.thousandlabs.tonedial.data.ListenStats.addListening(id, elapsed)
                }
            }
            lastTick = if (::player.isInitialized && player.isPlaying) now else 0L
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        UserStore.init(this)
        com.thousandlabs.tonedial.data.Covers.init(this)

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
        player.skipSilenceEnabled = skipSilence()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(audioSessionId)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // fichier supprimé, déplacé ou abîmé : on passe au suivant au lieu de rester bloqué
                errorsInARow++
                val title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                if (errorsInARow <= player.mediaItemCount.coerceAtMost(20) && player.hasNextMediaItem()) {
                    events.tryEmit(if (title.isNotBlank()) "« $title » est illisible : titre suivant" else "Titre illisible : titre suivant")
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    events.tryEmit("Lecture impossible : fichier introuvable ou abîmé")
                    errorsInARow = 0
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) errorsInARow = 0
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateButtons()
                val id = mediaItem?.mediaId?.toLongOrNull()
                if (id != null && (player.playWhenReady || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)) {
                    UserStore.recordPlay(id)
                }
                UserStore.savePosition(playPosition(), 0L)
                PlayerWidget.updateAll(this@PlaybackService)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // essai terminé et appli non activée : pas de lecture (widget, notification, écouteurs)
                if (isPlaying && !com.thousandlabs.tonedial.licence.Licence.canUse(this@PlaybackService)) { player.pause(); return }
                // compte le temps écoulé depuis le dernier relevé, puis repart de zéro
                val now = android.os.SystemClock.elapsedRealtime()
                val id = player.currentMediaItem?.mediaId?.toLongOrNull()
                if (!isPlaying && id != null && lastTick > 0) {
                    val elapsed = ((now - lastTick) * player.playbackParameters.speed).toLong()
                    com.thousandlabs.tonedial.data.ListenStats.addListening(id, elapsed.coerceIn(0, 15_000))
                    com.thousandlabs.tonedial.data.ListenStats.save()
                }
                lastTick = if (isPlaying) now else 0L
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
                updateButtons()
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
        updateButtons()
        // le cœur de la notification suit les favoris, même changés depuis l'appli
        scope.launch { UserStore.favorites.collect { updateButtons() } }
        registerHeadsetResume()
        PlayerWidget.updateAll(this)
    }

    // ---------- Boutons Favori et Aléatoire (notification, écran verrouillé) ----------

    fun updateButtons() {
        val s = session ?: return
        if (!notifButtons()) {
            s.setCustomLayout(emptyList())
            return
        }
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        val fav = id != null && id in UserStore.favorites.value
        val shuffle = player.shuffleModeEnabled
        s.setCustomLayout(
            listOf(
                androidx.media3.session.CommandButton.Builder()
                    .setDisplayName(if (fav) "Retirer des favoris" else "Ajouter aux favoris")
                    .setIconResId(if (fav) R.drawable.ic_notif_favorite else R.drawable.ic_notif_favorite_border)
                    .setSessionCommand(androidx.media3.session.SessionCommand(CMD_FAVORITE, android.os.Bundle.EMPTY))
                    .build(),
                androidx.media3.session.CommandButton.Builder()
                    .setDisplayName(if (shuffle) "Désactiver l'aléatoire" else "Activer l'aléatoire")
                    .setIconResId(if (shuffle) R.drawable.ic_notif_shuffle_on else R.drawable.ic_notif_shuffle)
                    .setSessionCommand(androidx.media3.session.SessionCommand(CMD_SHUFFLE, android.os.Bundle.EMPTY))
                    .build(),
            ),
        )
    }

    fun applySkipSilence() {
        if (::player.isInitialized) player.skipSilenceEnabled = skipSilence()
    }

    // ---------- Reprise de la lecture en branchant des écouteurs ou une enceinte Bluetooth ----------

    private var lastConnectResume = 0L
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        private var ready = false
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            // le premier appel liste les appareils déjà branchés : on l'ignore
            if (!ready) { ready = true; return }
            if (!resumeOnHeadset()) return
            val headset = addedDevices.any {
                it.isSink && it.type in setOf(
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                )
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (headset && !player.isPlaying && player.mediaItemCount > 0 && now - lastConnectResume > 5000) {
                lastConnectResume = now
                // laisse le temps au Bluetooth de prendre le son, sinon il sortirait du haut-parleur
                handler.postDelayed({
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }, 1500)
            }
        }
    }

    private fun registerHeadsetResume() {
        audioManager = getSystemService(android.media.AudioManager::class.java)
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, handler) }
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
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        scope.cancel()
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
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(androidx.media3.session.SessionCommand(CMD_FAVORITE, android.os.Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(CMD_SHUFFLE, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            when (customCommand.customAction) {
                CMD_FAVORITE -> player.currentMediaItem?.mediaId?.toLongOrNull()?.let { UserStore.toggleFavorite(it) }
                CMD_SHUFFLE -> {
                    val on = !player.shuffleModeEnabled
                    player.shuffleModeEnabled = on
                    if (on && player.mediaItemCount > 1) {
                        val cur = player.currentMediaItemIndex
                        val order = listOf(cur) + (0 until player.mediaItemCount).filter { it != cur }.shuffled()
                        player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
                    }
                }
            }
            updateButtons()
            return Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
        }

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
