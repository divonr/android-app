package com.example.ApI.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Desktop equivalent of Android's "long press": invokes [onRightClick] when the user
 * clicks the secondary (right) mouse button. The reported [Offset] is local to the
 * element this modifier is attached to, matching the offset that `detectTapGestures`
 * passes to `onLongPress`, so call sites can reuse the same anchoring logic.
 *
 * This is meant to be added next to existing long-press handlers, so both a touch
 * long-press and a desktop right-click open the same context menu.
 */
fun Modifier.onRightClick(onRightClick: (Offset) -> Unit): Modifier =
    this.pointerInput(onRightClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                    event.changes.forEach { it.consume() }
                    onRightClick(position)
                }
            }
        }
    }
