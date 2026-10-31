/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What shows under a song's title - see SongDetail - and the library facts it is filled from. */
class SongDetailsTest {

    @Test
    fun `the setting round-trips, and empty is the default of all three`() {
        assertEquals(SongDetail.entries, SongDetail.decode(""))
        val chosen = listOf(SongDetail.LENGTH, SongDetail.ARTISTS)
        assertEquals(chosen, SongDetail.decode(SongDetail.encode(chosen)))
        // Everything off is kept as off, not read back as the default.
        assertEquals(emptyList<SongDetail>(), SongDetail.decode(SongDetail.encode(emptyList())))
    }

    @Test
    fun `the line follows the chosen order and leaves out what a song lacks`() {
        val all = SongDetail.decode("")
        assertEquals(
            "Daft Punk, Julian Casablancas • Random Access Memories • 5:37",
            SongDetail.line(all, "Daft Punk, Julian Casablancas", "Random Access Memories", "5:37"),
        )
        assertEquals("5:37 • Daft Punk", SongDetail.line(SongDetail.decode("DA"), "Daft Punk", "Discovery", "5:37"))
        assertEquals("Daft Punk • 5:37", SongDetail.line(all, "Daft Punk", null, "5:37"))
        assertEquals("Daft Punk", SongDetail.line(all, "Daft Punk", "", null))
    }

    @Test
    fun `the library keeps a song's album and length, a file's own tags first`() {
        val dir = Files.createTempDirectory("ot-details-test").toFile()
        val db = Database(File(dir, "library.db"))
        try {
            val song = StoredSong("a", "Instant Crush", "Daft Punk", "")
            db.recordPlay(song, 1000, durationMs = 337_000, album = "Random Access Memories")
            // A later sighting without them does not erase what is known.
            db.upsertSong(song)
            assertEquals(SongFacts("Random Access Memories", 337_000), db.songFacts()["a"])
            db.upsertSong(StoredSong("b", "Unknown", "Nobody", ""))
            assertNull(db.songFacts()["b"])
        } finally {
            runCatching { db.close() }
            dir.deleteRecursively()
        }
    }
}
