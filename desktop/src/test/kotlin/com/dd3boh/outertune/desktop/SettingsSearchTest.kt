/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings search lands each setting on the page it is drawn on now. */
class SettingsSearchTest {

    private fun categoryOf(title: String): SettingsCategory =
        SETTING_ENTRIES.single { it.title == title }.category

    @Test
    fun `moved settings are found on their new pages`() {
        assertEquals(SettingsCategory.Appearance, categoryOf("Seek bar style"))
        assertEquals(SettingsCategory.Appearance, categoryOf("Visualiser"))
        assertEquals(SettingsCategory.System, categoryOf("Close to the system tray"))
        assertEquals(SettingsCategory.System, categoryOf("Battery saver"))
        assertEquals(SettingsCategory.System, categoryOf("Hardware acceleration"))
        assertEquals(SettingsCategory.Content, categoryOf("Hide explicit content"))
    }

    @Test
    fun `a search finds a setting by a word that is not in its title`() {
        assertTrue(searchSettings("tray").any { it.category == SettingsCategory.System })
        assertTrue(searchSettings("slider").any { it.title == "Seek bar thickness" })
    }

    @Test
    fun `every setting is listed once`() {
        val titles = SETTING_ENTRIES.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `every category has something to find`() {
        SettingsCategory.entries.forEach { category ->
            assertTrue("$category has no entries", SETTING_ENTRIES.any { it.category == category })
        }
    }
}
