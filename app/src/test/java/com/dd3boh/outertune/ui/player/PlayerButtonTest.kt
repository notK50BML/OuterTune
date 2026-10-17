/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerButtonTest {

    @Test
    fun `order survives a round trip`() {
        val order = listOf(PlayerButton.DOWNLOAD, PlayerButton.LIKE, PlayerButton.SHARE, PlayerButton.MENU)
        assertEquals(order, PlayerButton.decode(PlayerButton.encode(order)))
    }

    @Test
    fun `never more than the maximum, never a repeat, unknown codes ignored`() {
        val decoded = PlayerButton.decode("LLMDZSEHR")
        assertEquals(PlayerButton.MAX, decoded.size)
        assertEquals(decoded.distinct(), decoded)
        assertEquals(PlayerButton.LIKE, decoded.first())
    }

    @Test
    fun `codes are unique, since they are the stored format`() {
        assertEquals(PlayerButton.entries.size, PlayerButton.entries.map { it.code }.toSet().size)
    }

    @Test
    fun `an upgrade keeps the player exactly as it was`() {
        assertEquals(
            listOf(PlayerButton.SLEEP, PlayerButton.LIKE, PlayerButton.EQUALIZER, PlayerButton.MENU),
            PlayerButton.fromLegacy(showSleepTimer = true, showEqualizer = true),
        )
        assertEquals(listOf(PlayerButton.LIKE, PlayerButton.MENU), PlayerButton.fromLegacy(false, false))
    }

    @Test
    fun `reset is like and the menu`() {
        assertEquals(listOf(PlayerButton.LIKE, PlayerButton.MENU), PlayerButton.decode(PlayerButton.DEFAULT))
        assertTrue(PlayerButton.decode("").isEmpty())
    }
}
