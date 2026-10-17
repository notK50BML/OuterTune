/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.util.lerp

/**
 * The phone's shuffle button: two straight arrows when off, crossed when on - and here the one
 * turns into the other, the arrows swinging across each other rather than the icon being swapped.
 *
 * Drawn on a 24 grid with round caps and joins, like the rest of the icons.
 */
@Composable
fun ShuffleIcon(on: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val t by animateFloatAsState(if (on) 1f else 0f, animationSpec = tween(320))
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun arrow(fromY: Float, toY: Float) {
            val startX = 4f * u
            val endX = 19f * u
            val y0 = fromY * u
            val y1 = toY * u
            val line = Path().apply {
                moveTo(startX, y0)
                cubicTo(11.5f * u, y0, 11.5f * u, y1, endX, y1)
            }
            drawPath(line, tint, style = stroke)
            val head = Path().apply {
                moveTo(endX - 2.6f * u, y1 - 2.6f * u)
                lineTo(endX, y1)
                lineTo(endX - 2.6f * u, y1 + 2.6f * u)
            }
            drawPath(head, tint, style = stroke)
        }

        arrow(8f, lerp(8f, 16f, t))
        arrow(16f, lerp(16f, 8f, t))
    }
}
