/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max

/**
 * How the line being sung lights its words. Each platform keeps its own default: the desktop fades
 * each word in, the phone sweeps a light across - and either can have the other's.
 */
enum class LyricsHighlight(val label: String, val description: String) {
    Fade("Fade in", "Each word brightens over the time it is sung."),
    Sweep("Sweep", "A soft edge of light moves across the words, as on the phone."),
}

/** What kind of timing the lyrics on screen carry - the small tag beside the lyrics controls. */
enum class LyricsKind(val label: String) {
    WordSynced("Word-synced"),
    LineSynced("Line-synced"),
    Estimated("Estimated"),
    Unsynced("Unsynced"),
}

/**
 * Which [LyricsKind] [fetched] is, as drawn: word timings from the provider, word timings estimated
 * from lines ([estimating]), lines only, or plain text.
 */
fun lyricsKindOf(fetched: Lyrics?, estimating: Boolean): LyricsKind? = when {
    fetched == null || fetched.isEmpty -> null
    !fetched.synced -> LyricsKind.Unsynced
    fetched.wordSynced -> LyricsKind.WordSynced
    estimating -> LyricsKind.Estimated
    else -> LyricsKind.LineSynced
}

/** The lyrics-kind tag: small, rounded, quiet. */
@Composable
fun LyricsKindTag(kind: LyricsKind, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.12f),
        contentColor = tint.copy(alpha = 0.85f),
        modifier = modifier,
    ) {
        Text(
            kind.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The line being sung, drawn the phone's way: once dim, then once bright through a mask whose soft
 * edge moves with the voice - a port of the phone's KaraokeLyricLine.
 *
 * The boundary is a character offset turned into an x coordinate from the laid-out text, so wrapping,
 * kerning and right-to-left text come out as the plain text would; and the position is read in the
 * draw phase only, so the clock moving redraws this one line and recomposes nothing.
 */
@Composable
fun SweepLyricLine(
    line: LyricLine,
    style: TextStyle,
    sungColor: Color,
    unsungColor: Color,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    // The same string the fade style draws, with each word's span in it.
    val (text, spans) = remember(line) {
        val builder = StringBuilder()
        val spans = line.words.map { word ->
            val start = builder.length
            builder.append(word.text)
            val span = WordSpan(start until builder.length, word.startMs, word.endMs)
            if (word.trailingSpace) builder.append(' ')
            span
        }
        builder.toString().trimEnd() to spans.filter { !it.chars.isEmpty() }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        if (widthPx <= 0 || text.isEmpty() || spans.isEmpty()) {
            Text(text = line.text, style = style, color = sungColor, modifier = Modifier.fillMaxWidth())
            return@BoxWithConstraints
        }
        val measurer = rememberTextMeasurer()
        val layout = remember(text, widthPx, style) {
            measurer.measure(text, style, constraints = Constraints(minWidth = widthPx, maxWidth = widthPx), softWrap = true)
        }
        val heightDp = with(LocalDensity.current) { layout.size.height.toDp() }
        val layerPaint = remember { Paint() }
        Canvas(modifier = Modifier.fillMaxWidth().height(heightDp)) {
            drawText(layout, color = unsungColor)
            val reveal = revealedChars(spans, text.length, positionMs())
            if (reveal <= 0f) return@Canvas
            var boundaryLine = -1
            for (l in 0 until layout.lineCount) {
                if (reveal < layout.getLineEnd(l).toFloat()) {
                    boundaryLine = l
                    break
                }
            }
            if (boundaryLine == -1) {
                drawText(layout, color = sungColor)
                return@Canvas
            }
            val bandTop = layout.getLineTop(boundaryLine)
            if (bandTop > 0f) clipRect(0f, 0f, size.width, bandTop) { drawText(layout, color = sungColor) }
            val bandBottom = layout.getLineBottom(boundaryLine)
            val index = floor(reveal).toInt().coerceIn(0, text.length - 1)
            val fraction = (reveal - index).coerceIn(0f, 1f)
            val box = layout.getBoundingBox(index)
            val rtl = layout.getParagraphDirection(index) == ResolvedTextDirection.Rtl
            val boundaryX = if (rtl) box.right - box.width * fraction else box.left + box.width * fraction
            val edge = max((bandBottom - bandTop) * SOFT_EDGE_RATIO, 1f)
            val mask = Brush.horizontalGradient(
                colors = if (rtl) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent),
                startX = boundaryX - edge / 2f,
                endX = boundaryX + edge / 2f,
            )
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, bandTop, size.width, bandBottom), layerPaint)
                clipRect(0f, bandTop, size.width, bandBottom) { drawText(layout, color = sungColor) }
                drawRect(mask, topLeft = Offset(0f, bandTop), size = Size(size.width, bandBottom - bandTop), blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
    }
}

/** One word's characters in the drawn line, and when it is sung. */
internal data class WordSpan(val chars: IntRange, val startMs: Long, val endMs: Long)

/**
 * How much of the line has been sung at [positionMs], as a fractional character offset: evenly
 * across each word's characters while it is sung, held at a word's end through the gap after it,
 * and never backwards within a call.
 */
internal fun revealedChars(spans: List<WordSpan>, textLength: Int, positionMs: Long): Float {
    var revealed = 0f
    for (span in spans) {
        if (positionMs < span.startMs) break
        val from = span.chars.first.toFloat()
        val to = (span.chars.last + 1).toFloat()
        revealed = max(
            revealed,
            if (positionMs >= span.endMs) to
            else from + (to - from) * ((positionMs - span.startMs).toFloat() / (span.endMs - span.startMs).coerceAtLeast(1)),
        )
    }
    return revealed.coerceIn(0f, textLength.toFloat())
}

/** The soft edge's width, as a share of the line's height - the phone's. */
private const val SOFT_EDGE_RATIO = 0.45f
