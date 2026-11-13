package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.data.AnimeGroups
import com.thousandlabs.tonedial.data.Covers
import com.thousandlabs.tonedial.data.Features
import com.thousandlabs.tonedial.data.ListenStats
import com.thousandlabs.tonedial.data.RecapImage
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art
import com.thousandlabs.tonedial.playback.PlaybackService

// ------------------------------------------------------------------
// Réglages des nouvelles fonctions
// ------------------------------------------------------------------

@Composable
private fun ChoiceChips(options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.padding(start = 60.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = selected == v,
                onClick = { onPick(v) },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.AccentDim, selectedLabelColor = TdColors.Accent),
            )
        }
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = TdColors.TextDim,
        modifier = Modifier.padding(start = 60.dp, end = 20.dp, top = 2.dp, bottom = 4.dp),
    )
}

/** Fondu enchaîné et secousse, dans la rubrique « Lecture ». */
@Composable
fun PlaybackExtrasSettings() {
    val crossfadeOn by Features.crossfade.state.collectAsStateWithLifecycle()
    SettingSwitch(
        Icons.Outlined.SwapHoriz, "Fondu enchaîné",
        "La fin d'un titre se mélange au début du suivant, comme à la radio. Les pistes qui se suivent dans un même album s'enchaînent normalement.",
        crossfadeOn,
    ) {
        Features.crossfade.set(it)
        PlaybackService.instance?.applyCrossfade()
    }
    if (crossfadeOn) {
        var sec by remember { mutableIntStateOf(Features.crossfadeSeconds()) }
        SubLabel("Durée du fondu")
        ChoiceChips(listOf(2, 4, 6, 8, 10).map { it to "$it s" }, sec) { sec = it; Features.setCrossfadeSeconds(it) }
    }

    val shakeOn by Features.shake.state.collectAsStateWithLifecycle()
    SettingSwitch(
        Icons.Outlined.Vibration, "Secouer pour passer",
        "Deux secousses franches et rapprochées pendant la lecture, même écran éteint. Aucune autorisation à donner. Le capteur s'arrête quand la musique est en pause.",
        shakeOn,
    ) {
        Features.shake.set(it)
        PlaybackService.instance?.applyShake()
    }
    if (shakeOn) {
        var sens by remember { mutableIntStateOf(Features.shakeSensitivity()) }
        SubLabel("Sensibilité")
        ChoiceChips(listOf(0 to "Faible", 1 to "Moyenne", 2 to "Forte"), sens) { sens = it; Features.setShakeSensitivity(it) }
        var action by remember { mutableIntStateOf(Features.shakeAction()) }
        SubLabel("Action")
        ChoiceChips(listOf(0 to "Titre suivant", 1 to "Lecture / pause"), action) { action = it; Features.setShakeAction(it) }
        if (action == 1) SubLabel("Après une pause par secousse, une nouvelle secousse relance la musique pendant 10 minutes.")
        val vib by Features.shakeVibrate.state.collectAsStateWithLifecycle()
        SettingSwitch(Icons.Outlined.Vibration, "Vibration de confirmation", "Une courte vibration quand la secousse est prise en compte.", vib) {
            Features.shakeVibrate.set(it)
        }
        SubLabel("Si la secousse ne marche plus écran éteint : Paramètres du téléphone > Applications > Tone Dial > Batterie, choisissez « Sans restriction ».")
    }
}

/** Interrupteurs des fonctions d'affichage : onglet Animes, mix, bilan, traduction. */
@Composable
fun FeatureSettings() {
    val anime by Features.animeTab.state.collectAsStateWithLifecycle()
    SettingSwitch(Icons.Outlined.Movie, "Onglet Animes", "Regroupe vos openings et endings par anime sur l'accueil, avec l'affiche de chaque anime.", anime) {
        Features.animeTab.set(it)
    }
    val mixes by Features.mixes.state.collectAsStateWithLifecycle()
    SettingSwitch(Icons.Outlined.AutoAwesome, "Mix automatiques", "« Vos titres du moment », « Oubliés depuis longtemps » et « À découvrir », dans l'onglet Playlists.", mixes) {
        Features.mixes.set(it)
    }
    val recap by Features.recap.state.collectAsStateWithLifecycle()
    SettingSwitch(Icons.Outlined.Insights, "Bilan du mois", "Vos titres, artistes et openings les plus écoutés du mois, à partager en image.", recap) {
        Features.recap.set(it)
    }
    val tr by Features.translate.state.collectAsStateWithLifecycle()
    SettingSwitch(Icons.Outlined.Translate, "Traduction des paroles", "Bouton « Traduire en français » sous les paroles. La traduction est gardée sur le téléphone.", tr) {
        Features.translate.set(it)
    }
}

// ------------------------------------------------------------------
// Onglet Animes
// ------------------------------------------------------------------

@Composable
fun AnimesTab(vm: MainViewModel, nav: Nav) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val groups = remember(songs) { AnimeGroups.groups(songs) }
    if (groups.isEmpty()) {
        EmptyState(
            Icons.Outlined.Movie, "Aucun anime",
            "Les titres nommés comme « One Piece Opening 25 » ou « Black Clover Ending 3 » sont regroupés ici par anime.",
        )
        return
    }
    // affiches des animes, comme les photos d'artistes
    LaunchedEffect(groups.size) { Covers.fetchArtistImages(groups.map { it.name to true }) }
    val coverVersion by Covers.version.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(groups, key = { it.name }) { g ->
            CollectionRow(
                art = remember(g.name, coverVersion) { Covers.artistFile(g.name) } ?: g.songs.firstOrNull()?.art(),
                placeholder = Icons.Outlined.Movie,
                title = g.name,
                subtitle = AnimeGroups.describe(g),
                onClick = { nav.go(Screen.AnimeDetail(g.name)) },
            )
        }
    }
}

@Composable
fun AnimeDetailScreen(vm: MainViewModel, nav: Nav, name: String) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val coverVersion by Covers.version.collectAsStateWithLifecycle()
    val group = remember(songs, name) { AnimeGroups.group(songs, name) }
    if (group == null) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(name, nav.back)
            EmptyState(Icons.Outlined.Movie, "Aucun titre", "Les titres de cet anime ne sont plus dans la bibliothèque.")
        }
        return
    }
    SongCollectionScreen(
        vm, nav, group.name,
        "${AnimeGroups.describe(group)} · ${totalDuration(group.songs)}",
        remember(group.name, coverVersion) { Covers.artistFile(group.name) } ?: group.songs.firstOrNull()?.art(),
        Icons.Outlined.Movie,
        group.songs,
    )
}

// ------------------------------------------------------------------
// Bilan du mois
// ------------------------------------------------------------------

@Composable
private fun HighlightRow(label: String, art: Any?, placeholder: ImageVector, title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TdColors.TextDim)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(TdColors.Surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(art, size = 56.dp, placeholder = placeholder)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TdColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val dayNames = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

private fun dayLabel(key: String): String {
    // « 2026-11-12 » → « 12 novembre »
    val parts = key.split('-')
    val month = parts.getOrNull(1)?.toIntOrNull() ?: return key
    val day = parts.getOrNull(2)?.toIntOrNull() ?: return key
    return "$day ${dayNames.getOrElse(month - 1) { "" }}".trim()
}

@Composable
fun RecapScreen(vm: MainViewModel, nav: Nav) {
    var monthsAgo by remember { mutableIntStateOf(0) }
    val version by vm.statsVersion.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    val m = remember(monthsAgo, version) { ListenStats.month(monthsAgo) }

    val ranked: List<Pair<Song, Long>> = remember(m, songMap) {
        m.timeBySong.entries.sortedByDescending { it.value }.mapNotNull { e -> songMap[e.key]?.let { it to e.value } }
    }
    val topArtist: Pair<String, Long>? = remember(ranked) {
        val t = HashMap<String, Long>()
        ranked.forEach { (s, ms) -> s.artists.firstOrNull()?.takeIf { it != "Artiste inconnu" }?.let { a -> t[a] = (t[a] ?: 0L) + ms } }
        t.entries.maxByOrNull { it.value }?.let { it.key to it.value }
    }
    val topOpening: Pair<Song, Long>? = remember(ranked) { ranked.firstOrNull { Covers.looksAnime(it.first) } }
    val distinct = ranked.count { it.second >= 30_000 }
    val plays = m.plays.values.sum()
    val title = m.label.replaceFirstChar { it.uppercase() }

    val share = {
        vm.shareRecap(
            RecapImage.Card(
                month = title,
                listening = formatListening(m.totalMs),
                activeDays = m.activeDays,
                streak = m.bestStreak,
                songs = distinct,
                topSong = ranked.firstOrNull()?.let { "${it.first.title} · ${it.first.artist}" },
                topArtist = topArtist?.first,
                topOpening = topOpening?.first?.title,
                top5 = ranked.take(5).map { "${it.first.title} · ${formatListening(it.second)}" },
            )
        )
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Bilan du mois", nav.back, actions = {
            if (m.totalMs > 0) IconButton(onClick = share) { Icon(Icons.Outlined.Share, "Partager en image") }
        })
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Ce mois-ci", 1 to "Mois dernier", 2 to "Il y a 2 mois").forEach { (v, label) ->
                        FilterChip(
                            selected = monthsAgo == v,
                            onClick = { monthsAgo = v },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.AccentDim, selectedLabelColor = TdColors.Accent),
                        )
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            if (m.totalMs <= 0) {
                item {
                    EmptyState(Icons.Outlined.Insights, "Pas encore d'écoute ce mois-là", "Écoutez quelques titres, votre bilan se remplira tout seul.")
                }
                return@LazyColumn
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Temps d'écoute", formatListening(m.totalMs), Modifier.weight(1f))
                    StatTile("Jours d'écoute", m.activeDays.toString(), Modifier.weight(1f))
                    StatTile("Meilleure série", if (m.bestStreak <= 1) "${m.bestStreak} jour" else "${m.bestStreak} jours", Modifier.weight(1f))
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Écoutes", plays.toString(), Modifier.weight(1f))
                    StatTile("Titres différents", distinct.toString(), Modifier.weight(1f))
                }
            }
            ranked.firstOrNull()?.let { (s, ms) ->
                item { HighlightRow("Titre du mois", s.art(), Icons.Outlined.MusicNote, s.title, "${s.artist} · ${formatListening(ms)}") }
            }
            topArtist?.let { (a, ms) ->
                item { HighlightRow("Artiste du mois", Covers.artistFile(a), Icons.Outlined.Person, a, formatListening(ms)) }
            }
            topOpening?.let { (s, ms) ->
                item { HighlightRow("Opening du mois", s.art(), Icons.Outlined.Movie, s.title, "${s.artist} · ${formatListening(ms)}") }
            }
            m.bestDay?.let { (day, ms) ->
                item {
                    Text(
                        "Jour le plus musical : ${dayLabel(day)} (${formatListening(ms)})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            item { SectionLabel("Top 5 du mois") }
            items(ranked.take(5).withIndex().toList(), key = { it.value.first.id }) { (i, pair) ->
                val (s, ms) = pair
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = TdColors.Accent, modifier = Modifier.width(28.dp))
                    Artwork(s.art(), size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("${s.artist} · ${formatListening(ms)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = share,
                    colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Partager en image")
                }
                Text(
                    "L'image est aussi enregistrée dans la galerie (dossier Tone Dial).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}
