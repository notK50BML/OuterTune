/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.Word

/** Longest line a sweep will be estimated for. Beyond this the timing is bad data, not a long line. */
private const val MAX_PLAUSIBLE_LINE_MS = 30_000uL

/**
 * Invents word timings for line-synced lyrics, so the karaoke sweep can run on the ordinary LRC
 * files that make up nearly everything available - not just the rare ones carrying real per-word
 * timestamps.
 *
 * The guess is deliberately crude: a line knows when it starts and when it ends, so the sweep is
 * spread across its words in proportion to how long each word is. A singer does not actually
 * deliver a line at a constant rate - held notes, rests and pauses are exactly what this cannot
 * know about - so the highlight will drift within a line and snap back into place at the start of
 * the next one, which is the trade being made. Every line still begins and ends on time, because
 * the endpoints are real; only the distribution between them is fabricated.
 *
 * Length is used as the weight rather than a flat split per word because it is a better proxy for
 * duration than word count is: "I" and "everything" plainly do not take the same time to sing, and
 * weighting by characters gets that roughly right for free.
 *
 * Lines that already carry real word timings are left exactly as they are - a genuine sync is
 * always better than this, and where a provider supplies one it must win.
 */
fun SemanticLyrics.SyncedLyrics.withEstimatedWordSync(): SemanticLyrics.SyncedLyrics =
    SemanticLyrics.SyncedLyrics(
        text.map { line ->
            if (!line.words.isNullOrEmpty()) {
                line
            } else {
                estimateWords(line)?.let { line.copy(words = it) } ?: line
            }
        }
    )

/**
 * Word spans for one line, or null when the line cannot support a sweep: no real duration to spread
 * (the end is missing or not after the start), or nothing but whitespace to spread it over.
 * Returning null leaves the line to be drawn by the plain, unswept path, which is the correct
 * fallback rather than a degraded sweep.
 */
private fun estimateWords(line: SemanticLyrics.LyricLine): MutableList<Word>? {
    if (line.end <= line.start) return null
    // The LRC parser has no next line to end the last one against, so it ends it at Long.MAX_VALUE.
    // Spreading words across that would inch the highlight along at a rate of roughly nothing, which
    // looks broken rather than approximate - and any line claiming to run for minutes is bad data
    // whatever produced it. Falling back to the plain unswept line is the honest answer.
    if (line.end - line.start > MAX_PLAUSIBLE_LINE_MS) return null

    val spans = wordSpans(line.text)
    if (spans.isEmpty()) return null

    val weights = spans.map { it.last - it.first + 1 }
    val totalWeight = weights.sum()
    if (totalWeight <= 0) return null

    val duration = line.end - line.start
    val words = ArrayList<Word>(spans.size)
    var consumedWeight = 0

    spans.forEachIndexed { index, span ->
        // Each boundary is computed from the running weight total rather than by accumulating
        // per-word durations. Integer division loses a little on every word, and adding those
        // losses up would let the final word finish measurably before the line does; deriving each
        // edge from the total instead keeps the error bounded to one word and lands the last word
        // exactly on the line's end.
        val startOffset = duration * consumedWeight.toULong() / totalWeight.toULong()
        consumedWeight += weights[index]
        val endOffset = duration * consumedWeight.toULong() / totalWeight.toULong()
        words += Word(
            timeRange = (line.start + startOffset)..(line.start + endOffset),
            charRange = span,
            isRtl = false,
        )
    }
    return words
}

/**
 * Character ranges of the whitespace-separated words in [text], in order.
 *
 * The ranges cover the words themselves and not the spaces between them, so a sweep pauses in the
 * gaps rather than gliding through them - which is closer to how a real word-synced file behaves
 * and reads better at speed.
 */
private fun wordSpans(text: String): List<IntRange> {
    val spans = ArrayList<IntRange>()
    var start = -1
    text.forEachIndexed { index, char ->
        if (char.isWhitespace()) {
            if (start >= 0) {
                spans += start..<index
                start = -1
            }
        } else if (start < 0) {
            start = index
        }
    }
    if (start >= 0) spans += start..<text.length
    return spans
}
