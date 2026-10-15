/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
 * A rounded-edge rectangular channel with a flat handle, in the style of Poweramp's own EQ
 * sliders - a deliberate departure from Material3's default thin line + circular thumb, closer
 * to what a physical mixer fader looks like. [PowerampTrack] draws the channel and its filled
 * portion; [PowerampThumb] is the flat handle riding on top of it, supplied to [VerticalSlider]/
 * [androidx.compose.material3.Slider]'s own `thumb` slot so Compose keeps positioning it.
 *
 * The thumb's own width/height look swapped at first glance - that's intentional. [VerticalSlider]
 * builds this from a horizontal [androidx.compose.material3.Slider] and rotates the whole thing
 * -90 degrees, so a handle that should read as a wide, flat bar *after* rotation has to be tall
 * and narrow *before* it. The same dimensions work unrotated too, where they already read as a
 * narrow vertical bar sliding along a horizontal track - which is what a flat handle looks like
 * there as well.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerampTrack(
    sliderState: SliderState,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    trackThickness: Dp = 10.dp,
    /**
     * Whether the fill grows from the middle of the range rather than from its start.
     *
     * True for anything bipolar - an equaliser band, a balance control - where the middle is "no
     * change" rather than "none". Filling those from the end makes a flat band look half full,
     * which reads as half of something; from the centre, a flat band is an empty channel, a boost
     * grows upward and a cut grows downward, and a row of twelve of them shows the shape of the
     * curve without anyone reading a number.
     *
     * False for the one-directional ones - a compressor's ratio, a tempo - where the start of the
     * range really is the absence of the thing.
     */
    centerOrigin: Boolean = false,
    /**
     * Faint marks along the channel, at this many divisions. Zero draws none.
     *
     * They are there to give the eye something to judge position against, the way the detents on a
     * physical fader do. Drawn under the fill and at low alpha, so they are texture rather than a
     * scale - a channel with numbers on it would be a chart.
     */
    detents: Int = 0,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackThickness)
    ) {
        val range = sliderState.valueRange
        val fraction = ((sliderState.value - range.start) / (range.endInclusive - range.start))
            .coerceIn(0f, 1f)
        val cornerRadius = CornerRadius(size.height / 2f)

        drawRoundRect(
            color = inactiveColor,
            size = size,
            cornerRadius = cornerRadius,
        )

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
            // Between the centre and the thumb, in whichever direction that is. Drawn as a plain
            // rect rather than a rounded one: only the outer end of the fill is ever at the edge
            // of the channel, and rounding the end that meets the centre line puts a visible notch
            // there at every value.
            val centre = size.width / 2f
            val head = size.width * fraction
            val left = minOf(centre, head)
            val width = kotlin.math.abs(head - centre)
            if (width > 0.5f) {
                drawRect(
                    color = activeColor,
                    topLeft = Offset(left, 0f),
                    size = Size(width, size.height),
                )
            }
            // The centre itself, always visible, so "no change" is a position rather than an
            // absence of fill that could equally be a slider nobody has touched.
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

@Composable
fun PowerampThumb(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 8.dp,
    height: Dp = 28.dp,
) {
    // A line across the middle, like the moulded grip on a real fader cap. One line, because the
    // handle is eight device-independent pixels wide and anything more becomes a smudge - but it
    // is enough to say which way the handle faces and to stop it reading as a plain capsule.
    Canvas(
        modifier = modifier.size(width = width, height = height)
    ) {
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
