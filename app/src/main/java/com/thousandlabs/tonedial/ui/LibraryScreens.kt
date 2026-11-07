@file:OptIn(ExperimentalMaterial3Api::class)

package com.thousandlabs.tonedial.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.data.Playlist
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.SongArt
import com.thousandlabs.tonedial.data.SongSort
import com.thousandlabs.tonedial.data.PlaylistSort
import com.thousandlabs.tonedial.data.art
import kotlinx.coroutines.launch

/** Onglets de l'accueil, dans l'ordre par défaut. L'utilisateur peut les réorganiser. */
enum class HomeTab(val label: String) {
    SONGS("Titres"),
    ALBUMS("Albums"),
    ARTISTS("Artistes"),
    FOLDERS("Dossiers"),
    PLAYLISTS("Playlists"),
}

@Composable
fun rememberTabOrder(): List<HomeTab> {
    LaunchedEffect(Unit) {
        if (com.thousandlabs.tonedial.data.UserStore.tabOrder.value.isEmpty()) {
            com.thousandlabs.tonedial.data.UserStore.loadTabOrder(HomeTab.entries.map { it.name })
        }
    }
    val names by com.thousandlabs.tonedial.data.UserStore.tabOrder.collectAsStateWithLifecycle()
    return names.mapNotNull { n -> HomeTab.entries.firstOrNull { it.name == n } }.ifEmpty { HomeTab.entries }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, nav: Nav) {
    val tabs = rememberTabOrder()
    var reorderTabs by remember { mutableStateOf(false) }
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }

    // Changer d'onglet annule la sélection en cours
    LaunchedEffect(pager.currentPage) { nav.selection.clear() }

    // Retour depuis un autre onglet : revenir à « Titres » au lieu de quitter
    BackHandler(enabled = pager.currentPage != 0 && !nav.selection.active && !nav.flags.playerOpen) {
        scope.launch { pager.animateScrollToPage(0) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tone", fontWeight = FontWeight.Bold)
                    Text(" Dial", fontWeight = FontWeight.Bold, color = TdColors.Accent)
                }
            },
            actions = {
                IconButton(onClick = { nav.go(Screen.Search) }) { Icon(Icons.Outlined.Search, "Rechercher") }
                IconButton(onClick = { nav.go(Screen.Equalizer) }) { Icon(Icons.Outlined.Equalizer, "Égaliseur") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TdColors.SurfaceHigh) {
                        DropdownMenuItem(
                            text = { Text("Actualiser la bibliothèque") },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = { menu = false; vm.loadLibrary(); vm.notify("Bibliothèque actualisée") },
                        )
                        DropdownMenuItem(
                            text = { Text("Statistiques d'écoute") },
                            leadingIcon = { Icon(Icons.Outlined.BarChart, null) },
                            onClick = { menu = false; nav.go(Screen.Stats) },
                        )
                        DropdownMenuItem(
                            text = { Text("Réorganiser les onglets") },
                            leadingIcon = { Icon(Icons.Outlined.DragHandle, null) },
                            onClick = { menu = false; reorderTabs = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Réglages") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = { menu = false; nav.go(Screen.Settings) },
                        )
                        DropdownMenuItem(
                            text = { Text("Guide d'utilisation") },
                            leadingIcon = { Icon(Icons.Outlined.HelpOutline, null) },
                            onClick = { menu = false; nav.go(Screen.Guide) },
                        )
                        DropdownMenuItem(
                            text = { Text("À propos et mises à jour") },
                            leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick = { menu = false; nav.go(Screen.About) },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = TdColors.Background),
        )

        UpdateBanner()

        ScrollableTabRow(
            selectedTabIndex = pager.currentPage,
            containerColor = TdColors.Background,
            contentColor = TdColors.Accent,
            edgePadding = 12.dp,
            divider = { HorizontalDivider(color = TdColors.Divider) },
        ) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = {
                        // appui long sur un onglet : réorganiser les onglets
                        Text(tab.label, modifier = Modifier.combinedClickable(
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            onLongClick = { reorderTabs = true },
                        ))
                    },
                    selectedContentColor = TdColors.Accent,
                    unselectedContentColor = TdColors.TextDim,
                )
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            when (tabs.getOrElse(page) { HomeTab.SONGS }) {
                HomeTab.SONGS -> SongsTab(vm, nav)
                HomeTab.ALBUMS -> AlbumsTab(vm, nav)
                HomeTab.ARTISTS -> ArtistsTab(vm, nav)
                HomeTab.FOLDERS -> FoldersTab(vm, nav)
                HomeTab.PLAYLISTS -> PlaylistsTab(vm, nav)
            }
        }
    }

    if (reorderTabs) {
        TabOrderDialog(tabs, onDismiss = { reorderTabs = false }) { newOrder ->
            val current = tabs.getOrNull(pager.currentPage)
            com.thousandlabs.tonedial.data.UserStore.setTabOrder(newOrder.map { it.name })
            reorderTabs = false
            val target = newOrder.indexOf(current).coerceAtLeast(0)
            scope.launch { pager.scrollToPage(target) }
        }
    }
}

@Composable
private fun SongsTab(vm: MainViewModel, nav: Nav) {
    val songs by vm.sortedSongs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }

    if (loading && songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TdColors.Accent) }
        return
    }
    if (songs.isEmpty()) {
        EmptyState(
            Icons.Outlined.LibraryMusic,
            "Aucune musique trouvée",
            "Les fichiers audio téléchargés sur le téléphone apparaissent ici. Vérifiez la durée minimale dans les réglages.",
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { vm.play(songs, shuffle = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lecture aléatoire")
                }
                Spacer(Modifier.width(12.dp))
                Text(pluralTitles(songs.size), color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Outlined.Sort, null, modifier = Modifier.size(18.dp), tint = TdColors.TextDim)
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label, color = TdColors.TextDim)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, containerColor = TdColors.SurfaceHigh) {
                        SongSort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label, color = if (s == sort) TdColors.Accent else TdColors.Text) },
                                onClick = { vm.setSort(s); sortMenu = false },
                            )
                        }
                    }
                }
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SelectableSongRow(song, i, songs, nav, vm, player.currentId)
        }
    }
}

@Composable
private fun AlbumsTab(vm: MainViewModel, nav: Nav) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    if (albums.isEmpty()) {
        EmptyState(Icons.Outlined.Album, "Aucun album", "Les albums apparaîtront ici.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { nav.go(Screen.AlbumDetail(album.id)) }
            ) {
                ArtworkFill(album.art(), Modifier.fillMaxWidth().aspectRatio(1f), placeholder = Icons.Outlined.Album)
                Spacer(Modifier.height(8.dp))
                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${album.artist} · ${pluralTitles(album.songs.size)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab(vm: MainViewModel, nav: Nav) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    if (artists.isEmpty()) {
        EmptyState(Icons.Outlined.Person, "Aucun artiste", "Les artistes apparaîtront ici.")
        return
    }
    // complète en arrière-plan les photos d'artistes manquantes
    LaunchedEffect(artists.size) {
        com.thousandlabs.tonedial.data.Covers.fetchArtistImages(
            artists.filter { it.name != "Artiste inconnu" }.map { a -> a.name to a.songs.any { com.thousandlabs.tonedial.data.Covers.looksAnime(it) } }
        )
    }
    val coverVersion by com.thousandlabs.tonedial.data.Covers.version.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(artists, key = { it.name }) { a ->
            CollectionRow(
                art = remember(a.name, coverVersion) { com.thousandlabs.tonedial.data.Covers.artistFile(a.name) } ?: a.songs.firstOrNull()?.art(),
                placeholder = Icons.Outlined.Person,
                circle = true,
                title = a.name,
                subtitle = "${if (a.albumCount > 1) "${a.albumCount} albums" else "1 album"} · ${pluralTitles(a.songs.size)}",
                onClick = { nav.go(Screen.ArtistDetail(a.name)) },
            )
        }
    }
}

@Composable
private fun FoldersTab(vm: MainViewModel, nav: Nav) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    if (folders.isEmpty()) {
        EmptyState(Icons.Outlined.Folder, "Aucun dossier", "Les dossiers contenant de la musique apparaîtront ici.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(folders, key = { it.path }) { f ->
            CollectionRow(
                art = null,
                placeholder = Icons.Outlined.Folder,
                title = f.name,
                subtitle = "${pluralTitles(f.songs.size)} · ${f.path.removePrefix("/storage/emulated/0")}",
                onClick = { nav.go(Screen.FolderDetail(f.path)) },
            )
        }
    }
}

@Composable
private fun PlaylistsTab(vm: MainViewModel, nav: Nav) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        TextInputDialog(
            title = "Nouvelle playlist",
            placeholder = "Nom de la playlist",
            confirmLabel = "Créer",
            onDismiss = { creating = false },
            onConfirm = { vm.createPlaylist(it); creating = false },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionLabel("Playlists automatiques") }
        item {
            CollectionRow(null, Icons.Outlined.FavoriteBorder, title = "Favoris", subtitle = pluralTitles(favorites.size),
                onClick = { nav.go(Screen.AutoDetail(AutoList.FAVORITES)) })
            CollectionRow(null, Icons.Outlined.TrendingUp, title = "Les plus écoutés", subtitle = "Classement de vos écoutes",
                onClick = { nav.go(Screen.AutoDetail(AutoList.MOST_PLAYED)) })
            CollectionRow(null, Icons.Outlined.History, title = "Écoutés récemment", subtitle = "Vos derniers morceaux",
                onClick = { nav.go(Screen.AutoDetail(AutoList.RECENTLY_PLAYED)) })
            CollectionRow(null, Icons.Outlined.NewReleases, title = "Ajoutés récemment", subtitle = "Derniers fichiers téléchargés",
                onClick = { nav.go(Screen.AutoDetail(AutoList.RECENTLY_ADDED)) })
        }
        item {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel("Mes playlists") }
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Créer")
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "Aucune playlist pour l'instant. Appuyez sur « Créer », ou utilisez le menu d'un morceau pour l'ajouter à une playlist.",
                    color = TdColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(playlists, key = { it.id }) { p ->
            val coverVersion by com.thousandlabs.tonedial.data.Covers.version.collectAsStateWithLifecycle()
            val art = remember(p, coverVersion) { vm.playlistArt(p) }
            CollectionRow(
                art = art,
                placeholder = Icons.Outlined.QueueMusic,
                title = p.name,
                subtitle = pluralTitles(p.songIds.size),
                onClick = { nav.go(Screen.PlaylistDetail(p.id)) },
            )
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = TdColors.TextDim,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun CollectionRow(
    art: Any?,
    placeholder: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    circle: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, size = 52.dp, corner = if (circle) 26.dp else 10.dp, placeholder = placeholder)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TdColors.TextDim)
    }
}

// ------------------------------------------------------------------
// Écrans de détail
// ------------------------------------------------------------------

@Composable
fun SongCollectionScreen(
    vm: MainViewModel,
    nav: Nav,
    title: String,
    subtitle: String,
    art: Any?,
    placeholder: ImageVector,
    songs: List<Song>,
    numbered: Boolean = false,
    playlist: Playlist? = null,
    emptyText: String = "Aucun titre.",
    actions: @Composable () -> Unit = {},
) {
    val player by vm.player.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = title, onBack = nav.back, actions = actions)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(art, size = 112.dp, corner = 14.dp, placeholder = placeholder)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim, maxLines = 2)
                    }
                }
                if (songs.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.play(songs) },
                            colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Lire")
                        }
                        OutlinedButton(onClick = { vm.play(songs, shuffle = true) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(18.dp), tint = TdColors.Text)
                            Spacer(Modifier.width(6.dp))
                            Text("Aléatoire", color = TdColors.Text)
                        }
                        OutlinedButton(onClick = { vm.addToQueue(songs) }) {
                            Icon(Icons.Outlined.AddToQueue, "Ajouter à la file", modifier = Modifier.size(18.dp), tint = TdColors.Text)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(emptyText, color = TdColors.TextDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
                if (playlist != null) {
                    OutlinedButton(
                        onClick = { nav.go(Screen.AddToPlaylist(playlist.id)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Icon(Icons.Outlined.PlaylistAdd, null, modifier = Modifier.size(18.dp), tint = TdColors.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Ajouter des titres", color = TdColors.Accent)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            itemsIndexed(songs, key = { i, s -> "${s.id}_$i" }) { i, song ->
                SelectableSongRow(
                    song, i, songs, nav, vm, player.currentId, playlist,
                    leadingNumber = if (numbered) (if (song.track > 0) song.track else i + 1) else null,
                )
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(vm: MainViewModel, nav: Nav, id: Long) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == id }
    if (album == null) {
        MissingScreen(nav)
        return
    }
    SongCollectionScreen(
        vm, nav, album.name,
        "${album.artist}\n${pluralTitles(album.songs.size)} · ${totalDuration(album.songs)}",
        album.art(), Icons.Outlined.Album, album.songs, numbered = true,
    )
}

@Composable
fun ArtistDetailScreen(vm: MainViewModel, nav: Nav, name: String) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    val artist = artists.firstOrNull { it.name == name } ?: artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (artist == null) {
        MissingScreen(nav)
        return
    }
    SongCollectionScreen(
        vm, nav, artist.name,
        "${artist.albumCount} album(s) · ${pluralTitles(artist.songs.size)} · ${totalDuration(artist.songs)}",
        (com.thousandlabs.tonedial.data.Covers.artistFile(artist.name) ?: artist.songs.firstOrNull()?.art()), Icons.Outlined.Person, artist.songs,
    )
}

@Composable
fun FolderDetailScreen(vm: MainViewModel, nav: Nav, path: String) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val folder = folders.firstOrNull { it.path == path }
    if (folder == null) {
        MissingScreen(nav)
        return
    }
    SongCollectionScreen(
        vm, nav, folder.name,
        "${pluralTitles(folder.songs.size)} · ${folder.path.removePrefix("/storage/emulated/0")}",
        null, Icons.Outlined.Folder, folder.songs,
        actions = {
            IconButton(onClick = { vm.excludeFolders(listOf(folder.path)); nav.back() }) {
                Icon(Icons.Outlined.VisibilityOff, "Masquer ce dossier")
            }
        },
    )
}

@Composable
private fun SortMenuButton(current: PlaylistSort, options: List<PlaylistSort>, onPick: (PlaylistSort) -> Unit, extra: (@Composable (close: () -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.Sort, "Trier") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = TdColors.SurfaceHigh) {
            Text(
                "TRIER PAR",
                style = MaterialTheme.typography.labelLarge,
                color = TdColors.TextDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(o.label, color = if (o == current) TdColors.Accent else TdColors.Text) },
                    trailingIcon = { if (o == current) Icon(Icons.Outlined.Check, null, tint = TdColors.Accent) },
                    onClick = { open = false; onPick(o) },
                )
            }
            if (extra != null) {
                HorizontalDivider(color = TdColors.Divider)
                extra { open = false }
            }
        }
    }
}

@Composable
fun AutoDetailScreen(vm: MainViewModel, nav: Nav, kind: AutoList) {
    // recalcule quand les données ou le tri changent
    vm.favorites.collectAsStateWithLifecycle().value
    vm.songs.collectAsStateWithLifecycle().value
    vm.sortVersion.collectAsStateWithLifecycle().value
    val songs = vm.autoList(kind)
    val icon = when (kind) {
        AutoList.FAVORITES -> Icons.Outlined.FavoriteBorder
        AutoList.MOST_PLAYED -> Icons.Outlined.TrendingUp
        AutoList.RECENTLY_PLAYED -> Icons.Outlined.History
        AutoList.RECENTLY_ADDED -> Icons.Outlined.NewReleases
    }
    val empty = when (kind) {
        AutoList.FAVORITES -> "Aucun favori. Appuyez sur le cœur dans le lecteur pour en ajouter."
        AutoList.MOST_PLAYED, AutoList.RECENTLY_PLAYED -> "Écoutez quelques morceaux, ils apparaîtront ici."
        AutoList.RECENTLY_ADDED -> "Aucun titre."
    }
    val favSort = vm.favoritesSort()
    SongCollectionScreen(
        vm, nav, kind.label,
        "${pluralTitles(songs.size)} · ${totalDuration(songs)}" + if (kind == AutoList.FAVORITES) "\nTri : ${favSort.label}" else "",
        songs.firstOrNull()?.art(), icon, songs, emptyText = empty,
        actions = {
            if (kind == AutoList.FAVORITES) {
                SortMenuButton(
                    current = favSort,
                    options = PlaylistSort.entries.filter { it != PlaylistSort.CUSTOM },
                    onPick = { vm.setFavoritesSort(it) },
                )
            }
        },
    )
}

@Composable
fun PlaylistDetailScreen(vm: MainViewModel, nav: Nav, id: Long) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    vm.songs.collectAsStateWithLifecycle().value
    vm.sortVersion.collectAsStateWithLifecycle().value
    val playlist = playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        MissingScreen(nav)
        return
    }
    val songs = vm.playlistSongs(playlist)
    val sort = vm.playlistSort(playlist)
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    val coverVersion by com.thousandlabs.tonedial.data.Covers.version.collectAsStateWithLifecycle()
    val playlistArt = remember(playlist, songs, coverVersion) { vm.playlistArt(playlist) }
    val hasImage = remember(playlist, coverVersion) { com.thousandlabs.tonedial.data.Covers.playlistFile(playlist.id) != null }
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.setPlaylistImage(playlist, uri) }

    if (reordering) {
        ReorderScreen(
            title = playlist.name,
            initial = songs,
            onCancel = { reordering = false },
            onSave = { vm.savePlaylistOrder(playlist, it); reordering = false },
        )
        return
    }

    if (renaming) {
        TextInputDialog(
            title = "Renommer la playlist",
            initial = playlist.name,
            onDismiss = { renaming = false },
            onConfirm = { vm.renamePlaylist(playlist, it); renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la playlist ?") },
            text = { Text("« ${playlist.name} » sera supprimée. Vos fichiers de musique ne sont pas touchés.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deletePlaylist(playlist); nav.back() }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
            containerColor = TdColors.Surface,
        )
    }

    SongCollectionScreen(
        vm, nav, playlist.name,
        "${pluralTitles(songs.size)} · ${totalDuration(songs)}\nTri : ${sort.label}",
        playlistArt, Icons.Outlined.QueueMusic, songs,
        playlist = playlist,
        emptyText = "Playlist vide. Appuyez sur « Ajouter des titres » pour la remplir.",
        actions = {
            SortMenuButton(
                current = sort,
                options = PlaylistSort.entries,
                onPick = { vm.setPlaylistSort(playlist, it) },
                extra = { close ->
                    DropdownMenuItem(
                        text = { Text("Réorganiser à la main") },
                        leadingIcon = { Icon(Icons.Outlined.DragHandle, null) },
                        enabled = songs.size > 1,
                        onClick = { close(); reordering = true },
                    )
                },
            )
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TdColors.SurfaceHigh) {
                    DropdownMenuItem(text = { Text("Réorganiser à la main") }, leadingIcon = { Icon(Icons.Outlined.DragHandle, null) },
                        enabled = songs.size > 1, onClick = { menu = false; reordering = true })
                    DropdownMenuItem(text = { Text("Renommer") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Changer l'image") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                        onClick = {
                            menu = false
                            imagePicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        })
                    if (hasImage) {
                        DropdownMenuItem(text = { Text("Retirer l'image") }, leadingIcon = { Icon(Icons.Outlined.HideImage, null) },
                            onClick = { menu = false; vm.removePlaylistImage(playlist) })
                    }
                    DropdownMenuItem(text = { Text("Supprimer") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
        },
    )
}

/** Écran de réorganisation : on part de l'ordre affiché et on déplace les titres avec la poignée. */
@Composable
private fun ReorderScreen(title: String, initial: List<Song>, onCancel: () -> Unit, onSave: (List<Song>) -> Unit) {
    val items = remember(initial) { mutableStateListOf<Song>().apply { addAll(initial) } }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Réorganiser", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Annuler") } },
            actions = {
                TextButton(onClick = { onSave(items.toList()) }) {
                    Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Terminé")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = TdColors.Background),
        )
        Text(
            "Faites glisser la poignée à droite d'un titre pour le déplacer dans « $title ». Appuyez sur Terminé pour enregistrer.",
            style = MaterialTheme.typography.bodySmall,
            color = TdColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        ReorderList(items, Modifier.fillMaxSize())
    }
}

@Composable
private fun MissingScreen(nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Introuvable", nav.back)
        EmptyState(Icons.Outlined.Search, "Élément introuvable", "Il a peut-être été supprimé du téléphone.")
    }
}

// ------------------------------------------------------------------
// Recherche
// ------------------------------------------------------------------

@Composable
fun SearchScreen(vm: MainViewModel, nav: Nav) {
    var query by remember { mutableStateOf("") }
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val q = query.trim().lowercase()
    val foundSongs = remember(q, songs) {
        if (q.isEmpty()) emptyList() else songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }.sortedBy { it.title.lowercase() }
    }
    val foundAlbums = remember(q, albums) { if (q.isEmpty()) emptyList() else albums.filter { it.name.lowercase().contains(q) }.take(10) }
    val foundArtists = remember(q, artists) { if (q.isEmpty()) emptyList() else artists.filter { it.name.lowercase().contains(q) }.take(10) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(Icons.Outlined.ArrowBack, "Retour") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Titre, artiste ou album") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Effacer") }
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        if (q.isNotEmpty() && foundSongs.isEmpty() && foundAlbums.isEmpty() && foundArtists.isEmpty()) {
            EmptyState(Icons.Outlined.Search, "Aucun résultat", "Essayez avec un autre mot.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (foundArtists.isNotEmpty()) {
                item { SectionLabel("Artistes") }
                items(foundArtists, key = { "ar_" + it.name }) { a ->
                    CollectionRow(com.thousandlabs.tonedial.data.Covers.artistFile(a.name) ?: a.songs.firstOrNull()?.art(), Icons.Outlined.Person, a.name, pluralTitles(a.songs.size),
                        onClick = { nav.go(Screen.ArtistDetail(a.name)) }, circle = true)
                }
            }
            if (foundAlbums.isNotEmpty()) {
                item { SectionLabel("Albums") }
                items(foundAlbums, key = { "al_" + it.id }) { a ->
                    CollectionRow(a.art(), Icons.Outlined.Album, a.name, "${a.artist} · ${pluralTitles(a.songs.size)}",
                        onClick = { nav.go(Screen.AlbumDetail(a.id)) })
                }
            }
            if (foundSongs.isNotEmpty()) {
                item { SectionLabel("Titres") }
                itemsIndexed(foundSongs, key = { _, s -> "s_" + s.id }) { i, s ->
                    SelectableSongRow(s, i, foundSongs, nav, vm, player.currentId)
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Réglages
// ------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: MainViewModel, nav: Nav) {
    val minDur by vm.minDurationSec.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Réglages", nav.back)
        LazyColumn {
            item {
                SectionLabel("Bibliothèque")
                Text(
                    "Ignorer les fichiers plus courts que :",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Utile pour masquer les notes vocales, sonneries et sons de notification.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Aucun", 10 to "10 s", 30 to "30 s", 60 to "1 min").forEach { (v, label) ->
                        FilterChip(
                            selected = minDur == v,
                            onClick = { vm.setMinDuration(v) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TdColors.AccentDim,
                                selectedLabelColor = TdColors.Accent,
                            ),
                        )
                    }
                }
                ListItemRow(Icons.Outlined.Refresh, "Actualiser la bibliothèque", subtitle = "Rechercher les nouvelles musiques",
                    onClick = { vm.loadLibrary(); vm.notify("Bibliothèque actualisée") })
                var mode by remember { mutableStateOf(vm.artistMode()) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Person, null, tint = TdColors.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(18.dp))
                        Text("Identifier les artistes", style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (mode) {
                            com.thousandlabs.tonedial.data.ArtistMode.OFF -> "Seuls les artistes enregistrés dans les fichiers sont utilisés."
                            com.thousandlabs.tonedial.data.ArtistMode.GROUP -> "Vos titres restent exactement comme vous les avez nommés (« Eminem - Rap God »). L'artiste est repéré au début du nom, ou quand plusieurs titres commencent par les mêmes mots, et l'onglet Artistes regroupe ses chansons."
                            com.thousandlabs.tonedial.data.ArtistMode.FULL -> "« Eminem - Rap God » s'affiche avec le titre « Rap God » et l'artiste « Eminem ». Les mentions comme (Official Video) sont retirées."
                        } + " Vos fichiers ne sont jamais modifiés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TdColors.TextDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.thousandlabs.tonedial.data.ArtistMode.entries.forEach { m ->
                            FilterChip(
                                selected = mode == m,
                                onClick = { mode = m; vm.setArtistMode(m) },
                                label = { Text(m.label, style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.AccentDim, selectedLabelColor = TdColors.Accent),
                            )
                        }
                    }
                }
                val tabOrder = rememberTabOrder()
                var editTabs by remember { mutableStateOf(false) }
                ListItemRow(
                    Icons.Outlined.DragHandle, "Ordre des onglets",
                    subtitle = tabOrder.joinToString(" · ") { it.label },
                    onClick = { editTabs = true },
                )
                if (editTabs) {
                    TabOrderDialog(tabOrder, onDismiss = { editTabs = false }) {
                        com.thousandlabs.tonedial.data.UserStore.setTabOrder(it.map { t -> t.name })
                        editTabs = false
                    }
                }
                val excluded by vm.excludedFolders.collectAsStateWithLifecycle()
                ListItemRow(
                    Icons.Outlined.FolderOff, "Dossiers affichés",
                    subtitle = if (excluded.isEmpty()) "Masquer les audios WhatsApp, enregistrements…" else "${excluded.size} dossier(s) masqué(s)",
                    onClick = { nav.go(Screen.FolderFilter) },
                )
                SectionLabel("Pochettes")
                CoverSettings(vm, nav)
                SectionLabel("Lecture")
                var tapJump by remember { mutableStateOf(vm.tapJumpsInQueue()) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.PlaylistPlay, null, tint = TdColors.Text, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Aller au titre dans la file", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Appuyer sur un titre déjà présent dans la file d'attente y va directement, sans changer la file ni son ordre. Pour lancer une nouvelle lecture, utilisez les boutons Lire ou Aléatoire.",
                            style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                        )
                    }
                    Switch(
                        checked = tapJump,
                        onCheckedChange = { tapJump = it; vm.setTapJumpsInQueue(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = TdColors.Accent, checkedThumbColor = TdColors.Background),
                    )
                }
                SectionLabel("Openings et endings")
                val animeBatch by vm.animeBatch.collectAsStateWithLifecycle()
                if (animeBatch.running) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("Identification : ${animeBatch.done} / ${animeBatch.total} · ${animeBatch.found} trouvé(s)", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (animeBatch.total > 0) animeBatch.done.toFloat() / animeBatch.total else 0f },
                            modifier = Modifier.fillMaxWidth(), color = TdColors.Accent, trackColor = TdColors.Divider,
                        )
                        TextButton(onClick = { vm.stopIdentifyAllOpenings() }) { Text("Arrêter") }
                    }
                } else {
                    ListItemRow(
                        Icons.Outlined.TravelExplore, "Identifier tous les openings",
                        subtitle = if (animeBatch.finished) {
                            val missing = animeBatch.total - animeBatch.found
                            "Terminé : ${animeBatch.found} sur ${animeBatch.total} identifié(s)" +
                                (if (missing > 0) ", $missing à corriger à la main (Modifier titre, artiste, album)." else ".") +
                                " Appuyez pour relancer."
                        } else "Trouve l'interprète, la pochette et les paroles de chaque opening ou ending (liste intégrée pour One Piece et Black Clover, MyAnimeList pour les autres). Vos titres ne changent pas.",
                        onClick = { vm.identifyAllOpenings() },
                    )
                }
                SectionLabel("Paroles")
                LyricsSettings(vm)
                SectionLabel("Son")
                ListItemRow(Icons.Outlined.Equalizer, "Égaliseur et basses", onClick = { nav.go(Screen.Equalizer) })
                SectionLabel("Sauvegarde")
                BackupSettings(vm)
                SectionLabel("Statistiques")
                ListItemRow(Icons.Outlined.BarChart, "Statistiques d'écoute", subtitle = "Temps d'écoute, titres et artistes préférés", onClick = { nav.go(Screen.Stats) })
                SectionLabel("Aide")
                ListItemRow(Icons.Outlined.HelpOutline, "Guide d'utilisation", onClick = { nav.go(Screen.Guide) })
                ListItemRow(Icons.Outlined.Info, "À propos et mises à jour", onClick = { nav.go(Screen.About) })
            }
        }
    }
}

@Composable
fun CircleIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(TdColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = TdColors.Accent, modifier = Modifier.size(20.dp)) }
}

// ------------------------------------------------------------------
// Ajout de titres dans une playlist (sans doublons)
// ------------------------------------------------------------------

@Composable
fun PlaylistPickerScreen(vm: MainViewModel, nav: Nav, id: Long) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val songs by vm.sortedSongs.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        MissingScreen(nav)
        return
    }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val inPlaylist = remember(playlist.songIds) { playlist.songIds.toSet() }
    val q = query.trim().lowercase()
    val notInPlaylist = remember(songs, inPlaylist) { songs.filter { it.id !in inPlaylist } }
    val shown = remember(notInPlaylist, q) {
        if (q.isEmpty()) notInPlaylist else notInPlaylist.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
    }
    val allShownPicked = shown.isNotEmpty() && shown.all { it.id in picked }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Ajouter à « ${playlist.name} »", nav.back, actions = {
            IconButton(
                onClick = {
                    picked = if (allShownPicked) picked - shown.map { it.id }.toSet() else picked + shown.map { it.id }
                },
                enabled = shown.isNotEmpty(),
            ) {
                Icon(
                    Icons.Outlined.SelectAll,
                    if (allShownPicked) "Tout désélectionner" else "Tout sélectionner",
                    tint = if (allShownPicked) TdColors.Accent else TdColors.Text,
                )
            }
        })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Rechercher un titre, un artiste, un album") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Effacer") }
            },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            if (notInPlaylist.isEmpty()) "Tous vos titres sont déjà dans cette playlist."
            else "${pluralTitles(shown.size)} disponibles · les titres déjà présents sont masqués",
            style = MaterialTheme.typography.bodySmall,
            color = TdColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.id }) { song ->
                val isPicked = song.id in picked
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isPicked) TdColors.AccentDim else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { picked = if (isPicked) picked - song.id else picked + song.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.art(), size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                            color = if (isPicked) TdColors.Accent else TdColors.Text)
                        Text("${song.artist} · ${song.album}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                    }
                    Icon(
                        if (isPicked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        if (isPicked) "Coché" else "Non coché",
                        tint = if (isPicked) TdColors.Accent else TdColors.TextDim,
                    )
                }
            }
        }
        Surface(color = TdColors.Surface, shadowElevation = 8.dp) {
            Button(
                onClick = {
                    val toAdd = songs.filter { it.id in picked }
                    vm.addToPlaylist(playlist, toAdd)
                    nav.back()
                },
                enabled = picked.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
            ) {
                Icon(Icons.Outlined.PlaylistAdd, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (picked.isEmpty()) "Cochez des titres à ajouter" else "Ajouter (${picked.size})")
            }
        }
    }
}


@Composable
private fun CoverSettings(vm: MainViewModel, nav: Nav) {
    val progress by vm.coverProgress.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(com.thousandlabs.tonedial.data.Covers.autoEnabled()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Image, null, tint = TdColors.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Pochettes automatiques", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Quand un titre sans image est joué, l'appli cherche sa pochette officielle en ligne et la garde sur le téléphone.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            )
        }
        Switch(
            checked = auto,
            onCheckedChange = { auto = it; com.thousandlabs.tonedial.data.Covers.setAutoEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = TdColors.Accent, checkedThumbColor = TdColors.Background),
        )
    }
    if (progress.running) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Recherche des pochettes : ${progress.done} / ${progress.total} · ${progress.found} trouvée(s)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = TdColors.Accent,
                trackColor = TdColors.Divider,
            )
            Text(
                "Quelques secondes par titre. Vous pouvez utiliser l'appli normalement pendant ce temps.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = { vm.stopCoverSearch() }) { Text("Arrêter") }
        }
    } else {
        ListItemRow(
            Icons.Outlined.ImageSearch, "Rechercher toutes les pochettes manquantes",
            subtitle = if (progress.finished) "Terminé : ${progress.found} pochette(s) ajoutée(s). Appuyez pour relancer." else "Pour tous les titres et albums sans image. Connexion Internet nécessaire.",
            onClick = { vm.startCoverSearch() },
        )
    }
    if (progress.missing.isNotEmpty()) {
        ListItemRow(
            Icons.Outlined.HideImage, "Titres sans pochette (${progress.missing.size})",
            subtitle = "Introuvables en ligne : choisissez une image ou relancez la recherche titre par titre",
            onClick = { nav.go(Screen.MissingCovers) },
        )
    }
}

@Composable
private fun BackupSettings(vm: MainViewModel) {
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportBackup(uri) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importBackup(uri) }
    ListItemRow(
        Icons.Outlined.Backup, "Sauvegarder mes données",
        subtitle = "Playlists, favoris, écoutes, paroles, corrections et réglages dans un fichier",
        onClick = { exportLauncher.launch(com.thousandlabs.tonedial.data.Backup.suggestedName()) },
    )
    ListItemRow(
        Icons.Outlined.Restore, "Restaurer une sauvegarde",
        subtitle = "Sur ce téléphone ou un nouveau. Les playlists du même nom sont fusionnées.",
        onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
    )
    Text(
        "Conseil : enregistrez la sauvegarde dans Google Drive ou envoyez-la-vous par WhatsApp, pour la retrouver si vous changez de téléphone.",
        style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}


/** Réorganisation des onglets de l'accueil par glisser-déposer. */
@Composable
fun TabOrderDialog(current: List<HomeTab>, onDismiss: () -> Unit, onSave: (List<HomeTab>) -> Unit) {
    val items = remember { mutableStateListOf<HomeTab>().apply { addAll(current) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordre des onglets") },
        text = {
            Column {
                Text(
                    "Faites glisser la poignée pour changer l'ordre. Le premier onglet est celui qui s'ouvre au lancement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                ReorderableList(items, key = { it.name }, modifier = Modifier.height(300.dp)) { tab, i, dragging, handle ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", modifier = Modifier.width(26.dp), color = TdColors.TextDim, style = MaterialTheme.typography.bodySmall)
                        Icon(
                            when (tab) {
                                HomeTab.SONGS -> Icons.Outlined.MusicNote
                                HomeTab.ALBUMS -> Icons.Outlined.Album
                                HomeTab.ARTISTS -> Icons.Outlined.Person
                                HomeTab.FOLDERS -> Icons.Outlined.Folder
                                HomeTab.PLAYLISTS -> Icons.Outlined.QueueMusic
                            },
                            null,
                            tint = if (dragging) TdColors.Accent else TdColors.Text,
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(tab.label, modifier = Modifier.weight(1f), color = if (dragging) TdColors.Accent else TdColors.Text)
                        DragHandleBox(handle, dragging)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(items.toList()) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        containerColor = TdColors.Surface,
    )
}


@Composable
private fun LyricsSettings(vm: MainViewModel) {
    val progress by vm.lyricsBatch.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(com.thousandlabs.tonedial.data.LyricsBatch.autoEnabled()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Subject, null, tint = TdColors.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Paroles automatiques", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Dès qu'un titre sans paroles commence, l'appli les cherche en ligne et les garde sur le téléphone. Elles sont prêtes quand vous ouvrez l'onglet Paroles.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            )
        }
        Switch(
            checked = auto,
            onCheckedChange = { auto = it; com.thousandlabs.tonedial.data.LyricsBatch.setAutoEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = TdColors.Accent, checkedThumbColor = TdColors.Background),
        )
    }
    var romaji by remember { mutableStateOf(com.thousandlabs.tonedial.data.LyricsOnline.preferRomaji()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Translate, null, tint = TdColors.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text("Paroles japonaises en romaji", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Pour les chansons japonaises, l'appli choisit en priorité une version des paroles déjà écrite en lettres latines sur LRCLIB (« saikō no… »). Si aucune n'existe, elle garde l'écriture japonaise.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            )
        }
        Switch(
            checked = romaji,
            onCheckedChange = { romaji = it; com.thousandlabs.tonedial.data.LyricsOnline.setPreferRomaji(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = TdColors.Accent, checkedThumbColor = TdColors.Background),
        )
    }
    if (romaji && !progress.running) {
        ListItemRow(
            Icons.Outlined.Translate, "Passer les paroles japonaises en romaji",
            subtitle = "Pour les paroles japonaises déjà enregistrées : cherche leur version romaji et la met à la place quand elle existe. Les paroles collées à la main ne changent pas.",
            onClick = { vm.startRomajiBatch() },
        )
    }
    if (progress.running) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Recherche des paroles : ${progress.done} / ${progress.total} · ${progress.found} trouvée(s)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = TdColors.Accent,
                trackColor = TdColors.Divider,
            )
            TextButton(onClick = { vm.stopLyricsBatch() }) { Text("Arrêter") }
        }
    } else {
        ListItemRow(
            Icons.Outlined.Search, "Rechercher toutes les paroles manquantes",
            subtitle = if (progress.finished) "Terminé : ${progress.found} parole(s) ajoutée(s). Appuyez pour relancer." else "Pour tous les titres sans paroles. Connexion Internet nécessaire.",
            onClick = { vm.startLyricsBatch() },
        )
    }
}
