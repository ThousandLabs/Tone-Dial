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
import com.thousandlabs.tonedial.data.PlaylistSort
import com.thousandlabs.tonedial.data.sortPlaylistSongs
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
) {
    /** Position du morceau en cours dans la file (ordre de lecture). */
    val currentPos: Int get() = queue.indexOfFirst { it.windowIndex == currentWindow }
}

enum class AutoList(val label: String) {
    FAVORITES("Favoris"),
    MOST_PLAYED("Les plus écoutés"),
    RECENTLY_PLAYED("Écoutés récemment"),
    RECENTLY_ADDED("Ajoutés récemment"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    /** Tous les titres du téléphone, dossiers exclus compris. */
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    /** Titres affichés : dossiers exclus retirés, corrections manuelles appliquées. */
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
        // messages du service de lecture (titre illisible…)
        viewModelScope.launch { PlaybackService.events.collect { _messages.tryEmit(it) } }
        watchMediaStore()
        com.thousandlabs.tonedial.data.OpeningsBatch.statusMap()
        // artiste appliqué par l'identification groupée : listes et lecteur mis à jour
        viewModelScope.launch {
            com.thousandlabs.tonedial.data.OpeningsBatch.applied.collect {
                applyFilters()
                lyricsFor = null
                refreshPlayer()
            }
        }
    }

    // ---------------- Bibliothèque ----------------
    fun loadLibrary() {
        viewModelScope.launch {
            _loading.value = true
            _allSongs.value = MusicRepository.loadSongs(ctx, UserStore.minDurationSec.value * 1000L, artistMode())
            applyFilters()
            _loading.value = false
            // une seule fois : les groupes d'openings prennent désormais l'affiche de l'anime (MyAnimeList)
            if (!UserStore.getBool("anime_images_v2", false)) {
                UserStore.putBool("anime_images_v2", true)
                val animeGroups = _songs.value.filter { com.thousandlabs.tonedial.data.Covers.looksAnime(it) }
                    .flatMap { it.artists }.filter { it != "Artiste inconnu" }.distinct()
                com.thousandlabs.tonedial.data.Covers.resetAnimeArtistImages(animeGroups)
            }
            // une seule fois : retire les pochettes partagées à tort par l'ancienne recherche
            if (!UserStore.getBool("covers_cleanup_v2", false)) {
                UserStore.putBool("covers_cleanup_v2", true)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.thousandlabs.tonedial.data.Covers.cleanupFakeAlbums(_songs.value)
                }
            }
            tryRestore()
            refreshPlayer()
            // recherches groupées interrompues (appli fermée de force…) : elles reprennent
            com.thousandlabs.tonedial.data.OpeningsBatch.resumeIfNeeded(ctx, visibleOriginals())
            com.thousandlabs.tonedial.data.LyricsBatch.resumeIfNeeded(ctx, _songs.value)
        }
    }

    // ---------------- Mise à jour automatique de la bibliothèque ----------------
    private var mediaObserver: android.database.ContentObserver? = null
    private var reloadJob: Job? = null

    /** Une musique téléchargée, copiée ou supprimée apparaît (ou disparaît) sans relancer l'appli. */
    private fun watchMediaStore() {
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reloadJob?.cancel()
                reloadJob = viewModelScope.launch {
                    delay(2500) // un téléchargement déclenche plusieurs changements à la suite
                    reloadQuietly()
                }
            }
        }
        runCatching {
            ctx.contentResolver.registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
            mediaObserver = obs
        }
    }

    /** Recharge la liste sans écran de chargement ; la lecture en cours n'est pas touchée. */
    private suspend fun reloadQuietly() {
        if (_loading.value) return
        val fresh = runCatching {
            MusicRepository.loadSongs(ctx, UserStore.minDurationSec.value * 1000L, artistMode())
        }.getOrNull() ?: return
        val before = _allSongs.value.map { it.id }.toSet()
        if (fresh.map { it.id }.toSet() == before && fresh.size == _allSongs.value.size) return
        val added = fresh.count { it.id !in before }
        _allSongs.value = fresh
        applyFilters()
        if (added > 0) _messages.tryEmit(if (added == 1) "1 nouveau titre ajouté à la bibliothèque" else "$added nouveaux titres ajoutés à la bibliothèque")
    }

    // ---------------- Titres masqués ----------------
    val hiddenSongs = com.thousandlabs.tonedial.data.LibraryPrefs.hidden

    fun hideSongs(songs: List<Song>) {
        com.thousandlabs.tonedial.data.LibraryPrefs.setHidden(com.thousandlabs.tonedial.data.LibraryPrefs.hidden.value + songs.map { it.id })
        applyFilters()
        _messages.tryEmit(if (songs.size == 1) "Titre masqué (Réglages > Titres masqués pour le réafficher)" else "${songs.size} titres masqués")
    }

    fun unhideSong(id: Long) {
        com.thousandlabs.tonedial.data.LibraryPrefs.setHidden(com.thousandlabs.tonedial.data.LibraryPrefs.hidden.value - id)
        applyFilters()
    }

    /** Titres masqués un par un (pas ceux des dossiers masqués). */
    fun hiddenSongList(): List<Song> {
        val ids = com.thousandlabs.tonedial.data.LibraryPrefs.hidden.value
        return _allSongs.value.filter { it.id in ids }.map { com.thousandlabs.tonedial.data.LibraryPrefs.apply(it) }
    }

    // ---------------- Doublons ----------------
    /** Groupes de titres identiques (même titre, même artiste, durée à 3 secondes près). */
    fun duplicateGroups(): List<List<Song>> {
        fun norm(t: String) = com.thousandlabs.tonedial.data.CoverSearch.norm(t)
            .replace(Regex("\\b(official|video|audio|lyrics?|clip|officiel|hd|hq|mp3)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return _songs.value
            .groupBy { norm(it.title) + "|" + norm(it.artist) }
            .values
            .flatMap { group ->
                if (group.size < 2) emptyList()
                else {
                    // sous-groupes de durées proches
                    val sorted = group.sortedBy { it.duration }
                    val out = mutableListOf<MutableList<Song>>()
                    for (song in sorted) {
                        val last = out.lastOrNull()
                        if (last != null && song.duration - last.last().duration <= 3000) last += song else out += mutableListOf(song)
                    }
                    out.filter { it.size > 1 }
                }
            }
            .sortedBy { it.first().title.lowercase() }
    }

    // ---------------- Réglages de lecture ----------------
    fun skipSilence(): Boolean = PlaybackService.skipSilence()
    fun setSkipSilence(on: Boolean) {
        UserStore.putBool("skip_silence", on)
        PlaybackService.instance?.applySkipSilence()
    }
    fun resumeOnHeadset(): Boolean = PlaybackService.resumeOnHeadset()
    fun setResumeOnHeadset(on: Boolean) = UserStore.putBool("resume_on_headset", on)
    fun notifButtons(): Boolean = PlaybackService.notifButtons()
    fun setNotifButtons(on: Boolean) {
        UserStore.putBool("notif_buttons", on)
        PlaybackService.instance?.updateButtons()
    }

    /** Avance ou recule de quelques secondes dans le titre en cours. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        seekTo((c.currentPosition + deltaMs).coerceIn(0L, (dur - 500).coerceAtLeast(0L)))
    }

    private fun applyFilters() {
        _songs.value = _allSongs.value
            .filterNot { com.thousandlabs.tonedial.data.LibraryPrefs.isExcluded(it.folderPath) }
            .filterNot { com.thousandlabs.tonedial.data.LibraryPrefs.isHidden(it.id) }
            .map { com.thousandlabs.tonedial.data.LibraryPrefs.apply(it) }
    }

    /** Tous les dossiers contenant de la musique, pour le réglage des dossiers exclus. */
    val allFolders: StateFlow<List<Folder>> =
        _allSongs.map { MusicRepository.folders(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val excludedFolders = com.thousandlabs.tonedial.data.LibraryPrefs.excluded

    fun toggleExcludedFolder(path: String) {
        com.thousandlabs.tonedial.data.LibraryPrefs.toggleExcluded(path)
        applyFilters()
    }

    fun excludeFolders(paths: Collection<String>) {
        com.thousandlabs.tonedial.data.LibraryPrefs.setExcluded(com.thousandlabs.tonedial.data.LibraryPrefs.excluded.value + paths)
        applyFilters()
        _messages.tryEmit(if (paths.size == 1) "Dossier masqué" else "${paths.size} dossiers masqués")
    }

    // ---------------- Corrections manuelles ----------------
    fun setOverride(song: Song, title: String, artist: String, album: String) {
        val original = _allSongs.value.firstOrNull { it.id == song.id } ?: song
        com.thousandlabs.tonedial.data.LibraryPrefs.setOverride(
            song.id,
            com.thousandlabs.tonedial.data.LibraryPrefs.Override(
                title.trim().takeIf { it.isNotBlank() && it != original.title },
                artist.trim().takeIf { it.isNotBlank() && it != original.artist },
                album.trim().takeIf { it.isNotBlank() && it != original.album },
            ),
        )
        applyFilters()
        lyricsFor = null
        refreshPlayer()
        _messages.tryEmit("Informations modifiées")
    }

    fun resetOverride(song: Song) {
        com.thousandlabs.tonedial.data.LibraryPrefs.setOverride(song.id, null)
        applyFilters()
        _messages.tryEmit("Informations d'origine rétablies")
    }

    fun hasOverride(song: Song): Boolean = com.thousandlabs.tonedial.data.LibraryPrefs.overrides.value.containsKey(song.id)

    fun originalSong(song: Song): Song = _allSongs.value.firstOrNull { it.id == song.id } ?: song

    // ---------------- Identification en ligne (« Chercher l'artiste ») ----------------
    data class IdentifyState(val song: Song, val loading: Boolean, val result: com.thousandlabs.tonedial.data.CoverSearch.Identity?)

    private val _identify = MutableStateFlow<IdentifyState?>(null)
    val identify: StateFlow<IdentifyState?> = _identify.asStateFlow()

    /** Cherche en ligne la chanson officielle (pour un opening : son vrai titre et son interprète). */
    fun identifySong(song: Song) {
        _identify.value = IdentifyState(song, true, null)
        viewModelScope.launch {
            val r = runCatching { com.thousandlabs.tonedial.data.CoverSearch.identify(originalSong(song)) }.getOrNull()
            if (_identify.value?.song?.id == song.id) _identify.value = IdentifyState(song, false, r)
        }
    }

    fun dismissIdentify() {
        _identify.value = null
    }

    /** Applique le résultat : artiste corrigé (sans toucher au titre), pochette et paroles. */
    fun applyIdentity(song: Song, id: com.thousandlabs.tonedial.data.CoverSearch.Identity, silent: Boolean = false) {
        if (id.artist.isNotBlank()) {
            com.thousandlabs.tonedial.data.LibraryPrefs.setOverride(
                song.id,
                com.thousandlabs.tonedial.data.LibraryPrefs.Override(null, id.artist, null),
            )
            if (com.thousandlabs.tonedial.data.Covers.looksAnime(song)) com.thousandlabs.tonedial.data.OpeningsBatch.mark(song.id, id.artist)
            applyFilters()
        }
        viewModelScope.launch {
            id.image?.let { if (!com.thousandlabs.tonedial.data.Covers.hasCustom(song.id)) com.thousandlabs.tonedial.data.Covers.saveSongImage(song.id, it) }
            com.thousandlabs.tonedial.data.Covers.markResolved(song.id)
            if (com.thousandlabs.tonedial.data.UserStore.customLyrics(song.id) == null) {
                val updated = songMap.value[song.id] ?: song
                val text = com.thousandlabs.tonedial.data.LyricsOnline.find(updated)
                if (text != null) com.thousandlabs.tonedial.data.UserStore.setCustomLyrics(song.id, text, source = "lrclib")
            }
            lyricsFor = null
            refreshPlayer()
            if (!silent) _messages.tryEmit(if (id.artist.isNotBlank()) "Artiste : ${id.artist}" else "Image ajoutée")
        }
        _identify.value = null
    }

    /** Identification groupée : tourne hors de l'écran (service avec notification), voir OpeningsBatch. */
    val animeBatch = com.thousandlabs.tonedial.data.OpeningsBatch.progress
    val openingsStatus = com.thousandlabs.tonedial.data.OpeningsBatch.status

    /** Openings et endings visibles, sous leur nom d'origine (celui que lit la recherche). */
    private fun visibleOriginals(): List<Song> {
        val visible = _songs.value.map { it.id }.toSet()
        return _allSongs.value.filter { it.id in visible }
    }

    /** Identifie tous les openings et endings de la bibliothèque et applique leur interprète. */
    fun identifyAllOpenings() = com.thousandlabs.tonedial.data.OpeningsBatch.start(ctx, visibleOriginals())

    fun stopIdentifyAllOpenings() = com.thousandlabs.tonedial.data.OpeningsBatch.stop()

    /** Openings et endings de la bibliothèque, avec l'artiste affiché. */
    fun animeSongs(): List<Song> = _songs.value.filter { com.thousandlabs.tonedial.data.Covers.looksAnime(it) }

    // ---------------- Pochettes ----------------
    val coverProgress = com.thousandlabs.tonedial.data.Covers.progress

    fun startCoverSearch() {
        com.thousandlabs.tonedial.data.Covers.startBatch(ctx, _songs.value)
    }

    fun stopCoverSearch() = com.thousandlabs.tonedial.data.Covers.stopBatch()

    fun searchCover(song: Song) {
        viewModelScope.launch {
            _messages.tryEmit("Recherche de la pochette…")
            val ok = com.thousandlabs.tonedial.data.Covers.fetchOnline(song)
            if (ok) com.thousandlabs.tonedial.data.Covers.markResolved(song.id)
            _messages.tryEmit(if (ok) "Pochette trouvée" else "Aucune pochette trouvée (ou pas de connexion)")
        }
    }

    fun setCustomCover(song: Song, image: Uri) {
        viewModelScope.launch {
            val ok = com.thousandlabs.tonedial.data.Covers.setCustom(ctx, song.id, image)
            if (ok) com.thousandlabs.tonedial.data.Covers.markResolved(song.id)
            _messages.tryEmit(if (ok) "Pochette modifiée" else "Impossible de lire cette image")
        }
    }

    fun removeCustomCover(song: Song) {
        com.thousandlabs.tonedial.data.Covers.removeCustom(song.id)
        _messages.tryEmit("Pochette retirée")
    }

    fun hasCustomCover(song: Song) = com.thousandlabs.tonedial.data.Covers.hasCustom(song.id)

    fun setPlaylistImage(p: Playlist, image: Uri) {
        viewModelScope.launch {
            val ok = com.thousandlabs.tonedial.data.Covers.setPlaylistImage(ctx, p.id, image)
            _messages.tryEmit(if (ok) "Image de la playlist modifiée" else "Impossible de lire cette image")
        }
    }

    fun removePlaylistImage(p: Playlist) {
        com.thousandlabs.tonedial.data.Covers.removePlaylistImage(p.id)
        _messages.tryEmit("Image de la playlist retirée")
    }

    /** Image d'une playlist : celle choisie, sinon la pochette de son premier titre. */
    fun playlistArt(p: Playlist): Any? =
        com.thousandlabs.tonedial.data.Covers.playlistFile(p.id) ?: playlistSongs(p).firstOrNull()?.let {
            com.thousandlabs.tonedial.data.SongArt(it.id, it.albumId, com.thousandlabs.tonedial.data.Covers.stamp(it.id, it.albumId))
        }

    // ---------------- Suppression de fichiers ----------------
    /**
     * Prépare la suppression. Renvoie la demande de confirmation d'Android à afficher,
     * ou null si la suppression a déjà été faite directement.
     */
    fun deleteRequest(songs: List<Song>): android.content.IntentSender? {
        if (songs.isEmpty()) return null
        val uris = songs.map { it.uri }
        return when {
            android.os.Build.VERSION.SDK_INT >= 30 ->
                android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender
            android.os.Build.VERSION.SDK_INT == 29 -> {
                try {
                    uris.forEach { ctx.contentResolver.delete(it, null, null) }
                    onDeleted(songs); null
                } catch (e: android.app.RecoverableSecurityException) {
                    e.userAction.actionIntent.intentSender
                }
            }
            else -> {
                runCatching { uris.forEach { ctx.contentResolver.delete(it, null, null) } }
                onDeleted(songs); null
            }
        }
    }

    /** Après suppression : retire les titres de la file et des playlists, puis actualise. */
    fun onDeleted(songs: List<Song>) {
        val ids = songs.map { it.id }.toSet()
        val c = controller
        if (c != null) {
            for (i in c.mediaItemCount - 1 downTo 0) {
                val mid = c.getMediaItemAt(i).mediaId.toLongOrNull()
                if (mid != null && mid in ids) c.removeMediaItem(i)
            }
        }
        UserStore.playlists.value.forEach { p -> if (p.songIds.any { it in ids }) UserStore.removeFromPlaylist(p.id, ids.toList()) }
        UserStore.setFavorites(ids.toList(), false)
        _messages.tryEmit(if (songs.size == 1) "Fichier supprimé du téléphone" else "${songs.size} fichiers supprimés du téléphone")
        loadLibrary()
    }

    // ---------------- Vitesse de lecture ----------------
    private val _speed = MutableStateFlow(UserStore.getInt("speed_pct", 100) / 100f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    fun setSpeed(value: Float) {
        _speed.value = value
        UserStore.putInt("speed_pct", (value * 100).toInt())
        controller?.setPlaybackSpeed(value)
    }

    // ---------------- Sauvegarde ----------------
    fun exportBackup(target: Uri) {
        viewModelScope.launch {
            com.thousandlabs.tonedial.data.Backup.export(ctx, target, _allSongs.value)
                .onSuccess { _messages.tryEmit("Sauvegarde enregistrée ($it playlists)") }
                .onFailure { _messages.tryEmit("Échec de la sauvegarde : ${it.message ?: "erreur"}") }
        }
    }

    fun importBackup(source: Uri) {
        viewModelScope.launch {
            com.thousandlabs.tonedial.data.Backup.import(ctx, source, _allSongs.value.map { com.thousandlabs.tonedial.data.LibraryPrefs.apply(it) })
                .onSuccess { r ->
                    UserStore.setMinDurationSec(UserStore.getInt("min_duration_sec", 30))
                    loadLibrary()
                    _messages.tryEmit(
                        "Restauré : ${r.playlists} playlist(s), ${r.matched} titre(s) retrouvé(s)" +
                            if (r.missing > 0) ", ${r.missing} introuvable(s) sur ce téléphone" else ""
                    )
                }
                .onFailure { _messages.tryEmit(it.message ?: "Restauration impossible") }
        }
    }

    // ---------------- Statistiques ----------------
    val statsVersion = com.thousandlabs.tonedial.data.ListenStats.version
    fun stats(days: Int?) = com.thousandlabs.tonedial.data.ListenStats.summary(days)

    // ---------------- Raccourcis de l'icône ----------------
    private val _shortcut = MutableStateFlow<String?>(null)
    val shortcut: StateFlow<String?> = _shortcut.asStateFlow()

    fun onShortcut(action: String?) {
        if (action != null && action.startsWith("com.thousandlabs.tonedial.")) _shortcut.value = action
    }

    /** Exécute le raccourci dès que la bibliothèque et le lecteur sont prêts. */
    fun consumeShortcut(): String? {
        val a = _shortcut.value ?: return null
        if (a.endsWith(".SEARCH")) { _shortcut.value = null; return a }
        if (controller == null || _songs.value.isEmpty()) return null
        _shortcut.value = null
        when {
            a.endsWith(".SHUFFLE") -> play(_songs.value, shuffle = true)
            a.endsWith(".RESUME") -> controller?.let { c ->
                if (c.mediaItemCount == 0) play(_songs.value, shuffle = true)
                else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
            }
        }
        return a
    }

    fun setSort(s: SongSort) = UserStore.setSort(s)

    /** Mode d'identification des artistes ; l'ancien interrupteur désactivé devient « Regrouper, titres intacts ». */
    fun artistMode(): com.thousandlabs.tonedial.data.ArtistMode {
        val saved = UserStore.getString("artist_mode")
        return runCatching { com.thousandlabs.tonedial.data.ArtistMode.valueOf(saved!!) }.getOrElse {
            if (UserStore.getBool("smart_tags", true)) com.thousandlabs.tonedial.data.ArtistMode.FULL
            else com.thousandlabs.tonedial.data.ArtistMode.GROUP
        }
    }

    fun setArtistMode(mode: com.thousandlabs.tonedial.data.ArtistMode) {
        UserStore.putString("artist_mode", mode.name)
        loadLibrary()
    }

    fun smartTags(): Boolean = UserStore.getBool("smart_tags", true)

    fun setSmartTags(on: Boolean) {
        UserStore.putBool("smart_tags", on)
        loadLibrary()
    }

    fun setMinDuration(sec: Int) {
        UserStore.setMinDurationSec(sec)
        loadLibrary()
    }

    fun autoList(kind: AutoList): List<Song> {
        val map = songMap.value
        return when (kind) {
            AutoList.FAVORITES -> sortPlaylistSongs(
                UserStore.favorites.value.mapNotNull { map[it] },
                UserStore.favoritesSort(),
                UserStore::favoriteAddedAt,
            )
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
        return sortPlaylistSongs(p.songIds.mapNotNull { map[it] }, UserStore.playlistSort(p.id), p::addedAt)
    }

    val sortVersion = UserStore.sortVersion
    fun playlistSort(p: Playlist): PlaylistSort = UserStore.playlistSort(p.id)
    fun setPlaylistSort(p: Playlist, sort: PlaylistSort) = UserStore.setPlaylistSort(p.id, sort)
    fun favoritesSort(): PlaylistSort = UserStore.favoritesSort()
    fun setFavoritesSort(sort: PlaylistSort) = UserStore.setFavoritesSort(sort)

    /** Enregistre l'ordre choisi à la main et passe la playlist en ordre personnalisé. */
    fun savePlaylistOrder(p: Playlist, songs: List<Song>) {
        UserStore.setPlaylistOrder(p.id, songs.map { it.id })
        UserStore.setPlaylistSort(p.id, PlaylistSort.CUSTOM)
        _messages.tryEmit("Nouvel ordre enregistré")
    }

    // ---------------- Connexion au service ----------------
    private fun connect() {
        viewModelScope.launch {
            runCatching {
                val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
                val c = MediaController.Builder(ctx, token).buildAsync().await()
                c.addListener(listener)
                controller = c
                if (_speed.value != 1f) c.setPlaybackSpeed(_speed.value)
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
        val p = exo
        if (p != null) {
            // la file est enregistrée dans l'ordre de lecture : on la remet telle quelle
            p.setMediaItems(songs.map { it.toMediaItem() }, index, pos)
            if (UserStore.getBool("queue_shuffle", false)) {
                p.shuffleModeEnabled = true
                applyShuffleOrder(p, songs.indices.toList())
            }
            p.repeatMode = UserStore.getInt("queue_repeat", Player.REPEAT_MODE_OFF)
            p.prepare()
        } else {
            c.setMediaItems(songs.map { it.toMediaItem() }, index, pos)
            c.prepare()
        }
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
            currentId?.let { id -> songMap.value[id]?.let { com.thousandlabs.tonedial.data.Covers.autoFetch(ctx, it, _songs.value) } }
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
                    .setArtworkUri(com.thousandlabs.tonedial.playback.ArtBitmapLoader.artUri(id, albumId, artists.firstOrNull()?.takeUnless { it == "Artiste inconnu" }))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    // ---------------- Commandes de lecture ----------------
    /** Réglage : appuyer sur un titre déjà présent dans la file y va, au lieu de remplacer la file. */
    fun tapJumpsInQueue(): Boolean = UserStore.getBool("tap_jump_queue", true)
    fun setTapJumpsInQueue(on: Boolean) = UserStore.putBool("tap_jump_queue", on)

    /**
     * Appui sur un titre dans une liste. S'il est déjà dans la file d'attente, la lecture va
     * simplement à sa place (la file et son ordre restent intacts). Sinon, la liste est lancée.
     */
    fun playFromList(list: List<Song>, index: Int) {
        val c = controller
        val song = list.getOrNull(index) ?: return
        if (c != null && c.mediaItemCount > 0 && tapJumpsInQueue()) {
            val target = song.id.toString()
            val window = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == target }
            if (window != null) {
                if (window != c.currentMediaItemIndex) c.seekToDefaultPosition(window)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
                return
            }
        }
        play(list, index)
    }

    fun play(list: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        val start = if (shuffle) list.indices.random() else startIndex.coerceIn(list.indices)
        val p = exo
        if (p != null) {
            p.shuffleModeEnabled = shuffle
            p.setMediaItems(list.map { it.toMediaItem() }, start, 0L)
            // le morceau de départ est toujours le premier : rien ne s'affiche « avant » lui
            if (shuffle) applyShuffleOrder(p, startFirstOrder(start, list.size))
            p.prepare()
            p.play()
        } else {
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(list.map { it.toMediaItem() }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Ordre mélangé qui commence par `first`. */
    private fun startFirstOrder(first: Int, size: Int): List<Int> =
        listOf(first) + (0 until size).filter { it != first }.shuffled()

    /** Lecteur du service (même processus) : nécessaire pour personnaliser l'ordre aléatoire. */
    private val exo: androidx.media3.exoplayer.ExoPlayer? get() = PlaybackService.instance?.player

    /** Indices des titres dans l'ordre de lecture réel (ordre mélangé si l'aléatoire est actif). */
    private fun playOrder(p: Player): List<Int> {
        val tl = p.currentTimeline
        if (tl.isEmpty) return emptyList()
        val shuffle = p.shuffleModeEnabled
        val out = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun applyShuffleOrder(p: androidx.media3.exoplayer.ExoPlayer, order: List<Int>) {
        if (order.size != p.mediaItemCount) return
        p.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
        PlaybackService.instance?.saveQueueNow()
    }

    /**
     * Déplace un titre dans la file d'attente (positions dans l'ordre affiché).
     * En aléatoire, l'ordre mélangé est remplacé par celui choisi, sans couper la lecture.
     */
    fun moveInQueue(fromPos: Int, toPos: Int) {
        val p = exo ?: return
        val order = playOrder(p)
        if (fromPos !in order.indices || toPos !in order.indices || fromPos == toPos) return
        if (!p.shuffleModeEnabled) {
            p.moveMediaItem(order[fromPos], order[toPos])
        } else {
            val m = order.toMutableList()
            val w = m.removeAt(fromPos)
            m.add(toPos, w)
            applyShuffleOrder(p, m)
        }
    }

    /** Place un titre de la file juste après le morceau en cours. */
    fun queuePlayNext(pos: Int) {
        val p = exo ?: return
        val order = playOrder(p)
        val cur = order.indexOf(p.currentMediaItemIndex)
        if (cur < 0 || pos == cur) return
        moveInQueue(pos, if (pos < cur) cur else cur + 1)
        _messages.tryEmit("Sera lu juste après")
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        if (c.mediaItemCount == 0) {
            play(songs); return
        }
        val p = exo
        if (p != null && p.shuffleModeEnabled) {
            // en aléatoire, on insère puis on place les nouveaux titres juste après le morceau en cours
            val insertAt = p.currentMediaItemIndex + 1
            p.addMediaItems(insertAt, songs.map { it.toMediaItem() })
            val fresh = (insertAt until insertAt + songs.size).toSet()
            val order = playOrder(p).filterNot { it in fresh }.toMutableList()
            val cur = order.indexOf(p.currentMediaItemIndex)
            order.addAll(cur + 1, (insertAt until insertAt + songs.size).toList())
            applyShuffleOrder(p, order)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        }
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
        val p = exo
        if (p != null) {
            val on = !p.shuffleModeEnabled
            p.shuffleModeEnabled = on
            // en activant l'aléatoire, le morceau en cours devient le premier : la suite est mélangée après lui
            if (on && p.mediaItemCount > 1) applyShuffleOrder(p, startFirstOrder(p.currentMediaItemIndex, p.mediaItemCount))
            return
        }
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun saveQueueAsPlaylist(name: String) {
        val map = songMap.value
        val songs = _player.value.queue.mapNotNull { map[it.songId] }
        if (songs.isEmpty()) return
        createPlaylist(name, songs)
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

    /** Dernier titre retiré de la file, pour « Annuler ». */
    private var lastRemoved: Pair<Int, MediaItem>? = null
    private val _undo = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** Message avec bouton « Annuler ». */
    val undo: SharedFlow<String> = _undo.asSharedFlow()

    fun removeFromQueue(windowIndex: Int) {
        val c = controller ?: return
        if (windowIndex !in 0 until c.mediaItemCount) return
        val item = c.getMediaItemAt(windowIndex)
        c.removeMediaItem(windowIndex)
        lastRemoved = windowIndex to item
        _undo.tryEmit("« ${item.mediaMetadata.title ?: "Titre"} » retiré de la file")
    }

    fun undoRemove() {
        val c = controller ?: return
        val (index, item) = lastRemoved ?: return
        lastRemoved = null
        c.addMediaItem(index.coerceIn(0, c.mediaItemCount), item)
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
            // recherche automatique en ligne dès le début du morceau, sans ouvrir les paroles
            if ((l == null || l.lines.isEmpty()) && lyricsFor == song.id && com.thousandlabs.tonedial.data.LyricsBatch.autoEnabled()) {
                searchLyricsOnline(song, auto = true)
            }
        }
    }

    val lyricsBatch = com.thousandlabs.tonedial.data.LyricsBatch.progress
    fun startLyricsBatch() = com.thousandlabs.tonedial.data.LyricsBatch.start(ctx, _songs.value)
    fun stopLyricsBatch() = com.thousandlabs.tonedial.data.LyricsBatch.stop()
    fun startRomajiBatch() {
        lyricsFor = null
        com.thousandlabs.tonedial.data.LyricsBatch.startRomaji(ctx, _allSongs.value)
    }

    private val _lyricsSearching = MutableStateFlow(false)
    val lyricsSearching: StateFlow<Boolean> = _lyricsSearching.asStateFlow()
    private val _lyricsNotFoundOnline = MutableStateFlow<Set<Long>>(emptySet())
    val lyricsNotFoundOnline: StateFlow<Set<Long>> = _lyricsNotFoundOnline.asStateFlow()
    private val autoSearched = HashSet<Long>()

    /** Cherche les paroles sur LRCLIB. `auto` : recherche lancée toute seule, une fois par titre. */
    fun searchLyricsOnline(song: Song, auto: Boolean = false) {
        if (auto && !autoSearched.add(song.id)) return
        if (_lyricsSearching.value) return
        viewModelScope.launch {
            _lyricsSearching.value = true
            val text = com.thousandlabs.tonedial.data.LyricsOnline.find(song)
            _lyricsSearching.value = false
            if (text != null) {
                UserStore.setCustomLyrics(song.id, text, source = "lrclib")
                _lyricsNotFoundOnline.value = _lyricsNotFoundOnline.value - song.id
                lyricsFor = null
                refreshPlayer()
                if (!auto) _messages.tryEmit("Paroles trouvées et enregistrées")
            } else {
                _lyricsNotFoundOnline.value = _lyricsNotFoundOnline.value + song.id
                if (!auto) _messages.tryEmit("Aucune parole trouvée en ligne (ou pas de connexion)")
            }
        }
    }

    fun googleLyricsIntent(song: Song): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(com.thousandlabs.tonedial.data.LyricsOnline.googleUrl(song)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Colle les paroles copiées depuis le navigateur. */
    fun pasteLyrics(song: Song) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        if (text.isNullOrBlank()) {
            _messages.tryEmit("Rien à coller : copiez d'abord les paroles")
            return
        }
        saveLyrics(song, text)
    }

    fun saveLyrics(song: Song, text: String, message: String? = null) {
        UserStore.setCustomLyrics(song.id, text)
        if (text.isNotBlank() && lyricsFor == song.id) {
            // mise à jour immédiate, sans recharger (évite un clignotement pendant les réglages)
            _lyrics.value = LyricsLoader.parse(text, "Saisies dans l'appli")
        } else {
            lyricsFor = null
            refreshPlayer()
        }
        _messages.tryEmit(message ?: if (text.isBlank()) "Paroles supprimées" else "Paroles enregistrées")
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
        mediaObserver?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
