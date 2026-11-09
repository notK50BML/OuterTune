/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.ui.component.BottomSheetState

/**
 * Whether the full-screen equalizer panel is open, and the sheet it is drawn on.
 *
 * A CompositionLocal (same pattern as [com.dd3boh.outertune.LocalMenuState]) rather than a
 * parameter threaded through ControlsContent/ActionButtons/PlayerMenu, since the button that opens
 * it, the handle that opens it, and the panel itself all sit in genuinely different parts of the
 * composition tree, and none of those in between have any reason to know this exists.
 *
 * **The sheet is the single source of truth.** [visible] used to be a boolean of its own, kept in
 * step with the sheet by a pair of effects watching each other, and that arrangement cannot
 * express a drag: the moment a finger moved the sheet a pixel it counted as "open", which told the
 * other effect to animate it fully open - snatching it out from under the finger that was still
 * dragging it. Now [visible] simply reads where the sheet is, and setting it asks the sheet to go
 * there. A drag moves the sheet and nothing else; a button moves the sheet and nothing else; and
 * neither can fight the other because there is only one thing being moved.
 */
class EqualizerPanelState {

    /**
     * The sheet, once the panel has built it. The pull-down handle in the player drives this
     * directly, which is what lets the panel follow a finger rather than fire an animation when a
     * threshold is crossed.
     */
    var sheet: BottomSheetState? by mutableStateOf(null)
        internal set

    /** Asked for before the sheet existed - honoured when it arrives. */
    private var pendingOpen by mutableStateOf(false)

    /**
     * Set the moment a finger lands on the pull-down handle, so the panel's content is built then,
     * while the finger is still inside touch slop, rather than on the drag's first frame. Building
     * twelve faders, a response curve and a compressor in the middle of a drag stalled it for a few
     * frames, after which the sheet jumped to wherever the finger had got to - the "jumps out" the
     * queue never did. Cleared by [EqualizerPanel] once the sheet has sat collapsed for a moment.
     */
    var warm by mutableStateOf(false)
        internal set

    var visible: Boolean
        get() = sheet?.let { !it.isCollapsed } ?: pendingOpen
        set(value) {
            val target = sheet
            if (target == null) {
                pendingOpen = value
                return
            }
            if (value) target.expandSoft() else target.collapseSoft()
        }

    internal fun attach(newSheet: BottomSheetState) {
        if (sheet === newSheet) return
        sheet = newSheet
        if (pendingOpen) {
            pendingOpen = false
            newSheet.expandSoft()
        }
    }
}

val LocalEqualizerPanelState = staticCompositionLocalOf<EqualizerPanelState> {
    error("No EqualizerPanelState provided")
}

/**
 * Release speed above which the equaliser counts a release as a flick and goes all the way.
 *
 * The shared sheet default is 250 px/s, which on a modern phone is under 100dp a second - slower
 * than a finger moves while simply letting go. So nearly every release counted as a flick, and a
 * short pull opened the whole panel the moment the finger lifted. Measured in dp so it means the
 * same on every screen; below it, the panel settles by how far it was pulled.
 */
private val EqualizerFlingThresholdPerSecond = 700.dp

/**
 * Drags the equaliser sheet with the finger, 1:1, and settles it on release - used by both the
 * handle in the player that pulls it open and the one at the top of the panel that pushes it shut,
 * so the two directions feel the same.
 *
 * [warmState] marks the panel warm on touch-down (see [EqualizerPanelState.warm]); the closing
 * handle passes null, since the panel is already built by then.
 */
internal fun Modifier.equalizerSheetDrag(
    sheet: BottomSheetState?,
    warmState: EqualizerPanelState? = null,
): Modifier = this
    .pointerInput(warmState) {
        if (warmState == null) return@pointerInput
        // Observes only - never consumes - so the tap and drag handling still see everything.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            warmState.warm = true
        }
    }
    .pointerInput(sheet) {
        if (sheet == null) return@pointerInput
        val velocityTracker = VelocityTracker()
        val threshold = EqualizerFlingThresholdPerSecond.toPx()
        detectVerticalDragGestures(
            onDragStart = { velocityTracker.resetTracking() },
            onVerticalDrag = { change, dragAmount ->
                // Consumed, so the player's own sheet does not also take this drag and start
                // closing underneath the equaliser that is being pulled down.
                change.consume()
                velocityTracker.addPointerInputChange(change)
                // Negated for the same reason TopSheet negates it: down opens this one.
                sheet.dispatchRawDelta(-dragAmount)
            },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity().y
                velocityTracker.resetTracking()
                sheet.performFling(velocity, null, threshold)
            },
            // Settled where it is rather than snapped shut: a cancel (the handle leaving the
            // composition mid-drag, say) is not the user asking for it to close.
            onDragCancel = {
                velocityTracker.resetTracking()
                sheet.performFling(0f, null, threshold)
            },
        )
    }
