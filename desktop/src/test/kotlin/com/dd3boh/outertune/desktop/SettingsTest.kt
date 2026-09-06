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
 * Settings: defaults, persistence, and what happens when the stored value is nonsense.
 *
 * The last one is the reason this is tested at all. A settings layer that throws on a bad stored
 * value turns a cosmetic preference into an app that will not start, and it does so on exactly the
 * install where something has already gone wrong.
 */
class SettingsTest {

    private class FakeStore(initial: Map<String, String> = emptyMap()) : SettingsStore {
        val values = initial.toMutableMap()
        var writes = 0
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
            writes++
        }
    }

    @Test
    fun `an unset setting reads as its default`() {
        val settings = Settings(FakeStore())
        assertFalse("seek buttons should default off, as on Android", settings.showSeekButtons)
        assertTrue("value colouring should default on", settings.colourByValue)
        assertEquals(0, settings.lyricsOffsetMs)
        assertEquals(BackgroundStyle.Gradient, settings.background)
    }

    @Test
    fun `writing a setting stores it`() {
        val store = FakeStore()
        val settings = Settings(store)
        settings.showSeekButtons = true
        assertEquals("true", store.values["player.seekButtons"])
    }

    @Test
    fun `a stored setting is read back on the next run`() {
        val store = FakeStore()
        Settings(store).apply {
            showSeekButtons = true
            lyricsOffsetMs = -250
            background = BackgroundStyle.Frosted
        }
        // A second instance over the same store is what starting the app again looks like.
        val reloaded = Settings(store)
        assertTrue(reloaded.showSeekButtons)
        assertEquals(-250, reloaded.lyricsOffsetMs)
        assertEquals(BackgroundStyle.Frosted, reloaded.background)
    }

    @Test
    fun `writing the same value again does not touch the store`() {
        // Settings are bound to controls that fire on every frame of a drag. Writing through on each
        // one would be a database round trip per pixel.
        val store = FakeStore()
        val settings = Settings(store)
        settings.lyricsOffsetMs = 100
        val after = store.writes
        settings.lyricsOffsetMs = 100
        settings.lyricsOffsetMs = 100
        assertEquals("an unchanged value should not be written", after, store.writes)
    }

    @Test
    fun `an unparseable stored value falls back to the default`() {
        val store = FakeStore(
            mapOf(
                "player.seekButtons" to "yes please",
                "lyrics.offsetMs" to "quite a lot",
                "ui.background" to "Holographic",
            )
        )
        val settings = Settings(store)
        assertFalse(settings.showSeekButtons)
        assertEquals(0, settings.lyricsOffsetMs)
        assertEquals(BackgroundStyle.Gradient, settings.background)
    }

    @Test
    fun `a removed background style does not break an existing install`() {
        // The realistic version of the case above: someone's stored setting names a style that a
        // later version dropped. It should quietly become the default, not stop the app.
        val settings = Settings(FakeStore(mapOf("ui.background" to "SomeStyleThatWasRemoved")))
        assertEquals(BackgroundStyle.Gradient, settings.background)
    }

    @Test
    fun `every background style is offered with something to read`() {
        // These are shown as a list of choices, and a choice with no label is not a choice.
        BackgroundStyle.entries.forEach { style ->
            assertTrue("${style.name} has no label", style.label.isNotBlank())
            assertTrue("${style.name} has no description", style.description.isNotBlank())
        }
    }
}
