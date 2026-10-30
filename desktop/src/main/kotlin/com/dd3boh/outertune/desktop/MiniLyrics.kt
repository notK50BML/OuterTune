/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * The mini player's lyrics, in the middle of the bar: the line being sung, large, with the one
 * before and the one after smaller and dimmed above and below it.
 *
 * When the line changes they all move up a place together on a spring with a little overshoot, so
 * the next line grows into the middle as the last one shrinks out of it - the full player's lyrics,
 * folded into the height of the bar. A seek, or anything else that jumps more than a line, snaps
 * rather than scrolling through every line in between.
 */
@Composable
internal fun MiniLyrics(lyrics: Lyrics, positionMs: () -> Long, modifier: Modifier = Modifier) {
    // Changes only when the line does, so this recomposes once a line, not once a frame.
    val current by remember(lyrics) { derivedStateOf { LrcParser.lineAt(lyrics.lines, positionMs()) } }
    val focus = remember(lyrics) { Animatable(current.toFloat()) }
    LaunchedEffect(current) {
        if (abs(current - focus.targetValue) > 1.5f) {
            focus.snapTo(current.toFloat())
        } else {
            focus.animateTo(current.toFloat(), spring(dampingRatio = 0.6f, stiffness = 300f))
        }
    }
    MiniLyricsLines(lyrics.lines, current, { focus.value }, modifier)
}

/**
 * The lines themselves, around [focus] - a line index, between two while moving. Split from
 * [MiniLyrics] so a still frame of the move can be drawn on its own.
 *
 * The movement is all in the draw phase: position, size and fade are read from [focus] inside
 * graphicsLayer, so a line change animates without recomposing any text.
 */
@Composable
internal fun MiniLyricsLines(
    lines: List<LyricLine>,
    current: Int,
    focus: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colour = MaterialTheme.colorScheme.onSurface
    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        // Two either side of the current line: one on show, one to move in from beyond the edge.
        for (index in (current - 2)..(current + 2)) {
            val line = lines.getOrNull(index) ?: continue
            key(index) {
                Text(
                    // An empty timed line is an instrumental break - a note rather than nothing.
                    text = line.text.ifBlank { "♪" },
                    color = colour,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val distance = index - focus()
                            val away = abs(distance).coerceAtMost(2f)
                            translationY = distance * LINE_SPACING.toPx()
                            // Full size in the middle, NEIGHBOUR_SCALE a place out.
                            val scale = 1f - (1f - NEIGHBOUR_SCALE) * away.coerceAtMost(1f)
                            scaleX = scale
                            scaleY = scale
                            // Lit in the middle, dimmed a place out, gone two out - so a line
                            // fades as it leaves rather than being cut off by the bar's edge.
                            alpha = if (away <= 1f) 1f - (1f - NEIGHBOUR_ALPHA) * away else NEIGHBOUR_ALPHA * (2f - away)
                        },
                )
            }
        }
    }
}

/** How far apart the lines sit: the middle line and its neighbours fill the bar's height. */
private val LINE_SPACING = 18.dp

/** The lines either side, against the middle one. */
private const val NEIGHBOUR_SCALE = 0.74f
private const val NEIGHBOUR_ALPHA = 0.42f
