/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.EqSliderStyleKey
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.SliderStyle
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.ui.component.PlayerSliderTrack
import com.dd3boh.outertune.ui.component.VerticalSlider
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Width given to each band's column in the horizontally-scrolling strip, Poweramp-style. */
private val BandColumnWidth = 56.dp

/**
 * A 12-band parametric equalizer, backed by [com.dd3boh.outertune.audio.EqualizerAudioProcessor]
 * in the playback audio pipeline.
 *
 * Full-screen rather than a sheet, opened from the pull-down handle above the cover art or the
 * button beside the like button (both reading [LocalEqualizerPanelState], which is what makes this
 * survive a track change - the state lives above the player content, not inside the transient
 * 3-dot menu popup the first version of this was nested in). The gain strip is the everyday view;
 * tapping a band's frequency label opens an advanced editor underneath for its frequency, Q and
 * filter type - a graphic EQ most of the time, a parametric one when a band needs it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerPanel() {
    val panelState = LocalEqualizerPanelState.current

    AnimatedVisibility(
        visible = panelState.visible,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
    ) {
        EqualizerPanelContent(onDismiss = { panelState.visible = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerPanelContent(onDismiss: () -> Unit) {
    val playerConnection = LocalPlayerConnection.current ?: return

    BackHandler(onBack = onDismiss)

    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }
    val showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    var settingsJson by rememberPreference(EqualizerSettingsKey, "")
    // Squiggly's wave animation is driven by playback progress, which a gain slider has no
    // equivalent of, so it doesn't actually animate here - just renders as a plain line but
    // implies motion that never comes. Not offered as a choice, and coerced away if an older
    // build already saved it.
    var eqSliderStyleRaw by rememberEnumPreference(EqSliderStyleKey, SliderStyle.DEFAULT)
    val eqSliderStyle = if (eqSliderStyleRaw == SliderStyle.SQUIGGLY) SliderStyle.DEFAULT else eqSliderStyleRaw

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

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBackground(
            playerConnection = playerConnection,
            playerBackground = playerBackground,
            showLyrics = showLyrics,
            useDarkTheme = useDarkTheme,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            EqualizerPanelHandle(onDismiss = onDismiss)

            Column(
                modifier = Modifier
                    .fillMaxSize()
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

                // A plain Row of 12 columns squeezes every band into whatever width the screen
                // has; a LazyRow at a fixed per-band width instead gives each one real room to
                // drag in and lets the strip run wider than the screen, Poweramp-style, with the
                // rest reached by scrolling sideways.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    itemsIndexed(settings.bands) { index, band ->
                        BandColumn(
                            band = band,
                            enabled = settings.enabled,
                            selected = selectedBand == index,
                            sliderStyle = eqSliderStyle,
                            onGainChange = { updateBand(index, band.copy(gainDb = it)) },
                            onTapLabel = { selectedBand = if (selectedBand == index) null else index },
                        )
                    }
                }

                selectedBand?.let { index ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    BandEditor(
                        band = settings.bands[index],
                        onChange = { updateBand(index, it) },
                    )
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.equalizer_balance),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.balance,
                    onValueChange = { update(settings.copy(balance = it)) },
                    valueRange = -1f..1f,
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.equalizer_slider_style),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val eqSliderStyles = listOf(SliderStyle.DEFAULT, SliderStyle.SLIM)
                    eqSliderStyles.forEachIndexed { i, style ->
                        SegmentedButton(
                            selected = eqSliderStyle == style,
                            onClick = { eqSliderStyleRaw = style },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = eqSliderStyles.size),
                        ) {
                            Text(style.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                MiniPlaybackControls()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The handle this panel is opened from also lives at its top while it's open, dragged the other
 * way (up) to close it - the same affordance, reversed, rather than a separate close button doing
 * a different gesture for the same idea. A close icon sits beside it for anyone who'd rather tap.
 */
@Composable
private fun EqualizerPanelHandle(onDismiss: () -> Unit) {
    var dragAccumulatorPx by remember { mutableFloatStateOf(0f) }
    val closeThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onDismiss)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAccumulatorPx = 0f },
                    onDragCancel = { dragAccumulatorPx = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulatorPx += dragAmount
                        if (dragAccumulatorPx < -closeThresholdPx) {
                            onDismiss()
                            dragAccumulatorPx = 0f
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(32.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    RoundedCornerShape(2.dp)
                )
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandColumn(
    band: EqualizerSettings.EqBand,
    enabled: Boolean,
    selected: Boolean,
    sliderStyle: SliderStyle,
    onGainChange: (Float) -> Unit,
    onTapLabel: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(BandColumnWidth)
    ) {
        Text(
            text = "${if (band.gainDb > 0) "+" else ""}${band.gainDb.roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        VerticalSlider(
            value = band.gainDb,
            onValueChange = onGainChange,
            valueRange = EqualizerSettings.MIN_GAIN_DB..EqualizerSettings.MAX_GAIN_DB,
            enabled = enabled && band.enabled,
            track = { sliderState ->
                PlayerSliderTrack(
                    sliderState = sliderState,
                    colors = SliderDefaults.colors(),
                    style = sliderStyle,
                )
            },
            modifier = Modifier
                .weight(1f)
                .width(36.dp),
        )
        Text(
            text = formatFrequency(band.freqHz),
            style = if (selected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable(onClick = onTapLabel)
        )
    }
}

/**
 * Basic transport so the panel doesn't have to be closed just to skip a track or pause - it fills
 * the screen the way the queue does, so without this there'd be no way to touch playback at all
 * while it's open.
 */
@Composable
private fun MiniPlaybackControls() {
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isPlaying.collectAsState()

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = { playerConnection.player.seekToPrevious() }) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = null)
        }
        IconButton(onClick = { playerConnection.player.togglePlayPause() }) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
            )
        }
        IconButton(onClick = { playerConnection.player.seekToNext() }) {
            Icon(Icons.Rounded.SkipNext, contentDescription = null)
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
