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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** The furthest a song's lyrics can be moved either way, in milliseconds. */
const val LYRICS_OFFSET_LIMIT_MS = 3000

/** One wheel notch over the offset control. Enough to cross a whole line in a few notches. */
const val LYRICS_OFFSET_STEP_MS = 50

/** One wheel notch with Shift held - for the last bit of tuning, by ear, while the line is sung. */
const val LYRICS_OFFSET_FINE_STEP_MS = 10

/** Marks [LyricsOffsetControl] as having nothing in flight - no offset is this far out. */
private const val NOT_ASKED = Int.MIN_VALUE

/**
 * An offset as it is shown: "+250 ms", "−100 ms", "0 ms". A real minus sign rather than a hyphen,
 * so the two signs are the same width and the number does not jump about as it crosses zero.
 */
fun formatLyricsOffset(ms: Int): String = when {
    ms > 0 -> "+$ms ms"
    ms < 0 -> "−${abs(ms)} ms"
    else -> "0 ms"
}

/**
 * Turns scroll deltas into whole steps.
 *
 * A mouse wheel reports a whole 1 per notch, but a touchpad reports a stream of small fractions,
 * and rounding each of those would either never move or jump a step on every twitch. So they are
 * added up, and a step is taken each time the total passes a whole notch - the remainder carried
 * into the next scroll.
 */
class OffsetWheel {
    private var pending = 0f

    /** Whole notches in [delta] plus whatever was carried over; up (negative) counts as positive. */
    fun notches(delta: Float): Int {
        pending -= delta
        val whole = pending.toInt()
        pending -= whole
        return whole
    }
}

/**
 * The offset, its readout, and the ways to change it - the slider for a big move, the buttons and
 * the wheel for small ones. Every change goes straight to [onChange], so the lyrics move while it is
 * being tuned rather than when a dialog is closed.
 *
 * Over the control, each wheel notch is [LYRICS_OFFSET_STEP_MS], or [LYRICS_OFFSET_FINE_STEP_MS]
 * with Shift held. Shift-wheel arrives sideways on Windows, so either axis is read.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LyricsOffsetControl(valueMs: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(valueMs)
    val change by rememberUpdatedState(onChange)
    val wheel = remember { OffsetWheel() }
    // What this control last asked for, and the value passed in when it asked. Several scroll events
    // can arrive before the next frame brings [valueMs] up to date - a touchpad sends a stream of
    // them - and stepping each from the stale value would drop all but the last. Built on what was
    // asked until the value passed in moves, which is also how a change from elsewhere takes over.
    val asked = remember { intArrayOf(valueMs, NOT_ASKED) }
    // Caught up: from here the value passed in is the one to build on, even if it later goes back to
    // what it was before - a Reset, say - which would otherwise look like it had not moved yet.
    SideEffect { if (valueMs == asked[0]) asked[1] = NOT_ASKED }
    fun base(): Int = if (asked[1] == current) asked[0] else current
    fun set(ms: Int) {
        val value = ms.coerceIn(-LYRICS_OFFSET_LIMIT_MS, LYRICS_OFFSET_LIMIT_MS)
        asked[0] = value
        asked[1] = current
        change(value)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val scroll = event.changes.first().scrollDelta
                val delta = if (scroll.y != 0f) scroll.y else scroll.x
                if (delta == 0f) return@onPointerEvent
                val step = if (event.keyboardModifiers.isShiftPressed) LYRICS_OFFSET_FINE_STEP_MS else LYRICS_OFFSET_STEP_MS
                val notches = wheel.notches(delta)
                if (notches != 0) set(base() + notches * step)
                // Taken whole, so the lyrics or the page behind do not scroll as well.
                event.changes.forEach { it.consume() }
            },
    ) {
        Text(
            formatLyricsOffset(valueMs),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when {
                valueMs > 0 -> "Each line shows later"
                valueMs < 0 -> "Each line shows earlier"
                else -> "As the lyrics came"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = valueMs.toFloat(),
            // Whole steps of 10, so dragging lands on values the buttons and the wheel also reach.
            onValueChange = { set((it / LYRICS_OFFSET_FINE_STEP_MS).roundToInt() * LYRICS_OFFSET_FINE_STEP_MS) },
            valueRange = -LYRICS_OFFSET_LIMIT_MS.toFloat()..LYRICS_OFFSET_LIMIT_MS.toFloat(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { set(base() - LYRICS_OFFSET_STEP_MS) }) { Text("−$LYRICS_OFFSET_STEP_MS") }
            OutlinedButton(onClick = { set(base() - LYRICS_OFFSET_FINE_STEP_MS) }) { Text("−$LYRICS_OFFSET_FINE_STEP_MS") }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = { set(base() + LYRICS_OFFSET_FINE_STEP_MS) }) { Text("+$LYRICS_OFFSET_FINE_STEP_MS") }
            OutlinedButton(onClick = { set(base() + LYRICS_OFFSET_STEP_MS) }) { Text("+$LYRICS_OFFSET_STEP_MS") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Scroll over this to fine-tune: $LYRICS_OFFSET_STEP_MS ms a notch, $LYRICS_OFFSET_FINE_STEP_MS ms with Shift.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The playing song's timing offset, from the lyrics menu.
 *
 * Applied live - the lyrics behind it move with every step - and kept for this song alone. Cancel
 * puts back what the song had when the dialog opened; Done keeps what is showing.
 */
@Composable
fun LyricsOffsetDialog(valueMs: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val original = remember { valueMs }
    AlertDialog(
        // Clicking outside keeps the value, as Done does: it has already been applied, and quietly
        // undoing a tuned offset because the pointer strayed would be the worse surprise.
        onDismissRequest = onDismiss,
        title = { Text("Timing offset for this song") },
        text = { LyricsOffsetControl(valueMs, onChange, modifier = Modifier.width(360.dp)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onChange(0) }, enabled = valueMs != 0) { Text("Reset") }
                TextButton(onClick = { onChange(original); onDismiss() }) { Text("Cancel") }
            }
        },
    )
}
