package com.thousandlabs.tonedial.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Balayer vers la gauche pour passer au morceau suivant, vers la droite pour le précédent.
 */
fun Modifier.swipeTracks(onNext: () -> Unit, onPrevious: () -> Unit, threshold: Float = 140f): Modifier = composed {
    var offset by remember { mutableFloatStateOf(0f) }
    this
        .graphicsLayer {
            translationX = offset * 0.45f
            alpha = 1f - (kotlin.math.abs(offset) / 900f).coerceIn(0f, 0.5f)
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    when {
                        offset < -threshold -> onNext()
                        offset > threshold -> onPrevious()
                    }
                    offset = 0f
                },
                onDragCancel = { offset = 0f },
            ) { change, amount ->
                change.consume()
                offset += amount
            }
        }
}
