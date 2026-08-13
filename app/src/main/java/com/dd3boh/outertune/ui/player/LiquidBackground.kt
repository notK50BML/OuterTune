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
import com.dd3boh.outertune.audio.VisualizerFrame
import kotlin.math.PI
import kotlin.math.sin

/**
 * A slow, breathing wash of colour drawn from the current album art - optionally driven by the
 * actual audio instead of just an ambient sine drift.
 *
 * The ambient drift alone was a deliberate choice at first: reading the PCM stream needed an
 * [androidx.media3.common.audio.AudioProcessor] in the sink chain, and there wasn't one yet to
 * reuse. [EqualizerAudioProcessor][com.dd3boh.outertune.audio.EqualizerAudioProcessor] is now
 * exactly that tap point (built for the equalizer, reused here), already publishing smoothed
 * bass/mid/treble/transient energy - [reactiveFrame], when supplied, modulates the same blobs
 * this always drew rather than replacing them with something spikier: bass swells their radius,
 * treble adds a faster shimmer, and a transient gives everything a brief flash. Null keeps the
 * original pure-ambient behaviour exactly as it was, since amplitude data really is spiky enough
 * that a caller may reasonably want to skip it (battery saver, or just personal preference).
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
    reactiveFrame: VisualizerFrame? = null,
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

    // Additive on top of the ambient pulse/alpha rather than a replacement for them, so audio
    // reactivity reads as "the same breathing wash, now nudged by the music" instead of a
    // different-looking effect switching in and out as tracks get louder or quieter.
    val bassKick = reactiveFrame?.bass ?: 0f
    val trebleShimmer = reactiveFrame?.treble ?: 0f
    val transientFlash = reactiveFrame?.transient ?: 0f

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
                val pulse = 0.92f + 0.08f * sin(tau * breath) + bassKick * 0.4f + transientFlash * 0.15f
                val dynamicAlpha = (alpha * (1f + trebleShimmer * 0.6f + transientFlash * 0.8f)).coerceAtMost(0.9f)

                palette.forEachIndexed { i, color ->
                    val phase = i * (tau / palette.size)
                    val cx = w * (0.5f + 0.28f * sin(tau * slow + phase))
                    val cy = h * (0.5f + 0.28f * sin(tau * slow * 0.73f + phase * 1.7f))
                    val radius = baseRadius * pulse * (0.75f + 0.12f * i)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = dynamicAlpha), Color.Transparent),
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
