/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** How the player's buttons are stored - see PlayerButtonKind.encode/decode. */
class PlayerButtonsStorageTest {

    @Test
    fun `turning every button off stays off`() {
        // Stored as "" this read back as the legacy five: sleep, like, download, equaliser, menu.
        val stored = PlayerButtonKind.encode(emptyList())
        assertEquals(emptyList<PlayerButtonKind>(), PlayerButtonKind.decode(stored))
    }

    @Test
    fun `an untouched setting is still the legacy row`() {
        assertEquals(PlayerButtonKind.decode(PlayerButtonKind.LEGACY), PlayerButtonKind.decode(""))
    }

    @Test
    fun `a choice round-trips in order`() {
        val chosen = listOf(PlayerButtonKind.MENU, PlayerButtonKind.LIKE)
        assertEquals(chosen, PlayerButtonKind.decode(PlayerButtonKind.encode(chosen)))
    }
}
