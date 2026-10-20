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
        val b = KeyBindings.DEFAULT.bind(KeyAction.Shuffle, Chord.key(AwtKey.VK_E)).bindings
        assertEquals(KeyAction.Shuffle, b.actionFor(Chord.key(AwtKey.VK_E)))
        assertTrue(b.chordsFor(KeyAction.Equaliser).isEmpty())
        // Shuffle keeps its own S too: an action can have several keys.
        assertEquals(KeyAction.Shuffle, b.actionFor(Chord.key(AwtKey.VK_S)))
    }

    @Test
    fun takingAKeySaysWhereItCameFrom() {
        val moved = KeyBindings.DEFAULT.bind(KeyAction.Shuffle, Chord.key(AwtKey.VK_E))
        assertEquals(listOf(KeyAction.Equaliser), moved.displaced.map { it.action })
        // The equaliser had only E, so it is now bound to nothing - the case worth saying out loud.
        assertTrue(moved.displaced.single().leftUnbound)
        assertEquals("Equaliser now has no key", moved.message)

        // Play/pause has Space and K, so taking one leaves it working and the wording differs.
        val shared = KeyBindings.DEFAULT.bind(KeyAction.Mute, Chord.key(AwtKey.VK_K))
        assertEquals(false, shared.displaced.single().leftUnbound)
        assertEquals("K taken from Play / pause", shared.message)

        // Nothing displaced, nothing to say.
        assertEquals(null, KeyBindings.DEFAULT.bind(KeyAction.Mute, Chord.key(AwtKey.VK_F9)).message)
    }

    @Test
    fun resettingDoesNotLeaveTwoActionsHoldingOneKey() {
        // The three steps that used to collide: move Like off L, give L to Shuffle - which is
        // allowed, because Like no longer holds it - then put Like back.
        val moved = KeyBindings.DEFAULT.bind(KeyAction.Like, Chord.key(AwtKey.VK_B)).bindings
            .with(KeyAction.Like, listOf(Chord.key(AwtKey.VK_B)))
        val taken = moved.bind(KeyAction.Shuffle, Chord.key(AwtKey.VK_L)).bindings
        assertEquals(KeyAction.Shuffle, taken.actionFor(Chord.key(AwtKey.VK_L)))

        val back = taken.reset(KeyAction.Like)
        // L is Like's again, and Shuffle no longer claims it - so actionFor is not deciding this
        // by enum declaration order, which is what used to hand the press to Shuffle.
        assertEquals(KeyAction.Like, back.bindings.actionFor(Chord.key(AwtKey.VK_L)))
        assertTrue(Chord.key(AwtKey.VK_L) !in back.bindings.chordsFor(KeyAction.Shuffle))
        assertEquals(listOf(KeyAction.Shuffle), back.displaced.map { it.action })
        // Shuffle keeps S, so it is still usable and the message says only what moved.
        assertEquals("L taken from Shuffle", back.message)
    }

    @Test
    fun resettingAnUntouchedActionDisplacesNothing() {
        val r = KeyBindings.DEFAULT.reset(KeyAction.Like)
        assertEquals(emptyList<Rebind.Displaced>(), r.displaced)
        assertEquals(KeyAction.Like, r.bindings.actionFor(Chord.key(AwtKey.VK_L)))
    }

    @Test
    fun storedBindingsRoundTrip() {
        val b = KeyBindings.DEFAULT
            .bind(KeyAction.Like, Chord(AwtKey.VK_H, ctrl = true, shift = true)).bindings
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
