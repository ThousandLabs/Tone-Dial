@file:OptIn(ExperimentalMaterial3Api::class)

package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art

// ------------------------------------------------------------------
// Dossiers exclus
// ------------------------------------------------------------------

private val messagingHints = listOf("whatsapp", "telegram", "messenger", "voice notes", "voice recorder", "recordings", "enregistrements", "ringtones", "notifications", "alarms", "call", "appels")

@Composable
fun FolderFilterScreen(vm: MainViewModel, nav: Nav) {
    val folders by vm.allFolders.collectAsStateWithLifecycle()
    val excluded by vm.excludedFolders.collectAsStateWithLifecycle()
    val suggested = remember(folders, excluded) {
        folders.filter { f -> f.path !in excluded && messagingHints.any { f.path.lowercase().contains(it) } }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Dossiers affichés", nav.back)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Décochez un dossier pour masquer ses fichiers dans toute l'appli. Les fichiers ne sont pas supprimés, et vous pouvez les réafficher à tout moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (suggested.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TdColors.SurfaceHigh)
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FilterAlt, null, tint = TdColors.Accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Suggestion", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${suggested.size} dossier(s) semblent contenir des audios de messagerie (WhatsApp…), des enregistrements ou des sonneries : ${suggested.take(3).joinToString { it.name }}${if (suggested.size > 3) "…" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TdColors.TextDim,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { vm.excludeFolders(suggested.map { it.path }) },
                            colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
                        ) { Text("Masquer ces dossiers") }
                    }
                }
            }
            item { SectionLabel("Dossiers contenant de l'audio") }
            items(folders, key = { it.path }) { f ->
                val shown = !(f.path in excluded || excluded.any { f.path.startsWith("$it/") })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleExcludedFolder(f.path) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (shown) Icons.Outlined.Folder else Icons.Outlined.FolderOff,
                        null,
                        tint = if (shown) TdColors.Text else TdColors.TextDim,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (shown) TdColors.Text else TdColors.TextDim)
                        Text(
                            "${pluralTitles(f.songs.size)} · ${f.path.removePrefix("/storage/emulated/0")}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                        )
                    }
                    Checkbox(
                        checked = shown,
                        onCheckedChange = { vm.toggleExcludedFolder(f.path) },
                        colors = CheckboxDefaults.colors(checkedColor = TdColors.Accent, checkmarkColor = TdColors.Background),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Statistiques d'écoute
// ------------------------------------------------------------------

private fun formatListening(ms: Long): String {
    val min = ms / 60_000
    val h = min / 60
    val m = min % 60
    return when {
        h > 0 -> "$h h ${"%02d".format(m)}"
        else -> "$m min"
    }
}

@Composable
fun StatsScreen(vm: MainViewModel, nav: Nav) {
    var period by remember { mutableStateOf<Int?>(7) }
    val version by vm.statsVersion.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val stats = remember(period, version) { vm.stats(period) }

    val topSongs = remember(stats, songMap) {
        stats.plays.entries.sortedByDescending { it.value }.mapNotNull { e -> songMap[e.key]?.let { it to e.value } }.take(10)
    }
    val topArtists = remember(stats, songMap) {
        val m = HashMap<String, Long>()
        stats.timeBySong.forEach { (id, ms) -> songMap[id]?.artists?.forEach { a -> if (a != "Artiste inconnu") m[a] = (m[a] ?: 0L) + ms } }
        m.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
    }
    val totalPlays = stats.plays.values.sum()

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Statistiques", nav.back)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Pair<Int?, String>>(7 to "7 jours", 30 to "30 jours", null to "Depuis toujours").forEach { (p, label) ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.AccentDim, selectedLabelColor = TdColors.Accent),
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Temps d'écoute", formatListening(stats.totalMs), Modifier.weight(1f))
                    StatTile("Écoutes", totalPlays.toString(), Modifier.weight(1f))
                    StatTile("Jours actifs", stats.activeDays.toString(), Modifier.weight(1f))
                }
            }
            if (stats.byDay.isNotEmpty() && stats.byDay.any { it.second > 0 }) {
                item {
                    SectionLabel(if (period == null) "30 derniers jours" else "Par jour")
                    DayBars(stats.byDay)
                }
            }
            item { SectionLabel("Titres les plus écoutés") }
            if (topSongs.isEmpty()) {
                item {
                    Text(
                        "Écoutez quelques morceaux : vos titres préférés apparaîtront ici.",
                        color = TdColors.TextDim, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(topSongs.size) { i ->
                val (song, count) = topSongs[i]
                RankRow(i + 1, song, "${count} écoute${if (count > 1) "s" else ""}") { vm.play(topSongs.map { it.first }, i) }
            }
            if (topArtists.isNotEmpty()) {
                item { SectionLabel("Artistes les plus écoutés") }
                items(topArtists.size) { i ->
                    val (name, ms) = topArtists[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.go(Screen.ArtistDetail(name)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", modifier = Modifier.width(28.dp), color = TdColors.Accent, fontWeight = FontWeight.Bold)
                        Icon(Icons.Outlined.Person, null, tint = TdColors.TextDim)
                        Spacer(Modifier.width(12.dp))
                        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatListening(ms), style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                    }
                }
            }
            item {
                Text(
                    "Les statistiques sont calculées et gardées uniquement sur votre téléphone. Le détail jour par jour couvre les 120 derniers jours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TdColors.Surface)
            .padding(12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = TdColors.Accent, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim, maxLines = 1)
    }
}

@Composable
private fun DayBars(days: List<Pair<String, Long>>) {
    val max = (days.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    Row(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (_, ms) ->
            val frac = (ms.toFloat() / max).coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (ms > 0) frac.coerceAtLeast(0.04f) else 0.02f)
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (ms > 0) TdColors.Accent else TdColors.Divider)
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(days.first().first.substring(5).replace('-', '/'), style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
        Spacer(Modifier.weight(1f))
        Text("Aujourd'hui", style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
    }
}

@Composable
private fun RankRow(rank: Int, song: Song, info: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", modifier = Modifier.width(28.dp), color = TdColors.Accent, fontWeight = FontWeight.Bold)
        Artwork(song.art(), size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
        }
        Text(info, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
    }
}

// ------------------------------------------------------------------
// Titres sans pochette
// ------------------------------------------------------------------

@Composable
fun MissingCoversScreen(vm: MainViewModel, nav: Nav) {
    val progress by vm.coverProgress.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val coverVersion by com.thousandlabs.tonedial.data.Covers.version.collectAsStateWithLifecycle()
    val songs = remember(progress.missing, songMap, coverVersion) {
        progress.missing.mapNotNull { songMap[it] }
            .filter { com.thousandlabs.tonedial.data.Covers.stamp(it.id, it.albumId) == 0L }
            .sortedBy { it.title.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Titres sans pochette", nav.back)
        if (songs.isEmpty()) {
            EmptyState(Icons.Outlined.Image, "Tout est complet", "Tous les titres de la liste ont maintenant une pochette.")
            return@Column
        }
        Text(
            "Ces titres n'ont été trouvés dans aucun catalogue. Choisissez une image dans la galerie, ou relancez la recherche pour un titre précis (après avoir corrigé son nom si besoin).",
            style = MaterialTheme.typography.bodySmall,
            color = TdColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(songs, key = { it.id }) { song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.art(), size = 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                    }
                    IconButton(onClick = { vm.searchCover(song) }) { Icon(Icons.Outlined.ImageSearch, "Chercher en ligne", tint = TdColors.Text) }
                    IconButton(onClick = { nav.pickCover(song) }) { Icon(Icons.Outlined.AddPhotoAlternate, "Choisir dans la galerie", tint = TdColors.Accent) }
                }
            }
        }
    }
}

private enum class OpeningFilter(val label: String) { Found("Identifiés"), Missing("Non trouvés"), Pending("Pas encore cherchés") }

/** Openings et endings : lesquels sont déjà identifiés, lesquels restent à faire. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpeningsStatusScreen(vm: MainViewModel, nav: Nav) {
    val status by vm.openingsStatus.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val batch by vm.animeBatch.collectAsStateWithLifecycle()
    val groups = remember(status, songs) {
        songs.filter { com.thousandlabs.tonedial.data.Covers.looksAnime(it) }
            .sortedBy { it.title.lowercase() }
            .groupBy { s ->
                val st = status[s.id]
                when {
                    st?.artist != null || vm.hasOverride(s) -> OpeningFilter.Found
                    st != null -> OpeningFilter.Missing
                    else -> OpeningFilter.Pending
                }
            }
    }
    var filter by remember { mutableStateOf(OpeningFilter.Found) }
    val list = groups[filter].orEmpty()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Openings identifiés", nav.back)
        if (batch.running) {
            Text(
                "Identification en cours : ${batch.done} / ${batch.total}. La liste se met à jour au fur et à mesure.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpeningFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} (${groups[f].orEmpty().size})", maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.Accent, selectedLabelColor = TdColors.Background),
                )
            }
        }
        if (list.isEmpty()) {
            EmptyState(
                Icons.Outlined.TravelExplore,
                when (filter) {
                    OpeningFilter.Found -> "Aucun opening identifié"
                    OpeningFilter.Missing -> "Aucun échec"
                    OpeningFilter.Pending -> "Tout a été cherché"
                },
                when (filter) {
                    OpeningFilter.Found -> "Lancez « Identifier tous les openings » dans les réglages."
                    OpeningFilter.Missing -> "Tous les openings cherchés ont été trouvés."
                    OpeningFilter.Pending -> "Chaque opening a déjà été cherché au moins une fois."
                },
            )
            return@Column
        }
        if (filter == OpeningFilter.Missing) {
            Text(
                "Vérifiez le nom du fichier (« One Piece Opening 25 - The Peak »), relancez la recherche avec la loupe, ou corrigez l'artiste depuis le menu du titre.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.openMenu(song, null) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.art(), size = 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        val artist = status[song.id]?.artist ?: song.artist
                        Text(
                            if (filter == OpeningFilter.Found) artist else song.artist,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = if (filter == OpeningFilter.Found) TdColors.Accent else TdColors.TextDim,
                        )
                    }
                    if (filter == OpeningFilter.Found) {
                        Icon(Icons.Outlined.CheckCircle, "Identifié", tint = TdColors.Accent, modifier = Modifier.size(20.dp))
                    } else {
                        IconButton(onClick = { vm.identifySong(song) }) { Icon(Icons.Outlined.TravelExplore, "Identifier l'opening", tint = TdColors.Text) }
                    }
                }
            }
        }
    }
}

/** Titres masqués un par un, avec un bouton pour les réafficher. */
@Composable
fun HiddenSongsScreen(vm: MainViewModel, nav: Nav) {
    val hidden by vm.hiddenSongs.collectAsStateWithLifecycle()
    val all by vm.allSongs.collectAsStateWithLifecycle()
    val list = remember(hidden, all) { vm.hiddenSongList().sortedBy { it.title.lowercase() } }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Titres masqués", nav.back)
        if (list.isEmpty()) {
            EmptyState(Icons.Outlined.VisibilityOff, "Aucun titre masqué", "Pour cacher un titre sans supprimer le fichier : menu du titre > « Masquer ce titre ».")
            return@Column
        }
        Text(
            "Ces titres n'apparaissent plus dans la bibliothèque, mais les fichiers restent sur le téléphone.",
            style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { song ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.art(), size = 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                    }
                    TextButton(onClick = { vm.unhideSong(song.id) }) {
                        Icon(Icons.Outlined.Visibility, null, modifier = Modifier.size(18.dp), tint = TdColors.Accent)
                        Spacer(Modifier.width(6.dp))
                        Text("Réafficher", color = TdColors.Accent)
                    }
                }
            }
        }
    }
}

/** Fichiers présents plusieurs fois : même titre, même artiste, durée à 3 secondes près. */
@Composable
fun DuplicatesScreen(vm: MainViewModel, nav: Nav) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val groups = remember(songs) { vm.duplicateGroups() }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Titres en double", nav.back)
        if (groups.isEmpty()) {
            EmptyState(Icons.Outlined.ContentCopy, "Aucun doublon", "Chaque titre de la bibliothèque n'est présent qu'une fois.")
            return@Column
        }
        Text(
            "${groups.size} titre(s) en plusieurs exemplaires. Comparez l'emplacement et la taille, puis supprimez la copie en trop avec la corbeille (le fichier est vraiment effacé, après confirmation), ou masquez-la depuis son menu.",
            style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            groups.forEach { group ->
                item(key = "g" + group.first().id) {
                    Text(
                        "${group.first().title} · ${group.size} exemplaires",
                        style = MaterialTheme.typography.titleSmall, color = TdColors.Accent,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                items(group, key = { it.id }) { song ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.openMenu(song, null) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(song.art(), size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${song.folderPath.substringAfterLast('/')} · ${"%.1f".format(song.size / 1_048_576.0).replace('.', ',')} Mo · ${song.path.substringAfterLast('.', "").uppercase()} · ${com.thousandlabs.tonedial.data.formatDuration(song.duration)}",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                            )
                        }
                        IconButton(onClick = { nav.deleteSongs(listOf(song)) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Supprimer ce fichier", tint = TdColors.TextDim)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Pochettes et paroles : ce qui a été trouvé, et ce qui ne l'a pas été
// ------------------------------------------------------------------

private enum class ArtFilter(val label: String) { Found("Téléchargées"), Missing("Introuvables") }

private fun coverSourceLabel(src: String, viaAlbum: Boolean): String =
    when (src) {
        "online" -> "Trouvée en ligne"
        "gallery" -> "Choisie dans la galerie"
        "restored" -> "Restaurée d'une sauvegarde"
        else -> "Ajoutée"
    } + if (viaAlbum) " · pochette de l'album" else ""

private val syncedLine = Regex("""\[\d{1,2}:\d{2}""")

private fun lyricsSourceLabel(src: String?, text: String): String =
    when (src) {
        "lrclib" -> "Téléchargées (LRCLIB)"
        null -> "Ajoutées à la main"
        else -> "Téléchargées ($src)"
    } + if (syncedLine.containsMatchIn(text)) " · synchronisées" else " · texte simple"

/** Deux onglets (Pochettes, Paroles), chacun avec « Téléchargées » et « Introuvables ». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtStatusScreen(vm: MainViewModel, nav: Nav, startTab: Int) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val coverVersion by com.thousandlabs.tonedial.data.Covers.version.collectAsStateWithLifecycle()
    val coverProgress by vm.coverProgress.collectAsStateWithLifecycle()
    val lyricsProgress by vm.lyricsBatch.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(startTab.coerceIn(0, 1)) }
    var filter by remember { mutableStateOf(ArtFilter.Found) }

    val covers = remember(songs, coverVersion, coverProgress.missing) {
        val added = com.thousandlabs.tonedial.data.Covers.addedCovers(songs).sortedBy { it.song.title.lowercase() }
        val have = added.map { it.song.id }.toSet()
        val missing = coverProgress.missing.mapNotNull { id -> songs.firstOrNull { it.id == id } }
            .filter { it.id !in have }.sortedBy { it.title.lowercase() }
        added to missing
    }
    val lyrics = remember(songs, lyricsProgress.done, lyricsProgress.finished) {
        val all = com.thousandlabs.tonedial.data.UserStore.allLyrics()
        val byId = songs.associateBy { it.id }
        val found = all.mapNotNull { (id, v) -> byId[id]?.let { Triple(it, v.second, v.first) } }.sortedBy { it.first.title.lowercase() }
        val missing = com.thousandlabs.tonedial.data.LyricsBatch.missingIds().filter { it !in all.keys }.mapNotNull { byId[it] }
            .sortedBy { it.title.lowercase() }
        found to missing
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Pochettes et paroles", nav.back)
        TabRow(selectedTabIndex = tab, containerColor = TdColors.Background, contentColor = TdColors.Accent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pochettes") }, icon = { Icon(Icons.Outlined.Image, null) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paroles") }, icon = { Icon(Icons.Outlined.Subject, null) })
        }
        val nFound = if (tab == 0) covers.first.size else lyrics.first.size
        val nMissing = if (tab == 0) covers.second.size else lyrics.second.size
        val running = if (tab == 0) coverProgress.running else lyricsProgress.running
        Text(
            "$nFound téléchargée(s) · $nMissing introuvable(s)" + if (running) " · recherche en cours, la liste se met à jour" else "",
            style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArtFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} (${if (f == ArtFilter.Found) nFound else nMissing})", maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.Accent, selectedLabelColor = TdColors.Background),
                )
            }
        }
        val empty = if (filter == ArtFilter.Found) nFound == 0 else nMissing == 0
        if (empty) {
            EmptyState(
                if (tab == 0) Icons.Outlined.Image else Icons.Outlined.Subject,
                if (filter == ArtFilter.Found) "Rien pour l'instant" else "Aucun titre introuvable",
                when {
                    filter == ArtFilter.Found && tab == 0 -> "Lancez « Rechercher toutes les pochettes manquantes » dans les réglages."
                    filter == ArtFilter.Found -> "Lancez la recherche des paroles dans les réglages."
                    else -> "Tous les titres cherchés ont été trouvés."
                },
            )
            return@Column
        }
        if (filter == ArtFilter.Missing) {
            Text(
                if (tab == 0) "Introuvables dans les catalogues en ligne : relancez la recherche avec la loupe (après avoir corrigé le nom si besoin) ou choisissez une image dans la galerie."
                else "Introuvables sur LRCLIB : vérifiez le titre et l'artiste, ou ajoutez les paroles depuis l'onglet Paroles du lecteur.",
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            when {
                tab == 0 && filter == ArtFilter.Found -> items(covers.first, key = { it.song.id }) { a ->
                    ArtRow(a.song, coverSourceLabel(a.source, a.viaAlbum), true, onClick = { nav.openMenu(a.song, null) }) {}
                }
                tab == 0 -> items(covers.second, key = { it.id }) { song ->
                    ArtRow(song, song.artist, false, onClick = { nav.openMenu(song, null) }) {
                        IconButton(onClick = { vm.searchCover(song) }) { Icon(Icons.Outlined.ImageSearch, "Chercher en ligne", tint = TdColors.Text) }
                        IconButton(onClick = { nav.pickCover(song) }) { Icon(Icons.Outlined.AddPhotoAlternate, "Choisir dans la galerie", tint = TdColors.Accent) }
                    }
                }
                filter == ArtFilter.Found -> items(lyrics.first, key = { it.first.id }) { (song, src, text) ->
                    ArtRow(song, lyricsSourceLabel(src, text), true, onClick = { vm.play(listOf(song)); nav.openPlayer() }) {}
                }
                else -> items(lyrics.second, key = { it.id }) { song ->
                    ArtRow(song, song.artist, false, onClick = { vm.play(listOf(song)); nav.openPlayer() }) {}
                }
            }
        }
    }
}

@Composable
private fun ArtRow(song: Song, subtitle: String, found: Boolean, onClick: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.art(), size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (found) TdColors.Accent else TdColors.TextDim,
            )
        }
        actions()
        if (found) Icon(Icons.Outlined.CheckCircle, "Téléchargée", tint = TdColors.Accent, modifier = Modifier.size(20.dp))
    }
}
