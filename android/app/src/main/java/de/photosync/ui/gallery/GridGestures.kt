package de.photosync.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Changes grid density only while at least two pointers are down. Single-finger
 * gestures remain untouched so LazyGrid keeps native scrolling and flinging.
 */
@Composable
internal fun Modifier.pinchToResizeGrid(
    columns: Int,
    minColumns: Int,
    maxColumns: Int,
    onColumnsChanged: (Int) -> Unit,
): Modifier = composed {
    val latestColumns by rememberUpdatedState(columns)
    val latestOnColumnsChanged by rememberUpdatedState(onColumnsChanged)
    pointerInput(minColumns, maxColumns) {
        awaitEachGesture {
            var accumulatedZoom = 1f
            while (true) {
                val event = awaitPointerEvent()
                val pressedPointers = event.changes.count { it.pressed }
                if (pressedPointers >= 2) {
                    accumulatedZoom *= event.calculateZoom()
                    val nextColumns = when {
                        accumulatedZoom > 1.18f && latestColumns > minColumns -> latestColumns - 1
                        accumulatedZoom < 0.84f && latestColumns < maxColumns -> latestColumns + 1
                        else -> latestColumns
                    }
                    if (nextColumns != latestColumns) {
                        latestOnColumnsChanged(nextColumns)
                        accumulatedZoom = 1f
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                } else {
                    accumulatedZoom = 1f
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}
