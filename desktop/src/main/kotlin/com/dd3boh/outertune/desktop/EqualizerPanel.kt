/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Range the band sliders cover by default.
 *
 * Wider than this, by hand, stops being tone and starts being damage. A fetched AutoEQ correction
 * can legitimately exceed it though - see [rangeFor] - so this is the floor rather than a limit.
 */
private const val MAX_GAIN_DB = 12f

/**
 * The range to draw, given what the bands actually hold.
 *
 * Fixed at twelve, a loaded correction asking for sixteen would show its slider pinned at the top
 * and read as a milder curve than the one being applied. Rounded up to whole decibels so the scale
 * does not shift by a fraction every time a slider moves.
 */
private fun rangeFor(bands: List<EqBand>): Float =
    maxOf(MAX_GAIN_DB, kotlin.math.ceil(bands.maxOfOrNull { kotlin.math.abs(it.gainDb) } ?: 0f))

/**
 * How tall each band slider stands.
 *
 * Long, because this is the control being aimed at and a short slider makes a 1dB adjustment a
 * two-pixel movement. The panel used to give them 130dp and the whole thing read as compressed -
 * twelve stubby sliders under a header, on a screen with room for far more.
 */
private val SLIDER_LENGTH = 210.dp

/**
 * The equaliser: a response curve, a preset row, and one slider per band.
 *
 * The graph sits on top because it answers the question the sliders cannot. Twelve slider positions
 * do not tell you what the filter bank is doing - the bands overlap, so two neighbouring boosts
 * produce one large hump rather than two small ones, and the only way to see that is to draw the
 * combined response. See [EqResponse].
 *
 * Vertical sliders laid out left to right by frequency, because that is the shape every equaliser
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
    timeStretch: TimeStretch? = null,
    compressor: Compressor? = null,
) {
    var enabled by remember { mutableStateOf(equalizer.enabled) }
    var bands by remember { mutableStateOf(equalizer.bands()) }
    var preset by remember { mutableStateOf("Flat") }

    /** Everything that changes a gain does these three things, so they live in one place. */
    fun apply(newBands: List<EqBand>, fromPreset: String) {
        bands = newBands
        preset = fromPreset
        equalizer.setBands(newBands)
        // Changing a curve without turning the equaliser on would do nothing and look broken.
        if (!enabled) {
            enabled = true
            equalizer.enabled = true
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
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
                fontWeight = FontWeight.Bold,
                color = onColour,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { apply(Equalizer.DEFAULT_BANDS, "Flat") }) {
                Text("Reset", color = accent)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Dimmed rather than hidden while off. The curve is still what the equaliser would do, and
        // hiding it would make the panel jump every time the switch is touched.
        Box(modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }) {
            EqGraph(
                bands = bands,
                sampleRate = equalizer.sampleRate,
                onColour = onColour,
                height = 150.dp,
                rangeDb = rangeFor(bands),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        AutoEqSearch(accent = accent, onColour = onColour) { fetched, name ->
            apply(fetched, name)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Scrolls, because there are sixteen of these and wrapping them onto three lines would push
        // the sliders off the bottom of the drawer.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            Equalizer.PRESETS.forEach { (name, values) ->
                FilterChip(
                    selected = preset == name,
                    onClick = {
                        apply(
                            Equalizer.DEFAULT_BANDS.mapIndexed { i, band ->
                                band.copy(gainDb = values.getOrElse(i) { 0f })
                            },
                            name,
                        )
                    },
                    label = { Text(name) },
                )
            }
        }

        if (timeStretch != null) {
            Spacer(modifier = Modifier.height(18.dp))
            PlaybackDials(timeStretch = timeStretch, accent = accent, onColour = onColour)
        }

        if (compressor != null) {
            Spacer(modifier = Modifier.height(18.dp))
            CompressorSection(compressor = compressor, accent = accent, onColour = onColour)
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            bands.forEachIndexed { index, band ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(56.dp),
                ) {
                    Text(
                        // One decimal, because a slider that reads "+3" across a third of its travel
                        // looks broken. The unit is on the value rather than a header, so a glance
                        // at one band is self-contained.
                        text = "%+.1f".format(band.gainDb),
                        style = MaterialTheme.typography.labelSmall,
                        color = onColour.copy(alpha = if (band.gainDb == 0f) 0.45f else 0.85f),
                    )
                    Box(
                        modifier = Modifier.width(48.dp).height(SLIDER_LENGTH),
                        contentAlignment = Alignment.Center,
                    ) {
                        Slider(
                            value = band.gainDb,
                            onValueChange = { value ->
                                apply(
                                    bands.toMutableList().also { it[index] = band.copy(gainDb = value) },
                                    "",
                                )
                            },
                            valueRange = -rangeFor(bands)..rangeFor(bands),
                            colors = SliderDefaults.colors(
                                thumbColor = accent,
                                activeTrackColor = accent,
                            ),
                            modifier = Modifier.rotateVertical(SLIDER_LENGTH),
                        )
                    }
                    Text(
                        // A loaded correction puts bands at 1050Hz or 3300Hz, not at round
                        // numbers, so "3k" would label two different bands identically. One decimal
                        // above a kilohertz keeps them distinguishable without going to four digits.
                        text = when {
                            band.freqHz >= 10_000f -> "${(band.freqHz / 1000f).roundToInt()}k"
                            band.freqHz >= 1000f -> "%.1fk".format(band.freqHz / 1000f)
                            else -> band.freqHz.roundToInt().toString()
                        },
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
 * Search AutoEQ for a headphone correction and load it.
 *
 * A correction curve is not a preset - it is a measurement of one specific pair of headphones, and
 * it is the single most effective thing anyone can do to how their music sounds. Everything else in
 * this panel is taste; this is a known error being cancelled.
 *
 * Search runs after a pause rather than on every keystroke. Each query is up to three GitHub
 * requests, and firing them per character would exhaust an unauthenticated rate limit inside one
 * headphone name - the listings are cached for the session, but the first pass through has to fetch
 * them.
 */
@Composable
private fun AutoEqSearch(
    accent: Color,
    onColour: Color,
    onApply: (List<EqBand>, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AutoEq.Headphone>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.length < 3) {
            results = emptyList()
            failed = false
            return@LaunchedEffect
        }
        // Cancelled and restarted on every keystroke, so only the pause after the last one gets
        // this far.
        delay(400)
        searching = true
        failed = false
        results = runCatching { AutoEq.search(query) }
            .onFailure { failed = true }
            .getOrDefault(emptyList())
        searching = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "AutoEQ",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = onColour,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search headphones", color = onColour.copy(alpha = 0.5f)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = onColour,
                    unfocusedTextColor = onColour,
                    focusedBorderColor = accent,
                    unfocusedBorderColor = onColour.copy(alpha = 0.3f),
                    cursorColor = accent,
                ),
                modifier = Modifier.weight(1f),
            )
        }

        val status = when {
            searching -> "Searching..."
            failed -> "Could not reach AutoEQ. Check the connection and try again."
            query.length in 1..2 -> "Keep typing..."
            query.length >= 3 && results.isEmpty() -> "Nothing found for \"$query\" in oratory1990's measurements."
            else -> null
        }
        if (status != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = onColour.copy(alpha = 0.6f))
        }

        if (results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                // Capped, and the cap is stated rather than silently truncating: a search for "HD"
                // matches most of Sennheiser's catalogue, and a row of two hundred chips is not a
                // list anyone reads.
                results.take(30).forEach { headphone ->
                    AssistChip(
                        onClick = {
                            scope.launch {
                                val curve = AutoEq.fetchCurve(headphone)
                                if (curve != null) onApply(curve, headphone.name) else failed = true
                            }
                        },
                        label = { Text(headphone.name, maxLines = 1) },
                    )
                }
                if (results.size > 30) {
                    Text(
                        text = "+${results.size - 30} more - narrow the search",
                        style = MaterialTheme.typography.bodySmall,
                        color = onColour.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * The compressor: a switch, five dials, and a meter showing what it is doing.
 *
 * All five controls, not a single "amount" slider. A compressor with one knob is guessing at four
 * values on the user's behalf, and the four it has to guess are exactly the ones that decide whether
 * it sounds like control or like pumping. Anyone who does not want them can leave the switch off.
 *
 * The meter is the part that makes the rest legible. Threshold and ratio describe a rule; gain
 * reduction is the rule's actual effect on this track, second by second, and without it the dials
 * are set by reading numbers rather than by listening.
 */
@Composable
private fun CompressorSection(compressor: Compressor, accent: Color, onColour: Color) {
    var enabled by remember { mutableStateOf(compressor.enabled) }
    var threshold by remember { mutableStateOf(compressor.thresholdDb) }
    var ratio by remember { mutableStateOf(compressor.ratio) }
    var attack by remember { mutableStateOf(compressor.attackMs) }
    var release by remember { mutableStateOf(compressor.releaseMs) }
    var makeup by remember { mutableStateOf(compressor.makeupGainDb) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    compressor.enabled = it
                },
            )
            Text(
                text = "Compressor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onColour,
                modifier = Modifier.weight(1f),
            )
            GainReductionMeter(compressor = compressor, accent = accent, onColour = onColour)
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Dial(
                    label = "Threshold",
                    value = threshold,
                    valueRange = Compressor.MIN_THRESHOLD_DB..Compressor.MAX_THRESHOLD_DB,
                    onValueChange = { threshold = it; compressor.thresholdDb = it },
                    step = 0.5f,
                    coarseStep = 5f,
                    default = Compressor.DEFAULT_THRESHOLD_DB,
                    readout = { "%.0f dB".format(it) },
                    accent = accent,
                    onColour = onColour,
                    size = 64.dp,
                )
                Dial(
                    label = "Ratio",
                    value = ratio,
                    valueRange = Compressor.MIN_RATIO..Compressor.MAX_RATIO,
                    onValueChange = { ratio = it; compressor.ratio = it },
                    step = 0.1f,
                    coarseStep = 1f,
                    default = Compressor.DEFAULT_RATIO,
                    readout = { "%.1f:1".format(it) },
                    accent = accent,
                    onColour = onColour,
                    size = 64.dp,
                )
                Dial(
                    label = "Attack",
                    value = attack,
                    valueRange = Compressor.MIN_ATTACK_MS..Compressor.MAX_ATTACK_MS,
                    onValueChange = { attack = it; compressor.attackMs = it },
                    step = 0.5f,
                    coarseStep = 10f,
                    default = Compressor.DEFAULT_ATTACK_MS,
                    // Sub-millisecond attacks are a real setting, and "0 ms" would read as off.
                    readout = { if (it < 10f) "%.1f ms".format(it) else "%.0f ms".format(it) },
                    accent = accent,
                    onColour = onColour,
                    size = 64.dp,
                )
                Dial(
                    label = "Release",
                    value = release,
                    valueRange = Compressor.MIN_RELEASE_MS..Compressor.MAX_RELEASE_MS,
                    onValueChange = { release = it; compressor.releaseMs = it },
                    step = 5f,
                    coarseStep = 50f,
                    default = Compressor.DEFAULT_RELEASE_MS,
                    readout = { "%.0f ms".format(it) },
                    accent = accent,
                    onColour = onColour,
                    size = 64.dp,
                )
                Dial(
                    label = "Makeup",
                    value = makeup,
                    valueRange = Compressor.MIN_MAKEUP_DB..Compressor.MAX_MAKEUP_DB,
                    onValueChange = { makeup = it; compressor.makeupGainDb = it },
                    step = 0.25f,
                    coarseStep = 3f,
                    default = Compressor.DEFAULT_MAKEUP_DB,
                    readout = { "+%.1f dB".format(it) },
                    accent = accent,
                    onColour = onColour,
                    size = 64.dp,
                )
            }
        }
    }
}

/**
 * How much the compressor is pulling the signal down, right now.
 *
 * Polled on the frame clock rather than driven by the audio thread. The alternative is for the audio
 * thread to write into composition state, which would make it responsible for scheduling
 * recomposition - work on a thread with a hard deadline, in service of something the eye cannot
 * resolve faster than this anyway.
 *
 * Falls back rather than snapping: gain reduction is spiky, and a meter that tracks it exactly reads
 * as a flicker instead of a level. Rising is instant, because a peak that is not shown at its full
 * height has not been shown.
 */
@Composable
private fun GainReductionMeter(compressor: Compressor, accent: Color, onColour: Color) {
    var shown by remember { mutableStateOf(0f) }
    LaunchedEffect(compressor) {
        while (true) {
            withFrameMillis {
                val now = -compressor.currentReductionDb
                shown = if (now > shown) now else shown + (now - shown) * 0.25f
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .width(110.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(onColour.copy(alpha = 0.15f)),
        ) {
            Box(
                modifier = Modifier
                    // Full scale at 24dB of reduction, which is more than any musical setting
                    // reaches - so a meter near the end means something is set wrong.
                    .fillMaxWidth((shown / 24f).coerceIn(0f, 1f))
                    .height(8.dp)
                    .background(accent),
            )
        }
        Text(
            text = "-%.1f dB".format(shown),
            style = MaterialTheme.typography.labelSmall,
            color = onColour.copy(alpha = 0.7f),
        )
    }
}

/**
 * Tempo and pitch, as two knobs.
 *
 * Separate controls rather than one speed setting, because they are separate things once the audio
 * is being time-stretched rather than merely played faster - see [TimeStretch]. Practising along
 * with a track wants tempo alone; singing along with one written out of range wants pitch alone.
 *
 * Both read out in the units people think in: percent for tempo, semitones for pitch. Semitones with
 * one decimal, because a tenth of a semitone is roughly where a shift stops being audible as a
 * retune and starts being audible as being slightly out.
 */
@Composable
private fun PlaybackDials(timeStretch: TimeStretch, accent: Color, onColour: Color) {
    // Mirrored into composition state for the same reason the gains are: the audio thread reads the
    // TimeStretch continuously, and having the UI poll it would put two threads on the same field
    // for no benefit.
    var tempo by remember { mutableStateOf(timeStretch.tempo) }
    var pitch by remember { mutableStateOf(timeStretch.pitchSemitones) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Dial(
            label = "Tempo",
            value = tempo,
            valueRange = TimeStretch.MIN_TEMPO..TimeStretch.MAX_TEMPO,
            onValueChange = {
                tempo = it
                timeStretch.tempo = it
            },
            // Half a percent a notch. The fine adjustment this control exists for is the couple of
            // percent that brings a track into step with something else, and a dial that moves in
            // five percent jumps cannot express it.
            step = 0.005f,
            coarseStep = 0.05f,
            default = 1f,
            readout = { "%.0f%%".format(it * 100) },
            accent = accent,
            onColour = onColour,
        )
        Dial(
            label = "Pitch",
            value = pitch,
            valueRange = TimeStretch.MIN_SEMITONES..TimeStretch.MAX_SEMITONES,
            onValueChange = {
                pitch = it
                timeStretch.pitchSemitones = it
            },
            step = 0.1f,
            coarseStep = 1f,
            default = 0f,
            readout = { "%+.1f st".format(it) },
            accent = accent,
            onColour = onColour,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scroll to turn, shift to move faster, double-click to reset.",
                style = MaterialTheme.typography.bodySmall,
                color = onColour.copy(alpha = 0.6f),
            )
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
