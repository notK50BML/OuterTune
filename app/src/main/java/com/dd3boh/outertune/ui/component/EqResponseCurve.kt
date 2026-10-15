/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.audio.BiquadCoefficients
import com.dd3boh.outertune.models.EqualizerSettings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The equaliser's combined response, drawn.
 *
 * It answers the question twelve slider positions cannot. The bands overlap, so two neighbouring
 * boosts do not produce two bumps - they produce one larger one, and the only way to see that is to
 * draw the sum. It is also the difference between a panel that looks like a row of faders and one
 * that looks like an equaliser, which is the other half of why this exists.
 *
 * **The curve is the real filter bank, not a drawing of one.** Every point is
 * [BiquadCoefficients.cascadeDb] over the coefficients [BiquadCoefficients.forBand] produces - the
 * same function, from the same inputs, that the audio thread runs on every sample. The alternative,
 * re-deriving the cookbook formulae for display, gives a graph that is correct until one of the two
 * copies is edited and then silently graphs a filter the app is not applying. (The desktop does it
 * that way and says so in its own doc; this is the better half of the two.)
 *
 * Shapes with no gain are included for the same reason: a low-pass has no gainDb at all, so a curve
 * that skipped zero-gain bands would draw a flat line over a filter that is removing half the
 * spectrum.
 */
@Composable
fun EqResponseCurve(
    bands: List<EqualizerSettings.EqBand>,
    sampleRate: Int,
    lineColor: Color,
    fillColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    // Recomputed only when the curve could have changed. Each point is a cascade over every band,
    // so this is the one part worth not doing per frame - and it does not need to be: the gains
    // change when a finger moves, not when the screen refreshes.
    val curve = remember(bands, sampleRate) { responseCurve(bands, sampleRate) }
    val range = remember(curve) { rangeFor(curve) }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (curve.isEmpty() || range <= 0f) return@Canvas

        fun yFor(db: Float): Float = size.height / 2f - (db / range) * (size.height / 2f)

        drawGrid(gridColor, range, ::yFor)

        val path = Path()
        val fill = Path()
        curve.forEachIndexed { index, db ->
            val x = size.width * index / (curve.size - 1f)
            val y = yFor(db).coerceIn(0f, size.height)
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height / 2f)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        // Closed back along the zero line rather than the bottom edge, so a cut fills downward from
        // zero and a boost upward. Filling to the floor would draw a cut as a tall block, which
        // reads as more of something rather than less.
        fill.lineTo(size.width, size.height / 2f)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                colors = listOf(fillColor.copy(alpha = 0.35f), fillColor.copy(alpha = 0.04f)),
            ),
        )
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Zero line solid, the half-scale lines dashed, and nothing else - it is a shape, not a chart. */
private fun DrawScope.drawGrid(gridColor: Color, range: Float, yFor: (Float) -> Float) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
    listOf(range / 2f, -range / 2f).forEach { db ->
        val y = yFor(db)
        drawLine(
            color = gridColor.copy(alpha = 0.25f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
        )
    }
    val zero = yFor(0f)
    drawLine(
        color = gridColor.copy(alpha = 0.45f),
        start = Offset(0f, zero),
        end = Offset(size.width, zero),
        strokeWidth = 1.dp.toPx(),
    )
}

/**
 * The cascade's gain in dB at [POINTS] frequencies, log-spaced across the audible band.
 *
 * Log-spaced because that is how it is read and how the bands are placed: linear sampling would
 * spend most of its points above 10kHz, where the curve is usually flat, and almost none across the
 * bass, where it is not.
 */
internal fun responseCurve(
    bands: List<EqualizerSettings.EqBand>,
    sampleRate: Int,
    points: Int = POINTS,
): FloatArray {
    if (sampleRate <= 0 || bands.isEmpty() || points < 2) return FloatArray(0)
    val cascade = Array(bands.size) { BiquadCoefficients.forBand(bands[it], sampleRate) }
    val nyquist = sampleRate / 2f

    val logLow = ln(MIN_HZ)
    val logSpan = ln(MAX_HZ) - logLow
    val out = FloatArray(points)
    for (i in 0 until points) {
        val hz = exp(logLow + logSpan * i / (points - 1f))
        // Above Nyquist there is nothing to say, and the formulae are not defined there. Holding
        // the last value keeps the line continuous instead of dropping it to the floor, which on a
        // 22.05kHz file would otherwise draw a cliff that is not a filter.
        out[i] = if (hz >= nyquist) out.getOrElse(i - 1) { 0f }
        else BiquadCoefficients.cascadeDb(cascade, hz, sampleRate)
    }
    return out
}

/**
 * Half-height of the plot, in dB.
 *
 * Scaled to the curve rather than fixed at the band range, so a gentle correction is visible
 * instead of being a flat line across the middle of a +/-15dB plot. Rounded up to a whole number of
 * decibels and floored at [MIN_RANGE_DB] so the axis does not twitch as a slider moves, and so a
 * flat curve has a sensible scale rather than an infinitely magnified one.
 */
internal fun rangeFor(curve: FloatArray): Float {
    val peak = curve.maxOfOrNull { abs(it) } ?: 0f
    return max(ceil(peak.toDouble()).toFloat(), MIN_RANGE_DB)
}

private const val MIN_HZ = 20f
private const val MAX_HZ = 20_000f
private const val POINTS = 192
private const val MIN_RANGE_DB = 6f
