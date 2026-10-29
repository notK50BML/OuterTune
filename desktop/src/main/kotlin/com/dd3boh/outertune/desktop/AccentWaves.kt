/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Samsung's media player look: three soft waves above a flat progress line, nearly the same colour,
 * each drifting at its own pace so they cross and part - always moving, never loud.
 *
 * Over the played part of the line, fading in and out at its ends so they rise out of it rather than
 * starting with a cut. [filled] draws them as solid overlapping shapes, as Samsung does; otherwise
 * as lines a shade apart. [energy] (0..1) is how much they swell; [timeSeconds] drives the drift, so
 * a still frame is a moment of it.
 */
fun DrawScope.drawAccentWaves(
    end: Float,
    baseline: Float,
    height: Float,
    colour: Color,
    timeSeconds: Float,
    energy: Float,
    filled: Boolean,
    start: Float = 0f,
) {
    val span = end - start
    if (span < 4f) return
    // Bold enough to read as part of the bar rather than a scribble over it.
    val stroke = 2.4.dp.toPx()
    val reach = height * (0.25f + 0.75f * energy.coerceIn(0f, 1f)) * growIn(span)
    WAVES.forEach { wave ->
        val path = Path()
        var x = 0f
        val step = 2f
        while (x <= span) {
            // Tapered at both ends of the played part, so the wave grows out of the line.
            val taper = sin(PI * (x / span)).toFloat().coerceAtLeast(0f)
            val phase = (x / wave.wavelength.toPx()) * 2 * PI + timeSeconds * wave.speed + wave.offset
            // Above the line only: a wave dipping below it would cross the progress itself.
            val lift = (0.5f + 0.5f * sin(phase).toFloat()) * wave.scale * reach * taper
            val y = baseline - lift
            if (x == 0f) path.moveTo(start + x, y) else path.lineTo(start + x, y)
            x += step
        }
        if (filled) {
            path.lineTo(end, baseline)
            path.lineTo(start, baseline)
            path.close()
            drawPath(path, colour.copy(alpha = wave.fillAlpha))
        } else {
            drawPath(
                path = path,
                color = lerp(colour, wave.tint, wave.tintAmount).copy(alpha = wave.alpha),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Samsung's bar itself: a thick band with a flat bottom and a wavy top, from [start] to [end].
 * Three layers a shade apart, each its own wave, drawn over one another - the first solid, the
 * others translucent above it - so the top edge ripples where they cross. [baseline] is the band's
 * middle; [thickness] its height where the waves are flat; [rise] how far the waves lift its top.
 */
fun DrawScope.drawWaveBand(
    start: Float,
    end: Float,
    baseline: Float,
    thickness: Float,
    rise: Float,
    colour: Color,
    timeSeconds: Float,
    energy: Float,
) {
    val span = end - start
    if (span <= 0f) return
    val radius = thickness / 2
    // Round at both ends: the band runs between the centres of two half-circle caps.
    val left = start + radius
    val right = (end - radius).coerceAtLeast(left)
    val bottom = baseline + radius
    val flatTop = baseline - radius
    val reach = rise * (0.25f + 0.75f * energy.coerceIn(0f, 1f)) * growIn(span)
    val length = right - left
    // The deepest layer first, so the solid band ends up in front and the lighter ones show above it.
    WAVES.withIndex().reversed().forEach { (index, wave) ->
        val shade = if (index == 0) colour else lerp(colour, wave.tint, wave.tintAmount).copy(alpha = 0.45f - index * 0.1f)
        if (length > 1f) {
            val path = Path()
            path.moveTo(left, bottom)
            var x = 0f
            while (x <= length) {
                // Flat at both ends, so the band rises out of its rounded caps and back into them.
                val taper = sin(PI * (x / length)).toFloat().coerceAtLeast(0f)
                val phase = (x / wave.wavelength.toPx()) * 2 * PI + timeSeconds * wave.speed + wave.offset
                val lift = (0.5f + 0.5f * sin(phase).toFloat()) * wave.scale * reach * taper
                path.lineTo(left + x, flatTop - lift)
                x += 2f
            }
            path.lineTo(right, flatTop)
            path.lineTo(right, bottom)
            path.close()
            drawPath(path, shade)
        }
        if (index == 0) {
            drawCircle(shade, radius, Offset(left, baseline))
            drawCircle(shade, radius, Offset(right, baseline))
        }
    }
}

/**
 * How much of their height the waves have, by how long the played part is: none at the very start,
 * all of it from about 160dp on, in a smooth S between. A song a couple of seconds in shows a flat
 * rounded bar, not a tall arc squeezed into a few pixels.
 */
private fun DrawScope.growIn(span: Float): Float {
    val t = (span / 160.dp.toPx()).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** The waves on their own over a flat line - for a snapshot of the look. */
@Composable
fun AccentWaves(
    progress: Float,
    colour: Color,
    timeSeconds: Float,
    energy: Float,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    thin: Boolean = false,
) {
    Canvas(modifier) {
        val end = size.width * progress.coerceIn(0f, 1f)
        if (filled) {
            val thickness = (if (thin) 3.5.dp else 6.dp).toPx()
            val baseline = size.height - thickness / 2
            drawWaveBand(0f, end, baseline, thickness, size.height - thickness, colour, timeSeconds, energy)
        } else {
            val stroke = (if (thin) 1.8.dp else 3.dp).toPx()
            val baseline = size.height - stroke
            drawAccentWaves(end, baseline, size.height - stroke * 2, colour, timeSeconds, energy, filled = false)
            drawLine(colour.copy(alpha = 0.9f), Offset(0f, baseline), Offset(end, baseline), stroke, StrokeCap.Round)
        }
    }
}

private class Wave(
    val wavelength: Dp,
    /** Radians a second: each its own, so they drift apart and back together. */
    val speed: Float,
    val offset: Double,
    val scale: Float,
    val alpha: Float,
    /** As a solid shape: low, so where they overlap reads as deeper rather than as a new colour. */
    val fillAlpha: Float,
    val tint: Color,
    val tintAmount: Float,
)

/** Three, a shade apart: one towards white, one towards the accent's own depth, one in between. */
private val WAVES = listOf(
    Wave(wavelength = 120.dp, speed = 1.9f, offset = 0.0, scale = 1.00f, alpha = 0.55f, fillAlpha = 0.20f, tint = Color.White, tintAmount = 0.18f),
    Wave(wavelength = 86.dp, speed = -1.3f, offset = 2.1, scale = 0.78f, alpha = 0.40f, fillAlpha = 0.16f, tint = Color.White, tintAmount = 0.05f),
    Wave(wavelength = 155.dp, speed = 1.1f, offset = 4.0, scale = 0.62f, alpha = 0.30f, fillAlpha = 0.13f, tint = Color.Black, tintAmount = 0.12f),
)
