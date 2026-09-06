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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE_DEG = 135f
private const val SWEEP_ANGLE_DEG = 270f

/**
 * A knob, drawn as a knob.
 *
 * A port of the Android app's `ui/component/RotaryDial.kt`, and the port matters: the first attempt
 * here drew an arc and a pointer line and nothing else, which is exactly what that file's own
 * comment warns against - it "read as just another slider bent into a circle". The dark disc, the
 * bezel ring and the pointer sitting on the face are what make it a control you would reach out and
 * turn. They are the component, not decoration on top of it.
 *
 * Scroll is the primary gesture here rather than drag, which is the one deliberate difference from
 * the Android original: on a desktop the pointer is already over the control it is about to change,
 * and the wheel costs no travel. Dragging still works, with the same fixed 200dp-sweeps-the-range
 * mapping, so a small dial is no harder to place precisely than a large one.
 *
 * @param centeredAt for a boost/cut control where the range has no meaningful "off" reading and only
 *   distance from a rest point matters. The arc then grows outward from there in whichever direction
 *   the value went, so a dial at rest shows no arc rather than a permanently half-full ring.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RotaryDial(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    dialSize: Dp = 76.dp,
    /** Drives the arc and the pointer only - see [textColor]. */
    color: Color,
    /**
     * The label and readout colour, kept independent of [color].
     *
     * Readable text and a value-coded face are two different jobs. A small label tinted the same hue
     * as its own gradient arc is hard to read against the panel behind it, and the gradient's whole
     * point is that it is being read at a glance from the arc.
     */
    textColor: Color = color,
    enabled: Boolean = true,
    label: String? = null,
    valueLabel: String? = null,
    centeredAt: Float? = null,
    /** How far one wheel notch moves the value; ten times that with shift held. */
    step: Float = (valueRange.endInclusive - valueRange.start) / 100f,
    coarseStep: Float = step * 10f,
    /** Where a double-click returns to. Defaults to [centeredAt] when there is one. */
    default: Float? = centeredAt,
) {
    val density = LocalDensity.current
    // detectDragGestures runs in a coroutine that survives across separate physical gestures - it
    // loops "await down, track drag, on up, await the next down" for as long as pointerInput's keys
    // hold. Closing over `value` directly would mean every drag after the first computed its delta
    // against whatever the value was when that coroutine started, so the dial would move once and
    // then snap back. The same applies to the scroll handler.
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentRange by rememberUpdatedState(valueRange)
    val currentSteps by rememberUpdatedState(step to coarseStep)

    fun set(next: Float) {
        val clamped = next.coerceIn(currentRange.start, currentRange.endInclusive)
        if (clamped != currentValue) currentOnValueChange(clamped)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.75f),
            )
        }

        Canvas(
            modifier = Modifier
                .size(dialSize)
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    if (!enabled) return@onPointerEvent
                    val scrolled = event.changes.first().scrollDelta.y
                    if (scrolled != 0f) {
                        // Up is negative, and up should mean more.
                        val by = if (shiftHeld(event)) currentSteps.second else currentSteps.first
                        set(currentValue - scrolled * by)
                        event.changes.forEach { it.consume() }
                    }
                }
                .pointerInput(enabled, valueRange) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val span = currentRange.endInclusive - currentRange.start
                        // A fixed physical drag length sweeps the full range, rather than one tied
                        // to the dial's drawn size, so a small dial is not harder to place.
                        val pxForFullRange = with(density) { 200.dp.toPx() }
                        set(currentValue - dragAmount.y / pxForFullRange * span)
                    }
                }
                .pointerInput(default, enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { default?.let(::set) })
                },
        ) {
            val strokeWidthPx = size.minDimension * 0.09f
            val centre = Offset(size.width / 2f, size.height / 2f)
            val discRadius = size.minDimension / 2f - strokeWidthPx * 1.4f
            val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
            val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
            val alphaScale = if (enabled) 1f else 0.4f

            // The unfilled part of the sweep, so the dial has a visible travel even at rest. Without
            // it a knob showing no arc reads as broken rather than as centred.
            drawArc(
                color = textColor.copy(alpha = 0.15f * alphaScale),
                startAngle = START_ANGLE_DEG,
                sweepAngle = SWEEP_ANGLE_DEG,
                useCenter = false,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )

            val originFraction =
                centeredAt?.let { ((it - valueRange.start) / span).coerceIn(0f, 1f) } ?: 0f
            drawArc(
                color = color.copy(alpha = alphaScale),
                startAngle = START_ANGLE_DEG + SWEEP_ANGLE_DEG * minOf(originFraction, fraction),
                sweepAngle = SWEEP_ANGLE_DEG * abs(fraction - originFraction),
                useCenter = false,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )

            // The face. A flat dark disc with a thin bezel of the value colour.
            drawCircle(color = Color.Black.copy(alpha = 0.28f * alphaScale), radius = discRadius, center = centre)
            drawCircle(
                color = color.copy(alpha = 0.3f * alphaScale),
                radius = discRadius,
                center = centre,
                style = Stroke(width = discRadius * 0.05f),
            )

            val pointerAngle = Math.toRadians((START_ANGLE_DEG + SWEEP_ANGLE_DEG * fraction).toDouble())
            val direction = Offset(cos(pointerAngle).toFloat(), sin(pointerAngle).toFloat())
            drawLine(
                color = color.copy(alpha = alphaScale),
                start = centre + direction * (discRadius * 0.32f),
                end = centre + direction * (discRadius * 0.86f),
                strokeWidth = discRadius * 0.16f,
                cap = StrokeCap.Round,
            )
        }

        valueLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = textColor.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
    }
}

/**
 * Whether shift is down for a scroll event.
 *
 * Read off the AWT event rather than Compose's `keyboardModifiers`, which is not populated on the
 * scroll path in the desktop target. Guarded, because the native event is only an AWT one when this
 * is running on the desktop backend.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun shiftHeld(event: PointerEvent): Boolean {
    val native = event.nativeEvent as? java.awt.event.InputEvent ?: return false
    return native.modifiersEx and java.awt.event.InputEvent.SHIFT_DOWN_MASK != 0
}
