@file:OptIn(ExperimentalMaterial3Api::class)

package com.thousandlabs.tonedial.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.data.Playlist
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art
import com.thousandlabs.tonedial.data.formatDuration

sealed interface Screen {
    data object Home : Screen
    data class AlbumDetail(val id: Long) : Screen
    data class ArtistDetail(val name: String) : Screen
    data class FolderDetail(val path: String) : Screen
    data class PlaylistDetail(val id: Long) : Screen
    data class AutoDetail(val kind: AutoList) : Screen
    data object Search : Screen
    data object Equalizer : Screen
    data object Settings : Screen
    data object Guide : Screen
    data object About : Screen
}

/** Actions communes transmises aux écrans. */
class Nav(
    val go: (Screen) -> Unit,
    val back: () -> Unit,
    val openMenu: (Song, Playlist?) -> Unit,
    val openPlayer: () -> Unit,
)

@Composable
fun AppRoot(vm: MainViewModel) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    var playerOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<Song, Playlist?>?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val player by vm.player.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val current = player.currentId?.let { songMap[it] }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val nav = remember {
        Nav(
            go = { s -> stack = stack + s; playerOpen = false },
            back = { if (stack.size > 1) stack = stack.dropLast(1) },
            openMenu = { song, pl -> menuFor = song to pl },
            openPlayer = { playerOpen = true },
        )
    }

    BackHandler(enabled = playerOpen || stack.size > 1) {
        if (playerOpen) playerOpen = false else stack = stack.dropLast(1)
    }

    Scaffold(
        containerColor = TdColors.Background,
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = if (current != null) 72.dp else 0.dp)) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (val s = stack.last()) {
                        Screen.Home -> HomeScreen(vm, nav)
                        is Screen.AlbumDetail -> AlbumDetailScreen(vm, nav, s.id)
                        is Screen.ArtistDetail -> ArtistDetailScreen(vm, nav, s.name)
                        is Screen.FolderDetail -> FolderDetailScreen(vm, nav, s.path)
                        is Screen.PlaylistDetail -> PlaylistDetailScreen(vm, nav, s.id)
                        is Screen.AutoDetail -> AutoDetailScreen(vm, nav, s.kind)
                        Screen.Search -> SearchScreen(vm, nav)
                        Screen.Equalizer -> EqualizerScreen(nav)
                        Screen.Settings -> SettingsScreen(vm, nav)
                        Screen.Guide -> GuideScreen(nav)
                        Screen.About -> AboutScreen(nav)
                    }
                }
                if (current != null) {
                    MiniPlayer(song = current, ui = player, vm = vm, onOpen = { playerOpen = true })
                }
            }

            AnimatedVisibility(
                visible = playerOpen && current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                if (current != null) {
                    PlayerScreen(vm = vm, nav = nav, song = current, ui = player, onClose = { playerOpen = false })
                }
            }
        }
    }

    menuFor?.let { (song, pl) ->
        SongMenuSheet(vm = vm, nav = nav, song = song, playlist = pl, onDismiss = { menuFor = null }, closePlayer = { playerOpen = false })
    }
}

@Composable
fun SongMenuSheet(
    vm: MainViewModel,
    nav: Nav,
    song: Song,
    playlist: Playlist?,
    onDismiss: () -> Unit,
    closePlayer: () -> Unit,
) {
    val context = LocalContext.current
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    when (dialog) {
        "playlist" -> AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { dialog = null; onDismiss() },
            onPick = { vm.addToPlaylist(it, listOf(song)); dialog = null; onDismiss() },
            onCreate = { vm.createPlaylist(it, listOf(song)); dialog = null; onDismiss() },
        )
        "info" -> SongInfoDialog(song, vm.playCount(song.id)) { dialog = null; onDismiss() }
        "lyrics" -> TextInputDialog(
            title = "Paroles de « ${song.title} »",
            initial = com.thousandlabs.tonedial.data.UserStore.customLyrics(song.id) ?: "",
            placeholder = "Collez les paroles ici (le format .lrc synchronisé est accepté)",
            confirmLabel = "Enregistrer",
            singleLine = false,
            onDismiss = { dialog = null; onDismiss() },
            onConfirm = { vm.saveLyrics(song, it); dialog = null; onDismiss() },
        )
        "ringtone" -> AlertDialog(
            onDismissRequest = { dialog = null; onDismiss() },
            title = { Text("Autorisation nécessaire") },
            text = { Text("Pour changer la sonnerie, Android demande d'autoriser Tone Dial à modifier les paramètres système. Activez l'option sur l'écran suivant, puis revenez et recommencez.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(vm.writeSettingsIntent()) }
                    dialog = null; onDismiss()
                }) { Text("Ouvrir les paramètres") }
            },
            dismissButton = { TextButton(onClick = { dialog = null; onDismiss() }) { Text("Annuler") } },
            containerColor = TdColors.Surface,
        )
    }
    if (dialog != null) return

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = TdColors.Surface) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.art(), size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim, maxLines = 1)
                }
            }
            HorizontalDivider(color = TdColors.Divider, modifier = Modifier.padding(vertical = 6.dp))
            LazyColumn {
                item {
                    ListItemRow(Icons.Outlined.PlaylistPlay, "Lire ensuite", onClick = { vm.playNext(listOf(song)); onDismiss() })
                    ListItemRow(Icons.Outlined.AddToQueue, "Ajouter à la file d'attente", onClick = { vm.addToQueue(listOf(song)); onDismiss() })
                    ListItemRow(Icons.Outlined.PlaylistAdd, "Ajouter à une playlist", onClick = { dialog = "playlist" })
                    val fav = song.id in favorites
                    ListItemRow(
                        if (fav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (fav) "Retirer des favoris" else "Ajouter aux favoris",
                        onClick = { vm.toggleFavorite(song.id); onDismiss() },
                    )
                    if (playlist != null) {
                        ListItemRow(Icons.Outlined.RemoveCircleOutline, "Retirer de « ${playlist.name} »", onClick = {
                            vm.removeFromPlaylist(playlist, song); onDismiss()
                        })
                    }
                    ListItemRow(Icons.Outlined.Album, "Voir l'album", subtitle = song.album, onClick = {
                        if (albums.any { it.id == song.albumId }) {
                            closePlayer(); nav.go(Screen.AlbumDetail(song.albumId))
                        }
                        onDismiss()
                    })
                    ListItemRow(Icons.Outlined.Person, "Voir l'artiste", subtitle = song.artist, onClick = {
                        closePlayer(); nav.go(Screen.ArtistDetail(song.artist)); onDismiss()
                    })
                    ListItemRow(Icons.Outlined.Subject, "Ajouter ou modifier les paroles", onClick = { dialog = "lyrics" })
                    ListItemRow(Icons.Outlined.RingVolume, "Définir comme sonnerie", onClick = {
                        if (vm.setRingtone(song)) onDismiss() else dialog = "ringtone"
                    })
                    ListItemRow(Icons.Outlined.Share, "Partager le fichier", onClick = {
                        runCatching { context.startActivity(vm.shareIntent(song)) }
                        onDismiss()
                    })
                    ListItemRow(Icons.Outlined.Info, "Informations", onClick = { dialog = "info" })
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun SongInfoDialog(song: Song, plays: Int, onDismiss: () -> Unit) {
    val rows = listOf(
        "Titre" to song.title,
        "Artiste" to song.artist,
        "Album" to song.album,
        "Année" to (if (song.year > 0) song.year.toString() else "—"),
        "Durée" to formatDuration(song.duration),
        "Taille" to "%.1f Mo".format(song.size / 1_048_576.0),
        "Format" to song.path.substringAfterLast('.', "—").uppercase(),
        "Écoutes" to plays.toString(),
        "Emplacement" to song.path,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informations") },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, color = TdColors.TextDim, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        containerColor = TdColors.Surface,
    )
}
