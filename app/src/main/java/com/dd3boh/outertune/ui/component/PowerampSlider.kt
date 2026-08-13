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
        if (fraction > 0f) {
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
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .background(color, RoundedCornerShape(50))
    )
}
