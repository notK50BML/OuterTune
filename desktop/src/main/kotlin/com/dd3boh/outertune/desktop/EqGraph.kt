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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The equaliser's response, drawn.
 *
 * Coloured by height, not by frequency. A point sitting high on the curve is blue, one sitting low
 * is yellow, through green at the zero line - the same rule and the same three colours the dials and
 * band sliders use, so "blue means turned up" means one thing everywhere in the panel. The gradient
 * runs down the canvas rather than across it, which is what makes a boost read as blue wherever in
 * the spectrum it happens to be; a horizontal gradient would have coloured by frequency instead and
 * said nothing at all about the setting.
 *
 * The curve is the filter bank's real combined response - see [EqResponse] - so it shows what
 * overlapping bands actually do to each other rather than tracing the slider tops.
 */
@Composable
fun EqGraph(
    bands: List<EqBand>,
    sampleRate: Int,
    onColour: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    rangeDb: Float = 15f,
) {
    // Recomputed only when the bands or the format change. It is a hundred and sixty evaluations of
    // a transfer function, which is nothing once but wasteful on every frame of a drag.
    val curve = remember(bands, sampleRate) { EqResponse.curve(bands, sampleRate) }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (curve.isEmpty()) return@Canvas

        val midY = size.height / 2f
        fun yFor(db: Float) = midY - (db / rangeDb).coerceIn(-1f, 1f) * (size.height / 2f - 4f)

        // The zero line, so a flat response is visibly flat rather than merely central.
        drawLine(
            color = onColour.copy(alpha = 0.25f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )

        // Octave gridlines. Placed by frequency rather than evenly, which is what makes the spacing
        // itself readable as a log axis.
        listOf(100.0, 1_000.0, 10_000.0).forEach { hz ->
            val fraction = (Math.log(hz / EqResponse.MIN_HZ) /
                Math.log(EqResponse.MAX_HZ / EqResponse.MIN_HZ)).toFloat()
            val x = fraction * size.width
            drawLine(
                color = onColour.copy(alpha = 0.12f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
        }

        val path = Path()
        val fill = Path()
        curve.forEachIndexed { index, db ->
            val x = index.toFloat() / (curve.size - 1) * size.width
            val y = yFor(db)
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, midY)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, midY)
        fill.close()

        // Anchored to the plotted range rather than to the canvas, so a point at +6dB is the same
        // colour whether or not some other band happens to be pushing the range wider.
        val byHeight = Brush.verticalGradient(
            0f to ValueGradient.HIGH,
            0.5f to ValueGradient.MID,
            1f to ValueGradient.LOW,
            startY = yFor(rangeDb),
            endY = yFor(-rangeDb),
        )

        // Filled to the zero line as well as stroked. The area is what carries the shape at a
        // glance; the stroke is what makes it precise.
        drawPath(path = fill, brush = byHeight, alpha = 0.18f)
        drawPath(path = path, brush = byHeight, style = Stroke(width = 2.5f))
    }
}
