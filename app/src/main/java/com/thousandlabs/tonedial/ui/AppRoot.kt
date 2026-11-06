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
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class AlbumDetail(val id: Long) : Screen
    data class ArtistDetail(val name: String) : Screen
    data class FolderDetail(val path: String) : Screen
    data class PlaylistDetail(val id: Long) : Screen
    data class AddToPlaylist(val id: Long) : Screen
    data class Queue(val fromPlayer: Boolean) : Screen
    data class AutoDetail(val kind: AutoList) : Screen
    data object Search : Screen
    data object Equalizer : Screen
    data object Settings : Screen
    data object Guide : Screen
    data object About : Screen
}

/** Actions et états communs transmis aux écrans. */
class Nav(
    val go: (Screen) -> Unit,
    val back: () -> Unit,
    val openMenu: (Song, Playlist?) -> Unit,
    val openPlayer: () -> Unit,
    val selection: Selection,
    val flags: UiFlags,
)

@Composable
fun AppRoot(vm: MainViewModel) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    val flags = remember { UiFlags() }
    val selection = remember { Selection() }
    var menuFor by remember { mutableStateOf<Pair<Song, Playlist?>?>(null) }
    var lastBackPress by remember { mutableLongStateOf(0L) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? android.app.Activity

    val player by vm.player.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val current = player.currentId?.let { songMap[it] }

    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    // Revenir en arrière ; la file ouverte depuis le lecteur ramène au lecteur
    fun pop() {
        if (stack.size <= 1) return
        val top = stack.last()
        stack = stack.dropLast(1)
        if (top is Screen.Queue && top.fromPlayer) flags.playerOpen = true
    }

    val nav = remember {
        Nav(
            go = { s -> selection.clear(); stack = stack + s; flags.playerOpen = false },
            back = { selection.clear(); pop() },
            openMenu = { song, pl -> menuFor = song to pl },
            openPlayer = { flags.playerOpen = true },
            selection = selection,
            flags = flags,
        )
    }

    // Retour : ferme le lecteur, annule la sélection, ou revient à l'écran précédent
    BackHandler(enabled = flags.playerOpen || selection.active || stack.size > 1) {
        when {
            flags.playerOpen -> flags.playerOpen = false
            selection.active -> selection.clear()
            else -> pop()
        }
    }
    // Sur l'accueil : deux appuis rapprochés pour quitter
    BackHandler(enabled = !flags.playerOpen && !selection.active && stack.size == 1) {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) {
            activity?.finish()
        } else {
            lastBackPress = now
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("Appuyez encore une fois pour quitter", duration = SnackbarDuration.Short)
            }
        }
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
                        is Screen.AddToPlaylist -> PlaylistPickerScreen(vm, nav, s.id)
                        is Screen.Queue -> QueueScreen(vm, nav)
                        is Screen.AutoDetail -> AutoDetailScreen(vm, nav, s.kind)
                        Screen.Search -> SearchScreen(vm, nav)
                        Screen.Equalizer -> EqualizerScreen(nav)
                        Screen.Settings -> SettingsScreen(vm, nav)
                        Screen.Guide -> GuideScreen(nav)
                        Screen.About -> AboutScreen(nav)
                    }
                    if (selection.active) {
                        SelectionBar(vm, selection)
                    }
                }
                if (current != null) {
                    MiniPlayer(song = current, ui = player, vm = vm, onOpen = { flags.playerOpen = true })
                }
            }

            AnimatedVisibility(
                visible = flags.playerOpen && current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                if (current != null) {
                    PlayerScreen(vm = vm, nav = nav, song = current, ui = player, onClose = { flags.playerOpen = false })
                }
            }
        }
    }

    menuFor?.let { (song, pl) ->
        SongMenuSheet(vm = vm, nav = nav, song = song, playlist = pl, onDismiss = { menuFor = null }, closePlayer = { flags.playerOpen = false })
    }
}

/** Barre affichée en haut pendant une sélection multiple. */
@Composable
private fun SelectionBar(vm: MainViewModel, selection: Selection) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var pickPlaylist by remember { mutableStateOf(false) }

    if (pickPlaylist) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { pickPlaylist = false },
            onPick = { vm.addToPlaylist(it, selection.songs()); pickPlaylist = false; selection.clear() },
            onCreate = { vm.createPlaylist(it, selection.songs()); pickPlaylist = false; selection.clear() },
        )
    }

    Surface(color = TdColors.SurfaceHigh, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { selection.clear() }) { Icon(Icons.Outlined.Close, "Annuler la sélection") }
            Text(
                if (selection.count == 1) "1 sélectionné" else "${selection.count} sélectionnés",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { selection.selectAll() }) {
                Icon(
                    Icons.Outlined.SelectAll,
                    if (selection.allSelected) "Tout désélectionner" else "Tout sélectionner",
                    tint = if (selection.allSelected) TdColors.Accent else TdColors.Text,
                )
            }
            IconButton(onClick = { pickPlaylist = true }) { Icon(Icons.Outlined.PlaylistAdd, "Ajouter à une playlist", tint = TdColors.Accent) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Plus d'actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TdColors.SurfaceHigh) {
                    DropdownMenuItem(
                        text = { Text("Lire maintenant") },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                        onClick = { menu = false; vm.play(selection.songs()); selection.clear() },
                    )
                    DropdownMenuItem(
                        text = { Text("Lire ensuite") },
                        leadingIcon = { Icon(Icons.Outlined.PlaylistPlay, null) },
                        onClick = { menu = false; vm.playNext(selection.songs()); selection.clear() },
                    )
                    DropdownMenuItem(
                        text = { Text("Ajouter à la file d'attente") },
                        leadingIcon = { Icon(Icons.Outlined.AddToQueue, null) },
                        onClick = { menu = false; vm.addToQueue(selection.songs()); selection.clear() },
                    )
                    val allFav = selection.songs().all { it.id in favorites }
                    DropdownMenuItem(
                        text = { Text(if (allFav) "Retirer des favoris" else "Ajouter aux favoris") },
                        leadingIcon = { Icon(if (allFav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, null) },
                        onClick = { menu = false; vm.setFavorites(selection.songs(), !allFav); selection.clear() },
                    )
                    selection.playlist?.let { p ->
                        DropdownMenuItem(
                            text = { Text("Retirer de « ${p.name} »") },
                            leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) },
                            onClick = { menu = false; vm.removeFromPlaylist(p, selection.songs()); selection.clear() },
                        )
                    }
                }
            }
        }
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
                            vm.removeFromPlaylist(playlist, listOf(song)); onDismiss()
                        })
                    }
                    ListItemRow(Icons.Outlined.Album, "Voir l'album", subtitle = song.album, onClick = {
                        if (albums.any { it.id == song.albumId }) {
                            closePlayer(); nav.go(Screen.AlbumDetail(song.albumId))
                        }
                        onDismiss()
                    })
                    song.artists.take(4).forEach { name ->
                        ListItemRow(Icons.Outlined.Person, if (song.artists.size > 1) "Voir $name" else "Voir l'artiste", subtitle = name, onClick = {
                            closePlayer(); nav.go(Screen.ArtistDetail(name)); onDismiss()
                        })
                    }
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
