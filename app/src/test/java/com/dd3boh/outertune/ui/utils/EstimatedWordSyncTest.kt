/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invented word timings behind the karaoke sweep on ordinary line-synced lyrics.
 *
 * Everything here is an approximation by construction, so the tests are written around the
 * properties that have to hold rather than around exact milliseconds: words in order, nothing
 * before the line starts, nothing after it ends, and the specific improvements - syllables rather
 * than characters, punctuation costing time, and a line finishing when the singing does.
 */
class EstimatedWordSyncTest {

    private fun line(text: String, startMs: Long, endMs: Long) = SemanticLyrics.LyricLine(
        text = text,
        start = startMs.toULong(),
        end = endMs.toULong(),
        words = null,
        speaker = null,
        isTranslated = false,
    )

    private fun synced(vararg lines: SemanticLyrics.LyricLine) =
        SemanticLyrics.SyncedLyrics(lines.toList())

    private fun estimate(vararg lines: SemanticLyrics.LyricLine) =
        synced(*lines).withEstimatedWordSync().text

    // ---- syllables ------------------------------------------------------------------------------

    @Test
    fun `syllables are counted, not characters`() {
        // The case character weighting gets backwards, and the reason this changed: "through" is
        // seven characters and one syllable, "away" four and two. Weighted by length the sweep
        // sits on "through" for almost twice as long as on "away"; sung, it is the other way up.
        assertEquals(1, syllableCount("through"))
        assertEquals(2, syllableCount("away"))
        assertEquals(1, syllableCount("time"))
        assertEquals(1, syllableCount("made"))
        assertEquals(2, syllableCount("little"))
        assertEquals(1, syllableCount("I"))
        assertEquals(4, syllableCount("everything"))
    }

    @Test
    fun `a word with no vowels still takes time`() {
        // They are sung, and a zero would hand the word no slice at all - the sweep would jump
        // straight over it.
        assertTrue(syllableCount("hmm") >= 1)
        assertTrue(syllableCount("psst") >= 1)
        assertTrue(syllableCount("1999") >= 1)
    }

    @Test
    fun `punctuation does not change a word's syllable count`() {
        assertEquals(syllableCount("away"), syllableCount("away,"))
        assertEquals(syllableCount("away"), syllableCount("\"away...\""))
    }

    @Test
    fun `one character is one syllable in CJK and Hangul`() {
        // A vowel-group rule returns 1 for an entire line of these, which would give the whole
        // line to its first word.
        assertEquals(3, syllableCount("君の名"))
        assertEquals(2, syllableCount("안녕"))
        assertEquals(3, syllableCount("ありが"[0].toString() + "とう".substring(0, 2)))
    }

    @Test
    fun `a longer word gets a longer slice than a shorter one`() {
        val words = estimate(line("I everything", 0, 4000)).single().words!!
        val first = (words[0].timeRange.last - words[0].timeRange.first).toLong()
        val second = (words[1].timeRange.last - words[1].timeRange.first).toLong()
        assertTrue("\"I\" got ${first}ms and \"everything\" got ${second}ms", second > first * 2)
    }

    // ---- punctuation ------------------------------------------------------------------------------

    @Test
    fun `a comma delays the word after it`() {
        // Same words, same line length, one comma. The second word has to start later with it than
        // without it - that gap is the breath, and sweeping through it was what put the highlight
        // ahead of the singer for the rest of the line.
        val plain = estimate(line("hold on tightly now", 0, 4000)).single().words!!
        val comma = estimate(line("hold on, tightly now", 0, 4000)).single().words!!

        assertTrue(
            "the comma did not delay the following word: ${plain[2].timeRange.first} vs ${comma[2].timeRange.first}",
            comma[2].timeRange.first > plain[2].timeRange.first,
        )
    }

    @Test
    fun `a full stop delays more than a comma`() {
        val comma = estimate(line("hold on, tightly now", 0, 4000)).single().words!!
        val stop = estimate(line("hold on. tightly now", 0, 4000)).single().words!!
        assertTrue(
            "a full stop should hold longer than a comma",
            stop[2].timeRange.first > comma[2].timeRange.first,
        )
    }

    @Test
    fun `trailing punctuation on the last word costs nothing`() {
        // There is no following word to hold back from, so charging the line for it would only
        // stop the sweep short of its own end.
        val plain = estimate(line("hold on tightly now", 0, 4000)).single().words!!
        val stopped = estimate(line("hold on tightly now.", 0, 4000)).single().words!!
        assertEquals(
            plain.last().timeRange.last.toLong().toDouble(),
            stopped.last().timeRange.last.toLong().toDouble(),
            30.0,
        )
    }

    @Test
    fun `pauses cannot take over a short line`() {
        // Two words and a full stop. Unbounded, the hold would be most of the line, which reads as
        // the sweep having stalled rather than as a rest.
        val words = estimate(line("Stop. Now", 0, 2000)).single().words!!
        val sung = words.sumOf { (it.timeRange.last - it.timeRange.first).toLong() }
        assertTrue("only ${sung}ms of 2000 was spent sweeping", sung > 2000 * 0.6)
    }

    // ---- when the singing stops ---------------------------------------------------------------------

    @Test
    fun `a line followed by a long gap finishes with the singing`() {
        // A line's end time is really the next line's start, so a four-bar break is inside it. The
        // old behaviour spread the words across the whole thing and crawled; the pace of the rest
        // of the file says how long the words themselves take.
        val lines = estimate(
            line("one two three four", 0, 2000),
            line("five six seven eight", 2000, 4000),
            line("nine ten eleven twelve", 4000, 6000),
            // Same shape of line, but twenty seconds of instrumental after it.
            line("final words right here", 6000, 26000),
        )
        val last = lines.last().words!!
        val finishedAt = last.last().timeRange.last.toLong()
        assertTrue(
            "the sweep ran to ${finishedAt}ms, long past where the singing plausibly ended",
            finishedAt < 6000 + 6000,
        )
        assertTrue("the sweep finished before it started", finishedAt > 6000)
    }

    @Test
    fun `a line is never swept past its own end`() {
        // The other direction, and the one that would actually look broken: a line still lighting
        // up after the next has started.
        val lines = estimate(
            line("a b c", 0, 500),
            line("one two three four five six seven eight nine ten", 500, 1500),
        )
        lines.forEach { line ->
            line.words?.forEach { word ->
                assertTrue(
                    "a word ended at ${word.timeRange.last} past the line's end of ${line.end}",
                    word.timeRange.last <= line.end,
                )
                assertTrue(
                    "a word started at ${word.timeRange.first} before the line's start of ${line.start}",
                    word.timeRange.first >= line.start,
                )
            }
        }
    }

    // ---- what must be left alone -----------------------------------------------------------------

    @Test
    fun `real word timings are never replaced`() {
        // A genuine sync is always better than this, and where a provider supplies one it wins.
        val real = SemanticLyrics.Word(timeRange = 10uL..20uL, charRange = 0..3, isRtl = false)
        val withWords = line("word", 0, 1000).copy(words = mutableListOf(real))
        val out = synced(withWords).withEstimatedWordSync().text.single()
        assertEquals(listOf(real), out.words)
    }

    @Test
    fun `a line with no usable duration is left unswept`() {
        // Null words means the renderer draws it plainly, which is the right fallback rather than
        // a sweep built on a number that is not a duration.
        assertNull(estimate(line("some words", 1000, 1000)).single().words)
        assertNull(estimate(line("some words", 1000, 500)).single().words)
        // The LRC parser ends the final line at Long.MAX_VALUE for want of a next one.
        assertNull(estimate(line("some words", 0, 10_000_000)).single().words)
    }

    @Test
    fun `a blank line is left unswept`() {
        assertNull(estimate(line("", 0, 1000)).single().words)
        assertNull(estimate(line("   ", 0, 1000)).single().words)
    }

    @Test
    fun `words come out in order and cover every word in the line`() {
        val text = "Never gonna give you up, never gonna let you down"
        val words = estimate(line(text, 1000, 5000)).single().words!!
        assertEquals(text.split(" ").size, words.size)

        var previousEnd = 0uL
        words.forEachIndexed { index, word ->
            assertTrue("word $index runs backwards", word.timeRange.last >= word.timeRange.first)
            assertTrue("word $index starts before the previous one ended", word.timeRange.first >= previousEnd)
            previousEnd = word.timeRange.last
            assertNotNull(text.substring(word.charRange.first, word.charRange.last + 1))
        }
    }
}
