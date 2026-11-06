package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
 * Liste réorganisable générique : on attrape la poignée d'un élément et on le fait glisser.
 * `items` est modifiée pendant le glissement ; `onMoved(de, vers)` est appelé quand on lâche.
 */
@Composable
fun <T> ReorderableList(
    items: MutableList<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onMoved: (from: Int, to: Int) -> Unit = { _, _ -> },
    headerCount: Int = 0,
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    row: @Composable (item: T, index: Int, dragging: Boolean, handle: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var startIndex by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }

    fun indexOfDragged(): Int = draggingKey?.let { k -> items.indexOfFirst { key(it) == k } } ?: -1

    fun onDrag(delta: Float) {
        offset += delta
        val index = indexOfDragged()
        if (index < 0) return
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.index == index + headerCount } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = visible.firstOrNull {
            val li = it.index - headerCount
            li != index && li in items.indices && center > it.offset && center < it.offset + it.size
        }
        if (target != null) {
            val moved = items.removeAt(index)
            items.add(target.index - headerCount, moved)
            offset += (current.offset - target.offset).toFloat()
        }
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val top = current.offset + offset
        val bottom = top + current.size
        if (bottom > viewportEnd - 60) scope.launch { listState.scrollBy(24f) }
        else if (top < 60) scope.launch { listState.scrollBy(-24f) }
    }

    fun finish() {
        val end = indexOfDragged()
        val start = startIndex
        draggingKey = null
        offset = 0f
        startIndex = -1
        if (start >= 0 && end >= 0 && start != end) onMoved(start, end)
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = contentPadding) {
        header?.invoke(this)
        itemsIndexed(items, key = { _, item -> key(item) }) { i, item ->
            val k = key(item)
            val dragging = k == draggingKey
            val handle = Modifier.pointerInput(k) {
                detectDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        draggingKey = k
                        startIndex = items.indexOfFirst { key(it) == k }
                        offset = 0f
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { change, amount ->
                    change.consume()
                    onDrag(amount.y)
                }
            }
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) offset else 0f }
                    .background(if (dragging) TdColors.SurfaceHigh else Color.Transparent)
            ) {
                row(item, i, dragging, handle)
            }
        }
    }
}

/** Poignée de déplacement à placer à droite d'une ligne. */
@Composable
fun DragHandleBox(handle: Modifier, dragging: Boolean) {
    Box(handle.size(52.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.DragHandle, "Déplacer", tint = if (dragging) TdColors.Accent else TdColors.TextDim)
    }
}

/** Réorganisation d'une liste de titres (playlists). */
@Composable
fun ReorderList(items: MutableList<Song>, modifier: Modifier = Modifier) {
    ReorderableList(items, key = { it.id }, modifier = modifier) { song, i, dragging, handle ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${i + 1}", modifier = Modifier.width(30.dp), style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
            Artwork(song.art(), size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                    color = if (dragging) TdColors.Accent else TdColors.Text)
                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
            }
            DragHandleBox(handle, dragging)
        }
    }
}
