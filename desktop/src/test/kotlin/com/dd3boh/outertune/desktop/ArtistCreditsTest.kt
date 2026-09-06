/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which credits appear on a song, and which of them can be clicked.
 *
 * This replaces a test of click-offset arithmetic, which the drawing no longer uses - each name is
 * its own composable now, so it cannot be off by two. What is left is the decision that actually
 * broke: whether a credit is shown at all, and whether clicking it does anything.
 */
class ArtistCreditsTest {

    private fun linked(name: String) = StoredArtist("UC-$name", name)

    @Test
    fun `structured credits are used when there are any`() {
        val credits = creditsFor(listOf(linked("Alpha"), linked("Beta")), "ignored, this")
        assertEquals(listOf("Alpha", "Beta"), credits.map { it.artist.name })
    }

    @Test
    fun `every credit is clickable, linked or not`() {
        // The bug this is here for. Refusing the click on unlinked credits meant that for any song
        // whose artists came back without channel ids, nothing on the line responded at all - which
        // reads exactly like "clickable artists" not being implemented.
        val credits = creditsFor(listOf(linked("Real"), StoredArtist.unlinked("Bare Name")), "")
        assertEquals(2, credits.size)
        assertTrue("a linked credit lost its artist", credits[0].artist.id.startsWith("UC-"))
        assertTrue("an unlinked credit lost its name", credits[1].artist.name == "Bare Name")
    }

    @Test
    fun `only linked credits are underlined`() {
        // The underline is the promise that there is a channel behind the name. Both are clickable;
        // only one of them goes somewhere that was fetched rather than assembled from the library.
        val credits = creditsFor(listOf(linked("Real"), StoredArtist.unlinked("Bare Name")), "")
        assertTrue(credits[0].linked)
        assertTrue(!credits[1].linked)
    }

    @Test
    fun `the display string is used when there are no structured credits`() {
        // Songs stored before the library kept separate credits have only the joined string. Showing
        // it as one credit is lossy; showing nothing would be wrong about something already known.
        val credits = creditsFor(emptyList(), "Older Song Artist")
        assertEquals(1, credits.size)
        assertEquals("Older Song Artist", credits[0].artist.name)
        assertTrue("a fallback string is not a channel", !credits[0].linked)
    }

    @Test
    fun `nothing at all gives no credits rather than an empty name`() {
        assertTrue(creditsFor(emptyList(), "").isEmpty())
        assertTrue(creditsFor(emptyList(), "   ").isEmpty())
    }

    @Test
    fun `an unlinked credit keys on its own name`() {
        // Two songs crediting the same bare name should land on the same artist page rather than two
        // pages that happen to be spelled alike.
        assertEquals(
            StoredArtist.unlinked("Tyler, The Creator").id,
            StoredArtist.unlinked("tyler, the creator").id,
        )
    }
}
