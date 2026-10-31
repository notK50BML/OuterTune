/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DialogCornerRadius
import com.dd3boh.outertune.constants.LyricsOffsetDialKey
import com.dd3boh.outertune.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** The furthest a song's lyrics can be moved either way, in milliseconds. */
const val LYRICS_OFFSET_LIMIT_MS = 3000

/** The big buttons' step: enough to cross a line's worth of lag in a few presses. */
private const val COARSE_STEP_MS = 50

/** The small buttons' step - finer than this is below what anyone can hear. */
private const val FINE_STEP_MS = 10

/** How long a button must be held before it starts repeating, and how fast it repeats. */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_FIRST_MS = 120L
private const val REPEAT_FASTEST_MS = 40L

/**
 * An offset as it is shown: "+250 ms", "−100 ms", "0 ms". A real minus sign rather than a hyphen,
 * so both signs are the same width and the number does not shift as it crosses zero.
 */
fun formatLyricsOffset(ms: Int): String = when {
    ms > 0 -> "+$ms ms"
    ms < 0 -> "−${abs(ms)} ms"
    else -> "0 ms"
}

/** How far one full turn of the dial moves the offset - half a second, so a quarter turn is a beat. */
private const val DIAL_MS_PER_TURN = 500

/** The dial moves in the small buttons' steps, and ticks under the thumb at each big one. */
private const val DIAL_DEGREES_PER_STEP = 360f * FINE_STEP_MS / DIAL_MS_PER_TURN

/**
 * The playing song's own lyrics timing offset, from the lyrics menu, set one of two ways - chosen
 * at the top of the dialog and remembered ([LyricsOffsetDialKey]).
 *
 * **Buttons**, the default, rather than the slider this replaced. A slider across six seconds moves
 * tens of milliseconds for the slightest roll of the thumb, which is exactly the range being tuned.
 * Buttons land on exact values every time, work one-handed and with TalkBack, and holding one down
 * repeats - speeding up as it goes - for the bigger moves. Each step ticks.
 *
 * **Dial**, for those who would rather turn it: dragged round in a circle, clockwise for later, half
 * a second a turn in 10 ms steps, with a tick under the thumb every 50 ms so it can be counted by
 * feel while listening. Not the default because the thumb turning it covers the dial, and a drag
 * round a circle is harder to hold steady one-handed than a press.
 *
 * Every change goes to [onChange] as it happens, so the lyrics move under the dialog while it is
 * being tuned. Cancel puts back what the song had when it opened; Done and tapping outside keep it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsOffsetDialog(
    initialValue: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) = BasicAlertDialog(onDismissRequest = onDismiss) {
    val original = rememberSaveable { initialValue }
    var value by rememberSaveable { mutableIntStateOf(initialValue) }
    val haptic = LocalHapticFeedback.current
    val (useDial, onUseDialChange) = rememberPreference(LyricsOffsetDialKey, defaultValue = false)

    /** [tickEvery] is how far apart the haptic ticks fall: every step for the buttons, 50 ms on the dial. */
    fun set(ms: Int, tickEvery: Int = 1) {
        val next = ms.coerceIn(-LYRICS_OFFSET_LIMIT_MS, LYRICS_OFFSET_LIMIT_MS)
        if (next == value) return
        val crossed = Math.floorDiv(next, tickEvery) != Math.floorDiv(value, tickEvery)
        value = next
        if (crossed) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        onChange(next)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(DialogCornerRadius))
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text(
            stringResource(R.string.lyrics_offset_song_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        // Here, where the control is, rather than tucked away in settings: the choice is easiest
        // to make with both in front of you.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !useDial,
                onClick = { onUseDialChange(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.lyrics_offset_mode_buttons)) }
            SegmentedButton(
                selected = useDial,
                onClick = { onUseDialChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.lyrics_offset_mode_dial)) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            formatLyricsOffset(value),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(
                when {
                    value > 0 -> R.string.lyrics_offset_later
                    value < 0 -> R.string.lyrics_offset_earlier
                    else -> R.string.lyrics_offset_none
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        if (useDial) {
            OffsetDial(value, onTurn = { set(it, tickEvery = COARSE_STEP_MS) })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val step: (Int) -> Unit = { set(value + it) }
                StepButton(-COARSE_STEP_MS, step, Modifier.weight(1f))
                StepButton(-FINE_STEP_MS, step, Modifier.weight(1f))
                StepButton(FINE_STEP_MS, step, Modifier.weight(1f))
                StepButton(COARSE_STEP_MS, step, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (useDial) R.string.lyrics_offset_dial_hint else R.string.lyrics_offset_hold_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // One tap back to none, on the left where it cannot be hit instead of Done.
            TextButton(onClick = { set(0) }, enabled = value != 0) { Text(stringResource(R.string.reset)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (value != original) onChange(original)
                onDismiss()
            }) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lyrics_offset_done)) }
        }
    }
}

/**
 * One step button: a tap steps once, and holding it steps again and again, faster the longer it is
 * held. Big enough for a thumb - the full width shared four ways, and taller than a standard button.
 *
 * Steps on the press itself rather than the release, so a hold starts from the step the finger
 * already felt. The click that follows a press is then skipped; a click with no press before it -
 * TalkBack's double-tap - still steps once.
 */
@Composable
private fun StepButton(stepMs: Int, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    var steppedOnPress by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        // Released: by now the click that ends the press has been and gone. Cleared here too in
        // case there was none - a finger slid off cancels the click - so the next one still counts.
        if (!pressed) {
            steppedOnPress = false
            return@LaunchedEffect
        }
        steppedOnPress = true
        step(stepMs)
        delay(REPEAT_AFTER_MS)
        var interval = REPEAT_FIRST_MS
        while (true) {
            step(stepMs)
            delay(interval)
            interval = (interval * 9 / 10).coerceAtLeast(REPEAT_FASTEST_MS)
        }
    }
    val label = if (stepMs > 0) "+$stepMs" else "−${abs(stepMs)}"
    val description = stringResource(
        if (stepMs > 0) R.string.lyrics_offset_later_by else R.string.lyrics_offset_earlier_by,
        abs(stepMs),
    )
    FilledTonalButton(
        onClick = { if (steppedOnPress) steppedOnPress = false else step(stepMs) },
        interactionSource = interaction,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .height(64.dp)
            .semantics { contentDescription = description },
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The dial: a ring of ticks that turns with the offset, a fixed mark at the top to read it against,
 * and the whole face as the handle - 240 dp across, so the thumb never has to find it.
 *
 * Read as angles rather than distances: each move is the change in angle about the centre, wrapped
 * so crossing the left-hand side is a small step and not a whole turn back, and what does not yet
 * add up to a step is carried to the next move. Near the centre the angle swings wildly for the
 * slightest movement, so a small circle there does nothing.
 */
@Composable
private fun OffsetDial(valueMs: Int, onTurn: (Int) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(valueMs)
    val turn by rememberUpdatedState(onTurn)
    val face = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.lyrics_offset_dial_description, formatLyricsOffset(valueMs))
    Canvas(
        modifier = modifier
            .size(240.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val deadZone = 28.dp.toPx()
                var last: Float? = null
                var carried = 0f
                var at = 0
                detectDragGestures(
                    onDragStart = { last = null; carried = 0f; at = current },
                    onDragEnd = { last = null },
                    onDragCancel = { last = null },
                ) { change, _ ->
                    change.consume()
                    val fromCentre = change.position - Offset(size.width / 2f, size.height / 2f)
                    if (fromCentre.getDistance() < deadZone) {
                        last = null
                        return@detectDragGestures
                    }
                    // Screen y runs down, so a growing angle is clockwise - later.
                    val angle = Math.toDegrees(atan2(fromCentre.y, fromCentre.x).toDouble()).toFloat()
                    val previous = last
                    last = angle
                    if (previous == null) return@detectDragGestures
                    var moved = angle - previous
                    if (moved > 180f) moved -= 360f
                    if (moved < -180f) moved += 360f
                    carried += moved
                    val steps = (carried / DIAL_DEGREES_PER_STEP).toInt()
                    if (steps == 0) return@detectDragGestures
                    carried -= steps * DIAL_DEGREES_PER_STEP
                    at = (at + steps * FINE_STEP_MS).coerceIn(-LYRICS_OFFSET_LIMIT_MS, LYRICS_OFFSET_LIMIT_MS)
                    turn(at)
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(face, radius)
        // The ring turns with the value: one tick per 10 ms step, a longer one every 50 ms.
        rotate(valueMs * 360f / DIAL_MS_PER_TURN) {
            val steps = DIAL_MS_PER_TURN / FINE_STEP_MS
            for (i in 0 until steps) {
                val major = i % (COARSE_STEP_MS / FINE_STEP_MS) == 0
                val angle = Math.toRadians((i * 360.0 / steps) - 90.0)
                val outer = radius - 10.dp.toPx()
                val inner = outer - (if (major) 18.dp else 8.dp).toPx()
                val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                drawLine(
                    color = tick.copy(alpha = if (major) 0.9f else 0.45f),
                    start = center + direction * inner,
                    end = center + direction * outer,
                    strokeWidth = (if (major) 3.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // A grip near the rim, so the turning shows even between ticks.
            drawCircle(mark, 7.dp.toPx(), center + Offset(0f, -(radius - 44.dp.toPx())))
        }
        // The fixed mark the ring is read against.
        drawLine(
            color = mark,
            start = center + Offset(0f, -radius),
            end = center + Offset(0f, -(radius - 12.dp.toPx())),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
