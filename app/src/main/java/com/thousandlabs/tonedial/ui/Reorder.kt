package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.thousandlabs.tonedial.data.Song
import com.thousandlabs.tonedial.data.art
import kotlinx.coroutines.launch

/**
 * Liste réorganisable : on attrape la poignée à droite d'un titre et on le fait glisser.
 * `items` est modifiée directement ; l'appelant enregistre l'ordre à la fin.
 */
@Composable
fun ReorderList(items: MutableList<Song>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }

    fun onDrag(delta: Float) {
        val id = draggingId ?: return
        offset += delta
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.index == index } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = visible.firstOrNull {
            it.index != index && center > it.offset && center < it.offset + it.size
        }
        if (target != null) {
            val moved = items.removeAt(index)
            items.add(target.index, moved)
            offset += (current.offset - target.offset).toFloat()
        }
        // défilement automatique près des bords
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val top = current.offset + offset
        val bottom = top + current.size
        if (bottom > viewportEnd - 60) scope.launch { listState.scrollBy(24f) }
        else if (top < 60) scope.launch { listState.scrollBy(-24f) }
    }

    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(items, key = { _, s -> s.id }) { i, song ->
            val dragging = song.id == draggingId
            Row(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f }
                    .fillMaxWidth()
                    .background(if (dragging) TdColors.SurfaceHigh else Color.Transparent)
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    modifier = Modifier.width(30.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TdColors.TextDim,
                )
                Artwork(song.art(), size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                        color = if (dragging) TdColors.Accent else TdColors.Text)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                }
                Box(
                    Modifier
                        .size(56.dp)
                        .pointerInput(song.id) {
                            detectDragGestures(
                                onDragStart = { draggingId = song.id; offset = 0f },
                                onDragEnd = { draggingId = null; offset = 0f },
                                onDragCancel = { draggingId = null; offset = 0f },
                            ) { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.DragHandle, "Déplacer", tint = if (dragging) TdColors.Accent else TdColors.TextDim)
                }
            }
        }
    }
}
