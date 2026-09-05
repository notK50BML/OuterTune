/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The spectrum, as bars.
 *
 * Reads from [VisualizerTap], which holds each spectrum until the audio it describes is actually
 * audible - so what is drawn here matches what is being heard rather than what has been decoded.
 * Without that the bars lead the music by roughly the length of the output line's buffer, close to
 * a second, which reads as a fault rather than as a decoration.
 *
 * Driven by [withFrameNanos] rather than by a flow of arrays. The display should redraw when the
 * screen is ready to show something new, not whenever a decoder happens to produce a block - those
 * rates are unrelated, and coupling them would either drop frames or draw the same thing twice.
 * It also means this costs nothing when the window is not being composed at all.
 */
@Composable
fun SpectrumBars(
    tap: VisualizerTap,
    playedFrames: () -> Long,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    /** Kept at zero while paused or stopped, so the bars settle instead of freezing mid-jump. */
    active: Boolean = true,
) {
    // Held as a plain snapshot value: the tap reuses its array, so this is a copy taken per frame.
    var levels by remember { mutableStateOf(FloatArray(0)) }

    LaunchedEffect(tap, active) {
        if (!active) {
            // Fall to nothing rather than holding the last frame. A frozen spectrum over a paused
            // track looks like the app has hung.
            while (levels.any { it > 0.001f }) {
                withFrameNanos { }
                levels = FloatArray(levels.size) { levels[it] * 0.85f }
            }
            levels = FloatArray(levels.size)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            levels = tap.sample(playedFrames()).copyOf()
        }
    }

    val bars = levels
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (bars.isEmpty()) return@Canvas
        val gap = size.width / bars.size * 0.28f
        val barWidth = max(1f, size.width / bars.size - gap)
        bars.forEachIndexed { i, level ->
            // A visible stub at rest, so the shape of the display is legible in silence rather than
            // appearing only when something happens to be playing.
            val h = max(2f, level * size.height)
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * level),
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}
