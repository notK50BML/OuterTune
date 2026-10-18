/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent as AwtKey

class KeyBindingsTest {

    @Test
    fun defaultsAnswerWithoutAnythingStored() {
        val b = KeyBindings.decode("")
        assertEquals(KeyAction.PlayPause, b.actionFor(Chord.key(AwtKey.VK_SPACE)))
        assertEquals(KeyAction.Equaliser, b.actionFor(Chord.key(AwtKey.VK_E)))
        assertEquals(KeyAction.Next, b.actionFor(Chord.ctrl(AwtKey.VK_RIGHT)))
        assertEquals("", b.encode())
    }

    @Test
    fun aKeyMovesToTheActionItIsGiven() {
        // E belongs to the equaliser; giving it to shuffle takes it off the equaliser.
        val b = KeyBindings.DEFAULT.bind(KeyAction.Shuffle, Chord.key(AwtKey.VK_E))
        assertEquals(KeyAction.Shuffle, b.actionFor(Chord.key(AwtKey.VK_E)))
        assertTrue(b.chordsFor(KeyAction.Equaliser).isEmpty())
        // Shuffle keeps its own S too: an action can have several keys.
        assertEquals(KeyAction.Shuffle, b.actionFor(Chord.key(AwtKey.VK_S)))
    }

    @Test
    fun storedBindingsRoundTrip() {
        val b = KeyBindings.DEFAULT
            .bind(KeyAction.Like, Chord(AwtKey.VK_H, ctrl = true, shift = true))
            .with(KeyAction.Mute, emptyList())
        val again = KeyBindings.decode(b.encode())
        assertEquals(b.chordsFor(KeyAction.Like), again.chordsFor(KeyAction.Like))
        assertTrue(again.chordsFor(KeyAction.Mute).isEmpty())
        assertEquals(KeyAction.PlayPause, again.actionFor(Chord.key(AwtKey.VK_SPACE)))
    }

    @Test
    fun labelsReadLikeShortcuts() {
        assertEquals("Ctrl+Shift+E", Chord(AwtKey.VK_E, ctrl = true, shift = true).label)
        assertEquals("Space", Chord.key(AwtKey.VK_SPACE).label)
    }
}
