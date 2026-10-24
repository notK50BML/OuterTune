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
}
