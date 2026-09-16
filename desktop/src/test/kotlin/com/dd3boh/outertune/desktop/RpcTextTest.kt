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
 * Fitting text to Discord's limits, on the desktop side of the same duplicated file.
 *
 * Worth testing here too, not just on the phone: the failure is invisible when it happens - Discord
 * rejects the whole presence when one string is out of range rather than trimming it or reporting
 * why, so the symptom is no card at all for certain songs.
 */
class RpcTextTest {

    @Test
    fun `ordinary text passes through unchanged`() {
        assertEquals("A Song Title", RpcText.fit("A Song Title"))
    }

    @Test
    fun `surrounding space is trimmed`() {
        assertEquals("Title", RpcText.fit("  Title  "))
    }

    @Test
    fun `nothing usable falls back`() {
        assertEquals("Unknown", RpcText.fit(null, fallback = "Unknown"))
        assertEquals("Unknown", RpcText.fit("", fallback = "Unknown"))
        assertEquals("Unknown", RpcText.fit("   ", fallback = "Unknown"))
    }

    @Test
    fun `nothing usable and no fallback gives null`() {
        assertNull(RpcText.fit(null))
        assertNull(RpcText.fit("  "))
    }

    @Test
    fun `a one character title is padded rather than replaced`() {
        val fitted = RpcText.fit("4")!!
        assertTrue("too short for Discord: ${fitted.length}", fitted.length >= RpcText.MIN)
        assertTrue("the name was lost", fitted.startsWith("4"))
    }

    @Test
    fun `exactly at the limits is left alone`() {
        val two = "ab"
        val full = "x".repeat(RpcText.MAX)
        assertEquals(two, RpcText.fit(two))
        assertEquals(full, RpcText.fit(full))
    }

    @Test
    fun `over the limit is cut to fit`() {
        val long = "y".repeat(RpcText.MAX + 50)
        val fitted = RpcText.fit(long)!!
        assertTrue("still too long: ${fitted.length}", fitted.length <= RpcText.MAX)
        assertTrue("no sign it was cut", fitted.endsWith("…"))
    }

    @Test
    fun `a long title is cut at a word boundary when one is near the end`() {
        val title = "A Very Long Title That Goes On ".repeat(6) + "and then some more words here"
        val fitted = RpcText.fit(title)!!
        assertTrue(fitted.length <= RpcText.MAX)
        assertTrue("cut mid-word: …${fitted.takeLast(24)}", !fitted.dropLast(1).endsWith(" "))
        assertTrue(fitted.endsWith("…"))
    }

    @Test
    fun `a long unbroken run is still cut to fit`() {
        val fitted = RpcText.fit("z".repeat(400))!!
        assertTrue(fitted.length <= RpcText.MAX)
    }

    @Test
    fun `cutting never splits a character in two`() {
        val emoji = "🎵"
        val title = "pad ".repeat(30) + emoji.repeat(20)
        val fitted = RpcText.fit(title)!!

        assertTrue(fitted.length <= RpcText.MAX)
        fitted.forEachIndexed { index, char ->
            if (Character.isHighSurrogate(char)) {
                assertTrue(
                    "a high surrogate at $index has no partner",
                    index + 1 < fitted.length && Character.isLowSurrogate(fitted[index + 1]),
                )
            }
            if (Character.isLowSurrogate(char)) {
                assertTrue(
                    "a low surrogate at $index has no partner",
                    index > 0 && Character.isHighSurrogate(fitted[index - 1]),
                )
            }
        }
    }

    @Test
    fun `every result is within what Discord accepts`() {
        listOf(
            "4", "ab", "", "   ", "x".repeat(127), "x".repeat(128), "x".repeat(129),
            "y".repeat(1000), "word ".repeat(50), "🎵".repeat(100),
        ).forEach { input ->
            val fitted = RpcText.fit(input, fallback = "Unknown") ?: return@forEach
            assertTrue(
                "${fitted.length} characters for an input of ${input.length}",
                fitted.length in RpcText.MIN..RpcText.MAX,
            )
        }
    }

    @Test
    fun `button labels are cut without an ellipsis`() {
        val fitted = RpcText.fitButton("Listen on a service with a very long name indeed")
        assertTrue(fitted.length <= RpcText.BUTTON_MAX)
        assertTrue("a button should not trail off", !fitted.endsWith("…"))
    }

    @Test
    fun `a short button label is left alone`() {
        assertEquals("Listen on YouTube Music", RpcText.fitButton("Listen on YouTube Music"))
    }
}
