/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the tray icon's popup does and shows, filled in by the app - the queue lives inside it and
 * the tray outside, beside the window. Until the app has filled them in they do nothing.
 */
object TrayControls {
    @Volatile var togglePlay: () -> Unit = {}
    @Volatile var next: () -> Unit = {}
    @Volatile var previous: () -> Unit = {}

    /**
     * The queue, as a flow rather than a value: the popup collects it only while it is open, so the
     * app does not write anything here on every change for a popup that is usually closed.
     */
    @Volatile var queue: StateFlow<QueueState> = MutableStateFlow(QueueState())

    /**
     * The main window's theme as it is drawn - cover colours, pure black, the chosen font - so the
     * popup matches it. Snapshot state, so an open popup follows a change of song.
     */
    var colors by mutableStateOf<ColorScheme?>(null)
    var typography by mutableStateOf<Typography?>(null)
}
