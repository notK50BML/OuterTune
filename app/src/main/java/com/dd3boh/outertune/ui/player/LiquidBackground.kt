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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.audio.VisualizerFrame
import com.dd3boh.outertune.constants.LiquidShapeStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A soft wash of Material colour behind the player, in one of three silhouettes - people who liked
 * the spikier "flower", people who preferred the softer overlapping circles, and people who wanted
 * something closer to a real ferrofluid audio visualiser turned out to be different audiences, not
 * a question with one right answer, so [shapeStyle] picks between them rather than this settling
 * on any one permanently. All three are optionally driven by the actual audio; without it, each
 * still breathes on its own so it never reads as a static image. [LiquidShapeStyle.FERROFLUID] is
 * the one exception to "Material colour" in that first sentence - see its own section below for
 * why.
 *
 * Earlier versions here, worth recording so a future one doesn't repeat their mistakes:
 *
 * 1. Three large radial-gradient circles fading to [Color.Transparent] - too faint against a dark
 *    player surface, read as barely-there.
 * 2. A spiky path-based silhouette lerped toward black - visible, but the wrong colour family, and
 *    sized to fill most of the screen rather than reading as contained.
 * 3. Reverted the shape back to soft circles instead of fixing what was actually wrong with the
 *    spiky one - the shape itself was liked, it just needed to be smaller and softer, not replaced.
 * 4. Restored the spiky shape (smaller, blurred, real colour) but dropped the circles entirely,
 *    when the actual answer was to keep both and let it be a setting.
 *
 * Both styles share the same colour handling (real Material/album colour, never a black-lerped
 * tint) and the same motion fix: audio drives the clock's own *rate* (frame-accumulated, scaled by
 * bass/treble energy) rather than flashing the alpha on every transient, which is what "blinking
 * instead of flowing" turned out to be.
 *
 * The blur on [LiquidShapeStyle.PETAL] is a real per-frame
 * [RenderEffect][androidx.compose.ui.graphics.RenderEffect], unlike
 * [com.dd3boh.outertune.ui.player.FrostedBackground]'s blur on a static pre-shrunk image - this is
 * genuinely more expensive since it runs on a moving shape every frame, not once on a still image.
 * Kept modest ([PETAL_BLUR_RADIUS]) for that reason. [LiquidShapeStyle.SPHERES] skips it - the
 * soft radial gradients already fade out on their own, so a second blur pass would cost more than
 * it changes.
 */
private val PETAL_BLUR_RADIUS = 26.dp

/** Kept low, unlike [PETAL_BLUR_RADIUS] - this shape's whole point is sharp spikes and a glossy
 *  edge; heavy blur would turn it back into a soft cloud. Just enough to take the hard edge off
 *  the polygon's straight segments. */
private val FERROFLUID_BLUR_RADIUS = 5.dp

@Composable
fun LiquidBackground(
    colors: List<Color>,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 0.85f,
    reactiveFrame: VisualizerFrame? = null,
    shapeStyle: LiquidShapeStyle = LiquidShapeStyle.PETAL,
    /**
     * The petal's fill colour - primary by default (the same colour as the play/pause pill and
     * every other playback control), but the caller picks something else when the backdrop
     * behind it is already primary at full area (Theme surface): the blobs then use the
     * smaller-area secondary role instead of repeating the colour the whole screen is already.
     */
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    // Extraction can come back empty (still loading) or dull/greyish - either way this needs a
    // guaranteed-vibrant fallback, and the theme's own colour roles are exactly that.
    val themeAccent = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary
    val themeTertiary = MaterialTheme.colorScheme.tertiary
    // The petal is filled with whatever accentColor resolves to - see its own doc - not the
    // album-art extraction SPHERES uses below; the two silhouettes read as belonging to two
    // different parts of the player otherwise.
    val petalColor = accentColor
    val petalRimColor = remember(petalColor, themeSecondary) { lerp(petalColor, themeSecondary, 0.35f) }
    val spherePalette = remember(colors, themeAccent, themeSecondary, themeTertiary) {
        when (colors.size) {
            0 -> listOf(themeAccent, themeSecondary, themeTertiary)
            1 -> listOf(colors[0], themeSecondary, themeAccent)
            2 -> listOf(colors[0], colors[1], themeAccent)
            else -> colors.take(2) + themeAccent
        }
    }

    // A phase offset drawn once per composition (i.e. once per app process) - every launch gets
    // its own arrangement instead of always starting from the same shape.
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

    val baseModifier = modifier.fillMaxSize()

    when (shapeStyle) {
        LiquidShapeStyle.PETAL -> Box(
            modifier = baseModifier
                .blur(PETAL_BLUR_RADIUS)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val minDim = minOf(w, h)
                    val tau = (2 * PI).toFloat()
                    val t = flowTime * tau

                    // The contour wobble below moves the *outline*, but a fixed centre meant the
                    // shape as a whole just sat there pulsing in place - at a glance, especially
                    // blurred, that read as barely moving at all. A slow, small drift of the centre
                    // itself (two non-harmonic sines, same trick SPHERES uses) makes the shape
                    // visibly travel rather than only breathe.
                    val driftX = 0.5f * sin(t * 0.13f + seed) + 0.5f * sin(t * 0.05f + seed * 1.7f)
                    val driftY = 0.5f * sin(t * 0.11f + seed * 1.3f) + 0.5f * sin(t * 0.04f + seed * 2.1f)
                    val cx = w * (0.5f + 0.05f * driftX)
                    val cy = h * (0.42f + 0.04f * driftY)

                    // A contained effect, not a shape that reads as covering the screen.
                    val baseRadius = minDim * 0.20f * (1f + bass * 0.5f + transient * 0.25f)

                    // The whole petal pattern also slowly rotates, on top of pulsing in place -
                    // rotation reads as motion even where the pulse amplitude alone would not.
                    val spin = t * 0.06f

                    // Sampled around the circle as a radius multiplier per angle, then walked as a
                    // smooth closed contour - a plain polygon through the raw samples looks
                    // faceted, not like a flower's petals.
                    val sampleCount = 64
                    val radii = FloatArray(sampleCount) { i ->
                        val angle = tau * i / sampleCount
                        var r = 1f
                        // Ambient wobble: present with no audio at all, so the shape is never
                        // static. Large enough on its own to read as movement rather than a
                        // near-imperceptible shimmer under the blur.
                        r += 0.12f * sin(angle * 3 + t)
                        // Bass: a few big, slow lobes - the "petals" the shape reads as a flower
                        // from.
                        r += (0.12f + bass * 0.5f) * sin(angle * 5 + t * 0.4f + seed)
                        // Treble: many small, fast spikes layered on top of the petals.
                        r += (0.015f + treble * 0.2f) * sin(angle * 17 - t * 2.1f + seed * 1.3f)
                        // Transient: uniform across every angle, so a beat reads as the whole
                        // shape kicking outward for a moment, not one spike moving.
                        r += transient * 0.3f
                        (baseRadius * r).coerceAtLeast(baseRadius * 0.4f)
                    }

                    fun pointAt(i: Int): Offset {
                        val index = ((i % sampleCount) + sampleCount) % sampleCount
                        val angle = tau * index / sampleCount + spin
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
                                petalRimColor.copy(alpha = alpha),
                                petalColor.copy(alpha = alpha),
                                petalColor.copy(alpha = alpha * 0.75f),
                                // Fading to fully transparent before the path's own edge gives the
                                // blur pass a soft gradient to spread instead of a hard alpha
                                // cutoff to soften - the difference between a fuzzy cloud and a
                                // blurred sticker.
                                petalColor.copy(alpha = 0f),
                            ),
                            center = Offset(cx, cy - baseRadius * 0.2f),
                            radius = baseRadius * 2.2f,
                        ),
                    )
                }
        )

        LiquidShapeStyle.SPHERES -> Box(
            modifier = baseModifier
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val minDim = minOf(w, h)
                    val tau = (2 * PI).toFloat()
                    val t = flowTime * tau

                    val baseRadius = minDim * 0.32f * (1f + bass * 0.6f + transient * 0.3f)

                    spherePalette.forEachIndexed { i, color ->
                        // Two sine terms per axis at deliberately non-harmonic speeds, each blob
                        // on its own speed and phase - motion that never visibly loops on a cycle
                        // a listener would notice, and where each blob's path looks unrelated to
                        // the others'.
                        val phase = seed + i * 137f
                        val speedX = 0.55f + i * 0.15f
                        val speedY = 0.45f + i * 0.19f
                        val driftX = 0.5f * sin(t * speedX + phase) + 0.5f * sin(t * speedX * 0.29f + phase * 1.7f)
                        val driftY = 0.5f * sin(t * speedY + phase * 1.3f) + 0.5f * sin(t * speedY * 0.24f + phase * 2.1f)
                        // Treble jitters the radius quickly rather than the position, which reads
                        // as "shimmering" rather than "vibrating across the screen".
                        val trebleJitter = 1f + treble * 0.3f * sin(t * 5.2f + phase)

                        val cx = w * (0.5f + 0.26f * driftX)
                        val cy = h * (0.5f + 0.26f * driftY)
                        val radius = baseRadius * trebleJitter * (0.82f + 0.16f * i)

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color.copy(alpha = alpha),
                                    color.copy(alpha = alpha * 0.45f),
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

        // A dark, glossy magnetic-fluid crown, styled after real ferrofluid audio visualisers
        // (e.g. the FAV-LE22): a pool with sharp spikes standing up around its rim under a
        // magnetic field, not a soft flower. Two things make this read as ferrofluid rather than
        // just "a spiky dark blob":
        //
        // 1. Genuinely dark, not theme-coloured. Every other shape/style here deliberately uses
        //    real Material or album colour - this is the one exception, because actual ferrofluid
        //    is black regardless of what's lighting it, and tinting it with the theme's accent
        //    would stop it looking like the reference at all.
        // 2. A clipped specular highlight - a soft white glow confined to the crown's own
        //    silhouette (clipPath), standing in for the wet, reflective sheen a flat dark fill on
        //    its own can't suggest. Without it this is just a dark polygon, not a liquid.
        //
        // Straight lineTo segments between peak and valley vertices, not PETAL's smoothed
        // quadratic contour - actual ferrofluid spikes come to sharp points and the pool between
        // them pulls into sharp cusps too, so a faceted polygon (softened only by the shape's own
        // modest blur) is closer to the real geometry than a rounded one would be. Bass drives
        // spike height hard and fast rather than PETAL's gentle organic breathing, matching how
        // a magnetic response actually looks: snappy, not organic.
        LiquidShapeStyle.FERROFLUID -> Box(
            modifier = baseModifier
                .blur(FERROFLUID_BLUR_RADIUS)
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    val minDim = minOf(w, h)
                    val tau = (2 * PI).toFloat()
                    val t = flowTime * tau

                    val driftX = 0.5f * sin(t * 0.09f + seed) + 0.5f * sin(t * 0.035f + seed * 1.9f)
                    val driftY = 0.5f * sin(t * 0.08f + seed * 1.4f) + 0.5f * sin(t * 0.03f + seed * 2.4f)
                    val cx = w * (0.5f + 0.04f * driftX)
                    val cy = h * (0.45f + 0.03f * driftY)

                    val poolRadius = minDim * 0.15f * (1f + transient * 0.2f)
                    val spin = t * 0.045f
                    val spikeCount = 16

                    // Cheap, stable per-spike pseudo-randomness (a fixed function of i and seed,
                    // not of time) - real ferrofluid spikes are never perfectly uniform in height.
                    fun spikeVariance(i: Int) = 0.75f + 0.5f * (0.5f + 0.5f * sin(i * 12.9898f + seed * 78.233f))

                    fun peakPoint(i: Int): Offset {
                        val angle = tau * i / spikeCount + spin
                        val variance = spikeVariance(i)
                        // Snappy, not organic: bass punches spikes taller directly rather than
                        // easing them, the way an actual magnetic field response looks.
                        val bassHeight = 1f + bass * 2.2f
                        val trebleFlicker = 1f + treble * 0.18f * sin(t * 11f + i * 4.1f + seed)
                        val ambient = 1f + 0.08f * sin(t * 0.7f + i * 2.3f + seed)
                        val height = poolRadius * (1.6f + 2.2f * variance) * bassHeight * trebleFlicker * ambient
                        return Offset(cx + height * cos(angle), cy + height * sin(angle))
                    }

                    fun valleyPoint(i: Int): Offset {
                        val angle = tau * (i + 0.5f) / spikeCount + spin
                        val r = poolRadius * (0.9f + 0.05f * sin(t * 1.3f + i * 3.7f + seed))
                        return Offset(cx + r * cos(angle), cy + r * sin(angle))
                    }

                    val path = Path()
                    val startValley = valleyPoint(spikeCount - 1)
                    path.moveTo(startValley.x, startValley.y)
                    for (i in 0 until spikeCount) {
                        val peak = peakPoint(i)
                        val valley = valleyPoint(i)
                        path.lineTo(peak.x, peak.y)
                        path.lineTo(valley.x, valley.y)
                    }
                    path.close()

                    drawPath(
                        path = path,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF232326).copy(alpha = alpha),
                                Color(0xFF0A0A0C).copy(alpha = alpha),
                                Color(0xFF000000).copy(alpha = alpha * 0.9f),
                            ),
                            center = Offset(cx, cy - poolRadius * 0.3f),
                            radius = poolRadius * 5f,
                        ),
                    )

                    // The wet highlight - clipped to the crown's own outline so the glow never
                    // spills past its edge onto the backdrop behind it. Orbits slowly around the
                    // pool rather than sitting fixed, as if catching a moving light source, and
                    // flares slightly on a transient (a beat catching the light off a fresh spike).
                    clipPath(path) {
                        val glowAngle = t * 0.15f + seed
                        val glowCx = cx + poolRadius * 1.1f * cos(glowAngle)
                        val glowCy = cy + poolRadius * 1.1f * sin(glowAngle) - poolRadius * 0.6f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.35f + transient * 0.25f),
                                    Color.White.copy(alpha = 0f),
                                ),
                                center = Offset(glowCx, glowCy),
                                radius = poolRadius * 2.2f,
                            ),
                            radius = poolRadius * 2.2f,
                            center = Offset(glowCx, glowCy),
                        )
                    }
                }
        )
    }
}
