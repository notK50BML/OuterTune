/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * A [Slider] rotated into a vertical gain fader: drag up to increase, matching a physical mixer
 * fader or a graphic EQ band rather than a sideways volume bar.
 *
 * Compose has no vertical slider of its own, so this measures the underlying (horizontal) Slider
 * with its width/height constraints swapped, reports its own size back the right way round, then
 * rotates -90 degrees and re-centres it - the standard trick for turning any horizontally-laid-out
 * composable into a vertical one without reimplementing its drag handling.
 *
 * [track] has no default - callers always have an opinion on it here (the equalizer sheet passes
 * [PlayerSliderTrack]), so there is no "plain" case worth guessing a Material3 default track call
 * for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    track: @Composable (SliderState) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        track = track,
        modifier = modifier
            .graphicsLayer { rotationZ = -90f }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = (placeable.height - placeable.width) / 2,
                        y = (placeable.width - placeable.height) / 2,
                    )
                }
            }
    )
}
