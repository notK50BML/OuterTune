/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Importing a phone backup, against a database built to the phone's schema.
 *
 * A real backup cannot be committed as a fixture - it is somebody's library - so the fixture is
 * constructed here to the same shape: the `song`, `artist`, `song_artist_map`, `playlist` and
 * `playlist_song_map` tables the Room entities declare, with the columns this importer reads.
 *
 * The case worth the most is importing twice. An import that merges two devices must be safe to
 * repeat - people re-run them, and half the time it is because they are not sure the first one
 * worked.
 */
class BackupImportTest {

    private lateinit var root: File
    private lateinit var library: LibraryStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("outertune-import").toFile()
        library = LibraryStore(directory = File(root, "library"))
    }

    @After
    fun tearDown() {
        library.database.close()
        root.deleteRecursively()
    }

    /** A backup zip holding a database with [build] applied to it. */
    private fun backup(name: String = "song.db", build: (java.sql.Connection) -> Unit): File {
        val db = File(root, "fixture-${System.nanoTime()}.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE song (
                        id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL,
                        thumbnailUrl TEXT, liked INTEGER NOT NULL DEFAULT 0,
                        isLocal INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE TABLE artist (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
                statement.executeUpdate(
                    """
                    CREATE TABLE song_artist_map (
                        songId TEXT NOT NULL, artistId TEXT NOT NULL, position INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.executeUpdate("CREATE TABLE playlist (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
                statement.executeUpdate(
                    """
                    CREATE TABLE playlist_song_map (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
            build(connection)
        }

        val zip = File(root, "backup-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            // The settings file a real backup also carries, to prove it is ignored rather than
            // tripped over.
            out.putNextEntry(ZipEntry("settings.preferences_pb"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
            out.putNextEntry(ZipEntry(name))
            out.write(db.readBytes())
            out.closeEntry()
        }
        return zip
    }

    private fun java.sql.Connection.song(
        id: String,
        title: String,
        liked: Boolean = false,
        isLocal: Boolean = false,
    ) = prepareStatement("INSERT INTO song (id, title, thumbnailUrl, liked, isLocal) VALUES (?,?,?,?,?)")
        .use {
            it.setString(1, id); it.setString(2, title); it.setString(3, "http://art/$id")
            it.setInt(4, if (liked) 1 else 0); it.setInt(5, if (isLocal) 1 else 0)
            it.executeUpdate()
        }

    private fun java.sql.Connection.credit(songId: String, artistId: String, name: String, position: Int = 0) {
        prepareStatement("INSERT OR IGNORE INTO artist (id, name) VALUES (?,?)").use {
            it.setString(1, artistId); it.setString(2, name); it.executeUpdate()
        }
        prepareStatement("INSERT INTO song_artist_map (songId, artistId, position) VALUES (?,?,?)").use {
            it.setString(1, songId); it.setString(2, artistId); it.setInt(3, position); it.executeUpdate()
        }
    }

    private fun java.sql.Connection.playlist(id: String, name: String, songs: List<String>) {
        prepareStatement("INSERT INTO playlist (id, name) VALUES (?,?)").use {
            it.setString(1, id); it.setString(2, name); it.executeUpdate()
        }
        songs.forEachIndexed { index, songId ->
            prepareStatement("INSERT INTO playlist_song_map (playlistId, songId, position) VALUES (?,?,?)")
                .use {
                    it.setString(1, id); it.setString(2, songId); it.setInt(3, index); it.executeUpdate()
                }
        }
    }

    private fun import(zip: File) = runBlocking { BackupImport.importFrom(zip, library) }

    // ---- the happy path --------------------------------------------------------------------

    @Test
    fun `songs come across with their credits`() {
        val zip = backup { db ->
            db.song("a", "First")
            db.credit("a", "UCone", "Someone")
            db.credit("a", "UCtwo", "Someone Else", position = 1)
        }
        val summary = import(zip)

        assertFalse(summary.describe(), summary.failed)
        assertEquals(1, summary.songs)
        val stored = library.database.song("a")!!
        assertEquals("First", stored.title)
        assertEquals(listOf("Someone", "Someone Else"), stored.artistList.map { it.name })
        assertEquals("http://art/a", stored.thumbnail)
    }

    @Test
    fun `liked songs arrive liked`() {
        val zip = backup { db ->
            db.song("a", "Liked one", liked = true)
            db.song("b", "Not liked")
        }
        val summary = import(zip)

        assertEquals(1, summary.liked)
        assertTrue(library.isLiked("a"))
        assertFalse(library.isLiked("b"))
    }

    @Test
    fun `playlists arrive with their songs in order`() {
        val zip = backup { db ->
            db.song("a", "First")
            db.song("b", "Second")
            db.song("c", "Third")
            db.playlist("p1", "Evening", listOf("c", "a", "b"))
        }
        val summary = import(zip)

        assertEquals(1, summary.playlists)
        assertEquals(3, summary.playlistSongs)
        val playlist = library.playlists.value.single { it.name == "Evening" }
        assertEquals(listOf("Third", "First", "Second"), library.playlistSongs(playlist.id).map { it.title })
    }

    // ---- local files -----------------------------------------------------------------------

    @Test
    fun `local files are skipped and counted`() {
        // The phone can play a file on its own storage; this cannot. Importing those rows would
        // fill the library with entries that fail when clicked.
        val zip = backup { db ->
            db.song("a", "Streamable")
            db.song("local1", "On the phone", isLocal = true)
        }
        val summary = import(zip)

        assertEquals(1, summary.songs)
        assertEquals(1, summary.skippedLocal)
        assertTrue(library.database.song("local1") == null)
        assertTrue(summary.describe().contains("Skipped 1 local file"))
    }

    @Test
    fun `a playlist entry pointing at a skipped song is dropped`() {
        // Otherwise the playlist gains a row with nothing behind it.
        val zip = backup { db ->
            db.song("a", "Streamable")
            db.song("local1", "On the phone", isLocal = true)
            db.playlist("p1", "Mixed", listOf("a", "local1"))
        }
        val summary = import(zip)

        assertEquals(1, summary.playlistSongs)
        val playlist = library.playlists.value.single { it.name == "Mixed" }
        assertEquals(listOf("Streamable"), library.playlistSongs(playlist.id).map { it.title })
    }

    // ---- doing it twice --------------------------------------------------------------------

    @Test
    fun `importing the same backup twice changes nothing the second time`() {
        val zip = backup { db ->
            db.song("a", "First", liked = true)
            db.song("b", "Second")
            db.playlist("p1", "Evening", listOf("a", "b"))
        }
        import(zip)
        val second = import(zip)

        // Songs are re-read, so the count is the same - but nothing is duplicated.
        assertEquals(1, library.playlists.value.count { it.name == "Evening" })
        assertEquals(0, second.playlists)
        assertEquals(
            "the playlist gained duplicates",
            2,
            library.playlistSongs(library.playlists.value.single { it.name == "Evening" }.id).size,
        )
    }

    @Test
    fun `a second import does not unlike what the first liked`() {
        // toggleLiked is a toggle. Called blindly on the second pass it would turn every liked song
        // off again, which is the worst possible outcome for a "just to be safe" re-import.
        val zip = backup { db -> db.song("a", "First", liked = true) }
        import(zip)
        assertTrue(library.isLiked("a"))

        val second = import(zip)
        assertTrue("the second import unliked it", library.isLiked("a"))
        assertEquals(0, second.liked)
    }

    @Test
    fun `importing into an existing playlist of the same name merges rather than duplicating`() {
        val existing = library.createPlaylist("Evening")
        library.addToPlaylist(existing, StoredSong("a", "First", "Someone"))

        val zip = backup { db ->
            db.song("a", "First")
            db.song("b", "Second")
            db.playlist("p1", "Evening", listOf("a", "b"))
        }
        import(zip)

        assertEquals(1, library.playlists.value.count { it.name == "Evening" })
        assertEquals(
            listOf("First", "Second"),
            library.playlistSongs(existing).map { it.title },
        )
    }

    // ---- things that are not a backup ------------------------------------------------------

    @Test
    fun `a zip with no database says so`() {
        val zip = File(root, "empty.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("settings.preferences_pb"))
            out.write(byteArrayOf(1))
            out.closeEntry()
        }
        val summary = import(zip)
        assertTrue(summary.failed)
        assertTrue(summary.describe().contains("does not contain"))
    }

    @Test
    fun `something that is not a zip at all is reported, not thrown`() {
        val notAZip = File(root, "notes.txt").apply { writeText("hello") }
        val summary = import(notAZip)
        assertTrue(summary.failed)
        assertTrue(summary.describe().startsWith("Could not read the backup"))
    }

    @Test
    fun `a database missing the tables is reported, not thrown`() {
        // A zip holding some other application's database. It opens fine and then has nothing this
        // importer recognises.
        val db = File(root, "stranger.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeUpdate("CREATE TABLE unrelated (x INTEGER)") }
        }
        val zip = File(root, "stranger.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("stranger.db"))
            out.write(db.readBytes())
            out.closeEntry()
        }

        val summary = import(zip)
        assertTrue(summary.failed)
    }

    @Test
    fun `an empty backup reports that rather than claiming success`() {
        val summary = import(backup { })
        assertFalse(summary.failed)
        assertTrue(summary.describe().contains("Nothing to import"))
    }
}
