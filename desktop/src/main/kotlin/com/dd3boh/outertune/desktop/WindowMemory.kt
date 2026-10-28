/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Point

/**
 * Where the window was, so it opens there again: its size and place when floating, and whether it
 * was maximised. It used to open at 1000 by 700 wherever Windows put it, every time.
 *
 * A remembered place is only used while some screen still shows the middle of its top edge - the
 * strip it is dragged by - so a monitor unplugged since cannot leave it opening out of reach.
 */
object WindowMemory {
    private val DEFAULT_SIZE = DpSize(1000.dp, 700.dp)

    /** The smallest the window can be made, in the same units as its size: below it the layout breaks. */
    const val MIN_WIDTH = 520
    const val MIN_HEIGHT = 420

    fun initialSize(settings: Settings): DpSize =
        parse(settings.windowBounds)?.let { (_, _, w, h) -> DpSize(w.coerceAtLeast(MIN_WIDTH).dp, h.coerceAtLeast(MIN_HEIGHT).dp) }
            ?: DEFAULT_SIZE

    fun initialPosition(settings: Settings): WindowPosition {
        val (x, y, w, _) = parse(settings.windowBounds) ?: return WindowPosition.PlatformDefault
        val strip = Point(x + w / 2, y + 8)
        val onScreen = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.contains(strip) }
        }.getOrDefault(false)
        return if (onScreen) WindowPosition(x.dp, y.dp) else WindowPosition.PlatformDefault
    }

    /**
     * Kept as it changes. The floating size and place only while floating - a maximised window
     * keeps the floating ones it will go back to - and nothing while minimised, which is not a
     * place anyone wants to come back to.
     */
    fun remember(settings: Settings, state: WindowState) {
        if (state.isMinimized) return
        settings.windowMaximised = state.placement == WindowPlacement.Maximized
        val position = state.position
        if (state.placement != WindowPlacement.Floating || position !is WindowPosition.Absolute) return
        val bounds = listOf(position.x.value, position.y.value, state.size.width.value, state.size.height.value)
        if (bounds.any { it.isNaN() }) return
        settings.windowBounds = bounds.joinToString(",") { it.toInt().toString() }
    }

    private fun parse(text: String): List<Int>? =
        text.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
}
