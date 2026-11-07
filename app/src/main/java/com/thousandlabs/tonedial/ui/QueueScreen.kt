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
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art
import com.thousandlabs.tonedial.data.formatDuration

/**
 * File d'attente en écran complet, présentée comme une playlist :
 * tous les titres dans l'ordre de lecture, le morceau en cours surligné, tout est déplaçable.
 */
@Composable
fun QueueScreen(vm: MainViewModel, nav: Nav) {
    val ui by vm.player.collectAsStateWithLifecycle()
    val songMap by vm.songMap.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var moveFor by remember { mutableStateOf<Int?>(null) }

    val curPos = ui.currentPos
    val currentEntry = ui.queue.getOrNull(curPos)
    val current: Song? = currentEntry?.let { songMap[it.songId] }
    val upcomingCount = if (curPos >= 0) ui.queue.size - curPos - 1 else ui.queue.size

    // liste affichée (tous les titres dans l'ordre de lecture), resynchronisée avec le lecteur
    val entries = remember { mutableStateListOf<QueueEntry>() }
    LaunchedEffect(ui.queue) {
        entries.clear()
        entries.addAll(ui.queue)
    }

    val remainingMs = (if (curPos >= 0) ui.queue.drop(curPos + 1) else ui.queue).sumOf { songMap[it.songId]?.duration ?: 0L } +
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

        val listState = rememberLazyListState(initialFirstVisibleItemIndex = curPos.coerceAtLeast(0))
        val scope = rememberCoroutineScope()
        moveFor?.let { from ->
            val title = entries.getOrNull(from)?.let { songMap[it.songId]?.title }.orEmpty()
            PositionDialog(title, from, entries.size, onDismiss = { moveFor = null }) { to ->
                moveFor = null
                if (to != from) {
                    vm.moveInQueue(from, to)
                    // la liste se place sur la nouvelle position (+1 : l'en-tête)
                    scope.launch { listState.animateScrollToItem((to + 1 - 2).coerceAtLeast(0)) }
                }
            }
        }
        // le morceau en cours est-il visible à l'écran ?
        val currentVisible by remember {
            derivedStateOf {
                val idx = ui.currentPos + 1
                listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            }
        }

        Box(Modifier.fillMaxSize()) {
            ReorderableList(
                items = entries,
                key = { it.windowIndex },
                listState = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
                headerCount = 1,
                onMoved = { from, to -> vm.moveInQueue(from, to) },
                header = {
                    item(key = "header") {
                        Column {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(current?.art(), size = 112.dp, corner = 14.dp, placeholder = Icons.Outlined.QueueMusic)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("File d'attente", style = MaterialTheme.typography.titleLarge)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${pluralTitles(ui.queue.size)} · ${upcomingCount} à suivre\n${formatDuration(remainingMs)} restantes" +
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
                            Text(
                                "Glissez la poignée pour déplacer un titre (tenez-le près du bas ou du haut pour faire défiler), ou appui long > « Déplacer à la position… ». Les titres placés au-dessus du morceau en cours ne seront pas joués ensuite.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TdColors.TextDim,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                    }
                },
            ) { entry, index, dragging, handle ->
                val isCurrent = entry.windowIndex == ui.currentWindow
                QueueRow(
                    songMap[entry.songId],
                    number = index + 1,
                    dim = curPos >= 0 && index < curPos && !dragging,
                    highlighted = isCurrent || dragging,
                    onClick = { if (isCurrent) nav.openPlayer() else vm.jumpTo(entry.windowIndex) },
                    onLongClick = { menuFor = index },
                    trailing = {
                        if (isCurrent) {
                            Icon(Icons.Outlined.GraphicEq, "En cours", tint = TdColors.Accent, modifier = Modifier.padding(horizontal = 14.dp))
                        } else {
                            IconButton(onClick = { vm.removeFromQueue(entry.windowIndex) }) {
                                Icon(Icons.Outlined.Close, "Retirer", tint = TdColors.TextDim, modifier = Modifier.size(20.dp))
                            }
                        }
                        DragHandleBox(handle, dragging)
                        DropdownMenu(expanded = menuFor == index, onDismissRequest = { menuFor = null }, containerColor = TdColors.SurfaceHigh) {
                            if (!isCurrent) {
                                DropdownMenuItem(
                                    text = { Text("Lire ensuite") },
                                    leadingIcon = { Icon(Icons.Outlined.PlaylistPlay, null) },
                                    enabled = index != curPos + 1,
                                    onClick = { menuFor = null; vm.queuePlayNext(index) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Lire maintenant") },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                    onClick = { menuFor = null; vm.jumpTo(entry.windowIndex) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Déplacer à la position…") },
                                    leadingIcon = { Icon(Icons.Outlined.SwapVert, null) },
                                    onClick = { menuFor = null; moveFor = index },
                                )
                                DropdownMenuItem(
                                    text = { Text("Retirer de la file") },
                                    leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) },
                                    onClick = { menuFor = null; vm.removeFromQueue(entry.windowIndex) },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Ouvrir le lecteur") },
                                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                    onClick = { menuFor = null; nav.openPlayer() },
                                )
                            }
                        }
                    },
                )
            }

            // bouton pour revenir au morceau en cours
            if (!currentVisible && curPos >= 0) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem((curPos).coerceAtLeast(0)) } },
                    containerColor = TdColors.Accent,
                    contentColor = TdColors.Background,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    icon = { Icon(Icons.Outlined.GraphicEq, null) },
                    text = { Text("En cours") },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song?,
    number: Int,
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
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$number",
            modifier = Modifier.width(32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlighted) TdColors.Accent else TdColors.TextDim,
        )
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
