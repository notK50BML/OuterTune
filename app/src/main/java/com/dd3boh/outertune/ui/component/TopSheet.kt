/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.IntOffset
import kotlin.math.pow

/**
 * A sheet that comes down from the top, dragged rather than toggled.
 *
 * The same thing as [BottomSheet] upside down, and deliberately built on the same
 * [BottomSheetState] rather than on a state of its own. That state is where the feel lives - the
 * spring the animation settles with, the velocity above which a flick counts as a flick, the
 * overshoot handling - and the point of this is that the equaliser should open and close exactly
 * the way the queue does, not approximately the way it does. Sharing the state is how "exactly" is
 * achieved rather than asserted; a second implementation would start identical and drift.
 *
 * Only three things differ, and all three are the mirror:
 *
 * - **The offset is negative.** Collapsed means pushed up off the top of the screen rather than
 *   down off the bottom, so the sheet is drawn at `-(expanded - value)` instead of `+`.
 * - **The drag is inverted.** [BottomSheetState]'s draggable subtracts the delta, which is right
 *   for a sheet whose "open" direction is up. Here open is down, so the delta is negated on the
 *   way in and the same arithmetic produces the same result.
 * - **So is the velocity.** `performFling` reads a positive velocity as "towards open". For a
 *   bottom sheet that is a drag upward, which is a negative y; here it is a drag downward, which
 *   is a positive one, so the sign the bottom sheet applies is not applied.
 *
 * Everything else - [collapsedContent], the fading background, dismissal - behaves as it does
 * there.
 */
@Composable
fun TopSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    background: @Composable (BoxScope.() -> Unit) = { },
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit = { },
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                // Matched to BottomSheet's own curve so a scrim behind either sheet fades at the
                // same rate - they are often on screen one after the other.
                alpha = (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
            }
            .fillMaxSize(),
        content = background
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .offset {
                val y = (state.expandedBound - state.value)
                    .roundToPx()
                    .coerceAtLeast(0)
                IntOffset(x = 0, y = -y)
            }
            .pointerInput(state) {
                val velocityTracker = VelocityTracker()

                detectVerticalDragGestures(
                    onDragStart = {
                        // Within a few dp, not exactly: the collapse is a spring that spends its last
                        // few hundred ms a pixel or two above rest, and a swipe started then counted
                        // as "not from the mini player" - the first swipe away sprang back, the second
                        // (once settled) worked.
                        state.dragStartedCollapsed = state.value <= state.collapsedBound + 8.dp
                    },
                    onVerticalDrag = { change, dragAmount ->
                        velocityTracker.addPointerInputChange(change)
                        // Negated: see the class doc. Down opens this one.
                        state.dispatchRawDelta(-dragAmount)
                    },
                    onDragCancel = {
                        velocityTracker.resetTracking()
                        state.snapTo(state.collapsedBound)
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().y
                        velocityTracker.resetTracking()
                        state.performFling(velocity, onDismiss)
                    }
                )
            }
    ) {
        if (!state.isCollapsed) {
            content()
        }
        if (state.progress < 1f) {
            Box(
                modifier = Modifier
                    .graphicsLayer { alpha = 1f - state.progress },
                content = collapsedContent,
            )
        }
    }
}
