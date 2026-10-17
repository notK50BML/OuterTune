/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * Word timings for lyrics that only have line timings, so every synced file sings word by word.
 *
 * The desktop port of the phone's EstimatedWordSync (app/.../ui/utils/EstimatedWordSync.kt), with
 * the same three rules: words are weighted by syllables rather than letters, punctuation buys a
 * pause in proportion to this song's own pace, and a line's singing ends when the pace says it
 * does rather than stretching across an instrumental break to the next line. See that file for the
 * reasoning behind each; the numbers here are the same.
 *
 * Lines with real word timings are left exactly as they are.
 */
fun Lyrics.withEstimatedWords(): Lyrics {
    if (!synced) return this
    val ends = lines.indices.map { i ->
        lines.drop(i + 1).firstNotNullOfOrNull { it.timeMs }
    }
    val pace = paceMsPerSyllable(lines, ends)
    return copy(
        lines = lines.mapIndexed { i, line ->
            if (line.words.isNotEmpty()) line else estimateWords(line, ends[i], pace)?.let { line.copy(words = it) } ?: line
        }
    )
}

private fun paceMsPerSyllable(lines: List<LyricLine>, ends: List<Long?>): Double {
    val samples = lines.mapIndexedNotNull { i, line ->
        val start = line.timeMs ?: return@mapIndexedNotNull null
        val end = ends[i] ?: return@mapIndexedNotNull null
        val duration = end - start
        if (duration <= 0 || duration > MAX_PLAUSIBLE_LINE_MS) return@mapIndexedNotNull null
        val syllables = wordSpans(line.text).sumOf { syllableCount(line.text.substring(it.first, it.last + 1)) }
        if (syllables <= 0) return@mapIndexedNotNull null
        duration.toDouble() / syllables
    }.sorted()
    if (samples.isEmpty()) return DEFAULT_MS_PER_SYLLABLE
    return samples[samples.size / 2].coerceIn(MIN_MS_PER_SYLLABLE, MAX_MS_PER_SYLLABLE)
}

private fun estimateWords(line: LyricLine, end: Long?, paceMs: Double): List<LyricWord>? {
    val start = line.timeMs ?: return null
    // The last line has no next one to end against; give it what its words plausibly take.
    val spans = wordSpans(line.text)
    if (spans.isEmpty()) return null
    val words = spans.map { line.text.substring(it.first, it.last + 1) }
    val syllables = words.map { syllableCount(it).toDouble() }
    val pauses = words.mapIndexed { index, word -> if (index == words.lastIndex) 0.0 else pauseSyllables(word) }
    val sungWeight = syllables.sum()
    if (sungWeight <= 0.0) return null

    val available = when {
        end == null -> sungWeight * paceMs * PACE_SLACK
        end <= start || end - start > MAX_PLAUSIBLE_LINE_MS -> return null
        else -> (end - start).toDouble()
    }
    val estimatedSung = (sungWeight * paceMs * PACE_SLACK).coerceAtMost(available)

    val rawPause = pauses.sum()
    val maxPauseWeight = sungWeight * MAX_PAUSE_SHARE / (1.0 - MAX_PAUSE_SHARE)
    val pauseScale = if (rawPause > maxPauseWeight && rawPause > 0.0) maxPauseWeight / rawPause else 1.0
    val msPerWeight = estimatedSung / (sungWeight + rawPause * pauseScale)

    var consumed = 0.0
    return words.mapIndexed { index, word ->
        val wordStart = consumed * msPerWeight
        consumed += syllables[index]
        val wordEnd = consumed * msPerWeight
        consumed += pauses[index] * pauseScale
        LyricWord(
            text = word,
            startMs = start + wordStart.toLong(),
            endMs = start + wordEnd.toLong(),
            trailingSpace = index != words.lastIndex,
        )
    }
}

/** Longest line a sweep will be estimated for. Beyond this the timing is bad data, not a long line. */
private const val MAX_PLAUSIBLE_LINE_MS = 30_000L

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
