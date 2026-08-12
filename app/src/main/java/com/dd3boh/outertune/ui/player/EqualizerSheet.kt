/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.EqSliderStyleKey
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.ui.component.PlayerSliderTrack
import com.dd3boh.outertune.ui.component.VerticalSlider
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A 12-band parametric equalizer, backed by [com.dd3boh.outertune.audio.EqualizerAudioProcessor]
 * in the playback audio pipeline.
 *
 * The gain sliders are the everyday view; tapping a band opens an advanced editor underneath for
 * its frequency, Q and filter type - a graphic EQ most of the time, a parametric one when a band
 * needs it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return

    var settingsJson by rememberPreference(EqualizerSettingsKey, "")
    var eqSliderStyle by rememberEnumPreference(EqSliderStyleKey, DEFAULT_SLIDER_STYLE)

    var settings by remember {
        mutableStateOf(EqualizerSettings.parse(settingsJson).getOrDefault(EqualizerSettings.DEFAULT))
    }

    fun update(newSettings: EqualizerSettings) {
        settings = newSettings
        settingsJson = newSettings.toJson()
        playerConnection.service.equalizerAudioProcessor.setSettings(newSettings)
    }

    fun updateBand(index: Int, band: EqualizerSettings.EqBand) {
        update(settings.copy(bands = settings.bands.toMutableList().apply { this[index] = band }))
    }

    var selectedBand by remember { mutableStateOf<Int?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.equalizer),
                    style = MaterialTheme.typography.titleLarge,
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { update(settings.copy(enabled = it)) },
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EqualizerSettings.PRESETS.entries.toList()) { (name, gains) ->
                    AssistChip(
                        onClick = { update(settings.withPresetGains(gains)) },
                        label = { Text(name) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                settings.bands.forEachIndexed { index, band ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "${if (band.gainDb > 0) "+" else ""}${band.gainDb.roundToInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        VerticalSlider(
                            value = band.gainDb,
                            onValueChange = { updateBand(index, band.copy(gainDb = it)) },
                            valueRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB,
                            enabled = settings.enabled && band.enabled,
                            track = { sliderState ->
                                PlayerSliderTrack(
                                    sliderState = sliderState,
                                    colors = SliderDefaults.colors(),
                                    style = eqSliderStyle,
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .width(36.dp),
                        )
                        Text(
                            text = formatFrequency(band.freqHz),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = stringResource(R.string.equalizer_tap_a_band),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                settings.bands.forEachIndexed { index, band ->
                    AssistChip(
                        onClick = { selectedBand = if (selectedBand == index) null else index },
                        label = { Text(formatFrequency(band.freqHz), maxLines = 1) },
                    )
                }
            }

            selectedBand?.let { index ->
                Spacer(Modifier.height(12.dp))
                BandEditor(
                    band = settings.bands[index],
                    onChange = { updateBand(index, it) },
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Slider style",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SliderStyle.entries.forEachIndexed { i, style ->
                    SegmentedButton(
                        selected = eqSliderStyle == style,
                        onClick = { eqSliderStyle = style },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = SliderStyle.entries.size),
                    ) {
                        Text(style.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandEditor(
    band: EqualizerSettings.EqBand,
    onChange: (EqualizerSettings.EqBand) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${formatFrequency(band.freqHz)} band",
                style = MaterialTheme.typography.titleSmall,
            )
            Switch(
                checked = band.enabled,
                onCheckedChange = { onChange(band.copy(enabled = it)) },
            )
        }

        Spacer(Modifier.height(8.dp))

        LabeledSlider(
            label = "Frequency: ${formatFrequency(band.freqHz)}",
            // A frequency slider has to be logarithmic, not linear - a linear 16Hz-20kHz range
            // would spend 99% of its travel above 2kHz and leave the entire bass end crammed into
            // a couple of pixels. Slide in log-space, convert back to Hz for storage/display.
            value = log10(band.freqHz),
            onValueChange = { onChange(band.copy(freqHz = 10f.pow(it))) },
            valueRange = log10(EqualizerSettings.MIN_FREQ_HZ)..log10(EqualizerSettings.MAX_FREQ_HZ),
        )

        LabeledSlider(
            label = "Q: ${String.format(java.util.Locale.US, "%.2f", band.q)}",
            value = band.q,
            onValueChange = { onChange(band.copy(q = it)) },
            valueRange = EqualizerSettings.MIN_Q..EqualizerSettings.MAX_Q,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Filter type",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            EqualizerSettings.FilterType.entries.forEachIndexed { i, type ->
                SegmentedButton(
                    selected = band.type == type,
                    onClick = { onChange(band.copy(type = type)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = i,
                        count = EqualizerSettings.FilterType.entries.size
                    ),
                ) {
                    Text(
                        text = filterTypeLabel(type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Text(text = label, style = MaterialTheme.typography.labelMedium)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
    )
}

private fun filterTypeLabel(type: EqualizerSettings.FilterType) = when (type) {
    EqualizerSettings.FilterType.PEAKING -> "Peak"
    EqualizerSettings.FilterType.LOW_SHELF -> "Low shelf"
    EqualizerSettings.FilterType.HIGH_SHELF -> "High shelf"
    EqualizerSettings.FilterType.LOW_PASS -> "Low pass"
    EqualizerSettings.FilterType.HIGH_PASS -> "High pass"
}

private fun formatFrequency(freqHz: Float): String {
    if (freqHz < 1000f) return freqHz.roundToInt().toString()
    val kHz = freqHz / 1000f
    val rounded = kHz.roundToInt().toFloat()
    return if (kHz == rounded) "${rounded.roundToInt()}k" else "${String.format(java.util.Locale.US, "%.1f", kHz)}k"
}
