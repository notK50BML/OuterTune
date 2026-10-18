/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    private fun settings() = Settings(object : SettingsStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    })

    @Test
    fun roundTripOnTheDesktop() {
        val a = settings().apply {
            themeMode = ThemeMode.Dark
            pureBlack = true
            lyricsTextScale = 140
            eqBands = EqProfiles.encodeBands(listOf(EqBand(100f, 4f, 0.7f, EqBandType.LOW_SHELF)))
            discordToken = "secret"
            searchHistory = "private"
        }
        val text = Profiles.export(a, "Night", Profiles.Category.entries.toSet())
        assertFalse("tokens never leave", "secret" in text)
        assertFalse("history never leaves", "private" in text)

        val b = settings()
        val profile = assertNotNull(Profiles.parse(text)).let { Profiles.parse(text)!! }
        Profiles.apply(b, profile, Profiles.Category.entries.toSet())
        assertEquals(ThemeMode.Dark, b.themeMode)
        assertTrue(b.pureBlack)
        assertEquals(140, b.lyricsTextScale)
        assertEquals(a.eqBands, b.eqBands)
    }

    @Test
    fun onlyChosenCategoriesApply() {
        val a = settings().apply { themeMode = ThemeMode.Light; lyricsTextScale = 180 }
        val profile = Profiles.parse(Profiles.export(a, "x", Profiles.Category.entries.toSet()))!!
        val b = settings()
        Profiles.apply(b, profile, setOf(Profiles.Category.Lyrics))
        assertEquals(ThemeMode.System, b.themeMode)
        assertEquals(180, b.lyricsTextScale)
    }

    @Test
    fun aPhoneProfileAppliesThroughTheSharedNames() {
        val fromPhone = """
            {
              "outertuneProfile": 1, "name": "Phone", "createdOn": "android",
              "categories": {
                "appearance": { "common": { "themeMode": "dark", "pureBlack": true },
                                "android": { "darkMode": "ON" } },
                "equaliser": { "common": { "enabled": true, "balance": 0.25,
                    "bands": [ { "freqHz": 60, "gainDb": 5, "q": 0.8, "type": "low_shelf" } ] } },
                "lyrics": { "common": { "alignment": "left", "textScalePercent": 120 } },
                "player": { "common": { "buttons": "LMSZ" } }
              }
            }
        """.trimIndent()
        val s = settings()
        Profiles.apply(s, Profiles.parse(fromPhone)!!, Profiles.Category.entries.toSet())
        assertEquals(ThemeMode.Dark, s.themeMode)
        assertTrue(s.pureBlack)
        assertTrue(s.eqEnabled)
        assertEquals(0.25f, s.eqBalance)
        assertEquals(listOf(EqBand(60f, 5f, 0.8f, EqBandType.LOW_SHELF)), EqProfiles.decodeBands(s.eqBands))
        assertEquals(LyricsAlignment.Left, s.lyricsAlignment)
        assertEquals(120, s.lyricsTextScale)
        // An unknown button code from the other app is dropped, not kept.
        assertEquals("LMS", s.playerButtons)
    }

    @Test
    fun newerFormatIsRefused() {
        assertEquals(null, Profiles.parse("""{ "outertuneProfile": 99, "categories": {} }"""))
    }
}
