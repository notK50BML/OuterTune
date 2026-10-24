/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** An artist's "In your library": kept songs only, not everything played - see Database.songsByArtist. */
class ArtistLibrarySongsTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-artist-lib").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun song(id: String) = StoredSong(id, "Song $id", "Artist", artistList = listOf(StoredArtist("UCartist", "Artist")))

    @Test
    fun `played is not kept, liked and playlisted are`() {
        val db = Database(File(dir, "library.db"))
        db.recordPlay(song("played"), 1_000L)
        db.setLiked(song("liked"), true, 2_000L)
        db.createPlaylist("p1", "Mix", 3_000L)
        db.addToPlaylist("p1", song("listed"))
        val ids = db.songsByArtist("UCartist").map { it.id }.toSet()
        assertEquals(setOf("liked", "listed"), ids)
        db.close()
    }
}
