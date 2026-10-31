/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Translations, leading words and repeated lines in LRC - see LrcParser.parse. */
class LyricLinesTest {

    @Test
    fun `a line and its translation at the same time are one line`() {
        val lyrics = LrcParser.parse("[00:10.00]사랑해\n[00:10.00]I love you\n[00:15.00]Next", "test")
        assertEquals(2, lyrics.lines.size)
        assertEquals("사랑해", lyrics.lines[0].text)
        assertEquals("I love you", lyrics.lines[0].translation)
        assertNull(lyrics.lines[1].translation)
        // The original is the line found at that time, not the translation.
        assertEquals(0, LrcParser.lineAt(lyrics.lines, 12_000))
    }

    @Test
    fun `words before the first word mark are kept`() {
        val line = LrcParser.parse("[00:10.00]Hello <00:10.40>world <00:11.00>", "test").lines.single()
        assertEquals(listOf("Hello", "world"), line.words.map { it.text })
        assertEquals(10_000L, line.words[0].startMs)
        assertEquals(10_400L, line.words[0].endMs)
    }

    @Test
    fun `a repeated line keeps its own first word time and moves the copy`() {
        val lines = LrcParser.parse("[00:10.00][00:30.00]<00:10.50>la <00:11.00>la <00:11.50>", "test").lines
        assertEquals(10_500L, lines[0].words[0].startMs)
        assertEquals(30_500L, lines[1].words[0].startMs)
    }

    // ---- the phone's readings of LRC's variants -------------------------------------------

    @Test
    fun `untimed lines after a timed one are drawn beneath it`() {
        val lyrics = LrcParser.parse("Header words\n[00:10.00]First\nsecond half\n[00:15.00]Next", "test")
        assertEquals(listOf("First", "Next"), lyrics.lines.map { it.text })
        assertEquals("second half", lyrics.lines[0].translation)
    }

    @Test
    fun `a timestamp after words starts a line of its own`() {
        val lyrics = LrcParser.parse("[00:01.00]one[00:02.00]two", "test")
        assertEquals(listOf(1_000L to "one", 2_000L to "two"), lyrics.lines.map { it.timeMs to it.text })
    }

    @Test
    fun `word marks with no line timestamp still time the line`() {
        val line = LrcParser.parse("<00:11.20>Alpha <00:12.80>Bravo <00:13.50>", "test").lines.single()
        assertEquals(11_200L, line.timeMs)
        assertEquals("Alpha Bravo", line.text)
        assertEquals(listOf("Alpha", "Bravo"), line.words.map { it.text })
    }

    @Test
    fun `singer marks are taken out of the words`() {
        val lyrics = LrcParser.parse("[00:10.00]v1: Alpha\n[00:12.00] v2:Bravo\n[00:14.00]F:Charlie", "test")
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), lyrics.lines.map { it.text })
    }

    @Test
    fun `background vocals go beneath the line before them`() {
        val lyrics = LrcParser.parse("[00:10.00]Alpha\n[bg: <00:10.50>(Bravo)]\n[00:12.00]Charlie", "test")
        assertEquals(listOf("Alpha", "Charlie"), lyrics.lines.map { it.text })
        assertEquals("Bravo", lyrics.lines[0].translation)
    }

    @Test
    fun `a file stamped all at zero is plain lyrics`() {
        val lyrics = LrcParser.parse("[00:00.00]Alpha\n[00:00.00]Bravo", "test")
        assertEquals(false, lyrics.synced)
        assertEquals(listOf("Alpha", "Bravo"), lyrics.lines.map { it.text })
    }

    @Test
    fun `an empty line at a real line's time is dropped`() {
        val lyrics = LrcParser.parse("[00:10.00]Alpha\n[00:10.00]\n[00:15.00]Bravo", "test")
        assertEquals(listOf("Alpha", "Bravo"), lyrics.lines.map { it.text })
        assertNull(lyrics.lines[0].translation)
        assertEquals(0, LrcParser.lineAt(lyrics.lines, 12_000))
    }

    @Test
    fun `empty lines before the first words are dropped`() {
        val lyrics = LrcParser.parse("[00:01.00]\n[00:05.00]Alpha", "test")
        assertEquals(listOf(5_000L to "Alpha"), lyrics.lines.map { it.timeMs to it.text })
    }

    @Test
    fun `an offset cannot move a line before the start`() {
        val lyrics = LrcParser.parse("[offset:+5000]\n[00:01.00]Alpha\n[00:10.00]Bravo", "test")
        assertEquals(listOf(0L, 5_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `a fraction of more than three digits is still a timestamp`() {
        assertEquals(12_345L, LrcParser.parse("[00:12.3456]Alpha", "test").lines.single().timeMs)
    }

    @Test
    fun `what is shown is cleaned the way the phone cleans it`() {
        // A row cached before the fetch path cleaned anything, or a file beside a song.
        val lyrics = LrcParser.parseAny("[00:10.00]>> [Music] Alpha\n[00:12.00][Music]", "test")
        assertEquals(listOf("Alpha", "♪"), lyrics.lines.map { it.text })
    }
}
