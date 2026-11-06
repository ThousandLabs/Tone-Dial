@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.thousandlabs.tonedial.data.Playlist
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.SongArt
import com.thousandlabs.tonedial.data.art
import com.thousandlabs.tonedial.data.formatDuration

/** Pochette avec une icône de remplacement si le morceau n'a pas d'image. */
@Composable
fun Artwork(
    art: SongArt?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 8.dp,
    placeholder: ImageVector = Icons.Outlined.MusicNote,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(TdColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = TdColors.TextDim, modifier = Modifier.size(size * 0.45f))
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun ArtworkFill(art: SongArt?, modifier: Modifier = Modifier, corner: Dp = 12.dp, placeholder: ImageVector = Icons.Outlined.MusicNote) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(TdColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = TdColors.TextDim, modifier = Modifier.size(40.dp))
        if (art != null) {
            AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onLongClick: () -> Unit = onMore,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    trailingInfo: String? = null,
    leadingNumber: Int? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) TdColors.AccentDim else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (leadingNumber != null && !selected) {
                Text(
                    leadingNumber.toString(),
                    modifier = Modifier.width(28.dp),
                    color = if (isCurrent) TdColors.Accent else TdColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (leadingNumber == null) {
                Artwork(song.art(), size = 48.dp)
            }
            if (selected) {
                Box(
                    Modifier
                        .size(if (leadingNumber != null) 28.dp else 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TdColors.Accent.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Check, "Sélectionné", tint = TdColors.Background, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) TdColors.Accent else TdColors.Text,
            )
            Text(
                "${song.artist}  ·  ${trailingInfo ?: formatDuration(song.duration)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = TdColors.TextDim,
            )
        }
        if (selectionMode) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null,
                    tint = if (selected) TdColors.Accent else TdColors.TextDim,
                )
            }
        } else {
            IconButton(onClick = onMore) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Options", tint = TdColors.TextDim)
            }
        }
    }
}

/** Ligne de morceau reliée à la sélection multiple : appui long pour sélectionner. */
@Composable
fun SelectableSongRow(
    song: Song,
    index: Int,
    pool: List<Song>,
    nav: Nav,
    vm: MainViewModel,
    currentId: Long?,
    playlist: Playlist? = null,
    leadingNumber: Int? = null,
) {
    val sel = nav.selection
    SongRow(
        song = song,
        isCurrent = song.id == currentId,
        selected = sel.isSelected(song.id),
        selectionMode = sel.active,
        onClick = { if (sel.active) sel.toggle(song, pool, playlist) else vm.play(pool, index) },
        onLongClick = { sel.toggle(song, pool, playlist) },
        onMore = { nav.openMenu(song, playlist) },
        leadingNumber = leadingNumber,
    )
}

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Retour") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = TdColors.Background),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = TdColors.TextDim, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TdColors.TextDim)
    }
}

@Composable
fun ListItemRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = TdColors.Text,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "Valider",
    placeholder: String = "",
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                maxLines = if (singleLine) 1 else 12,
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        containerColor = TdColors.Surface,
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = "Nouvelle playlist",
            placeholder = "Nom de la playlist",
            confirmLabel = "Créer",
            onDismiss = { creating = false },
            onConfirm = { onCreate(it); creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à une playlist") },
        text = {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                item {
                    ListItemRow(Icons.Outlined.Add, "Nouvelle playlist", onClick = { creating = true }, tint = TdColors.Accent)
                    HorizontalDivider(color = TdColors.Divider)
                }
                items(playlists, key = { it.id }) { p ->
                    ListItemRow(Icons.Outlined.QueueMusic, p.name, subtitle = "${p.songIds.size} titres", onClick = { onPick(p) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        containerColor = TdColors.Surface,
    )
}

fun pluralTitles(n: Int) = if (n <= 1) "$n titre" else "$n titres"

fun totalDuration(songs: List<Song>) = formatDuration(songs.sumOf { it.duration })
