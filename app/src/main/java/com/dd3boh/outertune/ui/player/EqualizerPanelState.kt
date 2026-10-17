/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
