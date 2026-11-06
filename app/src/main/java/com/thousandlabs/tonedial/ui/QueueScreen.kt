@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art
import com.thousandlabs.tonedial.data.formatDuration

/**
 * File d'attente en écran complet, présentée comme une playlist :
 * morceau en cours, puis « À suivre » réorganisable. Les titres précédents sont repliés en haut.
 */
@Composable
fun QueueScreen(vm: MainViewModel, nav: Nav) {
    val ui by vm.player.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }

    val curPos = ui.currentPos
    val history = if (curPos > 0) ui.queue.subList(0, curPos) else emptyList()
    val currentEntry = ui.queue.getOrNull(curPos)
    val current: Song? = currentEntry?.let { songMap[it.songId] }
    val upcomingEntries = if (curPos >= 0) ui.queue.drop(curPos + 1) else ui.queue

    // liste affichée, resynchronisée avec la vraie file du lecteur
    val upcoming = remember { mutableStateListOf<QueueEntry>() }
    LaunchedEffect(upcomingEntries) {
        upcoming.clear()
        upcoming.addAll(upcomingEntries)
    }

    val remainingMs = upcomingEntries.sumOf { songMap[it.songId]?.duration ?: 0L } +
        ((current?.duration ?: 0L) - ui.positionMs).coerceAtLeast(0L)

    if (saving) {
        TextInputDialog(
            title = "Enregistrer la file comme playlist",
            placeholder = "Nom de la playlist",
            confirmLabel = "Enregistrer",
            onDismiss = { saving = false },
            onConfirm = { vm.saveQueueAsPlaylist(it); saving = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Vider la file d'attente ?") },
            text = { Text("La lecture s'arrête et la file est vidée. Vos playlists ne sont pas touchées.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearQueue(); nav.back() }) { Text("Vider") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
            containerColor = TdColors.Surface,
        )
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("File d'attente", nav.back, actions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = TdColors.SurfaceHigh) {
                    DropdownMenuItem(
                        text = { Text("Enregistrer comme playlist") },
                        leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, null) },
                        enabled = ui.queue.isNotEmpty(),
                        onClick = { menu = false; saving = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Vider la file") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        enabled = ui.queue.isNotEmpty(),
                        onClick = { menu = false; confirmClear = true },
                    )
                }
            }
        })

        if (ui.queue.isEmpty()) {
            EmptyState(Icons.Outlined.QueueMusic, "File d'attente vide", "Lancez un titre, un album ou une playlist pour remplir la file.")
            return@Column
        }

        val listState = rememberLazyListState()
        // nombre d'éléments affichés avant la liste réorganisable
        val headerCount = 1 + (if (history.isNotEmpty()) 1 + (if (showHistory) history.size else 0) else 0) + 1

        ReorderableList(
            items = upcoming,
            key = { it.windowIndex },
            listState = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            headerCount = headerCount,
            onMoved = { from, to -> vm.moveInQueue(from + curPos + 1, to + curPos + 1) },
            header = {
                // En-tête façon playlist
                item(key = "header") {
                    Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(current?.art(), size = 112.dp, corner = 14.dp, placeholder = Icons.Outlined.QueueMusic)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("File d'attente", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${pluralTitles(upcomingEntries.size)} à suivre\n${formatDuration(remainingMs)} restantes" +
                                        if (ui.shuffle) " · aléatoire" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TdColors.TextDim,
                                )
                            }
                        }
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { vm.toggleShuffle() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(18.dp),
                                    tint = if (ui.shuffle) TdColors.Accent else TdColors.Text)
                                Spacer(Modifier.width(6.dp))
                                Text(if (ui.shuffle) "Aléatoire activé" else "Aléatoire", color = if (ui.shuffle) TdColors.Accent else TdColors.Text)
                            }
                            OutlinedButton(onClick = { saving = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.PlaylistAdd, null, modifier = Modifier.size(18.dp), tint = TdColors.Text)
                                Spacer(Modifier.width(6.dp))
                                Text("En playlist", color = TdColors.Text)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // Titres précédents (repliés)
                if (history.isNotEmpty()) {
                    item(key = "history_toggle") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showHistory = !showHistory }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.History, null, tint = TdColors.TextDim, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Titres précédents (${history.size})", style = MaterialTheme.typography.bodyMedium, color = TdColors.TextDim, modifier = Modifier.weight(1f))
                            Icon(
                                if (showHistory) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                if (showHistory) "Replier" else "Déplier",
                                tint = TdColors.TextDim,
                            )
                        }
                    }
                    if (showHistory) {
                        history.forEach { e ->
                            item(key = "h_${e.windowIndex}") {
                                QueueRow(songMap[e.songId], dim = true, highlighted = false, onClick = { vm.jumpTo(e.windowIndex) })
                            }
                        }
                    }
                }

                // En cours + titre de la section À suivre
                item(key = "current") {
                    Column {
                        SectionLabel("En cours")
                        if (currentEntry != null) {
                            QueueRow(current, dim = false, highlighted = true, onClick = { nav.openPlayer() }, trailing = {
                                Icon(Icons.Outlined.GraphicEq, "En cours", tint = TdColors.Accent, modifier = Modifier.padding(horizontal = 14.dp))
                            })
                        }
                        SectionLabel(if (upcomingEntries.isEmpty()) "À suivre : rien" else "À suivre")
                    }
                }
            },
        ) { entry, index, dragging, handle ->
            val pos = curPos + 1 + index
            QueueRow(
                songMap[entry.songId],
                dim = false,
                highlighted = dragging,
                onClick = { vm.jumpTo(entry.windowIndex) },
                onLongClick = { menuFor = pos },
                trailing = {
                    IconButton(onClick = { vm.removeFromQueue(entry.windowIndex) }) {
                        Icon(Icons.Outlined.Close, "Retirer", tint = TdColors.TextDim, modifier = Modifier.size(20.dp))
                    }
                    DragHandleBox(handle, dragging)
                    DropdownMenu(expanded = menuFor == pos, onDismissRequest = { menuFor = null }, containerColor = TdColors.SurfaceHigh) {
                        DropdownMenuItem(
                            text = { Text("Lire ensuite") },
                            leadingIcon = { Icon(Icons.Outlined.PlaylistPlay, null) },
                            enabled = index > 0,
                            onClick = { menuFor = null; vm.queuePlayNext(pos) },
                        )
                        DropdownMenuItem(
                            text = { Text("Lire maintenant") },
                            leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                            onClick = { menuFor = null; vm.jumpTo(entry.windowIndex) },
                        )
                        DropdownMenuItem(
                            text = { Text("Retirer de la file") },
                            leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) },
                            onClick = { menuFor = null; vm.removeFromQueue(entry.windowIndex) },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun QueueRow(
    song: Song?,
    dim: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) TdColors.AccentDim else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song?.art(), size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song?.title ?: "Titre introuvable",
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = when {
                    highlighted -> TdColors.Accent
                    dim -> TdColors.TextDim
                    else -> TdColors.Text
                },
            )
            Text(
                "${song?.artist ?: ""}  ·  ${formatDuration(song?.duration ?: 0L)}",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim,
            )
        }
        trailing()
    }
}
