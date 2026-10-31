/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A sideways row for a mouse: hold the middle button and drag to move it, or turn the wheel with
 * Shift held - as a browser's middle-drag and Shift+wheel do. A mouse otherwise had no way along a
 * shelf but dragging its scrollbar, and most have none showing.
 *
 * The wheel only acts on a scroll nothing inside has taken, so a row that already scrolls with
 * Shift+wheel - or a touchpad's sideways swipe - is not moved twice.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.sideScroll(state: ScrollableState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val notch = with(LocalDensity.current) { WHEEL_STEP.toPx() }
    this
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val press = awaitPointerEvent(PointerEventPass.Initial)
                    if (press.type != PointerEventType.Press || !press.buttons.isTertiaryPressed) continue
                    var last = press.changes.first().position.x
                    press.changes.forEach { it.consume() }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.first()
                        if (!event.buttons.isTertiaryPressed) {
                            change.consume()
                            break
                        }
                        val moved = change.position.x - last
                        last = change.position.x
                        // The content follows the pointer, as a hand dragging the shelf along.
                        if (moved != 0f) state.dispatchRawDelta(-moved)
                        change.consume()
                    }
                }
            }
        }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.first()
            if (change.isConsumed) return@onPointerEvent
            val delta = when {
                event.keyboardModifiers.isShiftPressed && change.scrollDelta.y != 0f -> change.scrollDelta.y
                // Windows hands Shift+wheel over as a sideways scroll already, some mice tilt.
                change.scrollDelta.x != 0f -> change.scrollDelta.x
                else -> return@onPointerEvent
            }
            scope.launch { state.animateScrollBy(delta * notch) }
            change.consume()
        }
}

/** [horizontalScroll] with its own remembered state, and [sideScroll] on it - for a plain row. */
@Composable
fun Modifier.sideScrollable(): Modifier {
    val state = rememberScrollState()
    return horizontalScroll(state).sideScroll(state)
}

/** How far one notch of the wheel moves a row. */
private val WHEEL_STEP = 96.dp
