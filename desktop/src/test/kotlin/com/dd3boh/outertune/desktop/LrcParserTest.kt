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
 * The LRC parser, against the shapes real files come in.
 *
 * Lyrics are one of the few things where being slightly wrong is worse than being absent: a line
 * highlighted half a second early is more distracting than no highlight at all, because the eye
 * follows it. So the timing arithmetic is tested directly rather than by looking at it.
 */
class LrcParserTest {

    private val simple = """
        [ti:A Song]
        [ar:Someone]
        [00:12.00]First line
        [00:15.50]Second line
        [01:03.25]Third line
    """.trimIndent()

    @Test
    fun `timestamps become milliseconds`() {
        val lyrics = LrcParser.parse(simple, "test")
        assertEquals(listOf(12_000L, 15_500L, 63_250L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `metadata is not shown as a line`() {
        // The title and artist tags are for the file, not for the listener.
        val lyrics = LrcParser.parse(simple, "test")
        assertEquals(3, lyrics.lines.size)
        assertTrue(lyrics.lines.none { it.text.contains("A Song") })
    }

    @Test
    fun `two-digit fractions are hundredths, three are milliseconds`() {
        // The difference between reading "50" as 50ms and as 500ms is nearly half a second on every
        // line - enough to make the highlight visibly lead the vocal for a whole song.
        val lyrics = LrcParser.parse("[00:01.5]a\n[00:02.50]b\n[00:03.500]c", "test")
        assertEquals(listOf(1_500L, 2_500L, 3_500L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `a line with several timestamps appears at each of them`() {
        // A repeated chorus is written once with all its times. Taking only the first would drop the
        // repeat entirely, and the lyrics would stop following the song partway through.
        val lyrics = LrcParser.parse("[00:10.00][01:20.00][02:30.00]Chorus", "test")
        assertEquals(3, lyrics.lines.size)
        assertEquals(listOf(10_000L, 80_000L, 150_000L), lyrics.lines.map { it.timeMs })
        assertTrue(lyrics.lines.all { it.text == "Chorus" })
    }

    @Test
    fun `repeated lines come back in time order`() {
        // They are written where they first occur, so a file with two multi-stamp choruses arrives
        // interleaved. Out of order, the highlight jumps backwards mid-song.
        val lyrics = LrcParser.parse("[00:10.00][02:00.00]A\n[00:30.00][02:30.00]B", "test")
        assertEquals(listOf(10_000L, 30_000L, 120_000L, 150_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `word timings are stripped rather than shown`() {
        // Enhanced LRC marks each word. Per-word highlighting is a separate feature; until it
        // exists, leaving the markers in would print them as if they were lyrics.
        val lyrics = LrcParser.parse("[00:12.00]<00:12.00>Hello <00:12.50>there", "test")
        assertEquals("Hello there", lyrics.lines.single().text)
    }

    @Test
    fun `the offset tag shifts every line`() {
        // Files timed against a different master carry this. Ignoring it puts the whole song
        // consistently early or late, which is the one error that is obvious to every listener.
        val lyrics = LrcParser.parse("[offset:+500]\n[00:10.00]a", "test")
        assertEquals(9_500L, lyrics.lines.single().timeMs)

        val negative = LrcParser.parse("[offset:-500]\n[00:10.00]a", "test")
        assertEquals(10_500L, negative.lines.single().timeMs)
    }

    @Test
    fun `lyrics without timestamps are plain, not synced`() {
        val lyrics = LrcParser.parse("Just some words\nAnd some more", "test")
        assertFalse("plain text should not claim to be synced", lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertTrue("a plain line has no time", lyrics.lines.all { it.timeMs == null })
    }

    @Test
    fun `an empty line in the file is not an empty lyric line`() {
        val lyrics = LrcParser.parse("[00:10.00]a\n\n\n[00:20.00]b", "test")
        assertEquals(2, lyrics.lines.size)
    }

    @Test
    fun `a timestamp with no words is kept`() {
        // Instrumental breaks are written this way, and they are what makes a long gap read as
        // intentional rather than as the lyrics having stopped working.
        val lyrics = LrcParser.parse("[00:10.00]a\n[00:20.00]\n[00:30.00]b", "test")
        assertEquals(3, lyrics.lines.size)
        assertEquals("", lyrics.lines[1].text)
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertTrue(LrcParser.parse("", "test").isEmpty)
        assertTrue(LrcParser.parse("   \n  ", "test").isEmpty)
    }

    @Test
    fun `the current line is the last one that has started`() {
        val lines = LrcParser.parse(simple, "test").lines
        assertEquals(-1, LrcParser.lineAt(lines, 0))
        assertEquals(-1, LrcParser.lineAt(lines, 11_999))
        assertEquals(0, LrcParser.lineAt(lines, 12_000))
        assertEquals(0, LrcParser.lineAt(lines, 15_499))
        assertEquals(1, LrcParser.lineAt(lines, 15_500))
        assertEquals(2, LrcParser.lineAt(lines, 999_999))
    }

    @Test
    fun `finding the current line agrees with a plain scan`() {
        // The search is a binary one because it runs every frame. It has to give the same answer a
        // scan would at every position, including the boundaries.
        val lines = LrcParser.parse(simple, "test").lines
        for (ms in 0L..70_000L step 250L) {
            val scanned = lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= ms }
            assertEquals("at ${ms}ms", scanned, LrcParser.lineAt(lines, ms))
        }
    }

    @Test
    fun `plain lyrics never report a current line`() {
        // Nothing to follow, so nothing should be highlighted - a highlight moving through unsynced
        // lyrics would be inventing timings that are not in the file.
        val lines = LrcParser.parse("no timings here\nsecond line", "test").lines
        assertEquals(-1, LrcParser.lineAt(lines, 30_000))
    }

    @Test
    fun `a malformed timestamp does not lose the line`() {
        // Better to show a line without following it than to drop words the file contains.
        val lyrics = LrcParser.parse("[xx:yy.zz]still words", "test")
        assertEquals(1, lyrics.lines.size)
        assertFalse(lyrics.synced)
    }
}
