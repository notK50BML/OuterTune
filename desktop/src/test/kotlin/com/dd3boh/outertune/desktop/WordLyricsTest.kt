/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word-by-word timings, from both formats that carry them.
 *
 * Fixtures use invented words rather than any real song, since nothing here depends on the words
 * themselves - only on where their timings land.
 */
class WordLyricsTest {

    private fun ttml(body: String) = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body><div>$body</div></body>
        </tt>
    """.trimIndent()

    private val syllables = ttml(
        """<p begin="00:21.100" end="00:23.200">""" +
            """<span begin="00:21.100" end="00:21.600">Alpha </span>""" +
            """<span begin="00:21.600" end="00:22.100">Bravo </span>""" +
            """<span begin="00:22.100" end="00:23.200">Charlie</span></p>"""
    )

    // ---- TTML ------------------------------------------------------------------------------

    @Test
    fun `ttml gives a line with its words`() {
        val lyrics = LrcParser.parseTtml(syllables, "test")
        val line = lyrics.lines.single()
        assertEquals("Alpha Bravo Charlie", line.text)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), line.words.map { it.text })
    }

    @Test
    fun `each word keeps its own start and end`() {
        // The end being a real end, not the next word's start, is the point of using TTML at all -
        // a held final syllable is exactly where a next-word-start highlight runs ahead of the voice.
        val words = LrcParser.parseTtml(syllables, "test").lines.single().words
        assertEquals(21_100L, words[0].startMs)
        assertEquals(21_600L, words[0].endMs)
        assertEquals(22_100L, words[2].startMs)
        assertEquals(23_200L, words[2].endMs)
    }

    @Test
    fun `a ttml file reports itself as word synced`() {
        val lyrics = LrcParser.parseTtml(syllables, "test")
        assertTrue(lyrics.synced)
        assertTrue(lyrics.wordSynced)
    }

    @Test
    fun `unparseable ttml is empty rather than an exception`() {
        // A provider returning something unexpected should cost one lookup, not the whole pane.
        val lyrics = LrcParser.parseTtml("<tt xmlns=\"http://www.w3.org/ns/ttml\"><nonsense", "test")
        assertTrue(lyrics.isEmpty)
    }

    @Test
    fun `the format is sniffed, not taken from the source name`() {
        // The cache stores raw text; the stored source is a claim about where it came from, not
        // about what it is. Mislabelling must not change how it parses.
        val asTtml = LrcParser.parseAny(syllables, "mislabelled")
        assertTrue("TTML should still parse as TTML", asTtml.wordSynced)

        val asLrc = LrcParser.parseAny("[00:10.00]Delta", "BetterLyrics")
        assertEquals("Delta", asLrc.lines.single().text)
    }

    // ---- Enhanced LRC ----------------------------------------------------------------------

    @Test
    fun `enhanced lrc word marks become words`() {
        val lyrics = LrcParser.parse("[00:10.00]<00:10.00>Alpha <00:10.50>Bravo<00:11.20>", "test")
        val line = lyrics.lines.single()
        assertEquals("Alpha Bravo", line.text)
        assertEquals(listOf("Alpha", "Bravo"), line.words.map { it.text })
        assertEquals(10_000L, line.words[0].startMs)
        assertEquals(10_500L, line.words[0].endMs)
    }

    @Test
    fun `a trailing end mark closes the last word`() {
        // Enhanced LRC ends a line with a bare mark. Dropping it would throw away a time the file
        // actually stated and leave the last word to be guessed at from the following line.
        val line = LrcParser.parse("[00:10.00]<00:10.00>Alpha <00:10.50>Bravo<00:11.20>", "test")
            .lines.single()
        assertEquals(11_200L, line.words.last().endMs)
    }

    @Test
    fun `a last word with nothing after it is closed by the next line`() {
        val lines = LrcParser.parse(
            "[00:10.00]<00:10.00>Alpha <00:10.50>Bravo\n[00:11.00]<00:11.00>Charlie",
            "test",
        ).lines
        assertEquals(11_000L, lines[0].words.last().endMs)
    }

    @Test
    fun `a last word before a long gap does not stay lit for the whole gap`() {
        // Otherwise the final word before an instrumental break holds its highlight through the
        // break, which reads as the lyrics having frozen rather than as a rest.
        val line = LrcParser.parse(
            "[00:10.00]<00:10.00>Alpha <00:10.50>Bravo\n[02:00.00]<02:00.00>Charlie",
            "test",
        ).lines.first()
        val held = line.words.last().endMs - line.words.last().startMs
        assertTrue("the last word stayed lit for ${held}ms", held <= 2_000L)
    }

    @Test
    fun `a repeated line carries its words to each repeat`() {
        // Word times belong to the first occurrence. Left alone, every repeat would be checked
        // against the first one's times and light up all at once on arrival.
        val lines = LrcParser.parse(
            "[00:10.00][01:00.00]<00:10.00>Alpha <00:10.50>Bravo<00:11.00>",
            "test",
        ).lines
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].words.first().startMs)
        assertEquals(60_000L, lines[1].words.first().startMs)
        // And the shape of the line is preserved, not just its start.
        assertEquals(
            lines[0].words[1].startMs - lines[0].words[0].startMs,
            lines[1].words[1].startMs - lines[1].words[0].startMs,
        )
    }

    @Test
    fun `plain lrc has no words`() {
        // Not the same as a line of one word: a line timed as a whole must not be rendered as
        // though its first word lasts the entire line.
        val lyrics = LrcParser.parse("[00:10.00]Alpha Bravo Charlie", "test")
        assertTrue(lyrics.lines.single().words.isEmpty())
        assertTrue(lyrics.synced)
        assertFalse("line-timed lyrics are not word synced", lyrics.wordSynced)
    }

    // ---- Using them ------------------------------------------------------------------------

    @Test
    fun `shifting the lyrics moves the words with their line`() {
        // Shifting the line and leaving the words would put the highlight and the line it is
        // highlighting in different places, which is worse than not shifting at all.
        val shifted = LrcParser.parseTtml(syllables, "test").shiftedBy(500)
        val line = shifted.lines.single()
        assertEquals(21_600L, line.timeMs)
        assertEquals(21_600L, line.words.first().startMs)
        assertEquals(22_100L, line.words.first().endMs)
    }

    @Test
    fun `shifting plain lyrics leaves them alone`() {
        val plain = LrcParser.parse("no timings", "test")
        assertEquals(plain, plain.shiftedBy(1_000))
    }
}
