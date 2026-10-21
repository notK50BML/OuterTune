/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [creditedArtists] hands the artist picker - which decides both whether "Go to artist" asks
 * at all (more than one entry) and what each row opens.
 */
class CreditedArtistsTest {

    private fun song(vararg artists: Artist) = SongItem(
        id = "song",
        title = "Song",
        artists = artists.toList(),
        thumbnail = "",
    )

    @Test
    fun `keeps credit order`() {
        val picked = song(Artist("Daft Punk", "UCdp"), Artist("Julian Casablancas", "UCjc")).creditedArtists()
        assertEquals(listOf("Daft Punk", "Julian Casablancas"), picked.map { it.name })
        assertEquals(listOf("UCdp", "UCjc"), picked.map { it.id })
    }

    @Test
    fun `an artist credited twice is one choice`() {
        // A feature that is also the main artist would otherwise make a one-artist song ask "which?".
        val picked = song(Artist("A", "UCa"), Artist("A", "UCa")).creditedArtists()
        assertEquals(1, picked.size)
    }

    @Test
    fun `a credit with no channel opens the unlinked page for that name`() {
        val picked = song(Artist("Real", "UCreal"), Artist("Local Name", null)).creditedArtists()
        assertEquals(2, picked.size)
        assertTrue(picked[0].linkable)
        assertFalse(picked[1].linkable)
        assertEquals(StoredArtist.unlinked("Local Name"), picked[1])
    }

    @Test
    fun `no credits means nothing to open`() {
        assertTrue(song().creditedArtists().isEmpty())
    }
}
