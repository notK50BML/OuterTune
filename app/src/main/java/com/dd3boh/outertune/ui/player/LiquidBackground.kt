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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import com.dd3boh.outertune.audio.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A ferrofluid-style silhouette - a dark, glossy blob with a crown of spikes that lengthen and
 * sharpen with the music, the way real ferrofluid spikes when a magnetic field ramps up - rather
 * than a soft wash of colour. Optionally driven by the actual audio; without it, the shape still
 * breathes on its own so it never reads as a static image.
 *
 * The previous version here was three large, mostly-transparent radial gradients - a deliberate
 * "ambient wash" look, but one that read as barely-there against a dark player background, which
 * is exactly the complaint this rewrite addresses: a near-black, high-contrast shape with a
 * coloured rim light is visible on both a dark and a light surface, the way an actual ferrofluid
 * demo is (black fluid on a plain background), instead of disappearing into it.
 *
 * [reactiveFrame], when supplied, drives the actual spike geometry: bass swells a handful of big,
 * slow lobes (the classic ferrofluid "crown"), treble adds many small fast spikes on top, and a
 * transient gives the whole silhouette a brief uniform outward kick, reading as the shape
 * flinching on a beat rather than one spike spinning around it. Null keeps a gentle ambient wobble
 * so the shape is never fully still.
 *
 * Cost is one ~64-point Path rebuilt per frame plus two gradient fills - cheap enough for
 * continuous animation, and [isActive] stops the clock entirely when the player is hidden,
 * paused, or the device is in power saving mode.
 */
@Composable
fun LiquidBackground(
    colors: List<Color>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.9f,
    reactiveFrame: VisualizerFrame? = null,
) {
    // Extraction can come back empty (still loading) or dull/greyish - either way this needs a
    // real hue to tint the rim light with, so the theme's own primary is a guaranteed fallback
    // rather than trusting the cover alone.
    val themeAccent = MaterialTheme.colorScheme.primary
    val dominant = remember(colors, themeAccent) { colors.firstOrNull() ?: themeAccent }

    // Ferrofluid reads as near-black with the cover's colour only as a tint, not a wash - the core
    // stays dark even over a light theme's light surface, and the rim light is where the actual
    // colour shows through, like light catching an oily, glossy surface.
    val coreColor = remember(dominant) { lerp(Color.Black, dominant, 0.28f) }
    val rimColor = remember(dominant) { lerp(dominant, Color.White, 0.2f) }

    val transition = rememberInfiniteTransition(label = "ferrofluid")
    val clock = if (isActive) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
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
                val cx = w * 0.5f
                val cy = h * 0.42f
                val baseRadius = maxOf(w, h) * 0.34f
                val tau = (2 * PI).toFloat()
                val t = clock * tau

                // Sampled around the circle as a radius multiplier per angle, then walked through
                // as a smooth closed contour rather than straight segments between the raw
                // samples - a plain polygon through 64 points looks faceted, not fluid.
                val sampleCount = 64
                val radii = FloatArray(sampleCount) { i ->
                    val angle = tau * i / sampleCount
                    var r = 1f
                    // Ambient wobble: present with no audio at all, so the shape is never static.
                    r += 0.05f * sin(angle * 3 + t)
                    // Bass: a few big, slow lobes - the crown a real ferrofluid forms as the field
                    // ramps up.
                    r += (0.08f + bass * 0.55f) * sin(angle * 5 + t * 0.4f)
                    // Treble: many small, fast spikes layered on top of the bass lobes.
                    r += (0.015f + treble * 0.22f) * sin(angle * 17 - t * 2.3f)
                    // Transient: uniform across every angle, so a beat reads as the whole
                    // silhouette kicking outward for a frame, not one spike moving.
                    r += transient * 0.35f
                    (baseRadius * r).coerceAtLeast(baseRadius * 0.4f)
                }

                fun pointAt(i: Int): Offset {
                    val index = ((i % sampleCount) + sampleCount) % sampleCount
                    val angle = tau * index / sampleCount
                    val r = radii[index]
                    return Offset(cx + r * cos(angle), cy + r * sin(angle))
                }

                val path = Path()
                val first = pointAt(0)
                path.moveTo(first.x, first.y)
                for (i in 0 until sampleCount) {
                    val current = pointAt(i)
                    val next = pointAt(i + 1)
                    val midpoint = Offset((current.x + next.x) / 2f, (current.y + next.y) / 2f)
                    path.quadraticBezierTo(current.x, current.y, midpoint.x, midpoint.y)
                }
                path.close()

                drawPath(
                    path = path,
                    brush = Brush.radialGradient(
                        colors = listOf(
                            rimColor.copy(alpha = alpha),
                            coreColor.copy(alpha = alpha),
                            coreColor.copy(alpha = alpha * 0.85f),
                        ),
                        center = Offset(cx, cy - baseRadius * 0.25f),
                        radius = baseRadius * 2.1f,
                    ),
                )

                // A soft, mostly-transparent halo just outside the silhouette - the glow a dark
                // glossy fluid picks up from whatever light is around it, and the last bit of
                // "this is meant to look wet/reflective" rather than a flat cutout shape.
                drawPath(
                    path = path,
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, rimColor.copy(alpha = alpha * 0.25f)),
                        center = Offset(cx, cy),
                        radius = baseRadius * 1.35f,
                    ),
                )
            }
    )
}
