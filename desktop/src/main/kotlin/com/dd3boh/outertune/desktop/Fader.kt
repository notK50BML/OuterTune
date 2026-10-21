/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A fader's channel, as the phone draws it - see the Android PowerampSlider these are ported from.
 *
 * The desktop's bands were a rotated Material slider: a six-pixel line with a round thumb on it.
 * That is a correct control and it does not look like an equaliser, because the thing it is missing
 * is the thing a fader actually is - a channel cut into a panel, with a cap riding in it. A line and
 * a dot read as a setting; a channel and a cap read as a mixing desk, which is what twelve of them
 * in a row are meant to be.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FaderTrack(
    sliderState: SliderState,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    trackThickness: Dp = 8.dp,
    /**
     * Whether the fill grows from the middle of the range rather than from its start.
     *
     * True for anything bipolar - an equaliser band - where the middle is "no change" rather than
     * "none". Filling those from the end makes a flat band look half full, which reads as half of
     * something; from the centre, a flat band is an empty channel, a boost grows one way and a cut
     * the other, and a row of twelve shows the shape of the curve without anyone reading a number.
     */
    centerOrigin: Boolean = false,
    /**
     * Faint marks along the channel, at this many divisions. Zero draws none.
     *
     * Texture rather than a scale: they give the eye something to judge a position against, the way
     * the detents on a physical fader do. A channel with numbers on it would be a chart.
     */
    detents: Int = 0,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(trackThickness)) {
        val range = sliderState.valueRange
        val span = range.endInclusive - range.start
        val fraction = if (span == 0f) 0f else ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
        val cornerRadius = CornerRadius(size.height / 2f)

        drawRoundRect(color = inactiveColor, size = size, cornerRadius = cornerRadius)

        if (detents > 1) {
            for (i in 1 until detents) {
                val x = size.width * i / detents
                drawLine(
                    color = activeColor.copy(alpha = 0.18f),
                    start = Offset(x, size.height * 0.2f),
                    end = Offset(x, size.height * 0.8f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        if (centerOrigin) {
            // A plain rect rather than a rounded one: only the outer end of the fill ever reaches
            // the edge of the channel, and rounding the end that meets the centre would put a
            // visible notch there at every value.
            val centre = size.width / 2f
            val head = size.width * fraction
            val width = kotlin.math.abs(head - centre)
            if (width > 0.5f) {
                drawRect(
                    color = activeColor,
                    topLeft = Offset(minOf(centre, head), 0f),
                    size = Size(width, size.height),
                )
            }
            // Always drawn, so "no change" is a position rather than an absence of fill - which
            // would otherwise look the same as a fader nobody has touched.
            drawLine(
                color = activeColor.copy(alpha = 0.5f),
                start = Offset(centre, 0f),
                end = Offset(centre, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        } else if (fraction > 0f) {
            drawRoundRect(
                color = activeColor,
                size = Size(size.width * fraction, size.height),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/**
 * The cap that rides in the channel.
 *
 * Sized tall and narrow because the slider it sits on is rotated a quarter turn, so these dimensions
 * arrive on screen the other way round - a wide, flat cap across a vertical fader, which is what one
 * looks like. The grip line is what stops it reading as a plain capsule.
 */
@Composable
fun FaderCap(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 8.dp,
    height: Dp = 28.dp,
) {
    Canvas(modifier = modifier.size(width = width, height = height)) {
        drawRoundRect(
            color = color,
            size = size,
            cornerRadius = CornerRadius(size.minDimension / 2f),
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.28f),
            start = Offset(size.width * 0.5f, size.height * 0.28f),
            end = Offset(size.width * 0.5f, size.height * 0.72f),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}
