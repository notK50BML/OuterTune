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
 * Which credit a click lands on.
 *
 * Worth testing on its own because getting it wrong is invisible in the running app: every name
 * renders, every underline appears, and clicking one simply opens a different artist. That is the
 * exact failure this feature was asked for to fix, so it should not be reintroduced by an off-by-two
 * in the offsets.
 */
class ArtistNamesTest {

    private fun artist(name: String, linkable: Boolean = true) =
        if (linkable) StoredArtist("id-$name", name) else StoredArtist.unlinked(name)

    /** The string ArtistNames builds, rebuilt here so the ranges are checked against real text. */
    private fun rendered(artists: List<StoredArtist>) = artists.joinToString(SEPARATOR) { it.name }

    /** Who a click at [offset] would open. */
    private fun hit(artists: List<StoredArtist>, offset: Int): StoredArtist? =
        creditRanges(artists).firstOrNull { offset in it.first }?.second?.takeIf { it.linkable }

    @Test
    fun `each range covers exactly its own name`() {
        val artists = listOf(artist("LE SSERAFIM"), artist("Nile Rodgers"), artist("Kai"))
        val text = rendered(artists)
        creditRanges(artists).forEach { (range, credit) ->
            assertEquals(
                "range for ${credit.name} covered '${text.substring(range)}'",
                credit.name,
                text.substring(range),
            )
        }
    }

    @Test
    fun `a click in the middle of a name opens that name`() {
        val artists = listOf(artist("Alpha"), artist("Beta"), artist("Gamma"))
        val text = rendered(artists)
        assertEquals("Alpha", hit(artists, text.indexOf("Alpha") + 2)?.name)
        assertEquals("Beta", hit(artists, text.indexOf("Beta") + 2)?.name)
        assertEquals("Gamma", hit(artists, text.indexOf("Gamma") + 2)?.name)
    }

    @Test
    fun `the first and last characters of a name still hit it`() {
        // The ends are where an off-by-one shows up, and clicking the first letter of a name is a
        // perfectly ordinary thing to do.
        val artists = listOf(artist("Alpha"), artist("Beta"))
        val text = rendered(artists)
        assertEquals("Beta", hit(artists, text.indexOf("Beta"))?.name)
        assertEquals("Beta", hit(artists, text.length - 1)?.name)
        assertEquals("Alpha", hit(artists, 0)?.name)
    }

    @Test
    fun `a click on the separator opens nobody`() {
        val artists = listOf(artist("Alpha"), artist("Beta"))
        // "Alpha, Beta" - offsets 5 and 6 are the comma and the space.
        assertNull(hit(artists, 5))
        assertNull(hit(artists, 6))
    }

    @Test
    fun `an unlinkable credit is not clickable`() {
        // A credit with no artist page must not open one. This is what keeps a plain text name from
        // navigating to whatever artist happened to be next in the list.
        val artists = listOf(artist("Real Artist"), artist("Just A Name", linkable = false))
        val text = rendered(artists)
        assertNull(hit(artists, text.indexOf("Just A Name") + 2))
        assertEquals("Real Artist", hit(artists, 0)?.name)
    }

    @Test
    fun `an empty credit does not shift everyone after it`() {
        // The bug the extraction was made for. Counting the separator by "the cursor has moved"
        // agrees with the builder for every ordinary list and disagrees the moment a name is empty -
        // and then every range after it is two characters adrift, so clicking one artist opens the
        // one before.
        val artists = listOf(artist(""), artist("Beta"), artist("Gamma"))
        val text = rendered(artists)
        assertEquals("Beta", hit(artists, text.indexOf("Beta") + 1)?.name)
        assertEquals("Gamma", hit(artists, text.indexOf("Gamma") + 1)?.name)
    }

    @Test
    fun `names with commas in them still map correctly`() {
        // "Tyler, The Creator" contains the separator. The ranges are built from lengths rather than
        // by searching for commas, so this works - but it is exactly the case a search-based
        // implementation would break on, and it is a real artist.
        val artists = listOf(artist("Tyler, The Creator"), artist("Kali Uchis"))
        val text = rendered(artists)
        assertEquals("Tyler, The Creator", hit(artists, 0)?.name)
        assertEquals("Tyler, The Creator", hit(artists, 8)?.name)
        assertEquals("Kali Uchis", hit(artists, text.indexOf("Kali"))?.name)
    }

    @Test
    fun `a single credit covers the whole string`() {
        val artists = listOf(artist("Solo"))
        val ranges = creditRanges(artists)
        assertEquals(1, ranges.size)
        assertEquals(0 until 4, ranges[0].first)
    }

    @Test
    fun `no credits is not an error`() {
        assertTrue(creditRanges(emptyList()).isEmpty())
    }

    @Test
    fun `an offset past the end opens nobody`() {
        val artists = listOf(artist("Alpha"))
        assertNull(hit(artists, 99))
    }
}
