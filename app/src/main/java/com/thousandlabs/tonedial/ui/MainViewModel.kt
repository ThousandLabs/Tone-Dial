package com.thousandlabs.tonedial.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.thousandlabs.tonedial.data.Album
import com.thousandlabs.tonedial.data.Artist
import com.thousandlabs.tonedial.data.Folder
import com.thousandlabs.tonedial.data.Lyrics
import com.thousandlabs.tonedial.data.LyricsLoader
import com.thousandlabs.tonedial.data.MusicRepository
import com.thousandlabs.tonedial.data.Playlist
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.SongSort
import com.thousandlabs.tonedial.data.UserStore
import com.thousandlabs.tonedial.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueEntry(val windowIndex: Int, val songId: Long)

data class PlayerUi(
    val currentId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentWindow: Int = -1,
)

enum class AutoList(val label: String) {
    FAVORITES("Favoris"),
    MOST_PLAYED("Les plus écoutés"),
    RECENTLY_PLAYED("Écoutés récemment"),
    RECENTLY_ADDED("Ajoutés récemment"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _player = MutableStateFlow(PlayerUi())
    val player: StateFlow<PlayerUi> = _player.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()
    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val favorites = UserStore.favorites
    val playlists = UserStore.playlists
    val sort = UserStore.sort
    val minDurationSec = UserStore.minDurationSec

    val songMap: StateFlow<Map<Long, Song>> =
        _songs.map { list -> list.associateBy { it.id } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val sortedSongs: StateFlow<List<Song>> =
        combine(_songs, UserStore.sort) { list, s -> MusicRepository.sort(list, s) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> =
        _songs.map { MusicRepository.albums(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val artists: StateFlow<List<Artist>> =
        _songs.map { MusicRepository.artists(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders: StateFlow<List<Folder>> =
        _songs.map { MusicRepository.folders(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var controller: MediaController? = null
    private var restored = false
    private var ticker: Job? = null
    private var lyricsFor: Long? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshPlayer()
        }
    }

    init {
        connect()
    }

    // ---------------- Bibliothèque ----------------
    fun loadLibrary() {
        viewModelScope.launch {
            _loading.value = true
            _songs.value = MusicRepository.loadSongs(ctx, UserStore.minDurationSec.value * 1000L)
            _loading.value = false
            tryRestore()
            refreshPlayer()
        }
    }

    fun setSort(s: SongSort) = UserStore.setSort(s)

    fun setMinDuration(sec: Int) {
        UserStore.setMinDurationSec(sec)
        loadLibrary()
    }

    fun autoList(kind: AutoList): List<Song> {
        val map = songMap.value
        return when (kind) {
            AutoList.FAVORITES -> UserStore.favorites.value.mapNotNull { map[it] }.sortedBy { it.title.lowercase() }
            AutoList.MOST_PLAYED -> UserStore.playCounts.value.entries
                .sortedByDescending { it.value }.mapNotNull { map[it.key] }.take(100)
            AutoList.RECENTLY_PLAYED -> UserStore.lastPlayed.value.entries
                .sortedByDescending { it.value }.mapNotNull { map[it.key] }.take(100)
            AutoList.RECENTLY_ADDED -> _songs.value.sortedByDescending { it.dateAdded }.take(100)
        }
    }

    fun playCount(id: Long): Int = UserStore.playCounts.value[id] ?: 0

    fun playlistSongs(p: Playlist): List<Song> {
        val map = songMap.value
        return p.songIds.mapNotNull { map[it] }
    }

    // ---------------- Connexion au service ----------------
    private fun connect() {
        viewModelScope.launch {
            runCatching {
                val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
                val c = MediaController.Builder(ctx, token).buildAsync().await()
                c.addListener(listener)
                controller = c
                tryRestore()
                refreshPlayer()
                startTicker()
            }.onFailure { _messages.tryEmit("Impossible de démarrer le lecteur") }
        }
    }

    private fun tryRestore() {
        val c = controller ?: return
        if (restored || _songs.value.isEmpty()) return
        restored = true
        if (c.mediaItemCount > 0) return
        val saved = UserStore.savedQueue() ?: return
        val map = songMap.value.ifEmpty { _songs.value.associateBy { it.id } }
        val savedCurrent = saved.ids.getOrNull(saved.index)
        val songs = saved.ids.mapNotNull { map[it] }
        if (songs.isEmpty()) return
        val index = songs.indexOfFirst { it.id == savedCurrent }.coerceAtLeast(0)
        val pos = if (songs.getOrNull(index)?.id == savedCurrent) saved.positionMs else 0L
        c.setMediaItems(songs.map { it.toMediaItem() }, index, pos)
        c.prepare()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _player.value = _player.value.copy(
                        positionMs = c.currentPosition,
                        durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                    )
                }
                delay(400)
            }
        }
    }

    private fun refreshPlayer() {
        val c = controller ?: return
        val shuffle = c.shuffleModeEnabled
        val tl = c.currentTimeline
        val queue = ArrayList<QueueEntry>()
        if (!tl.isEmpty) {
            var i = tl.getFirstWindowIndex(shuffle)
            var guard = 0
            while (i != C.INDEX_UNSET && guard < 20_000) {
                val id = c.getMediaItemAt(i).mediaId.toLongOrNull()
                if (id != null) queue += QueueEntry(i, id)
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
                guard++
            }
        }
        val currentId = c.currentMediaItem?.mediaId?.toLongOrNull()
        _player.value = PlayerUi(
            currentId = currentId,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: (songMap.value[currentId]?.duration ?: 0L),
            shuffle = shuffle,
            repeatMode = c.repeatMode,
            queue = queue,
            currentWindow = c.currentMediaItemIndex,
        )
        if (currentId != lyricsFor) {
            lyricsFor = currentId
            _lyrics.value = null
            val song = currentId?.let { songMap.value[it] }
            if (song != null) loadLyrics(song)
        }
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    // ---------------- Commandes de lecture ----------------
    fun play(list: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) list.indices.random() else startIndex.coerceIn(list.indices)
        c.setMediaItems(list.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs); return
        }
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        _messages.tryEmit(if (songs.size == 1) "Sera lu juste après" else "${songs.size} titres lus juste après")
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs); return
        }
        c.addMediaItems(songs.map { it.toMediaItem() })
        _messages.tryEmit(if (songs.size == 1) "Ajouté à la file d'attente" else "${songs.size} titres ajoutés à la file")
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(_songs.value, shuffle = true); return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _player.value = _player.value.copy(positionMs = ms)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun jumpTo(windowIndex: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(windowIndex)
        c.play()
    }

    fun removeFromQueue(windowIndex: Int) {
        val c = controller ?: return
        if (windowIndex in 0 until c.mediaItemCount) c.removeMediaItem(windowIndex)
    }

    fun clearQueue() {
        controller?.clearMediaItems()
    }

    // ---------------- Favoris et playlists ----------------
    fun toggleFavorite(id: Long) = UserStore.toggleFavorite(id)

    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        val p = UserStore.createPlaylist(name, songs.map { it.id })
        _messages.tryEmit(if (songs.size > 1) "Playlist « ${p.name} » créée avec ${songs.size} titres" else "Playlist « ${p.name} » créée")
    }

    fun addToPlaylist(p: Playlist, songs: List<Song>) {
        UserStore.addToPlaylist(p.id, songs.map { it.id })
        _messages.tryEmit(if (songs.size == 1) "Ajouté à « ${p.name} »" else "${songs.size} titres ajoutés à « ${p.name} »")
    }

    fun removeFromPlaylist(p: Playlist, songs: List<Song>) {
        UserStore.removeFromPlaylist(p.id, songs.map { it.id })
        _messages.tryEmit(if (songs.size == 1) "Retiré de « ${p.name} »" else "${songs.size} titres retirés de « ${p.name} »")
    }

    fun setFavorites(songs: List<Song>, favorite: Boolean) {
        UserStore.setFavorites(songs.map { it.id }, favorite)
        _messages.tryEmit(
            when {
                favorite && songs.size == 1 -> "Ajouté aux favoris"
                favorite -> "${songs.size} titres ajoutés aux favoris"
                songs.size == 1 -> "Retiré des favoris"
                else -> "${songs.size} titres retirés des favoris"
            }
        )
    }
    fun movePlaylistSong(p: Playlist, from: Int, to: Int) = UserStore.movePlaylistSong(p.id, from, to)
    fun renamePlaylist(p: Playlist, name: String) = UserStore.renamePlaylist(p.id, name)
    fun deletePlaylist(p: Playlist) = UserStore.deletePlaylist(p.id)

    // ---------------- Paroles ----------------
    private fun loadLyrics(song: Song) {
        viewModelScope.launch {
            _lyricsLoading.value = true
            val l = LyricsLoader.load(ctx, song)
            if (lyricsFor == song.id) _lyrics.value = l
            _lyricsLoading.value = false
        }
    }

    fun saveLyrics(song: Song, text: String) {
        UserStore.setCustomLyrics(song.id, text)
        lyricsFor = null
        refreshPlayer()
        _messages.tryEmit(if (text.isBlank()) "Paroles supprimées" else "Paroles enregistrées")
    }

    // ---------------- Sonnerie et partage ----------------
    /** Renvoie false si l'autorisation « Modifier les paramètres système » manque. */
    fun setRingtone(song: Song): Boolean {
        if (!Settings.System.canWrite(ctx)) return false
        return runCatching {
            RingtoneManager.setActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE, song.uri)
            _messages.tryEmit("« ${song.title} » est maintenant votre sonnerie")
            true
        }.getOrElse {
            _messages.tryEmit("Impossible de définir la sonnerie")
            true
        }
    }

    fun writeSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(song: Song): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, song.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Partager « ${song.title} »").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun notify(msg: String) {
        _messages.tryEmit(msg)
    }

    override fun onCleared() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
