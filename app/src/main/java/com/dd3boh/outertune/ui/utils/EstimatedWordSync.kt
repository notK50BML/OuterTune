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
 * Every line still begins on time, because its start is real. What is fabricated is the
 * distribution across it, and three things decide that:
 *
 * **Syllables, not characters.** The first version weighted each word by its length, which is a
 * worse proxy than it looks: "through" is seven characters and one syllable, "away" is four and
 * two. Singing is measured in syllables, so the sweep now is too - and the difference is visible
 * on any line with a long monosyllable in it, where the highlight used to sit on one word while
 * the singer had moved on.
 *
 * **Punctuation is time.** A comma is a breath and a full stop is a rest; neither has any ink, and
 * the old version swept straight through both at full speed, so the highlight arrived at the next
 * phrase before the singer did and stayed ahead for the rest of the line. Each mark is now worth a
 * share of the line, in proportion to how long a syllable takes *in this song* - a slow ballad gets
 * a longer comma than a fast verse, which is the thing a fixed number of milliseconds cannot do.
 *
 * **A line ends when the singing ends.** A line's end time is really the next line's start, which
 * includes whatever instrumental sits between them - so on a line followed by a four-bar break, the
 * sweep used to crawl across the words at a quarter speed and finish long after the vocal. The pace
 * of the whole file gives a better estimate of how long the words themselves take, and the sweep
 * now finishes there and holds. See [paceMsPerSyllable].
 *
 * The highlight will still drift within a line - held notes and rests are exactly what this cannot
 * know about - and snap back at the start of the next, which is the trade. Lines carrying real word
 * timings are left exactly as they are: a genuine sync is always better than this, and where a
 * provider supplies one it must win.
 */
fun SemanticLyrics.SyncedLyrics.withEstimatedWordSync(): SemanticLyrics.SyncedLyrics {
    val pace = paceMsPerSyllable(text)
    return SemanticLyrics.SyncedLyrics(
        text.map { line ->
            if (!line.words.isNullOrEmpty()) {
                line
            } else {
                estimateWords(line, pace)?.let { line.copy(words = it) } ?: line
            }
        }
    )
}

/**
 * How long one syllable takes in this song, in milliseconds.
 *
 * Measured from the file rather than assumed, because the thing it is needed for is deciding when a
 * line's singing has finished - and that answer is three times different between a ballad and a
 * patter verse. Only lines that look like continuous singing contribute: a line whose stated
 * duration is implausible, or which is followed by a gap, would drag the estimate towards the
 * length of the gap rather than the length of the words.
 *
 * The median, not the mean. The distribution's tail is entirely made of lines that end at a chorus
 * break, and those are exactly the outliers this exists to be robust against - one of them can move
 * a mean by half, and cannot move a median at all.
 */
private fun paceMsPerSyllable(lines: List<SemanticLyrics.LyricLine>): Double {
    val samples = lines.mapNotNull { line ->
        if (line.end <= line.start) return@mapNotNull null
        val duration = (line.end - line.start)
        if (duration > MAX_PLAUSIBLE_LINE_MS) return@mapNotNull null
        val syllables = wordSpans(line.text).sumOf { syllableCount(line.text.substring(it.first, it.last + 1)) }
        if (syllables <= 0) return@mapNotNull null
        duration.toDouble() / syllables
    }.sorted()

    if (samples.isEmpty()) return DEFAULT_MS_PER_SYLLABLE
    return samples[samples.size / 2].coerceIn(MIN_MS_PER_SYLLABLE, MAX_MS_PER_SYLLABLE)
}

/**
 * Word spans for one line, or null when the line cannot support a sweep: no real duration to spread
 * (the end is missing or not after the start), or nothing but whitespace to spread it over.
 * Returning null leaves the line to be drawn by the plain, unswept path, which is the correct
 * fallback rather than a degraded sweep.
 */
private fun estimateWords(line: SemanticLyrics.LyricLine, paceMs: Double): MutableList<Word>? {
    if (line.end <= line.start) return null
    // The LRC parser has no next line to end the last one against, so it ends it at Long.MAX_VALUE.
    // Spreading words across that would inch the highlight along at a rate of roughly nothing, which
    // looks broken rather than approximate - and any line claiming to run for minutes is bad data
    // whatever produced it. Falling back to the plain unswept line is the honest answer.
    if (line.end - line.start > MAX_PLAUSIBLE_LINE_MS) return null

    val spans = wordSpans(line.text)
    if (spans.isEmpty()) return null

    val words = spans.map { line.text.substring(it.first, it.last + 1) }
    val syllables = words.map { syllableCount(it).toDouble() }
    // The last word's trailing punctuation buys nothing: there is no following word for the sweep
    // to be held back from, and charging the line for it would just stop the sweep short of its own
    // end.
    val pauses = words.mapIndexed { index, word ->
        if (index == words.lastIndex) 0.0 else pauseSyllables(word)
    }

    val sungWeight = syllables.sum()
    if (sungWeight <= 0.0) return null

    val available = (line.end - line.start).toDouble()
    // What the words themselves plausibly take, from this song's own pace. Capped by the line's
    // real duration, so this can only ever finish a sweep early - never run it past the point where
    // the next line has already started.
    val estimatedSung = (sungWeight * paceMs * PACE_SLACK).coerceAtMost(available)

    // Pauses are charged against the span the sweep actually occupies, and capped as a share of it.
    // Without the cap a two-word line ending in a full stop would spend most of itself holding
    // still, which reads as the sweep having stalled rather than as a rest.
    val rawPause = pauses.sum()
    val maxPauseWeight = sungWeight * MAX_PAUSE_SHARE / (1.0 - MAX_PAUSE_SHARE)
    val pauseScale = if (rawPause > maxPauseWeight && rawPause > 0.0) maxPauseWeight / rawPause else 1.0

    val totalWeight = sungWeight + rawPause * pauseScale
    val msPerWeight = estimatedSung / totalWeight

    val out = ArrayList<Word>(spans.size)
    var consumed = 0.0
    spans.forEachIndexed { index, span ->
        // Each boundary is derived from the running total rather than accumulated per word, so
        // rounding cannot compound: the error stays bounded to one word instead of letting the last
        // one finish measurably early.
        val startMs = consumed * msPerWeight
        consumed += syllables[index]
        val endMs = consumed * msPerWeight
        consumed += pauses[index] * pauseScale

        out += Word(
            timeRange = (line.start + startMs.toULongMs())..(line.start + endMs.toULongMs()),
            charRange = span,
            isRtl = false,
        )
    }
    return out
}

private fun Double.toULongMs(): ULong = if (this <= 0.0) 0uL else this.toLong().toULong()

/**
 * How much of a pause the mark at the end of [word] is worth, measured in syllables.
 *
 * In syllables rather than milliseconds so it scales with the song: multiplied by this file's own
 * pace, a comma in a ballad is longer than a comma in a patter verse, which is what makes the hold
 * read as a breath in both rather than as a stall in one and a twitch in the other.
 *
 * The values are ordered the way the marks are: a comma is a breath, a full stop is a rest, and a
 * dash or an ellipsis is the singer visibly waiting. Nothing here is measured from anything - they
 * are the relative durations punctuation implies when read aloud, which is the same thing the mark
 * is telling a singer.
 */
private fun pauseSyllables(word: String): Double {
    val trailing = word.takeLastWhile { !it.isLetterOrDigit() }
    if (trailing.isEmpty()) return 0.0
    return when {
        trailing.contains("...") || trailing.contains('…') -> PAUSE_ELLIPSIS
        trailing.any { it == '.' || it == '!' || it == '?' } -> PAUSE_SENTENCE
        trailing.any { it == '-' || it == '–' || it == '—' } -> PAUSE_DASH
        trailing.any { it == ',' || it == ';' || it == ':' } -> PAUSE_COMMA
        else -> 0.0
    }
}

/**
 * Roughly how many syllables [word] is sung in.
 *
 * Deliberately a heuristic. A real syllabifier needs a pronunciation dictionary per language, which
 * is not a reasonable thing to ship for a highlight that is admitted to be an approximation - and
 * the failure mode of getting one wrong is that the sweep is slightly ahead on one word, which is
 * strictly better than what weighting by characters did on every word.
 *
 * Two scripts, because they count differently. In CJK and Hangul a character is a syllable, so they
 * are counted directly; a vowel-group rule would return 1 for an entire line of them. Everything
 * else is counted by vowel groups, which is the standard English approximation and is close enough
 * in the other Latin-script languages that show up in lyrics.
 */
internal fun syllableCount(word: String): Int {
    val cleaned = word.trim { !it.isLetterOrDigit() && it != '\'' }
    if (cleaned.isEmpty()) return 0

    var syllabic = 0
    var latin = StringBuilder()
    for (char in cleaned) {
        if (char.isSyllabicScript()) syllabic++ else latin.append(char)
    }

    val latinWord = latin.toString().lowercase()
    var groups = 0
    var inVowel = false
    for (char in latinWord) {
        val vowel = char in "aeiouy"
        if (vowel && !inVowel) groups++
        inVowel = vowel
    }
    // A trailing "e" is usually silent - "time", "made" - but not when it is the only vowel group
    // the word has, or when it follows a consonant plus "l" as in "little", where it is the
    // syllable's own nucleus.
    if (groups > 1 && latinWord.endsWith("e") && !latinWord.endsWith("le")) groups--

    val total = syllabic + groups
    // A word made entirely of consonants is still sung - "hmm", "psst", and every digit-only token
    // once the stripping above has had its way.
    return if (total > 0) total else if (cleaned.isNotEmpty()) 1 else 0
}

/** Scripts where one character is one syllable. */
private fun Char.isSyllabicScript(): Boolean = when (code) {
    in 0x3040..0x30FF -> true // kana
    in 0x3400..0x4DBF -> true // CJK extension A
    in 0x4E00..0x9FFF -> true // CJK unified
    in 0xAC00..0xD7AF -> true // hangul syllables
    in 0xF900..0xFAFF -> true // CJK compatibility
    else -> false
}

/**
 * Character ranges of the whitespace-separated words in [text], in order.
 *
 * The ranges cover the words themselves and not the spaces between them. Since consecutive words
 * are given touching time ranges, the effect is that a word holds the highlight for its whole slice
 * and the gap after it is crossed instantly at the boundary - so the spaces cost no sweep time and
 * the words get all of it, which is what makes the highlight track the syllables rather than
 * drifting ahead of them.
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

/** Pause lengths, in syllables. See [pauseSyllables]. */
private const val PAUSE_COMMA = 0.45
private const val PAUSE_SENTENCE = 1.0
private const val PAUSE_DASH = 0.8
private const val PAUSE_ELLIPSIS = 1.4

/**
 * The most of a line the pauses may take.
 *
 * A short line ending in a full stop would otherwise spend most of itself holding still, which
 * reads as the sweep having stalled rather than as a rest.
 */
private const val MAX_PAUSE_SHARE = 0.3

/**
 * Applied to the pace-derived estimate of how long a line's words take.
 *
 * Above one, because finishing a sweep slightly late is a much smaller error than finishing it
 * early: late simply means the last word is still brightening as the next line starts, while early
 * leaves the line fully lit with the singer still on it, which reads as the lyrics being out of
 * sync rather than as an approximation.
 */
private const val PACE_SLACK = 1.15

private const val DEFAULT_MS_PER_SYLLABLE = 260.0
private const val MIN_MS_PER_SYLLABLE = 90.0
private const val MAX_MS_PER_SYLLABLE = 900.0
