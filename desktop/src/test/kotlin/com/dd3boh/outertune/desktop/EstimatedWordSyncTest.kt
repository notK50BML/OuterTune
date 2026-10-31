/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatedWordSyncTest {

    private fun lyrics(vararg lines: Pair<Long, String>) =
        Lyrics(lines.map { (at, text) -> LyricLine(at, text) }, synced = true, source = "test")

    @Test
    fun `every line gets one word per word, in order, inside the line`() {
        val out = lyrics(0L to "Waiting in the car", 4_000L to "Waiting for a ride in the dark", 8_000L to "end")
            .withEstimatedWords()
        val first = out.lines[0]
        assertEquals(listOf("Waiting", "in", "the", "car"), first.words.map { it.text })
        first.words.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs) }
        assertTrue(first.words.first().startMs >= 0)
        assertTrue(first.words.last().endMs <= 4_000L)
    }

    @Test
    fun `a comma holds the sweep before the next word`() {
        val out = lyrics(0L to "Hold on, wait for me", 3_000L to "next").withEstimatedWords()
        val words = out.lines[0].words
        val afterComma = words[2].startMs - words[1].endMs
        val plainGap = words[1].startMs - words[0].endMs
        assertTrue("comma pause $afterComma vs $plainGap", afterComma > plainGap)
    }

    @Test
    fun `real word timings are left alone`() {
        val real = LyricLine(0L, "a b", words = listOf(LyricWord("a", 0, 100), LyricWord("b", 500, 900)))
        val out = Lyrics(listOf(real, LyricLine(2_000L, "c")), synced = true, source = "t").withEstimatedWords()
        assertEquals(real, out.lines[0])
    }

    @Test
    fun `unsynced lyrics are untouched`() {
        val plain = Lyrics(listOf(LyricLine(null, "a b c")), synced = false, source = "t")
        assertEquals(plain, plain.withEstimatedWords())
    }

    @Test
    fun `a line before a long break finishes when the words do, not at the break`() {
        val out = lyrics(
            0L to "one two three four", 2_000L to "five six seven eight",
            4_000L to "nine ten", 20_000L to "after the break",
        ).withEstimatedWords()
        assertTrue("swept across the break", out.lines[2].words.last().endMs < 8_000L)
    }

    @Test
    fun `a line before a break longer than any line is still swept, from the song's pace`() {
        // Thirty seconds and more is a break rather than a line. The phone times such a line from
        // the pace; this used to leave it with no sweep at all.
        val out = lyrics(
            0L to "one two three four", 2_000L to "five six seven eight",
            4_000L to "nine ten", 60_000L to "after the break",
        ).withEstimatedWords()
        val words = out.lines[2].words
        assertEquals(listOf("nine", "ten"), words.map { it.text })
        assertTrue("swept across the break", words.last().endMs < 8_000L)
    }
}
