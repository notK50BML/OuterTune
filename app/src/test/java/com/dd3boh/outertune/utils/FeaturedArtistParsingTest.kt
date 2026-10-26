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
    fun `a name with brackets of its own is kept whole`() {
        assertEquals(listOf("(G)I-DLE"), parse("Song (feat. (G)I-DLE)"))
        assertEquals(listOf("(G)I-DLE", "Yuqi"), parse("Song (feat. (G)I-DLE & Yuqi)"))
        assertEquals(listOf("(G)I-DLE"), parse("Song ft. (G)I-DLE [Live]"))
    }

    @Test
    fun `a spaced dash ends an unbracketed clause`() {
        assertEquals(listOf("B"), parse("Song ft. B - Remix"))
        assertEquals(listOf("Jay-Z"), parse("Song ft. Jay-Z"))
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

    // ---- what the enricher actually searches: ArtistCreditEnricher.featuredGroups -------------

    private fun groups(title: String, vararg credited: String) =
        ArtistCreditEnricher.featuredGroups(title, credited.toList())
            .map { it.whole to it.parts }

    @Test
    fun `a name the song already credits is not searched again, commas and all`() {
        // Compared piece by piece, "Tyler" and "The Creator" were each searched as another artist.
        assertEquals(emptyList<Any>(), groups("Song (feat. Tyler, The Creator)", "Tyler, The Creator"))
        assertEquals(emptyList<Any>(), groups("Song (feat. Earth, Wind & Fire)", "Earth, Wind & Fire"))
        // By artistKey, not by exact spelling.
        assertEquals(emptyList<Any>(), groups("Song (feat. I-DLE)", "i-dle"))
    }

    @Test
    fun `what is not credited is offered whole before it is offered in pieces`() {
        assertEquals(
            listOf("Earth, Wind & Fire" to listOf("Earth", "Wind", "Fire")),
            groups("Song (feat. Earth, Wind & Fire)"),
        )
        assertEquals(
            listOf("Florence and the Machine" to listOf("Florence", "the Machine")),
            groups("Song (feat. Florence and the Machine)"),
        )
    }

    @Test
    fun `a credited name in the middle leaves the names either side apart`() {
        assertEquals(
            listOf("B" to listOf("B"), "D" to listOf("D")),
            groups("Song (feat. B, C & D)", "C"),
        )
    }

    @Test
    fun `brackets and dashes end a clause exactly as for the name list`() {
        assertEquals(listOf("(G)I-DLE" to listOf("(G)I-DLE")), groups("Song (feat. (G)I-DLE)"))
        assertEquals(listOf("B" to listOf("B")), groups("Song ft. B - Remix"))
        assertEquals(listOf("B" to listOf("B"), "C" to listOf("C")), groups("Song ft. B (ft. C)"))
        assertEquals(listOf("X" to listOf("X")), groups("Song (featuring X)"))
        assertEquals(listOf("X" to listOf("X")), groups("Song w/ X"))
    }
}
