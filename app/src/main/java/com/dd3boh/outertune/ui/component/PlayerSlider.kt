/*
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.SliderStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Track thickness of the SLIM style. */
private val SlimTrackHeight = 4.dp

/** Stroke thickness of the SQUIGGLY wave. */
private val SquigglyStrokeWidth = 4.dp

/** Wave height of the squiggly active track. */
private val SquigglyAmplitude = 2.dp

/** Distance between wave crests of the squiggly active track. */
private val SquigglyWavelength = 36.dp

/** Gap that keeps the inactive track's left edge clear of the wave's end. */
private val SquigglyTrackGap = 3.dp

/** Duration of one full wave cycle. Larger is slower. */
private const val SquigglyPeriodMillis = 2500

/** Amplitude (in px) below which the wave is drawn as a straight line. */
private const val SquigglyFlatAmplitudeThresholdPx = 1.0f

/** Line segments per wavelength; deriving the step from the wavelength keeps smoothness density-independent. */
private const val SquigglySegmentsPerWavelength = 12

private val TwoPi = (2 * PI).toFloat()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSliderTrack(
    sliderState: SliderState,
    modifier: Modifier = Modifier,
    colors: SliderColors = SliderDefaults.colors(),
    trackHeight: Dp = 10.dp,
    style: SliderStyle = SliderStyle.SQUIGGLY,
    animate: Boolean = false,
    /** Thickness, as a share of each style's own - see SliderWeight. */
    weight: Float = 1f,
    /** A dot where the song has got to - see SliderDotKey. Not on the squiggle, which is its own mark. */
    dot: Boolean = false,
) {
    val inactiveTrackColor = colors.inactiveTrackColor
    val activeTrackColor = colors.activeTrackColor
    val inactiveTickColor = colors.inactiveTickColor
    val activeTickColor = colors.activeTickColor

    val valueRange = sliderState.valueRange
    val fraction = calcFraction(
        valueRange.start,
        valueRange.endInclusive,
        sliderState.value.coerceIn(valueRange.start, valueRange.endInclusive)
    )
    val tickFractions = stepsToTickFractions(sliderState.steps)

    when (style) {
        SliderStyle.SQUIGGLY -> {
            // Only advance the wave phase while playing, so a paused/seeking player
            // does not keep redrawing the (invisible) wave every frame.
            val phase = remember { Animatable(0f) }
            LaunchedEffect(animate) {
                if (animate) {
                    phase.snapTo(0f)
                    phase.animateTo(
                        targetValue = TwoPi,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = SquigglyPeriodMillis, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    )
                }
            }
            // Flatten the wave smoothly while paused or while the user is seeking.
            val amplitude by animateDpAsState(
                targetValue = if (animate) SquigglyAmplitude else 0.dp,
                animationSpec = tween(durationMillis = 300),
                label = "amplitude"
            )

            Canvas(
                modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                drawSquigglyTrack(
                    activeRangeEnd = fraction,
                    inactiveTrackColor = inactiveTrackColor,
                    activeTrackColor = activeTrackColor,
                    trackStrokeWidth = SquigglyStrokeWidth.toPx() * weight,
                    amplitude = amplitude.toPx(),
                    wavelength = SquigglyWavelength.toPx(),
                    phase = phase.value,
                    trackGap = SquigglyTrackGap.toPx()
                )
            }
        }

        SliderStyle.WAVES, SliderStyle.WAVE_LINES -> {
            // Samsung's look: a band whose top edge ripples (or lines over a flat one), drifting
            // while the song plays and settling flat when it is paused or being dragged.
            val swell by animateFloatAsState(if (animate) 1f else 0f, tween(600), label = "swell")
            var time by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(animate) {
                if (!animate) return@LaunchedEffect
                val start = time
                val began = withFrameNanos { it }
                while (true) withFrameNanos { time = start + (it - began) / 1e9f }
            }
            Canvas(
                modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                val isRtl = layoutDirection == LayoutDirection.Rtl
                val y = center.y
                val end = size.width * fraction
                // Breathing a little on their own, so the waves never settle into a pattern. They
                // rise above the track's own box, into the space over it - no higher than
                // AccentWaveHeight, so they never reach the title above the bar.
                val breath = 0.75f + 0.25f * sin(time * 0.7f)
                withTransform({ if (isRtl) scale(-1f, 1f, pivot = center) }) {
                    if (style == SliderStyle.WAVES) {
                        // The bar itself: flat bottom, wavy top - no separate line under it.
                        val thickness = WavesBandThickness.toPx() * weight
                        drawLine(inactiveTrackColor, Offset(end, y), Offset(size.width, y), thickness / 2, StrokeCap.Round)
                        drawWaveBand(end, y, thickness, AccentWaveHeight.toPx(), activeTrackColor, time, breath * swell)
                        if (dot && end > 0f) drawCircle(activeTrackColor, thickness * 0.9f, Offset(end, y))
                    } else {
                        val stroke = WaveLinesTrackHeight.toPx() * weight
                        drawAccentWaves(end, y, AccentWaveHeight.toPx(), activeTrackColor, time, breath * swell)
                        drawLine(inactiveTrackColor, Offset(end, y), Offset(size.width, y), stroke, StrokeCap.Round)
                        drawLine(activeTrackColor, Offset(0f, y), Offset(end, y), stroke, StrokeCap.Round)
                        if (dot && end > 0f) drawCircle(activeTrackColor, stroke * 1.1f, Offset(end, y))
                    }
                }
            }
        }

        SliderStyle.DEFAULT, SliderStyle.SLIM -> {
            val resolvedHeight = (if (style == SliderStyle.SLIM) SlimTrackHeight else trackHeight) * weight
            Canvas(
                modifier
                    .fillMaxWidth()
                    .height(trackHeight)
            ) {
                drawTrack(
                    tickFractions,
                    0f,
                    fraction,
                    inactiveTrackColor,
                    activeTrackColor,
                    inactiveTickColor,
                    activeTickColor,
                    resolvedHeight
                )
                if (dot && fraction > 0f) {
                    val x = size.width * (if (layoutDirection == LayoutDirection.Rtl) 1 - fraction else fraction)
                    drawCircle(activeTrackColor, resolvedHeight.toPx() * 1.1f, Offset(x, center.y))
                }
            }
        }
    }
}

/** How high the accent waves rise above the line at their fullest. */
private val AccentWaveHeight = 18.dp

/** The WAVES band's height where its waves are flat. */
private val WavesBandThickness = 7.dp

/** The flat line under the WAVE_LINES waves. */
private val WaveLinesTrackHeight = 4.dp

/**
 * Samsung's bar itself: a thick band with a flat bottom and a wavy top, from the start to [end].
 * Three layers a shade apart, each its own wave, drawn over one another - the first solid, the
 * others translucent above it - so the top edge ripples where they cross. Rounded at both ends.
 * [baseline] is the band's middle; [thickness] its height where the waves are flat; [rise] how far
 * the waves lift its top. The desktop's AccentWaves.kt draws the same.
 */
private fun DrawScope.drawWaveBand(
    end: Float,
    baseline: Float,
    thickness: Float,
    rise: Float,
    colour: Color,
    timeSeconds: Float,
    energy: Float,
) {
    if (end <= 0f) return
    val radius = thickness / 2
    // Round at both ends: the band runs between the centres of two half-circle caps.
    val left = radius
    val right = (end - radius).coerceAtLeast(left)
    val bottom = baseline + radius
    val flatTop = baseline - radius
    val reach = rise * (0.25f + 0.75f * energy.coerceIn(0f, 1f)) * growIn(end)
    val length = right - left
    // The deepest layer first, so the solid band ends up in front and the lighter ones show above it.
    AccentWaveSpecs.withIndex().reversed().forEach { (index, wave) ->
        val shade = if (index == 0) colour else lerp(colour, wave.tint, wave.tintAmount).copy(alpha = 0.45f - index * 0.1f)
        if (length > 1f) {
            val path = Path()
            path.moveTo(left, bottom)
            var x = 0f
            while (x <= length) {
                // Flat at both ends, so the band rises out of its rounded caps and back into them.
                val taper = sin(PI * (x / length)).toFloat().coerceAtLeast(0f)
                val phase = (x / wave.wavelength.toPx()) * TwoPi + timeSeconds * wave.speed + wave.offset
                val lift = (0.5f + 0.5f * sin(phase)) * wave.scale * reach * taper
                path.lineTo(left + x, flatTop - lift)
                x += 2f
            }
            path.lineTo(right, flatTop)
            path.lineTo(right, bottom)
            path.close()
            drawPath(path, shade)
        }
        if (index == 0) {
            drawCircle(shade, radius, Offset(left, baseline))
            drawCircle(shade, radius, Offset(right, baseline))
        }
    }
}

/**
 * The same three waves as lines a shade apart, over the played part of a flat line, fading in and
 * out at its ends so they rise out of it.
 */
private fun DrawScope.drawAccentWaves(
    end: Float,
    baseline: Float,
    height: Float,
    colour: Color,
    timeSeconds: Float,
    energy: Float,
) {
    if (end < 4f) return
    // Bold enough to read as part of the bar rather than a scribble over it.
    val stroke = 2.4.dp.toPx()
    val reach = height * (0.25f + 0.75f * energy.coerceIn(0f, 1f)) * growIn(end)
    AccentWaveSpecs.forEach { wave ->
        val path = Path()
        var x = 0f
        while (x <= end) {
            val taper = sin(PI * (x / end)).toFloat().coerceAtLeast(0f)
            val phase = (x / wave.wavelength.toPx()) * TwoPi + timeSeconds * wave.speed + wave.offset
            // Above the line only: a wave dipping below it would cross the progress itself.
            val lift = (0.5f + 0.5f * sin(phase)) * wave.scale * reach * taper
            if (x == 0f) path.moveTo(x, baseline - lift) else path.lineTo(x, baseline - lift)
            x += 2f
        }
        drawPath(
            path,
            lerp(colour, wave.tint, wave.tintAmount).copy(alpha = wave.alpha),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * How much of their height the waves have, by how long the played part is: none at the very start,
 * all of it from about 160dp on, in a smooth S between. A song a couple of seconds in shows a flat
 * rounded bar, not a tall arc squeezed into a few pixels.
 */
private fun DrawScope.growIn(span: Float): Float {
    val t = (span / 160.dp.toPx()).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

private class AccentWaveSpec(
    val wavelength: Dp,
    /** Radians a second: each its own, so they drift apart and back together. */
    val speed: Float,
    val offset: Float,
    val scale: Float,
    val alpha: Float,
    val fillAlpha: Float,
    val tint: Color,
    val tintAmount: Float,
)

/** Three, a shade apart: one towards white, one towards the accent's own depth, one in between. */
private val AccentWaveSpecs = listOf(
    AccentWaveSpec(120.dp, 1.9f, 0f, 1.00f, 0.55f, 0.20f, Color.White, 0.18f),
    AccentWaveSpec(86.dp, -1.3f, 2.1f, 0.78f, 0.40f, 0.16f, Color.White, 0.05f),
    AccentWaveSpec(155.dp, 1.1f, 4.0f, 0.62f, 0.30f, 0.13f, Color.Black, 0.12f),
)

/** @param trackHeight thickness of the drawn line (stroke width), not a layout height. */
private fun DrawScope.drawTrack(
    tickFractions: FloatArray,
    activeRangeStart: Float,
    activeRangeEnd: Float,
    inactiveTrackColor: Color,
    activeTrackColor: Color,
    inactiveTickColor: Color,
    activeTickColor: Color,
    trackHeight: Dp
) {
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val sliderLeft = Offset(0f, center.y)
    val sliderRight = Offset(size.width, center.y)
    val sliderStart = if (isRtl) sliderRight else sliderLeft
    val sliderEnd = if (isRtl) sliderLeft else sliderRight
    val tickSize = 2.0.dp.toPx()
    val trackStrokeWidth = trackHeight.toPx()
    drawLine(
        inactiveTrackColor,
        sliderStart,
        sliderEnd,
        trackStrokeWidth,
        StrokeCap.Round
    )
    val sliderValueEnd = Offset(
        sliderStart.x +
                (sliderEnd.x - sliderStart.x) * activeRangeEnd,
        center.y
    )

    val sliderValueStart = Offset(
        sliderStart.x +
                (sliderEnd.x - sliderStart.x) * activeRangeStart,
        center.y
    )

    drawLine(
        activeTrackColor,
        sliderValueStart,
        sliderValueEnd,
        trackStrokeWidth,
        StrokeCap.Round
    )

    for (tick in tickFractions) {
        val outsideFraction = tick > activeRangeEnd || tick < activeRangeStart
        drawCircle(
            color = if (outsideFraction) inactiveTickColor else activeTickColor,
            center = Offset(lerp(sliderStart, sliderEnd, tick).x, center.y),
            radius = tickSize / 2f
        )
    }
}

/** Draws the active portion as a sine wave and the inactive portion as a straight line. */
private fun DrawScope.drawSquigglyTrack(
    activeRangeEnd: Float,
    inactiveTrackColor: Color,
    activeTrackColor: Color,
    trackStrokeWidth: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    trackGap: Float
) {
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val sliderLeft = Offset(0f, center.y)
    val sliderRight = Offset(size.width, center.y)
    val sliderStart = if (isRtl) sliderRight else sliderLeft
    val sliderEnd = if (isRtl) sliderLeft else sliderRight
    val direction = if (isRtl) -1f else 1f

    val activeEnd = Offset(
        sliderStart.x + (sliderEnd.x - sliderStart.x) * activeRangeEnd,
        center.y
    )

    if (amplitude <= SquigglyFlatAmplitudeThresholdPx || wavelength <= 0f) {
        // Flat: both portions are straight lines meeting at activeEnd, so no gap is needed.
        drawLine(
            inactiveTrackColor,
            activeEnd,
            sliderEnd,
            trackStrokeWidth,
            StrokeCap.Round
        )
        drawLine(
            activeTrackColor,
            sliderStart,
            activeEnd,
            trackStrokeWidth,
            StrokeCap.Round
        )
        return
    }

    // A butt left edge set a gap past the wave keeps the inactive cap from showing beside the
    // off-center wave end; the cap circle restores the rounded far end.
    val inactiveStart = Offset(activeEnd.x + direction * trackGap, center.y)
    if ((sliderEnd.x - inactiveStart.x) * direction > 0f) {
        drawLine(
            inactiveTrackColor,
            inactiveStart,
            sliderEnd,
            trackStrokeWidth,
            StrokeCap.Butt
        )
        drawCircle(
            color = inactiveTrackColor,
            radius = trackStrokeWidth / 2f,
            center = sliderEnd
        )
    }

    val length = abs(activeEnd.x - sliderStart.x)
    val step = (wavelength / SquigglySegmentsPerWavelength).coerceAtLeast(1f)
    val path = Path().apply {
        // Start exactly on the curve. Starting at center.y would leave a short
        // vertical stub that shows up as a fixed thick dot under the round cap.
        moveTo(sliderStart.x, center.y + amplitude * sin(phase))
        var x = step
        while (x <= length) {
            val px = sliderStart.x + direction * x
            val py = center.y + amplitude * sin(TwoPi * x / wavelength + phase)
            lineTo(px, py)
            x += step
        }
        // The loop can stop up to one step short of the active end; finish the
        // curve exactly at activeEnd so it joins the inactive line without a gap.
        val endY = center.y + amplitude * sin(TwoPi * length / wavelength + phase)
        lineTo(activeEnd.x, endY)
    }
    drawPath(
        path = path,
        color = activeTrackColor,
        style = Stroke(width = trackStrokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

private fun stepsToTickFractions(steps: Int): FloatArray {
    return if (steps == 0) floatArrayOf() else FloatArray(steps + 2) { it.toFloat() / (steps + 1) }
}

private fun calcFraction(a: Float, b: Float, pos: Float) =
    (if (b - a == 0f) 0f else (pos - a) / (b - a)).coerceIn(0f, 1f)
