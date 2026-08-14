/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val StartAngleDeg = 135f
private const val SweepAngleDeg = 270f

/**
 * A knob-style control, dragged (not tapped-and-slid, since there's no natural "track" a finger
 * can follow around a small circle) to change a value within a range - vertical drag distance
 * maps to the value the same way it would on a fader, only drawn as a dial with a 270-degree arc
 * indicator so a whole row of these reads as "physical controls" rather than more sliders.
 */
@Composable
fun RotaryDial(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    dialSize: Dp = 76.dp,
    color: Color = LocalContentColor.current,
    enabled: Boolean = true,
    label: String? = null,
    valueLabel: String? = null,
    /**
     * For a boost/cut style control (balance, a shelf/band gain) where [valueRange] itself has no
     * "off" reading worth showing - only distance from this point does. Null (the default) is a
     * plain 0-to-max control, where the arc grows from the low end same as it always has.
     */
    centeredAt: Float? = null,
) {
    val density = LocalDensity.current
    // detectDragGestures runs in a coroutine that survives across separate physical drag
    // gestures (it loops "await down, track drag, on up, await the next down" for as long as
    // pointerInput's keys stay the same) - closing over `value` directly meant every drag after
    // the first one computed its delta against whatever `value` was when that coroutine started,
    // not the value that resulted from the previous drag. rememberUpdatedState keeps the closure
    // reading the live value on every call instead of a stale one captured at coroutine start.
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        label?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.75f))
        }

        Canvas(
            modifier = Modifier
                .size(dialSize)
                .pointerInput(enabled, valueRange) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val range = valueRange.endInclusive - valueRange.start
                        // A fixed physical drag length (not tied to the dial's own drawn size)
                        // sweeps the full range, so a small dial isn't harder to place precisely
                        // than a large one.
                        val pxForFullRange = with(density) { 200.dp.toPx() }
                        val delta = -dragAmount.y / pxForFullRange * range
                        currentOnValueChange((currentValue + delta).coerceIn(valueRange.start, valueRange.endInclusive))
                    }
                }
        ) {
            // A flat physical knob - dark disc, thin bezel, a colored progress arc hugging the
            // rim, and a pointer line on the face showing exactly where that arc ends. The arc
            // alone (the old design) read as "just another slider bent into a circle"; the disc
            // is what makes it read as a knob you'd actually turn.
            val strokeWidthPx = size.minDimension * 0.09f
            val center = Offset(size.width / 2f, size.height / 2f)
            val discRadius = size.minDimension / 2f - strokeWidthPx * 1.4f
            val rangeSpan = valueRange.endInclusive - valueRange.start
            val fraction = ((value - valueRange.start) / rangeSpan).coerceIn(0f, 1f)
            val alphaScale = if (enabled) 1f else 0.4f

            // Plain controls sweep the arc from the low end up to the current value, same as a
            // normal progress indicator. A centered one instead sweeps from wherever "off" sits -
            // usually the middle - out toward the value, in whichever direction that is, so 0
            // shows no arc at all rather than a permanent half-full ring.
            val originFraction = centeredAt?.let { ((it - valueRange.start) / rangeSpan).coerceIn(0f, 1f) } ?: 0f
            drawArc(
                color = color.copy(alpha = alphaScale),
                startAngle = StartAngleDeg + SweepAngleDeg * minOf(originFraction, fraction),
                sweepAngle = SweepAngleDeg * kotlin.math.abs(fraction - originFraction),
                useCenter = false,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )

            drawCircle(
                color = Color.Black.copy(alpha = 0.28f * alphaScale),
                radius = discRadius,
                center = center,
            )
            drawCircle(
                color = color.copy(alpha = 0.3f * alphaScale),
                radius = discRadius,
                center = center,
                style = Stroke(width = discRadius * 0.05f),
            )

            val pointerAngleRad = Math.toRadians((StartAngleDeg + SweepAngleDeg * fraction).toDouble())
            val direction = Offset(cos(pointerAngleRad).toFloat(), sin(pointerAngleRad).toFloat())
            val pointerWidthPx = discRadius * 0.16f
            drawLine(
                color = color.copy(alpha = alphaScale),
                start = center + direction * (discRadius * 0.32f),
                end = center + direction * (discRadius * 0.86f),
                strokeWidth = pointerWidthPx,
                cap = StrokeCap.Round,
            )
        }

        valueLabel?.let {
            Text(text = it, style = MaterialTheme.typography.titleSmall, color = color.copy(alpha = if (enabled) 1f else 0.5f))
        }
    }
}
