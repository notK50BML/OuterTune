/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether music is actually playing - the bars move only then, and hold still when paused. */
val LocalPlaybackActive = compositionLocalOf { false }

/**
 * The phone's now-playing mark: three bars rising and falling, over the cover of the song that is
 * playing, wherever that song is listed.
 *
 * Each bar runs on its own period, none a multiple of another, so together they never settle into
 * a visible loop. Paused, they stop where they are rather than vanishing - the song is still the
 * current one, it is just not moving.
 */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, colour: Color = Color.White, animate: Boolean = LocalPlaybackActive.current) {
    val transition = rememberInfiniteTransition()
    val heights = BAR_PERIODS.mapIndexed { i, period ->
        if (animate) {
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Reverse),
            ).value
        } else {
            STILL_HEIGHTS[i]
        }
    }
    Canvas(modifier = modifier) {
        val gap = size.width * 0.12f
        val barWidth = (size.width - gap * 2) / 3
        heights.forEachIndexed { i, h ->
            val barHeight = size.height * h
            drawRoundRect(
                color = colour,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
            )
        }
    }
}

/** A cover with the now-playing bars over it when [playing]. Otherwise exactly [Artwork]. */
@Composable
fun SongArtwork(url: String?, size: Dp, playing: Boolean, cornerRadius: Dp = 4.dp) {
    Box(contentAlignment = Alignment.Center) {
        Artwork(url, size = size, cornerRadius = cornerRadius)
        if (playing) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                PlayingBars(modifier = Modifier.size(size * 0.42f))
            }
        }
    }
}

private val BAR_PERIODS = listOf(380, 520, 450)
private val STILL_HEIGHTS = listOf(0.55f, 0.9f, 0.4f)
