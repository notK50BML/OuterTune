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

/**
 * Whether the full-screen equalizer panel is open.
 *
 * A CompositionLocal (same pattern as [com.dd3boh.outertune.LocalMenuState]) rather than a
 * parameter threaded through ControlsContent/ActionButtons/PlayerMenu, since the button that opens
 * it, the handle that opens it, and the panel itself all sit in genuinely different parts of the
 * composition tree, and none of those in between (ControlsContent, the three call sites that
 * render it) have any reason to know this exists.
 */
class EqualizerPanelState {
    var visible by mutableStateOf(false)
}

val LocalEqualizerPanelState = staticCompositionLocalOf<EqualizerPanelState> {
    error("No EqualizerPanelState provided")
}
