/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import com.dd3boh.outertune.ui.utils.withEstimatedWordSync
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics

/**
 * The widget's lyrics: the line being sung, with the words sung so far lit, and the lines either
 * side of it - the Windows mini player's look.
 *
 * Read with the app's own parser, so LRC, enhanced LRC and TTML all carry their word timings here
 * as they do in the player, and lines without them are estimated the same way when the player
 * estimates them.
 */
object WidgetLyrics {

    /** One line: when it starts, its text, and for each word when it starts and where it ends. */
    class Line(val start: Long, val text: String, val words: List<Pair<Long, Int>>)

    /** What the widget shows: [sungChars] of [current] are lit, the rest dimmed. */
    data class Frame(val previous: String?, val current: String, val sungChars: Int, val next: String?)

    /**
     * How far ahead the lit words run. The widget is redrawn about once a second, so lighting what
     * is sung half a second from now puts each word, on average, on time.
     */
    private const val LEAD_MS = 500L

    /** Timed lines, in order. Empty for unsynced lyrics - there is no "current line" to show then. */
    fun parse(raw: String?, estimate: Boolean): List<Line> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching {
            LrcUtils.parseLyrics(raw, null, LrcUtils.LrcParserOptions(trim = true, multiLine = true, errorText = null), null)
        }.getOrNull() as? SemanticLyrics.SyncedLyrics ?: return emptyList()
        val lyrics = if (estimate) runCatching { parsed.withEstimatedWordSync() }.getOrDefault(parsed) else parsed
        return lyrics.text
            // Translations are shown under their line in the player; on a widget there is no room.
            .filter { !it.isTranslated && it.text.isNotBlank() }
            .map { line ->
                Line(
                    start = line.start.toLong(),
                    text = line.text,
                    words = line.words.orEmpty()
                        .map { it.timeRange.first.toLong() to (it.charRange.last + 1).coerceAtMost(line.text.length) }
                        .sortedBy { it.first },
                )
            }
            .sortedBy { it.start }
    }

    /** The lines around [positionMs]; before the first line, that line coming up. Null for none. */
    fun frameAt(lines: List<Line>, positionMs: Long): Frame? {
        if (lines.isEmpty()) return null
        val index = lines.indexOfLast { it.start <= positionMs }
        if (index < 0) return Frame(null, lines[0].text, 0, lines.getOrNull(1)?.text)
        val line = lines[index]
        val sung = if (line.words.isEmpty()) {
            line.text.length
        } else {
            line.words.lastOrNull { it.first <= positionMs + LEAD_MS }?.second ?: 0
        }
        return Frame(lines.getOrNull(index - 1)?.text, line.text, sung, lines.getOrNull(index + 1)?.text)
    }

    /** Whether any line carries word timings, so the widget is worth redrawing more often. */
    fun hasWords(lines: List<Line>) = lines.any { it.words.isNotEmpty() }
}
