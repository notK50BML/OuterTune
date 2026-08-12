/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * A static wash of the current cover's two dominant colours, each as its own soft blotch rather
 * than blended into one flat gradient.
 *
 * A flat two-colour gradient looks fine when the pair is close in hue, but on a cover with two
 * strongly different dominant colours the blend runs through a muddy middle band that belongs to
 * neither. Keeping them as separate, offset, soft-edged blotches avoids ever mixing them directly.
 */
@Composable
fun FollowThemeBackground(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    alpha: Float = 0.5f,
) {
    if (colors.isEmpty()) return

    val palette = remember(colors) {
        if (colors.size >= 2) colors.take(2) else listOf(colors[0], colors[0])
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val radius = maxOf(w, h) * 0.75f

                // Offset toward opposite corners so the two blotches overlap only in a band across
                // the middle, rather than sitting concentrically and blending everywhere.
                val positions = listOf(
                    Offset(w * 0.22f, h * 0.2f),
                    Offset(w * 0.82f, h * 0.85f),
                )

                palette.forEachIndexed { i, color ->
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                            center = positions[i],
                            radius = radius,
                        ),
                        radius = radius,
                        center = positions[i],
                    )
                }
            }
    )
}
