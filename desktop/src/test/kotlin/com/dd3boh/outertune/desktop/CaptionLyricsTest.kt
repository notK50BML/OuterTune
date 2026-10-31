/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A YouTube caption track on its way to being lyrics, which must come out as it does on the phone.
 *
 * The track and the text it should become are the ones in `:innertube`'s CaptionAnnotationTest,
 * which runs them through the phone's two steps. Here they go through the desktop's
 * LyricsRepository.captionLyrics; if either side changes what it does, one of the two fails.
 */
class CaptionLyricsTest {

    private val transcript = "[00:01.000]>> [Music]\n" +
        "[00:05.000]♪ Lanterns over the harbour ♪\n" +
        "[00:08.000]> We waited by the water. Nobody came home\n" +
        "[00:14.000][Singing] and the tide rolled in\n" +
        "[00:18.000][inaudible]\n" +
        "[00:20.000][Applause]"

    private val stored = "[00:01.000]♪\n" +
        "[00:05.000]Lanterns over the harbour\n" +
        "[00:08.000]We waited by the water.\n" +
        "[00:11.53]Nobody came home\n" +
        "[00:14.000]and the tide rolled in\n" +
        "[00:18.000]\n" +
        "[00:20.000][Applause]"

    @Test
    fun `the desktop keeps the same text the phone keeps`() {
        val (text, _) = LyricsRepository.captionLyrics(transcript)!!
        assertEquals(stored, text)
    }

    @Test
    fun `and draws the same lines from it`() {
        val (_, lyrics) = LyricsRepository.captionLyrics(transcript)!!
        assertTrue(lyrics.synced)
        assertEquals(
            listOf(
                1_000L to "♪",
                5_000L to "Lanterns over the harbour",
                8_000L to "We waited by the water.",
                11_530L to "Nobody came home",
                14_000L to "and the tide rolled in",
                18_000L to "",
                20_000L to "[Applause]",
            ),
            lyrics.lines.map { it.timeMs to it.text },
        )
    }

    @Test
    fun `reading it back from the cache gives the same lines`() {
        // What is stored is read again on every later play, through parseAny's own cleanup.
        val (text, fresh) = LyricsRepository.captionLyrics(transcript)!!
        assertEquals(fresh, LrcParser.parseAny(text, LyricsRepository.YOUTUBE_SUBTITLE))
    }

    @Test
    fun `a track of nothing but annotations is no lyrics`() {
        assertNull(LyricsRepository.captionLyrics("[00:01.000][Music]\n[00:30.000][Singing]\n[00:40.000][Music]"))
        assertNull(LyricsRepository.captionLyrics(""))
    }
}
