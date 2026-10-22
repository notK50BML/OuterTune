/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Names read out of a title's "(feat. X)" clause - see ArtistCreditEnricher.parseFeaturedArtistNames.
 *
 * Every name found costs a search and can add an artist to a song, so the words that must NOT be
 * read as a credit matter as much as the ones that must.
 */
class FeaturedArtistParsingTest {

    private fun parse(title: String) = ArtistCreditEnricher.parseFeaturedArtistNames(title)

    @Test
    fun `the usual spellings`() {
        assertEquals(listOf("B"), parse("Song (feat. B)"))
        assertEquals(listOf("B"), parse("Song (ft. B)"))
        assertEquals(listOf("B"), parse("Song [feat B]"))
    }

    @Test
    fun `featuring is read in full rather than missed`() {
        assertEquals(listOf("B"), parse("Song (featuring B)"))
    }

    @Test
    fun `every clause, and every name in one`() {
        assertEquals(listOf("B", "C"), parse("Song ft. B (ft. C)"))
        assertEquals(listOf("B", "C", "D"), parse("Song (feat. B, C & D)"))
    }

    @Test
    fun `a trailing bracket is not swallowed into the name`() {
        assertEquals(listOf("A"), parse("Song ft. A [Deluxe]"))
    }

    @Test
    fun `ordinary title words are not credits`() {
        assertEquals(emptyList<String>(), parse("Dancing With Myself"))
        assertEquals(emptyList<String>(), parse("Godzilla vs Kong"))
        assertEquals(emptyList<String>(), parse("Left Behind"))
        assertEquals(emptyList<String>(), parse("Craft. Beer"))
    }

    @Test
    fun `a name credited twice is listed once`() {
        assertEquals(listOf("B"), parse("Song (feat. B) [ft. b]"))
    }
}
