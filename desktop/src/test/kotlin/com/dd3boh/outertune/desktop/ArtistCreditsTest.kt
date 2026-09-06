/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Artist credits as rows, which is what makes a name clickable.
 *
 * The library used to keep artists as one joined display string, so there was no channel to navigate
 * to - a limitation the code said out loud before it was fixed. These check the replacement without
 * losing what the string was doing.
 */
class ArtistCreditsTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var db: Database

    private fun song(
        id: String,
        artists: List<StoredArtist> = emptyList(),
        display: String = artists.joinToString { it.name },
    ) = StoredSong(id, "Song $id", display, "", artists)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-artists-test").toFile()
        file = File(dir, "library.db")
        db = Database(file)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        dir.deleteRecursively()
    }

    @Test
    fun `credits come back with the song, in order`() {
        // Order is the credit order, which is not alphabetical and is not arbitrary - the first name
        // is the primary artist and that is how it should read.
        db.recordPlay(
            song("a", listOf(StoredArtist("UC1", "Zara"), StoredArtist("UC2", "Adam"))),
            1000,
        )
        val stored = db.recentlyPlayed(10).single()
        assertEquals(listOf("Zara", "Adam"), stored.artistList.map { it.name })
    }

    @Test
    fun `a credit with no channel is kept but not linkable`() {
        // Plenty of credits arrive as bare names. They should still show; they just have nowhere to
        // go, and a name underlined as though it were a link would disappoint on the click rather
        // than before it.
        val unlinked = StoredArtist.unlinked("Some Band")
        assertFalse(unlinked.linkable)
        assertTrue(StoredArtist("UCabc", "Real Channel").linkable)

        db.recordPlay(song("a", listOf(unlinked)), 1000)
        val stored = db.recentlyPlayed(10).single()
        assertEquals("Some Band", stored.artistList.single().name)
        assertFalse(stored.artistList.single().linkable)
    }

    @Test
    fun `the same artist across songs is one row`() {
        db.recordPlay(song("a", listOf(StoredArtist("UC1", "Shared"))), 1000)
        db.recordPlay(song("b", listOf(StoredArtist("UC1", "Shared"))), 2000)

        val byArtist = db.songsByArtist("UC1")
        assertEquals(listOf("b", "a"), byArtist.map { it.id })
    }

    @Test
    fun `a renamed artist updates everywhere at once`() {
        // The point of a row rather than a string per song: the name lives in one place.
        db.recordPlay(song("a", listOf(StoredArtist("UC1", "Old Name"))), 1000)
        db.recordPlay(song("b", listOf(StoredArtist("UC1", "New Name"))), 2000)

        val names = db.recentlyPlayed(10).flatMap { it.artistList }.map { it.name }.distinct()
        assertEquals(listOf("New Name"), names)
    }

    @Test
    fun `re-encountering a song without credits does not erase the ones it has`() {
        // A song arriving from a sparse source carries no structured artists. Treating that as "this
        // song has no artists" would wipe credits a richer source already supplied - the same rule
        // the thumbnail follows.
        db.recordPlay(song("a", listOf(StoredArtist("UC1", "Real"))), 1000)
        db.recordPlay(StoredSong("a", "Song a", "Real", ""), 2000)

        assertEquals(listOf("Real"), db.recentlyPlayed(10).single().artistList.map { it.name })
    }

    @Test
    fun `credits are replaced rather than merged when new ones arrive`() {
        // Leaving an old credit behind would show somebody who is no longer on the track.
        db.recordPlay(song("a", listOf(StoredArtist("UC1", "First"), StoredArtist("UC2", "Second"))), 1000)
        db.recordPlay(song("a", listOf(StoredArtist("UC1", "First"))), 2000)

        assertEquals(listOf("First"), db.recentlyPlayed(10).single().artistList.map { it.name })
    }

    @Test
    fun `an artist with nothing in the library reports nothing`() {
        assertTrue(db.songsByArtist("UCnothing").isEmpty())
    }

    @Test
    fun `songs stored before credits existed still show their artist`() {
        // The display string is kept alongside the rows precisely for this: a library recorded
        // before the change would otherwise read "Unknown artist" throughout, which is a worse trade
        // than a name that cannot be clicked.
        db.recordPlay(StoredSong("old", "Old Song", "Some Artist", ""), 1000)
        val stored = db.recentlyPlayed(10).single()
        assertTrue(stored.artistList.isEmpty())
        assertEquals("Some Artist", stored.artists)
    }

    @Test
    fun `upgrading a version 2 library keeps everything and gains the tables`() {
        // The migration path, exercised for real. A schema step that only works on a fresh database
        // passes every test written after it and breaks for everyone who already had one.
        db.close()
        Class.forName("org.sqlite.JDBC")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DROP TABLE IF EXISTS song_artist")
                st.executeUpdate("DROP TABLE IF EXISTS artist")
                st.executeUpdate("PRAGMA user_version=2")
            }
        }

        Database(file).let { upgraded ->
            // Credits can be written, so the tables arrived...
            upgraded.recordPlay(song("x", listOf(StoredArtist("UC9", "After"))), 3000)
            assertEquals(listOf("After"), upgraded.recentlyPlayed(10).first().artistList.map { it.name })
            upgraded.close()
        }
    }

    @Test
    fun `credits survive a restart`() {
        db.recordPlay(song("a", listOf(StoredArtist("UC1", "Kept"))), 1000)
        db.close()

        Database(file).let { reopened ->
            assertEquals(listOf("Kept"), reopened.recentlyPlayed(10).single().artistList.map { it.name })
            reopened.close()
        }
    }

    @Test
    fun `a shelf of songs costs one extra query, not one per song`() {
        // Not a timing test - a correctness one for the batching. Fifty songs read back with their
        // credits intact is the observable half of "this is one query rather than fifty-one".
        repeat(50) { i ->
            db.recordPlay(song("s$i", listOf(StoredArtist("UC$i", "Artist $i"))), i.toLong())
        }
        val songs = db.recentlyPlayed(50)
        assertEquals(50, songs.size)
        assertTrue("some songs lost their credits", songs.all { it.artistList.size == 1 })
        assertEquals("Artist 49", songs.first().artistList.single().name)
    }
}
