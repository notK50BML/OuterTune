/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * A slow, breathing wash of colour drawn from the current album art.
 *
 * Deliberately not driven by the audio signal. Two reasons: reading the PCM stream would mean
 * inserting an [androidx.media3.common.audio.AudioProcessor] into the sink chain, which is the
 * code path that produces sound and is a poor place for an untested change; and amplitude data
 * is spiky, so anything calm enough to sit behind a player ends up so heavily smoothed that the
 * audio barely shows through anyway. A slow sine drift reads as "breathing" more convincingly
 * than a smoothed waveform does, and costs nothing.
 *
 * Cost is a handful of radial gradients per frame. The animation is read inside [drawBehind], so
 * a frame redraws without recomposing anything, and [isActive] stops the clock entirely when the
 * player is hidden, paused, or the device is in power saving mode.
 */
@Composable
fun LiquidBackground(
    colors: List<Color>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.45f,
) {
    if (colors.isEmpty()) return

    // Three blobs, each drifting on its own period so the pattern never visibly repeats.
    // Primes-ish durations keep them out of phase with one another.
    val transition = rememberInfiniteTransition(label = "liquid")
    val slow = if (isActive) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Restart),
            label = "slow",
        ).value
    } else {
        0f
    }
    val breath = if (isActive) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
            label = "breath",
        ).value
    } else {
        0.5f
    }

    // Pad out to three so a one- or two-colour album still gets depth.
    val palette = remember(colors) {
        when (colors.size) {
            1 -> listOf(colors[0], colors[0], colors[0])
            2 -> listOf(colors[0], colors[1], colors[0])
            else -> colors.take(3)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                // A blob radius near the screen's larger dimension keeps edges off-screen, so the
                // result reads as a wash rather than as three discernible circles.
                val baseRadius = maxOf(w, h) * 0.85f

                // sin() over the shared clock gives smooth, seamless looping motion: at t = 0 and
                // t = 1 every term returns to the same value, so the restart is invisible.
                val tau = (2 * PI).toFloat()
                val pulse = 0.92f + 0.08f * sin(tau * breath)

                palette.forEachIndexed { i, color ->
                    val phase = i * (tau / palette.size)
                    val cx = w * (0.5f + 0.28f * sin(tau * slow + phase))
                    val cy = h * (0.5f + 0.28f * sin(tau * slow * 0.73f + phase * 1.7f))
                    val radius = baseRadius * pulse * (0.75f + 0.12f * i)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                }
            }
    )
}
