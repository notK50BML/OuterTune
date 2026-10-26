/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What makes two spellings of an artist's name the same artist - see ArtistCreditEnricher.artistKey. */
class ArtistKeyTest {

    private fun key(name: String) = ArtistCreditEnricher.artistKey(name)

    @Test
    fun `case, punctuation and a Topic suffix do not split an artist`() {
        assertEquals(key("i-dle"), key("I-DLE"))
        assertEquals(key("i-dle"), key("idle"))
        assertEquals(key("i-dle"), key("i-dle - Topic"))
        assertEquals(key("AC/DC"), key("ACDC"))
    }

    @Test
    fun `different names stay different`() {
        // A rename is a different name - the refresher follows those, the key does not guess.
        assertNotEquals(key("(G)I-DLE"), key("i-dle"))
        assertNotEquals(key("Nirvana"), key("Nirvana UK"))
    }

    @Test
    fun `letters beyond ASCII count`() {
        assertEquals(key("태연"), key("태연 - Topic"))
        assertNotEquals("", key("태연"))
    }

    @Test
    fun `a credit key keeps names of punctuation alone apart`() {
        // artistKey reduces both of these to nothing; a song crediting "!!!" and "???" is still
        // crediting two artists.
        assertNotEquals(ArtistCreditEnricher.creditKey("!!!"), ArtistCreditEnricher.creditKey("???"))
        assertEquals(ArtistCreditEnricher.creditKey("i-dle"), ArtistCreditEnricher.creditKey("I-DLE"))
    }
}
