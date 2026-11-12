package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
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
    onDraggingChange: (Boolean) -> Unit = {},
    row: @Composable (item: T, index: Int, dragging: Boolean, handle: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edgePx = with(density) { 110.dp.toPx() }      // zone près des bords qui fait défiler
    val maxSpeedPx = with(density) { 34.dp.toPx() }   // vitesse maximale, par image (~60 par seconde)
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var startIndex by remember { mutableIntStateOf(-1) }
    // position voulue du haut de l'élément tenu, dans le repère de la liste à l'écran (suit le doigt)
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragSize by remember { mutableIntStateOf(0) }

    fun indexOfDragged(): Int = draggingKey?.let { k -> items.indexOfFirst { key(it) == k } } ?: -1

    /** Si le centre de l'élément tenu passe sur une autre ligne, il prend sa place. */
    fun checkSwap() {
        val index = indexOfDragged()
        if (index < 0) return
        val center = dragTop + dragSize / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            val li = it.index - headerCount
            li != index && li in items.indices && center > it.offset && center < it.offset + it.size
        } ?: return
        val to = target.index - headerCount
        // garde la liste immobile quand la première ligne visible est concernée par l'échange
        val first = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        items.add(to, items.removeAt(index))
        if (first == index + headerCount || first == target.index) {
            scope.launch { listState.scrollToItem(first, firstOffset) }
        }
    }

    fun onDrag(delta: Float) {
        val info = listState.layoutInfo
        dragTop = (dragTop + delta).coerceIn(
            info.viewportStartOffset - dragSize / 2f,
            info.viewportEndOffset - dragSize / 2f,
        )
        checkSwap()
    }

    // défilement automatique continu tant que l'élément est tenu près d'un bord, même doigt immobile
    LaunchedEffect(draggingKey) {
        if (draggingKey == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val top = dragTop
            val bottom = dragTop + dragSize
            val ratio = when {
                bottom > info.viewportEndOffset - edgePx ->
                    ((bottom - (info.viewportEndOffset - edgePx)) / edgePx).coerceIn(0f, 1f)
                top < info.viewportStartOffset + edgePx ->
                    -((info.viewportStartOffset + edgePx - top) / edgePx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (ratio != 0f) {
                // lent à l'entrée de la zone, très rapide tout contre le bord
                val speed = maxSpeedPx * (0.12f + 0.88f * ratio * ratio)
                val scrolled = listState.scrollBy(if (ratio > 0) speed else -speed)
                if (scrolled != 0f) checkSwap()
            }
        }
    }

    fun finish() {
        val end = indexOfDragged()
        val start = startIndex
        draggingKey = null
        startIndex = -1
        onDraggingChange(false)
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
                        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }
                        if (info != null) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            dragTop = info.offset.toFloat()
                            dragSize = info.size
                            draggingKey = k
                            startIndex = items.indexOfFirst { key(it) == k }
                            onDraggingChange(true)
                        }
                    },
                    onDragEnd = { finish() },
                    onDragCancel = { finish() },
                ) { change, amount ->
                    change.consume()
                    if (draggingKey == k) onDrag(amount.y)
                }
            }
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) {
                            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }
                            if (info != null) dragTop - info.offset else 0f
                        } else 0f
                    }
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<Int?>(null) }
    moving?.let { from ->
        items.getOrNull(from)?.let { song ->
            PositionDialog(song.title, from, items.size, onDismiss = { moving = null }) { to ->
                moving = null
                if (to != from) {
                    items.add(to, items.removeAt(from))
                    scope.launch { listState.animateScrollToItem((to - 2).coerceAtLeast(0)) }
                }
            }
        }
    }
    ReorderableList(items, key = { it.id }, modifier = modifier, listState = listState) { song, i, dragging, handle ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { moving = i }
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

/**
 * Déplacement direct sans glisser : « Déplacer à la position 59 », « Placer en premier / en dernier ».
 * [from] et la valeur rendue sont des positions comptées à partir de 0.
 */
@Composable
fun PositionDialog(title: String, from: Int, count: Int, onDismiss: () -> Unit, onMove: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val wanted = text.trim().toIntOrNull()
    val valid = wanted != null && wanted in 1..count
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer le titre") },
        text = {
            Column {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = TdColors.TextDim)
                Spacer(Modifier.height(4.dp))
                Text("Position actuelle : ${from + 1} sur $count", style = MaterialTheme.typography.bodySmall, color = TdColors.TextDim)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    label = { Text("Nouvelle position (1 à $count)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMove(0) }, enabled = from != 0, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.VerticalAlignTop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("En premier", maxLines = 1)
                    }
                    OutlinedButton(onClick = { onMove(count - 1) }, enabled = from != count - 1, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.VerticalAlignBottom, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("En dernier", maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMove(wanted!! - 1) }, enabled = valid) { Text("Déplacer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
        containerColor = TdColors.Surface,
    )
}
