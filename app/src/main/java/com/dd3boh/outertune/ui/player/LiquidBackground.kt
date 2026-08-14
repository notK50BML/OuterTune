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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dd3boh.outertune.audio.VisualizerFrame
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A slow-flowing wash of Material colour behind the player - a handful of large, soft-edged
 * blobs drifting on layered sine terms (a cheap stand-in for the domain-warp distortion a real
 * fluid shader computes, in the spirit of Apple Music's Now Playing background) rather than one
 * hard-edged shape. Optionally driven by the actual audio.
 *
 * Two rewrites happened here before this one, both worth recording so a third doesn't repeat the
 * same mistakes:
 *
 * 1. The original three-blob version drew every blob as a radial gradient fading straight to
 *    [Color.Transparent] at a low alpha, over a large radius - soft, but so faint against a dark
 *    player surface that it read as barely-there.
 * 2. The follow-up replaced it with a single spiky, path-based silhouette (a "ferrofluid" crown)
 *    lerped toward black - visible, but the wrong shape entirely (a sharp many-pointed petal
 *    filling most of the screen) and the wrong colour family (near-black rather than the actual
 *    Material/album colours).
 *
 * This version keeps the first version's blob shape (soft radial gradients, several of them,
 * still cheap) but fixes what made it hard to see: real Material colours at real opacity instead
 * of a black-lerped tint, a smaller footprint so it reads as a contained effect rather than
 * something filling the whole screen, a per-process-launch random phase so the arrangement is
 * different every time the app starts rather than always the same three circles, and stronger
 * weighting on the audio terms so it visibly tracks bass/treble/transients rather than mostly
 * drifting on its own.
 *
 * Cost is a handful of radial gradients per frame, same as before. The animation is read inside
 * [drawBehind], so a frame redraws without recomposing anything, and [isActive] stops the clock
 * entirely when the player is hidden, paused, or the device is in power saving mode.
 */
@Composable
fun LiquidBackground(
    colors: List<Color>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.8f,
    reactiveFrame: VisualizerFrame? = null,
) {
    // Extraction can come back empty (still loading) or dull/greyish - either way this needs a
    // guaranteed-vibrant fallback, and the theme's own colour roles are exactly that: real
    // Material colours rather than a fixed extra hue.
    val themeAccent = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary
    val themeTertiary = MaterialTheme.colorScheme.tertiary

    val palette = remember(colors, themeAccent, themeSecondary, themeTertiary) {
        when (colors.size) {
            0 -> listOf(themeAccent, themeSecondary, themeTertiary)
            1 -> listOf(colors[0], themeSecondary, themeAccent)
            2 -> listOf(colors[0], colors[1], themeAccent)
            else -> colors.take(2) + themeAccent
        }
    }

    // A phase offset drawn once per composition (i.e. once per time the player background is
    // actually created, which in practice means once per app process) - every launch gets its own
    // arrangement of the same three blobs instead of always starting from the same position.
    val seed = remember { Random.nextFloat() * 1000f }

    val transition = rememberInfiniteTransition(label = "liquidFlow")
    val clock = if (isActive) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
            label = "clock",
        ).value
    } else {
        0f
    }

    val bass = reactiveFrame?.bass ?: 0f
    val treble = reactiveFrame?.treble ?: 0f
    val transient = reactiveFrame?.transient ?: 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val minDim = minOf(w, h)
                val tau = (2 * PI).toFloat()
                val t = clock * tau

                // A contained effect, not a shape that reads as covering the whole screen - the
                // earlier ferrofluid version's problem was exactly this ratio being too large.
                val baseRadius = minDim * 0.32f * (1f + bass * 0.6f + transient * 0.3f)
                val dynamicAlpha = (alpha * (1f + transient * 0.5f)).coerceAtMost(0.98f)

                palette.forEachIndexed { i, color ->
                    // Two sine terms per axis at deliberately non-harmonic speeds, each blob on its
                    // own speed and phase - the closest a few lines of trig gets to real domain
                    // warping: motion that never visibly loops on a cycle a listener would notice,
                    // and where each blob's path looks unrelated to the others'.
                    val phase = seed + i * 137f
                    val speedX = 0.55f + i * 0.15f
                    val speedY = 0.45f + i * 0.19f
                    val driftX = 0.5f * sin(t * speedX + phase) + 0.5f * sin(t * speedX * 0.29f + phase * 1.7f)
                    val driftY = 0.5f * sin(t * speedY + phase * 1.3f) + 0.5f * sin(t * speedY * 0.24f + phase * 2.1f)
                    // Treble jitters the radius quickly rather than the position, which reads as
                    // "shimmering" rather than "vibrating across the screen".
                    val trebleJitter = 1f + treble * 0.3f * sin(t * 5.2f + phase)

                    val cx = w * (0.5f + 0.26f * driftX)
                    val cy = h * (0.5f + 0.26f * driftY)
                    val radius = baseRadius * trebleJitter * (0.82f + 0.16f * i)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = dynamicAlpha),
                                color.copy(alpha = dynamicAlpha * 0.45f),
                                Color.Transparent,
                            ),
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
