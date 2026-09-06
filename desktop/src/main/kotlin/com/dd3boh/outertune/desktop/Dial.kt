/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whether shift is down for a scroll event.
 *
 * Read off the AWT event rather than Compose's `keyboardModifiers`, which is not available on the
 * scroll path in the desktop target. The cast is guarded because the native event is only an AWT one
 * when this is actually running on the desktop backend.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun shiftHeld(event: androidx.compose.ui.input.pointer.PointerEvent): Boolean {
    val native = event.nativeEvent as? java.awt.event.InputEvent ?: return false
    return native.modifiersEx and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0
}

/** Where the arc starts and ends, as compass degrees. Open at the bottom, like a volume knob. */
private const val SWEEP_START = 135f
private const val SWEEP_DEGREES = 270f

/**
 * A knob, turned by scrolling over it.
 *
 * Scroll rather than drag as the primary gesture, because on a desktop the pointer is already
 * hovering over the control it is about to change and the wheel is the one input that costs no
 * travel. Dragging works too, vertically, for anyone who reaches for it - but the wheel is what this
 * is built around.
 *
 * A dial rather than a slider for these particular values. Tempo and pitch have a strong default in
 * the middle that people return to constantly, and a dial makes "back to centre" a visible position
 * rather than a pixel to hunt for; a double-click puts it there exactly. They are also
 * multiplicative, and the circular sweep reads as a ratio in a way a linear track does not.
 *
 * @param step how much one wheel notch moves the value. Fine, deliberately - these are settings
 *   people nudge, and a dial that jumps a semitone per notch cannot be used to find a quarter of
 *   one.
 * @param coarseStep how much a notch moves with shift held, for crossing the range quickly.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Dial(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    readout: (Float) -> String,
    accent: Color,
    onColour: Color,
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    step: Float = (valueRange.endInclusive - valueRange.start) / 100f,
    coarseStep: Float = step * 10f,
    /** The value a double-click returns to. Null means no reset. */
    default: Float? = null,
) {
    // Read through a snapshot rather than captured: the pointer modifiers are keyed on Unit so they
    // are installed once, and a captured lambda would go on calling the first composition's
    // onValueChange with the first composition's value - the dial would move one notch and stick.
    val current = rememberUpdatedState(value)
    val change = rememberUpdatedState(onValueChange)
    val range = rememberUpdatedState(valueRange)
    val steps = rememberUpdatedState(step to coarseStep)

    fun nudge(notches: Float, coarse: Boolean) {
        val by = if (coarse) steps.value.second else steps.value.first
        val next = (current.value + notches * by).coerceIn(range.value.start, range.value.endInclusive)
        if (next != current.value) change.value(next)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .size(size)
                .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll) { event ->
                    val scrolled = event.changes.first().scrollDelta.y
                    // Scrolling up is negative, and up should mean more.
                    if (scrolled != 0f) {
                        nudge(-scrolled, shiftHeld(event))
                        event.changes.forEach { it.consume() }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { changed, dragged ->
                        // Vertical, and scaled so a full sweep of the dial takes about the dial's
                        // own height of travel - a drag that has to cross the whole window to move
                        // one semitone is not a control anyone uses twice.
                        if (abs(dragged.y) > 0f) {
                            nudge(-dragged.y / 2f, coarse = false)
                            changed.consume()
                        }
                    }
                }
                .pointerInput(default) {
                    detectTapGestures(onDoubleTap = { default?.let { change.value(it) } })
                },
        ) {
            val stroke = this.size.minDimension * 0.11f
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = onColour.copy(alpha = 0.18f),
                startAngle = SWEEP_START,
                sweepAngle = SWEEP_DEGREES,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )

            val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
            val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

            // Filled outward from the dial's resting value, not from the start of the range. That
            // is what makes the fill mean "faster" or "slower" rather than "how far along" - and it
            // has to be the default rather than zero, because tempo rests at 1.0 and a dial filling
            // from 0.5 would show a half-full ring as its neutral position.
            val origin = default ?: valueRange.start
            val originFraction = ((origin - valueRange.start) / span).coerceIn(0f, 1f)
            val from = minOf(fraction, originFraction)
            val to = maxOf(fraction, originFraction)

            drawArc(
                color = accent,
                startAngle = SWEEP_START + from * SWEEP_DEGREES,
                sweepAngle = (to - from) * SWEEP_DEGREES,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )

            // The pointer. A knob without one is a progress ring - the mark is what says this is a
            // thing that turns and can be aimed.
            val angle = Math.toRadians((SWEEP_START + fraction * SWEEP_DEGREES).toDouble())
            val radius = arcSize.minDimension / 2f
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)
            drawLine(
                color = accent,
                start = centre + Offset(
                    (cos(angle) * radius * 0.32f).toFloat(),
                    (sin(angle) * radius * 0.32f).toFloat(),
                ),
                end = centre + Offset(
                    (cos(angle) * radius * 0.78f).toFloat(),
                    (sin(angle) * radius * 0.78f).toFloat(),
                ),
                strokeWidth = stroke * 0.55f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = readout(value),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = onColour,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onColour.copy(alpha = 0.65f),
        )
    }
}
