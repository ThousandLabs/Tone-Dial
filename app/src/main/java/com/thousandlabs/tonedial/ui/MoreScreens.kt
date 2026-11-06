@file:OptIn(ExperimentalMaterial3Api::class)

package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
