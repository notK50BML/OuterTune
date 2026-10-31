/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which provider's answer is kept - the phone's rules, see LyricsRace. */
class LyricsRaceTest {

    private val order = LyricsSource.DEFAULT

    private fun words() = "w" to Lyrics(
        listOf(LyricLine(0L, "a", words = listOf(LyricWord("a", 0, 100)))),
        synced = true,
        source = "test",
    )

    private fun lines() = "l" to Lyrics(listOf(LyricLine(0L, "a")), synced = true, source = "test")

    private fun plain() = "p" to Lyrics(listOf(LyricLine(null, "a")), synced = false, source = "test")

    @Test
    fun `word timings beat line timings from a provider higher in the order`() {
        val race = LyricsRace(order)
        race.offer(LyricsSource.LrcLib, lines())
        race.offer(LyricsSource.KuGou, words())
        assertEquals(LyricsSource.KuGou, race.best?.source)
    }

    @Test
    fun `between two of a kind the order decides, not who answered first`() {
        val race = LyricsRace(order)
        race.offer(LyricsSource.KuGou, lines())
        race.offer(LyricsSource.LrcLib, lines())
        assertEquals(LyricsSource.LrcLib, race.best?.source)
    }

    @Test
    fun `captions sit under a real line-timed file and over plain words`() {
        val race = LyricsRace(LyricsSource.ordered("YouTube Subtitle,YouTube Music,KuGou"))
        race.offer(LyricsSource.YouTubeMusic, plain())
        race.offer(LyricsSource.YouTubeSubtitle, lines())
        assertEquals(LyricsSource.YouTubeSubtitle, race.best?.source)
        race.offer(LyricsSource.KuGou, lines())
        assertEquals(LyricsSource.KuGou, race.best?.source)
    }

    @Test
    fun `line timings never end the lookup early`() {
        // A slower provider could still answer with word timings.
        val race = LyricsRace(order)
        assertFalse(race.offer(LyricsSource.BetterLyrics, null))
        assertFalse(race.offer(LyricsSource.LrcLib, lines()))
    }

    @Test
    fun `word timings end it only once everyone above has answered`() {
        val race = LyricsRace(order)
        assertFalse(race.offer(LyricsSource.LrcLib, words()))
        assertTrue(race.offer(LyricsSource.BetterLyrics, null))
    }

    @Test
    fun `the top provider's word timings end it at once`() {
        assertTrue(LyricsRace(order).offer(LyricsSource.BetterLyrics, words()))
    }

    @Test
    fun `nobody with lyrics leaves nothing held`() {
        val race = LyricsRace(order)
        order.forEach { race.offer(it, null) }
        assertNull(race.best)
    }
}
