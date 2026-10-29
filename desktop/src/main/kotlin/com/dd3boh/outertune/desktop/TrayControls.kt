/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * What the tray icon's menu does, filled in by the app - the queue lives inside it and the tray
 * outside, beside the window. Until the app has filled them in they do nothing.
 */
object TrayControls {
    @Volatile var togglePlay: () -> Unit = {}
    @Volatile var next: () -> Unit = {}
    @Volatile var previous: () -> Unit = {}
}
