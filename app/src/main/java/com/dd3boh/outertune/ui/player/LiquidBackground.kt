/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.audio.VisualizerFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A soft, spiky "flower" silhouette behind the player - a petal-crown shape that grows and
 * shimmers with bass/treble/transients, blurred just enough to read as a wash of colour rather
 * than a hard-edged sticker. Optionally driven by the actual audio; without it, the shape still
 * breathes on its own so it never reads as a static image.
 *
 * Three earlier versions here, worth recording so a fourth doesn't repeat their mistakes:
 *
 * 1. Three large radial-gradient circles fading to [Color.Transparent] - too faint against a dark
 *    player surface, read as barely-there.
 * 2. A spiky path-based silhouette (the shape kept here) lerped toward black - visible, but the
 *    wrong colour family, and sized to fill most of the screen rather than reading as contained.
 * 3. Reverted the shape back to soft circles instead of fixing what was actually wrong with the
 *    spiky one - the shape itself was liked, it just needed to be smaller and softer, not replaced.
 *
 * This version keeps version 2's spike geometry (the shape that read as "flower") with version 3's
 * fixes: real Material/album colour instead of a black-lerped tint, and audio driving the clock's
 * own *rate* (frame-accumulated, scaled by bass/treble energy) rather than flashing the alpha on
 * every transient, which is what "blinking instead of flowing" turned out to be. New here: a
 * smaller footprint and a real blur so the petal edges soften into the background rather than
 * cutting a sharp silhouette.
 *
 * The blur is a real per-frame [RenderEffect][androidx.compose.ui.graphics.RenderEffect], unlike
 * [com.dd3boh.outertune.ui.player.FrostedBackground]'s blur on a static pre-shrunk image - this is
 * genuinely more expensive since it runs on a moving shape every frame, not once on a still image.
 * Kept modest (18dp) for that reason; if it turns out too heavy on lower-end hardware, dropping the
 * radius further (or gating it behind the existing audio-reactive toggle) is the next lever.
 */
@Composable
fun LiquidBackground(
    colors: List<Color>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.85f,
    reactiveFrame: VisualizerFrame? = null,
) {
    // Extraction can come back empty (still loading) or dull/greyish - either way this needs a
    // guaranteed-vibrant fallback, and the theme's own primary is exactly that.
    val themeAccent = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary
    val dominant = remember(colors, themeAccent) { colors.firstOrNull() ?: themeAccent }
    val rimColor = remember(dominant, themeSecondary) { lerp(dominant, themeSecondary, 0.35f) }

    // A phase offset drawn once per composition (i.e. once per app process) - every launch gets
    // its own arrangement of spikes instead of always starting from the same shape.
    val seed = remember { Random.nextFloat() * 1000f }

    val bass = reactiveFrame?.bass ?: 0f
    val treble = reactiveFrame?.treble ?: 0f
    val transient = reactiveFrame?.transient ?: 0f

    // Frame-accumulated rather than a fixed-duration Animatable, so the rate below can change
    // moment to moment with the music instead of the shape always cycling at the same speed while
    // only its *brightness* reacted - which is what read as blinking rather than flowing.
    var flowTime by remember { mutableFloatStateOf(0f) }
    val currentBass by rememberUpdatedState(bass)
    val currentTreble by rememberUpdatedState(treble)
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (nanos - lastFrameNanos) / 1_000_000_000f
                    val energy = ((currentBass + currentTreble) / 2f).coerceIn(0f, 1f)
                    flowTime += deltaSeconds * (0.22f + energy * 0.5f)
                }
                lastFrameNanos = nanos
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .blur(18.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                val minDim = minOf(w, h)
                val cx = w * 0.5f
                val cy = h * 0.42f
                val tau = (2 * PI).toFloat()
                val t = flowTime * tau

                // A contained effect, not a shape that reads as covering the screen.
                val baseRadius = minDim * 0.20f * (1f + bass * 0.5f + transient * 0.25f)

                // Sampled around the circle as a radius multiplier per angle, then walked as a
                // smooth closed contour - a plain polygon through the raw samples looks faceted,
                // not like a flower's petals.
                val sampleCount = 64
                val radii = FloatArray(sampleCount) { i ->
                    val angle = tau * i / sampleCount
                    var r = 1f
                    // Ambient wobble: present with no audio at all, so the shape is never static.
                    r += 0.05f * sin(angle * 3 + t)
                    // Bass: a few big, slow lobes - the "petals" the shape reads as a flower from.
                    r += (0.08f + bass * 0.5f) * sin(angle * 5 + t * 0.4f + seed)
                    // Treble: many small, fast spikes layered on top of the petals.
                    r += (0.015f + treble * 0.2f) * sin(angle * 17 - t * 2.1f + seed * 1.3f)
                    // Transient: uniform across every angle, so a beat reads as the whole shape
                    // kicking outward for a moment, not one spike moving.
                    r += transient * 0.3f
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
                            dominant.copy(alpha = alpha),
                            dominant.copy(alpha = alpha * 0.75f),
                        ),
                        center = Offset(cx, cy - baseRadius * 0.2f),
                        radius = baseRadius * 1.8f,
                    ),
                )
            }
    )
}
