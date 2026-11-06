@file:OptIn(ExperimentalMaterial3Api::class)

package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art
import com.thousandlabs.tonedial.data.formatDuration
import com.thousandlabs.tonedial.playback.SleepTimer
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(song: Song, ui: PlayerUi, vm: MainViewModel, onOpen: () -> Unit) {
    val progress = if (ui.durationMs > 0) (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .background(TdColors.Surface)
            .clickable(onClick = onOpen)
            .swipeTracks(onNext = { vm.next() }, onPrevious = { vm.previous() })
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = TdColors.Accent,
            trackColor = TdColors.Divider,
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.art(), size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
            }
            IconButton(onClick = vm::togglePlay) {
                Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Lecture", modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { vm.next() }) {
                Icon(Icons.Rounded.SkipNext, "Suivant", modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun PlayerScreen(vm: MainViewModel, nav: Nav, song: Song, ui: PlayerUi, onClose: () -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val timer by SleepTimer.state.collectAsStateWithLifecycle()
    var showLyrics by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    val speed by vm.speed.collectAsStateWithLifecycle()
    val artColor = rememberArtColor(song)
    // glisser vers le bas pour fermer le lecteur
    var dragDown by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = dragDown }
            .background(Brush.verticalGradient(listOf(artColor, TdColors.Background, TdColors.Background)))
            .clickable(enabled = false) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragDown > 220f) onClose()
                        dragDown = 0f
                    },
                    onDragCancel = { dragDown = 0f },
                ) { change, amount ->
                    if (amount > 0 || dragDown > 0) {
                        dragDown = (dragDown + amount).coerceAtLeast(0f)
                        change.consume()
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            // Barre du haut
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.KeyboardArrowDown, "Réduire", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LECTURE EN COURS", style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, letterSpacing = 1.5.sp), color = TdColors.TextDim)
                    Text(song.album, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { nav.openMenu(song, null) }) { Icon(Icons.Outlined.MoreVert, "Options") }
            }

            // Pochette ou paroles
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showLyrics) {
                    LyricsPanel(vm, song, ui.positionMs)
                } else {
                    ArtworkFill(
                        song.art(),
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .swipeTracks(onNext = { vm.next() }, onPrevious = { vm.previous() }),
                        corner = 20.dp,
                    )
                }
            }

            // Titre + favori
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = TdColors.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fav = song.id in favorites
                IconButton(onClick = { vm.toggleFavorite(song.id) }) {
                    Icon(
                        if (fav) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        if (fav) "Retirer des favoris" else "Ajouter aux favoris",
                        tint = if (fav) TdColors.Accent else TdColors.Text,
                    )
                }
            }

            // Barre de progression
            SeekBar(ui, vm)

            // Commandes
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Outlined.Shuffle, "Aléatoire", tint = if (ui.shuffle) TdColors.Accent else TdColors.TextDim)
                }
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Précédent", modifier = Modifier.size(38.dp))
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(TdColors.Accent)
                        .clickable(onClick = vm::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (ui.isPlaying) "Pause" else "Lecture",
                        tint = TdColors.Background,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { vm.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Suivant", modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = vm::cycleRepeat) {
                    Icon(
                        if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                        "Répétition",
                        tint = if (ui.repeatMode == Player.REPEAT_MODE_OFF) TdColors.TextDim else TdColors.Accent,
                    )
                }
            }

            // Outils
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Icons.Outlined.Subject, "Paroles", active = showLyrics) { showLyrics = !showLyrics }
                ToolButton(Icons.Outlined.QueueMusic, "File") { nav.go(Screen.Queue(fromPlayer = true)) }
                ToolButton(Icons.Outlined.Timer, if (timer.active) timerLabel(timer) else "Minuterie", active = timer.active) { showTimer = true }
                ToolButton(Icons.Outlined.Speed, if (speed == 1f) "Vitesse" else formatSpeed(speed), active = speed != 1f) { showSpeed = true }
                ToolButton(Icons.Outlined.Equalizer, "Égaliseur") { onClose(); nav.go(Screen.Equalizer) }
            }
        }
    }

    if (showTimer) SleepTimerDialog(onDismiss = { showTimer = false })
    if (showSpeed) SpeedDialog(current = speed, onPick = { vm.setSpeed(it) }, onDismiss = { showSpeed = false })
}

@Composable
private fun timerLabel(t: SleepTimer.State): String {
    if (t.endOfTrack) return "Fin du titre"
    val endAt = t.endAt ?: return "Minuterie"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return formatDuration((endAt - now).coerceAtLeast(0))
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (active) TdColors.Accent else TdColors.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (active) TdColors.Accent else TdColors.TextDim)
    }
}

@Composable
private fun SeekBar(ui: PlayerUi, vm: MainViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = ui.durationMs.coerceAtLeast(1L)
    val value = if (dragging) dragValue else (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column(Modifier.padding(top = 8.dp)) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                vm.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = TdColors.Accent,
                activeTrackColor = TdColors.Accent,
                inactiveTrackColor = TdColors.Divider,
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(if (dragging) (dragValue * duration).toLong() else ui.positionMs),
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            )
            Spacer(Modifier.weight(1f))
            Text(formatDuration(ui.durationMs), style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
        }
    }
}

@Composable
private fun LyricsPanel(vm: MainViewModel, song: Song, positionMs: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val loading by vm.lyricsLoading.collectAsStateWithLifecycle()
    val searching by vm.lyricsSearching.collectAsStateWithLifecycle()
    val notFound by vm.lyricsNotFoundOnline.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }

    if (editing) {
        TextInputDialog(
            title = "Paroles de « ${song.title} »",
            initial = com.thousandlabs.tonedial.data.UserStore.customLyrics(song.id) ?: lyrics?.plainText.orEmpty(),
            placeholder = "Collez les paroles ici (le format .lrc synchronisé est accepté)",
            confirmLabel = "Enregistrer",
            singleLine = false,
            onDismiss = { editing = false },
            onConfirm = { vm.saveLyrics(song, it); editing = false },
        )
    }

    val l = lyrics
    // Recherche automatique en ligne, une seule fois par titre, quand rien n'est trouvé sur le téléphone
    LaunchedEffect(song.id, loading, l == null) {
        if (!loading && l == null) vm.searchLyricsOnline(song, auto = true)
    }

    val openGoogle = { runCatching { context.startActivity(vm.googleLyricsIntent(song)) }; Unit }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(TdColors.Surface)
    ) {
        when {
            (loading || searching) && (l == null || l.lines.isEmpty()) -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = TdColors.Accent)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (searching) "Recherche des paroles en ligne…" else "Chargement…",
                    style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                )
            }
            l == null || l.lines.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Subject, null, tint = TdColors.TextDim, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("Pas de paroles pour ce titre", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (song.id in notFound) "Rien trouvé automatiquement. Cherchez sur Google, copiez les paroles, puis revenez les coller ici."
                    else "Recherchez-les en ligne, ou ajoutez-les vous-même.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.searchLyricsOnline(song) },
                    colors = ButtonDefaults.buttonColors(containerColor = TdColors.Accent),
                ) {
                    Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (song.id in notFound) "Réessayer la recherche" else "Rechercher automatiquement")
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = openGoogle) {
                        Icon(Icons.Outlined.Language, null, modifier = Modifier.size(16.dp), tint = TdColors.Text)
                        Spacer(Modifier.width(6.dp))
                        Text("Google", color = TdColors.Text)
                    }
                    OutlinedButton(onClick = { vm.pasteLyrics(song) }) {
                        Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(16.dp), tint = TdColors.Text)
                        Spacer(Modifier.width(6.dp))
                        Text("Coller", color = TdColors.Text)
                    }
                    OutlinedButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp), tint = TdColors.Text)
                        Spacer(Modifier.width(6.dp))
                        Text("Saisir", color = TdColors.Text)
                    }
                }
            }
            else -> {
                val state = rememberLazyListState()
                val activeIndex = if (l.synced) {
                    l.lines.indexOfLast { (it.timeMs ?: 0L) <= positionMs + 300 }.coerceAtLeast(0)
                } else -1
                LaunchedEffect(activeIndex) {
                    if (activeIndex >= 0) runCatching { state.animateScrollToItem((activeIndex - 3).coerceAtLeast(0)) }
                }
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(vertical = 24.dp, horizontal = 20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(l.lines) { i, line ->
                        val active = i == activeIndex
                        Text(
                            line.text.ifBlank { "·" },
                            style = if (l.synced) MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 28.sp)
                            else MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                            color = when {
                                !l.synced -> TdColors.Text
                                active -> TdColors.Accent
                                else -> TdColors.TextDim
                            },
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (l.synced) 6.dp else 1.dp)
                                .clickable(enabled = l.synced && line.timeMs != null) {
                                    line.timeMs?.let { vm.seekTo(it) }
                                },
                        )
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Box {
                            Text(
                                "Source : ${l.source}  ·  Options",
                                style = MaterialTheme.typography.bodySmall,
                                color = TdColors.TextDim,
                                modifier = Modifier.clickable { options = true }.padding(vertical = 8.dp),
                            )
                            DropdownMenu(expanded = options, onDismissRequest = { options = false }, containerColor = TdColors.SurfaceHigh) {
                                DropdownMenuItem(
                                    text = { Text("Rechercher d'autres paroles en ligne") },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    onClick = { options = false; vm.searchLyricsOnline(song) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Chercher sur Google") },
                                    leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                    onClick = { options = false; openGoogle() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Coller depuis le presse-papiers") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                                    onClick = { options = false; vm.pasteLyrics(song) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Modifier") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    onClick = { options = false; editing = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Supprimer ces paroles") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { options = false; vm.saveLyrics(song, "") },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit) {
    val timer by SleepTimer.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minuterie de sommeil") },
        text = {
            Column {
                Text("La musique se mettra en pause automatiquement.", color = TdColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                    ListItemRow(Icons.Outlined.Timer, if (m < 60) "$m minutes" else if (m == 60) "1 heure" else "1 h 30", onClick = {
                        SleepTimer.start(m); onDismiss()
                    })
                }
                ListItemRow(Icons.Outlined.MusicNote, "À la fin du titre en cours", onClick = { SleepTimer.endOfTrack(); onDismiss() })
                if (timer.active) {
                    ListItemRow(Icons.Outlined.TimerOff, "Arrêter la minuterie", tint = TdColors.Accent, onClick = { SleepTimer.cancel(); onDismiss() })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        containerColor = TdColors.Surface,
    )
}


private fun formatSpeed(v: Float): String =
    (if (v % 1f == 0f) "%.0f" else if ((v * 10) % 1f == 0f) "%.1f" else "%.2f").format(v).replace('.', ',') + "×"

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vitesse de lecture") },
        text = {
            Column {
                Text(
                    "La tonalité est conservée : les voix ne deviennent ni aiguës ni graves. Pratique pour les podcasts et les cours audio.",
                    style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f).forEach { v ->
                        FilterChip(
                            selected = current == v,
                            onClick = { onPick(v) },
                            label = { Text(if (v == 1f) "Normale" else formatSpeed(v)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TdColors.AccentDim, selectedLabelColor = TdColors.Accent),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        containerColor = TdColors.Surface,
    )
}

/** Couleur dominante (sombre) de la pochette, pour le fond du lecteur. */
@Composable
private fun rememberArtColor(song: Song): androidx.compose.ui.graphics.Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coverVersion by com.thousandlabs.tonedial.data.Covers.version.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf(TdColors.SurfaceHigh) }
    LaunchedEffect(song.id, coverVersion) {
        val request = coil.request.ImageRequest.Builder(context)
            .data(song.art())
            .allowHardware(false)
            .size(96)
            .build()
        val result = coil.Coil.imageLoader(context).execute(request)
        val bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bmp == null) {
            target = TdColors.SurfaceHigh
            return@LaunchedEffect
        }
        val rgb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val p = androidx.palette.graphics.Palette.from(bmp).generate()
            p.getDarkVibrantColor(p.getMutedColor(p.getDominantColor(0)))
        }
        target = if (rgb == 0) TdColors.SurfaceHigh
        else androidx.compose.ui.graphics.Color(androidx.core.graphics.ColorUtils.blendARGB(rgb, 0xFF0D1017.toInt(), 0.35f))
    }
    val animated by androidx.compose.animation.animateColorAsState(target, label = "artColor")
    return animated
}
