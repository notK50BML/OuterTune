/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The library's storage, against a real SQLite file.
 *
 * A real file rather than an in-memory database, because several of the things worth checking here -
 * that the schema survives a restart, that opening twice does not try to create everything again,
 * that a legacy import runs exactly once - are only true of something that persists.
 */
class DatabaseTest {

    private lateinit var dir: File
    private lateinit var db: Database

    private fun song(id: String, title: String = "Song $id", thumb: String = "") =
        StoredSong(id, title, "Artist", thumb)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-db-test").toFile()
        db = Database(File(dir, "library.db"))
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        dir.deleteRecursively()
    }

    // ---- schema ----------------------------------------------------------------------------

    @Test
    fun `opening an existing database again does not fail`() {
        db.recordPlay(song("a"), 1000)
        db.close()

        // The migration must notice it has already run. Re-running CREATE TABLE without IF NOT
        // EXISTS, or bumping the version before the work commits, both surface exactly here.
        val reopened = Database(File(dir, "library.db"))
        assertEquals(listOf("a"), reopened.recentlyPlayed(10).map { it.id })
        reopened.close()
    }

    @Test
    fun `data survives a restart`() {
        db.recordPlay(song("a"), 1000)
        db.setLiked(song("b"), liked = true, atMs = 2000)
        val playlist = "p1"
        db.createPlaylist(playlist, "Mix", 3000)
        db.addToPlaylist(playlist, song("c"))
        db.close()

        val reopened = Database(File(dir, "library.db"))
        assertEquals(1, reopened.recentlyPlayed(10).size)
        assertEquals(1, reopened.likedSongs().size)
        assertEquals(listOf("c"), reopened.playlistSongs(playlist).map { it.id })
        reopened.close()
    }

    // ---- songs -----------------------------------------------------------------------------

    @Test
    fun `re-encountering a song does not erase what was already known about it`() {
        // A song arrives from a rich source with a cover, then again from a sparse one - a queue
        // entry, a search result - with none. A plain upsert would blank the cover, and the song
        // would lose its artwork by being played again.
        db.recordPlay(song("a", thumb = "https://art"), 1000)
        db.recordPlay(song("a", thumb = ""), 2000)
        assertEquals("https://art", db.recentlyPlayed(10).first().thumbnail)
    }

    @Test
    fun `a better title does replace an older one`() {
        db.recordPlay(song("a", title = "Untitled"), 1000)
        db.recordPlay(song("a", title = "Real Title"), 2000)
        assertEquals("Real Title", db.recentlyPlayed(10).first().title)
    }

    // ---- history ---------------------------------------------------------------------------

    @Test
    fun `history is most recent first`() {
        db.recordPlay(song("a"), 1000)
        db.recordPlay(song("b"), 3000)
        db.recordPlay(song("c"), 2000)
        assertEquals(listOf("b", "c", "a"), db.recentlyPlayed(10).map { it.id })
    }

    @Test
    fun `replaying a song moves it rather than duplicating it`() {
        db.recordPlay(song("a"), 1000)
        db.recordPlay(song("b"), 2000)
        db.recordPlay(song("a"), 3000)
        assertEquals(listOf("a", "b"), db.recentlyPlayed(10).map { it.id })
    }

    @Test
    fun `the limit is honoured`() {
        repeat(20) { db.recordPlay(song("s$it"), it.toLong()) }
        assertEquals(5, db.recentlyPlayed(5).size)
    }

    // ---- liked -----------------------------------------------------------------------------

    @Test
    fun `liking and unliking`() {
        db.setLiked(song("a"), liked = true, atMs = 1000)
        assertEquals(listOf("a"), db.likedSongs().map { it.id })

        db.setLiked(song("a"), liked = false, atMs = 2000)
        assertTrue(db.likedSongs().isEmpty())

        // And the song itself is still known - unliking is not deleting.
        db.recordPlay(song("a"), 3000)
        assertEquals(1, db.recentlyPlayed(10).size)
    }

    @Test
    fun `liking twice does not move it to the top`() {
        // The timestamp is when it was liked, and liking something already liked is not an event.
        db.setLiked(song("a"), liked = true, atMs = 1000)
        db.setLiked(song("b"), liked = true, atMs = 2000)
        db.setLiked(song("a"), liked = true, atMs = 3000)
        assertEquals(listOf("b", "a"), db.likedSongs().map { it.id })
    }

    // ---- playlists -------------------------------------------------------------------------

    @Test
    fun `a playlist keeps the order songs were added in`() {
        db.createPlaylist("p", "Mix", 0)
        listOf("c", "a", "b").forEach { db.addToPlaylist("p", song(it)) }
        assertEquals(listOf("c", "a", "b"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `adding a song already in the playlist does nothing`() {
        db.createPlaylist("p", "Mix", 0)
        db.addToPlaylist("p", song("a"))
        db.addToPlaylist("p", song("b"))
        db.addToPlaylist("p", song("a"))
        // Not moved to the end, and not duplicated: neither is what anyone means by "add" when the
        // song is visibly already there.
        assertEquals(listOf("a", "b"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `removing from the middle keeps the rest in order`() {
        db.createPlaylist("p", "Mix", 0)
        listOf("a", "b", "c", "d").forEach { db.addToPlaylist("p", song(it)) }
        db.removeFromPlaylist("p", "b")
        assertEquals(listOf("a", "c", "d"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `a removal leaves no gap for the next addition to fall into`() {
        // Positions are renumbered after a removal. Left with a hole, reading in order still works -
        // so this looks fine - but the next insert is computed from MAX(position) and the gap grows,
        // and a reorder written against stale indices puts songs where nobody asked.
        db.createPlaylist("p", "Mix", 0)
        listOf("a", "b", "c").forEach { db.addToPlaylist("p", song(it)) }
        db.removeFromPlaylist("p", "a")
        db.removeFromPlaylist("p", "b")
        db.addToPlaylist("p", song("d"))
        assertEquals(listOf("c", "d"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `reordering rewrites the whole order`() {
        db.createPlaylist("p", "Mix", 0)
        listOf("a", "b", "c").forEach { db.addToPlaylist("p", song(it)) }
        db.reorderPlaylist("p", listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `a failed reorder leaves the playlist as it was`() {
        // The reorder deletes and re-inserts, so a failure partway would otherwise leave a playlist
        // holding only whatever had been inserted so far - which is worse than either order.
        db.createPlaylist("p", "Mix", 0)
        listOf("a", "b", "c").forEach { db.addToPlaylist("p", song(it)) }

        val failed = runCatching {
            db.transaction {
                db.reorderPlaylist("p", listOf("c", "b", "a"))
                error("something went wrong afterwards")
            }
        }
        assertTrue(failed.isFailure)
        assertEquals(listOf("a", "b", "c"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `deleting a playlist keeps its songs in the library`() {
        db.createPlaylist("p", "Mix", 0)
        db.addToPlaylist("p", song("a"))
        db.recordPlay(song("a"), 1000)

        db.deletePlaylist("p")

        assertTrue(db.playlists().isEmpty())
        assertTrue("the playlist took its songs with it", db.playlistSongs("p").isEmpty())
        assertEquals("the song should still be in the library", 1, db.recentlyPlayed(10).size)
    }

    @Test
    fun `playlists report how many songs they hold`() {
        db.createPlaylist("p", "Mix", 0)
        db.createPlaylist("q", "Empty", 1)
        listOf("a", "b").forEach { db.addToPlaylist("p", song(it)) }

        val byId = db.playlists().associateBy { it.id }
        assertEquals(2, byId.getValue("p").songCount)
        // An empty playlist must still appear - a LEFT JOIN is the difference, and an inner one
        // makes a newly created playlist invisible until something is put in it.
        assertNotNull(byId["q"])
        assertEquals(0, byId.getValue("q").songCount)
    }

    @Test
    fun `renaming works and does not disturb contents`() {
        db.createPlaylist("p", "Mix", 0)
        db.addToPlaylist("p", song("a"))
        db.renamePlaylist("p", "Better name")
        assertEquals("Better name", db.playlists().first().name)
        assertEquals(listOf("a"), db.playlistSongs("p").map { it.id })
    }

    @Test
    fun `nested transactions do not commit early`() {
        // The lock is reentrant, so nesting is easy to do by accident. An inner block that committed
        // on its way out would leave the outer one's later work unprotected - and the rollback below
        // would then only undo part of it.
        db.createPlaylist("p", "Mix", 0)
        val failed = runCatching {
            db.transaction {
                db.addToPlaylist("p", song("a"))
                db.transaction { db.addToPlaylist("p", song("b")) }
                error("fail after the inner transaction")
            }
        }
        assertTrue(failed.isFailure)
        assertTrue("the inner transaction committed independently", db.playlistSongs("p").isEmpty())
    }
}

/**
 * The store on top of the database, and the one-time import of the old text files.
 */
class LibraryStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-store-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `an existing text library is imported once`() {
        File(dir, "recently-played.tsv").writeText(
            "id1\tFirst\tArtist\thttp://a\nid2\tSecond\tArtist\t\n"
        )
        File(dir, "liked.tsv").writeText("id2\tSecond\tArtist\t\n")

        val store = LibraryStore(dir)
        // Order is preserved: the old format kept most-recent-first and nothing else, so the first
        // line has to come out first.
        assertEquals(listOf("id1", "id2"), store.recentlyPlayed.value.map { it.id })
        assertEquals(listOf("id2"), store.liked.value.map { it.id })
        assertEquals("http://a", store.recentlyPlayed.value.first().thumbnail)
        store.close()

        // The files are renamed, not deleted - so a failed import can be looked at, and so a second
        // run cannot import everything again.
        assertFalse(File(dir, "recently-played.tsv").exists())
        assertTrue(File(dir, "recently-played.tsv.imported").exists())

        val second = LibraryStore(dir)
        assertEquals(2, second.recentlyPlayed.value.size)
        second.close()
    }

    @Test
    fun `a malformed line is skipped rather than losing the file`() {
        File(dir, "liked.tsv").writeText("good\tTitle\tArtist\n\nnonsense\n")
        val store = LibraryStore(dir)
        assertEquals(listOf("good"), store.liked.value.map { it.id })
        store.close()
    }

    @Test
    fun `no legacy files is not an error`() {
        val store = LibraryStore(dir)
        assertTrue(store.recentlyPlayed.value.isEmpty())
        assertTrue(store.liked.value.isEmpty())
        store.close()
    }

    @Test
    fun `the flows follow what is written`() {
        val store = LibraryStore(dir)
        val s = StoredSong("a", "Song", "Artist")

        store.recordPlay(s)
        assertEquals(listOf("a"), store.recentlyPlayed.value.map { it.id })

        store.toggleLiked(s)
        assertTrue(store.isLiked("a"))
        store.toggleLiked(s)
        assertFalse(store.isLiked("a"))

        val id = store.createPlaylist("Mix")
        store.addToPlaylist(id, s)
        assertEquals(1, store.playlists.value.single().songCount)
        store.close()
    }
}
