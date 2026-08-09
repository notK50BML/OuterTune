/*
 * Copyright (C) 2026 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import org.akanework.gramophone.logic.utils.SemanticLyrics.Word
import kotlin.math.floor
import kotlin.math.max

/**
 * One lyric line drawn with a word-level illumination sweep.
 *
 * The line is painted twice: once dim, for the whole line, and once bright, masked so that only the
 * part that has already been sung shows through. The boundary between the two moves with playback,
 * with a short gradient across it so it reads as a light sweeping over the words rather than words
 * switching colour one at a time.
 *
 * Three things make this behave where the obvious implementation does not:
 *
 * - **Real glyph geometry.** The boundary is a character offset, turned into an x coordinate with
 *   [TextLayoutResult.getBoundingBox]. Nothing here assumes words are equally wide or re-measures
 *   the text itself, so kerning, ligatures and shaping are exactly what [Text] would have produced,
 *   and a line that wraps needs no special case: a wrapped line's characters simply report boxes on
 *   the visual line they landed on.
 *
 * - **Draw-phase position reads.** [positionProvider] is called inside the draw lambda, so the
 *   playback clock ticking sixty times a second invalidates drawing and nothing else. Reading it
 *   during composition instead would recompose every visible lyric on every frame.
 *
 * - **Player-driven progress.** The sweep is a pure function of the position [positionProvider]
 *   reports, with no animation running alongside it, so a seek lands exactly where it should
 *   instead of unwinding from wherever an animation happened to be.
 *
 * Cost per frame is two text draws and one small layer for the line the boundary is on, regardless
 * of how long the line is. Only lines near the playhead should be drawn this way; everything else
 * is cheaper as a plain [Text].
 *
 * @param text the full line, as it should be displayed
 * @param words word timings for [text], whose char ranges index into it
 * @param style text style, including the alignment the line should be laid out with
 * @param sungColor colour of the part already sung
 * @param unsungColor colour of the rest of the line
 * @param positionProvider current playback position in milliseconds; called once per drawn frame
 */
@Composable
fun KaraokeLyricLine(
    text: String,
    words: List<Word>,
    style: TextStyle,
    sungColor: Color,
    unsungColor: Color,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0

        // Without a width there is nothing to lay out against, and without text or timings there is
        // nothing to sweep. Fall back rather than drawing a blank.
        // No width to lay out against, no text, or no timings: draw the line plainly. It is drawn
        // in the *sung* colour rather than the unsung one on purpose - this is the line that is
        // playing, and dimming it here makes a missing-word-timings problem look identical to a
        // sweep that has stopped working.
        if (widthPx <= 0 || text.isEmpty() || words.isEmpty()) {
            Text(text = text, style = style, color = sungColor, modifier = Modifier.fillMaxWidth())
            return@BoxWithConstraints
        }

        val measurer = rememberTextMeasurer()
        val layout = remember(text, widthPx, style) {
            measurer.measure(
                text = text,
                style = style,
                constraints = Constraints(minWidth = widthPx, maxWidth = widthPx),
                softWrap = true,
            )
        }

        // Char ranges come from the lyric provider and are only as trustworthy as it is; a range
        // that runs off the end of the text would make getBoundingBox throw mid-draw.
        val safeWords = remember(text, words) {
            words.filter { it.charRange.first in text.indices && it.charRange.last < text.length }
        }
        if (safeWords.isEmpty()) {
            Text(text = text, style = style, color = sungColor, modifier = Modifier.fillMaxWidth())
            return@BoxWithConstraints
        }

        val heightDp = with(LocalDensity.current) { layout.size.height.toDp() }
        val layerPaint = remember { Paint() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp)
        ) {
            drawText(layout, color = unsungColor)

            val reveal = revealedCharOffset(safeWords, text.length, positionProvider())
            if (reveal <= 0f) return@Canvas

            // The visual line the boundary sits on. Trailing whitespace is deliberately counted as
            // part of the line it trails, so a boundary that lands exactly on a line's last space
            // resolves against that line's geometry rather than the next line's.
            var boundaryLine = -1
            for (line in 0 until layout.lineCount) {
                if (reveal < layout.getLineEnd(line).toFloat()) {
                    boundaryLine = line
                    break
                }
            }
            if (boundaryLine == -1) {
                drawText(layout, color = sungColor) // the whole line has been sung
                return@Canvas
            }

            // Everything above the boundary line is fully sung and needs no mask.
            val bandTop = layout.getLineTop(boundaryLine)
            if (bandTop > 0f) {
                clipRect(0f, 0f, size.width, bandTop) {
                    drawText(layout, color = sungColor)
                }
            }

            val bandBottom = layout.getLineBottom(boundaryLine)
            val index = floor(reveal).toInt().coerceIn(0, text.length - 1)
            val fraction = (reveal - index).coerceIn(0f, 1f)
            val box = layout.getBoundingBox(index)

            // Which way the sweep travels. A line mixing directions cannot have a single contiguous
            // sung region at all, so the paragraph's own direction is the only sensible answer, and
            // an embedded run of the other direction is at most one glyph out of step.
            val rtl = layout.getParagraphDirection(index) == ResolvedTextDirection.Rtl
            val boundaryX = if (rtl) box.right - box.width * fraction else box.left + box.width * fraction

            // Soft edge scaled to the text size, so it looks the same at any lyric font size.
            val edge = max((bandBottom - bandTop) * SOFT_EDGE_RATIO, 1f)
            val gradient = Brush.horizontalGradient(
                colors = if (rtl) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent),
                startX = boundaryX - edge / 2f,
                endX = boundaryX + edge / 2f,
            )

            // The bright pass for this band goes into its own layer so the gradient can be used as
            // an alpha mask (DstIn keeps the destination, scaled by the mask's alpha) instead of
            // being blended into the colour, which would wash the text out against the background.
            drawIntoCanvas { canvas ->
                val bounds = Rect(0f, bandTop, size.width, bandBottom)
                canvas.saveLayer(bounds, layerPaint)
                clipRect(0f, bandTop, size.width, bandBottom) {
                    drawText(layout, color = sungColor)
                }
                drawRect(
                    brush = gradient,
                    topLeft = Offset(0f, bandTop),
                    size = Size(size.width, bandBottom - bandTop),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}

/** Width of the gradient at the sweep's leading edge, as a fraction of the line's height. */
private const val SOFT_EDGE_RATIO = 0.45f

/**
 * How much of the line has been sung at [positionMs], as a fractional character offset into a line
 * of [textLength] characters.
 *
 * Within a word the offset advances evenly across that word's characters. Between words it holds
 * still at the end of the word that just finished — the gap is whitespace, which has no ink, so the
 * pause is invisible rather than looking like a stall.
 *
 * The result never moves backwards within a call, which keeps a provider that emits slightly
 * overlapping words from making the sweep stutter.
 */
internal fun revealedCharOffset(words: List<Word>, textLength: Int, positionMs: Long): Float {
    if (positionMs < 0 || words.isEmpty()) return 0f
    val position = positionMs.toULong()

    var revealed = 0f
    for (word in words) {
        val start = word.timeRange.first
        if (position < start) break // not started; anything later has not started either

        val from = word.charRange.first.toFloat()
        val to = (word.charRange.last + 1).toFloat()
        val end = word.timeRange.last
        revealed = max(
            revealed,
            if (position >= end) {
                to
            } else {
                val span = (end - start).coerceAtLeast(1uL).toFloat()
                from + (to - from) * ((position - start).toFloat() / span)
            }
        )
    }
    return revealed.coerceIn(0f, textLength.toFloat())
}
