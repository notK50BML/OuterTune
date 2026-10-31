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
 * Which lyrics providers are asked about a song, and in what order.
 *
 * The YouTube two were added after people had already saved an order, so the part that matters most
 * is that a saved order still works and gains them rather than losing anything.
 */
class LyricsProvidersTest {

    private val all = LyricsSource.entries.toSet()

    @Test
    fun `the default order puts YouTube's two last, captions after its lyrics`() {
        assertEquals(
            listOf(
                LyricsSource.BetterLyrics,
                LyricsSource.LrcLib,
                LyricsSource.KuGou,
                LyricsSource.YouTubeMusic,
                LyricsSource.YouTubeSubtitle,
            ),
            LyricsSource.DEFAULT,
        )
        assertEquals(LyricsSource.entries.toSet(), LyricsSource.DEFAULT.toSet())
    }

    @Test
    fun `an order saved before the YouTube sources existed keeps itself and gains them at the end`() {
        assertEquals(
            listOf(
                LyricsSource.KuGou,
                LyricsSource.LrcLib,
                LyricsSource.BetterLyrics,
                LyricsSource.YouTubeMusic,
                LyricsSource.YouTubeSubtitle,
            ),
            LyricsSource.ordered("KuGou,LRCLIB,BetterLyrics"),
        )
    }

    @Test
    fun `a YouTube source moved up stays where it was put`() {
        val order = LyricsSource.ordered("YouTube Music,LRCLIB")
        assertEquals(LyricsSource.YouTubeMusic, order.first())
        assertEquals(LyricsSource.entries.size, order.size)
    }

    @Test
    fun `a song from YouTube is asked of every provider switched on, in order`() {
        val order = LyricsSource.ordered("YouTube Subtitle,KuGou")
        val enabled = all - LyricsSource.LrcLib
        assertEquals(
            listOf(LyricsSource.YouTubeSubtitle, LyricsSource.KuGou, LyricsSource.BetterLyrics, LyricsSource.YouTubeMusic),
            LyricsRepository.askable(order, enabled, "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `a file on this computer is not asked of the providers that need a YouTube id`() {
        assertEquals(
            listOf(LyricsSource.BetterLyrics, LyricsSource.LrcLib, LyricsSource.KuGou),
            LyricsRepository.askable(LyricsSource.DEFAULT, all, "local:0123456789abcdef0123"),
        )
    }

    @Test
    fun `the YouTube sources are on until switched off, as on the phone`() {
        val store = object : SettingsStore {
            val values = HashMap<String, String>()
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) { values[key] = value }
        }
        val settings = Settings(store)
        assertTrue(LyricsSource.entries.all { settings.isEnabled(it) })

        settings.setEnabled(LyricsSource.YouTubeSubtitle, false)
        assertFalse(Settings(store).isEnabled(LyricsSource.YouTubeSubtitle))
        assertTrue(Settings(store).isEnabled(LyricsSource.YouTubeMusic))
    }

    @Test
    fun `only the captions are marked as a transcript`() {
        assertEquals(listOf(LyricsSource.YouTubeSubtitle), LyricsSource.entries.filter { it.transcript })
        assertEquals(
            listOf(LyricsSource.YouTubeMusic, LyricsSource.YouTubeSubtitle),
            LyricsSource.entries.filter { it.byVideoId },
        )
    }
}
