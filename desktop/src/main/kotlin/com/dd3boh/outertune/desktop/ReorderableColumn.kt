/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp

/**
 * A short list reordered by dragging each row's handle, as the phone's settings lists are - the
 * player buttons, the tabs. Replaces up and down arrows, which took a click per place moved.
 *
 * For lists that fit a dialog: every row is composed, no lazy list. The held row lifts and follows
 * the pointer, and steps a whole row at a time, so the list settles between moves. Each row is keyed
 * by [key] and reads its index when a drag starts, so the row grabbed is always the row that moves.
 *
 * @param row draws one row; put [DragHandle] - or [handle] on your own - where it should be grabbed.
 */
@Composable
fun <T> ReorderableColumn(
    items: SnapshotStateList<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    row: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    var dragging by remember { mutableStateOf(-1) }
    var offset by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }

    Column(modifier = modifier) {
        items.forEachIndexed { index, item ->
            key(key(item)) {
                val currentIndex by rememberUpdatedState(index)
                val held = index == dragging
                val handle = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragging = currentIndex; offset = 0f },
                            onDragEnd = { dragging = -1; offset = 0f },
                            onDragCancel = { dragging = -1; offset = 0f },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.y
                            val steps = (offset / rowHeight).toInt()
                            if (steps != 0 && dragging >= 0) {
                                val target = (dragging + steps).coerceIn(0, items.lastIndex)
                                if (target != dragging) {
                                    items.add(target, items.removeAt(dragging))
                                    dragging = target
                                }
                                offset -= steps * rowHeight
                            }
                        }
                    }
                Box(
                    modifier = Modifier
                        .onSizeChanged { if (it.height > 0) rowHeight = it.height.toFloat() }
                        .zIndex(if (held) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (held) offset else 0f
                            shadowElevation = if (held) 10f else 0f
                            alpha = if (held) 0.94f else 1f
                        },
                ) {
                    row(index, item, handle)
                }
            }
        }
    }
}

/** The phone's reorder grip, as the queue draws it. Pass the handle modifier from [ReorderableColumn]. */
@Composable
fun DragHandle(handle: Modifier) {
    Icon(
        OuterTuneIcons.dragHandle,
        contentDescription = "Drag to reorder",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = handle.size(22.dp),
    )
}
