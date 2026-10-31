/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 *
 * A line too long for the space slides sideways as it is sung rather than being cut short - see
 * [sungFraction] for how far along it is.
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
    val style = miniLyricStyle()
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val room = constraints.maxWidth
        val line = lyrics.lines.getOrNull(current)
        val long = remember(line, room, style) {
            line != null && measurer.measure(line.text, style, maxLines = 1, softWrap = false).size.width > room
        }
        // Lit word by word when the line has word timings - its own, or estimated ones when
        // "Estimate word timings" is on - as the full player's lyrics are.
        val byWord = line != null && line.words.isNotEmpty()
        // The song's time, every frame, but only while the line being sung needs it: too long to
        // show whole, or lit word by word. The position the bar is handed moves in steps, and a
        // line sliding or lighting in steps judders. Between steps it is carried forward on the
        // frame clock, never more than a second past the last one - so a pause stops it within
        // that, rather than it running on.
        var songTime by remember { mutableLongStateOf(positionMs()) }
        LaunchedEffect(current, long, byWord) {
            if (!long && !byWord) return@LaunchedEffect
            var anchor = positionMs()
            var anchoredAt = -1L
            while (true) withFrameNanos { now ->
                val position = positionMs()
                if (position != anchor || anchoredAt < 0) {
                    anchor = position
                    anchoredAt = now
                }
                songTime = anchor + ((now - anchoredAt) / 1_000_000L).coerceAtMost(1_000L)
            }
        }
        MiniLyricsLines(
            lines = lyrics.lines,
            current = current,
            focus = { focus.value },
            style = style,
            sung = { if (line == null) 0f else sungFraction(line, lyrics.lines.getOrNull(current + 1)?.timeMs, songTime) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The lines themselves, around [focus] - a line index, between two while moving. Split from
 * [MiniLyrics] so a still frame of the move can be drawn on its own.
 *
 * The movement is all in the draw phase: position, size and fade are read from [focus] inside
 * graphicsLayer, so a line change animates without recomposing any text. [sung] is how much of the
 * current line has been sung, 0 to 1, for sliding one that does not fit.
 */
@Composable
internal fun MiniLyricsLines(
    lines: List<LyricLine>,
    current: Int,
    focus: () -> Float,
    modifier: Modifier = Modifier,
    style: TextStyle = miniLyricStyle(),
    sung: () -> Float = { 0f },
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    // Each line in its singer's colour when the lyrics are coloured by singer - see withSingerNames.
    val colourOf = rememberVoiceColours(onSurface)
    Box(
        modifier = modifier
            .clipToBounds()
            // A soft edge at either side, so a line sliding past comes in and goes out rather than
            // being sliced off at the edge of the space.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = EDGE_FADE.toPx() / size.width
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        fade to Color.Black,
                        1f - fade to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Two either side of the current line: one on show, one to move in from beyond the edge.
        for (index in (current - 2)..(current + 2)) {
            val line = lines.getOrNull(index) ?: continue
            key(index) {
                val placement = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val distance = index - focus()
                        val away = abs(distance).coerceAtMost(2f)
                        translationY = distance * LINE_SPACING.toPx()
                        // Full size in the middle, NEIGHBOUR_SCALE a place out.
                        val scale = 1f - (1f - NEIGHBOUR_SCALE) * away.coerceAtMost(1f)
                        scaleX = scale
                        scaleY = scale
                        // Lit in the middle, dimmed a place out, gone two out - so a line fades as
                        // it leaves rather than being cut off by the bar's edge.
                        alpha = if (away <= 1f) 1f - (1f - NEIGHBOUR_ALPHA) * away else NEIGHBOUR_ALPHA * (2f - away)
                    }
                // An empty timed line is an instrumental break - a note rather than nothing.
                val text = line.text.ifBlank { "♪" }
                val colour = colourOf(line.voice)
                if (index == current) {
                    SlidingLine(text, style, colour, sung, byWord = line.words.isNotEmpty(), modifier = placement)
                } else {
                    Text(
                        text = text,
                        color = colour,
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = placement,
                    )
                }
            }
        }
    }
}

/**
 * One line, centred when it fits. When it does not, it starts at the left edge and slides left as it
 * is sung, keeping the point [sung] has reached about [SUNG_POINT] of the way across - the words
 * being sung stay in view and the ones still to come show beside them, until the line's end reaches
 * the right edge and it stops there.
 */
@Composable
private fun SlidingLine(text: String, style: TextStyle, colour: Color, sung: () -> Float, byWord: Boolean, modifier: Modifier) {
    var textLayout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    // How far across the line the singing has got, in the text's own pixels - from the text's
    // layout, so the edge of the lit part falls where the letters are rather than at a share of
    // the width.
    fun reached(): Float {
        val layout = textLayout ?: return 0f
        val chars = sung() * text.length
        val whole = chars.toInt().coerceIn(0, text.length)
        if (whole >= text.length) return layout.size.width.toFloat()
        val from = layout.getHorizontalPosition(whole, usePrimaryDirection = true)
        val to = layout.getHorizontalPosition(whole + 1, usePrimaryDirection = true)
        return from + (to - from) * (chars - whole)
    }
    Layout(
        content = {
            Text(
                text = text,
                color = if (byWord) colour.copy(alpha = UNSUNG_ALPHA) else colour,
                style = style,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { textLayout = it },
            )
            // [byWord]: the same line again, fully lit and cut off at the point reached, over the
            // dimmed one - so the words light as they are sung.
            if (byWord) {
                Text(
                    text = text,
                    color = colour,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.drawWithContent { clipRect(right = reached()) { this@drawWithContent.drawContent() } },
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)) }
        val first = placeables.first()
        val width = constraints.maxWidth
        val overflow = (first.width - width).coerceAtLeast(0)
        layout(width, first.height) {
            placeables.forEach { placeable ->
                if (overflow == 0) {
                    placeable.placeRelative((width - placeable.width) / 2, 0)
                } else {
                    placeable.placeRelativeWithLayer(0, 0) {
                        translationX = -(reached() - width * SUNG_POINT).coerceIn(0f, overflow.toFloat())
                    }
                }
            }
        }
    }
}

/**
 * How much of [line] has been sung by [timeMs], 0 to 1, as a share of its characters.
 *
 * From the words' own timings when the lyrics have them, so a long held word holds the line still
 * and a quick run of words moves it on quickly - it follows the voice. Without them, evenly over
 * the line's time, reaching the end a little before the next line starts ([nextStartMs], or a few
 * seconds on for the last line) so its last words are on screen while they are still being sung.
 */
internal fun sungFraction(line: LyricLine, nextStartMs: Long?, timeMs: Long): Float {
    if (line.words.isNotEmpty()) {
        val lengths = line.words.map { it.text.length + if (it.trailingSpace) 1 else 0 }
        val total = lengths.sum().coerceAtLeast(1)
        var sung = 0f
        for ((word, length) in line.words.zip(lengths)) {
            when {
                timeMs >= word.endMs -> sung += length
                timeMs > word.startMs -> {
                    sung += length * (timeMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
                    break
                }
                else -> break
            }
        }
        return (sung / total).coerceIn(0f, 1f)
    }
    val start = line.timeMs ?: return 0f
    val length = ((nextStartMs ?: (start + LAST_LINE_MS)) - start).coerceAtLeast(1L)
    return ((timeMs - start).toFloat() / (length * 0.9f)).coerceIn(0f, 1f)
}

@Composable
private fun miniLyricStyle(): TextStyle = LocalTextStyle.current.merge(
    TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
)

/** How far apart the lines sit: the middle line and its neighbours fill the bar's height. */
private val LINE_SPACING = 20.dp

/** The lines either side, against the middle one. */
private const val NEIGHBOUR_SCALE = 0.7f
private const val NEIGHBOUR_ALPHA = 0.42f

/** The width over which a line fades at either side of the space. */
private val EDGE_FADE = 14.dp

/** Where across the space the point being sung sits while a long line slides. */
private const val SUNG_POINT = 0.6f

/** Words of the current line still to be sung, against the ones that have been. */
private const val UNSUNG_ALPHA = 0.45f

/** The last line has no next to end at: this long, then. */
private const val LAST_LINE_MS = 5_000L
