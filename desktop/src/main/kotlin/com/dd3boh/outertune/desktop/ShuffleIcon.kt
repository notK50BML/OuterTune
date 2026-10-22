/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.util.lerp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The shuffle button, the same on both apps: two parallel arrows when off - the phone's
 * shuffle_off - and the phone's crossed Material shuffle when on, the one turning into the other.
 *
 * The two ends are one shape because of a happy fact about the on icon: its square corner
 * arrowheads are ordinary chevrons turned to lie along a 45° line. So the arrows tilt from
 * horizontal to diagonal, swinging across each other, and each head turns with its line and grows
 * into the corner - no cross-fade, no swap. The arrow that ends up passing underneath opens a gap
 * where they cross, as the Material glyph has.
 *
 * Kept identical to the Android app's ShuffleIcon; a change to one belongs in the other.
 */
@Composable
fun ShuffleIcon(on: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (on) 1f else 0f, animationSpec = tween(320, easing = FastOutSlowInEasing))
    Canvas(modifier = modifier) { drawShuffle(t, tint) }
}

/** The icon at [t], 0 off and 1 on, on a 24-unit grid scaled to the canvas. */
fun DrawScope.drawShuffle(t: Float, tint: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    val arm = lerp(2.8f, 6f, t) * u

    fun arrow(sx: Float, sy: Float, ex: Float, ey: Float, gap: Float) {
        val x0 = sx * u
        val y0 = sy * u
        val x1 = ex * u
        val y1 = ey * u
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 0.01f) return
        val ux = dx / len
        val uy = dy / len
        if (gap > 0.001f) {
            val a = 0.5f - gap / 2
            val b = 0.5f + gap / 2
            drawLine(tint, androidx.compose.ui.geometry.Offset(x0, y0), androidx.compose.ui.geometry.Offset(x0 + dx * a, y0 + dy * a), stroke.width, StrokeCap.Square)
            drawLine(tint, androidx.compose.ui.geometry.Offset(x0 + dx * b, y0 + dy * b), androidx.compose.ui.geometry.Offset(x1, y1), stroke.width, StrokeCap.Square)
        } else {
            drawLine(tint, androidx.compose.ui.geometry.Offset(x0, y0), androidx.compose.ui.geometry.Offset(x1, y1), stroke.width, StrokeCap.Square)
        }
        // The head: two arms leaning back from the tip at 45° either side of the line.
        val c = 1f / sqrt(2f)
        val bx = -ux
        val by = -uy
        val head = Path().apply {
            moveTo(x1 + (bx * c - by * c) * arm, y1 + (bx * c + by * c) * arm)
            lineTo(x1, y1)
            lineTo(x1 + (bx * c + by * c) * arm, y1 + (-bx * c + by * c) * arm)
        }
        drawPath(head, tint, style = stroke)
    }

    // Off: two arrows along 8 and 16. On: bottom-left to top-right over, top-left to bottom-right
    // under - the Material glyph's two strokes.
    arrow(lerp(5f, 4f, t), lerp(8f, 20f, t), lerp(19f, 20f, t), lerp(8f, 4f, t), gap = 0f)
    arrow(lerp(5f, 4f, t), lerp(16f, 4f, t), lerp(19f, 20f, t), lerp(16f, 20f, t), gap = 0.36f * t)
}
