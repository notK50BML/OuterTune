/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Range each band may be moved. Wider than this stops being tone and starts being damage. */
private const val MAX_GAIN_DB = 12f

/** How tall each band slider stands. */
private val SLIDER_LENGTH = 130.dp

/**
 * Twelve sliders and a row of presets.
 *
 * Vertical sliders, laid out left to right by frequency, because that is the shape every equaliser
 * has had for fifty years and the shape people read without being told. A column of horizontal
 * sliders would be easier to build and would need a legend.
 *
 * The gains are held here rather than read back from [Equalizer] on every frame: the audio thread
 * reads that object continuously, and having the UI poll it would mean two threads reading and
 * writing the same list for no benefit. This owns the values and pushes them down.
 */
@Composable
fun EqualizerPanel(
    equalizer: Equalizer,
    accent: Color,
    onColour: Color,
    modifier: Modifier = Modifier,
) {
    var enabled by remember { mutableStateOf(equalizer.enabled) }
    var gains by remember { mutableStateOf(equalizer.bands().map { it.gainDb }) }
    var preset by remember { mutableStateOf("Flat") }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    equalizer.enabled = it
                },
            )
            Text(
                text = "Equaliser",
                style = MaterialTheme.typography.titleMedium,
                color = onColour,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Equalizer.PRESETS.forEach { (name, values) ->
                FilterChip(
                    selected = preset == name,
                    onClick = {
                        preset = name
                        gains = values
                        equalizer.setBands(
                            Equalizer.DEFAULT_BANDS.mapIndexed { i, band ->
                                band.copy(gainDb = values.getOrElse(i) { 0f })
                            }
                        )
                        // Turning a preset on without turning the equaliser on would do nothing and
                        // look broken.
                        if (!enabled) {
                            enabled = true
                            equalizer.enabled = true
                        }
                    },
                    label = { Text(name) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Equalizer.DEFAULT_FREQUENCIES.forEachIndexed { index, hz ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(56.dp),
                ) {
                    Text(
                        text = "%+d".format(gains.getOrElse(index) { 0f }.roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = onColour.copy(alpha = 0.7f),
                    )
                    Box(
                        modifier = Modifier.width(48.dp).height(SLIDER_LENGTH),
                        contentAlignment = Alignment.Center,
                    ) {
                    Slider(
                        value = gains.getOrElse(index) { 0f },
                        onValueChange = { value ->
                            gains = gains.toMutableList().also { it[index] = value }
                            equalizer.setGain(index, value)
                            preset = ""
                            if (!enabled) {
                                enabled = true
                                equalizer.enabled = true
                            }
                        },
                        valueRange = -MAX_GAIN_DB..MAX_GAIN_DB,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                        ),
                        modifier = Modifier.rotateVertical(SLIDER_LENGTH),
                    )
                    }
                    Text(
                        text = if (hz >= 1000f) "${(hz / 1000f).roundToInt()}k" else hz.roundToInt().toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        color = onColour.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * Turns a horizontal slider on its side.
 *
 * A rotated layer rather than a hand-drawn vertical control. Compose has no vertical slider, and
 * drawing one means also handling drag, keyboard focus and accessibility by hand - whereas pointer
 * input is transformed by the layer, so a rotated slider still drags correctly.
 *
 * [requiredWidth] is what makes it work: it lets the slider lay itself out at its full horizontal
 * length while the parent box only reserves the narrow column the rotation actually occupies.
 * Without it the slider would be squeezed into the column's width and then rotated, ending up
 * stubby.
 */
private fun Modifier.rotateVertical(length: Dp): Modifier = this
    .requiredWidth(length)
    .graphicsLayer { rotationZ = -90f }
