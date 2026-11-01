/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/**
 * Database.fillAlbums, behind "Fill in from a backup" and Go to album's lookup: it fills in a
 * song's album name and id only where they are blank, and never adds a song.
 */
class FillAlbumsTest {

    private fun library() = LibraryStore(Files.createTempDirectory("ot-albums").toFile())

    @Test
    fun `fills blanks, keeps what a song already has, adds nothing`() {
        val library = library()
        val db = library.database
        db.upsertSong(StoredSong("a", "Song A", "Artist"))
        db.upsertSong(StoredSong("b", "Song B", "Artist"), album = "Kept album", albumId = "MPREb_kept")

        val filled = db.fillAlbums(
            mapOf(
                "a" to ("Album A" to "MPREb_a"),
                "b" to ("Other album" to "MPREb_other"),
                "not-in-library" to ("X" to "MPREb_x"),
            )
        )

        assertEquals(1, filled)
        val facts = db.songFacts()
        assertEquals(SongFacts("Album A", 0, "MPREb_a"), facts["a"])
        assertEquals(SongFacts("Kept album", 0, "MPREb_kept"), facts["b"])
        assertEquals(null, facts["not-in-library"])
        assertEquals(2, db.songs(listOf("a", "b", "not-in-library")).size)
    }

    @Test
    fun `an id alone is filled in beside a name already there`() {
        val library = library()
        val db = library.database
        db.upsertSong(StoredSong("a", "Song A", "Artist"), album = "Named only")
        db.fillAlbums(mapOf("a" to ("Named only" to "MPREb_a")))
        assertEquals("MPREb_a", db.songFacts()["a"]?.albumId)
    }
}
