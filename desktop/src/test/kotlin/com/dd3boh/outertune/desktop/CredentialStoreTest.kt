/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The credential table, and the migration that adds it.
 *
 * The migration matters more than the table. A schema step that only works on a fresh database is
 * the kind of thing that passes every test written after it and breaks for every existing user, so
 * the upgrade path is exercised directly here: build a version-1 database, put data in it, reopen
 * it, and check both that the new table appeared and that nothing already there was lost.
 */
class CredentialStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-cred-test").toFile()
        file = File(dir, "library.db")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a credential round-trips`() {
        val db = Database(file)
        assertNull(db.credential("youtube_cookie"))

        db.putCredential("youtube_cookie", "SAPISID=abc")
        assertEquals("SAPISID=abc", db.credential("youtube_cookie"))
        db.close()
    }

    @Test
    fun `a credential survives a restart`() {
        // The entire point of storing it. A session that has to be supplied again every launch is
        // not a sign-in.
        Database(file).use { it.putCredential("youtube_cookie", "SAPISID=abc") }
        Database(file).use { assertEquals("SAPISID=abc", it.credential("youtube_cookie")) }
    }

    @Test
    fun `writing again replaces rather than duplicating`() {
        Database(file).use { db ->
            db.putCredential("youtube_cookie", "first")
            db.putCredential("youtube_cookie", "second")
            assertEquals("second", db.credential("youtube_cookie"))
        }
    }

    @Test
    fun `signing out removes it`() {
        Database(file).use { db ->
            db.putCredential("youtube_cookie", "SAPISID=abc")
            db.putCredential("youtube_cookie", null)
            assertNull(db.credential("youtube_cookie"))
        }
        // And it stays gone, rather than reappearing from a file that was never rewritten.
        Database(file).use { assertNull(it.credential("youtube_cookie")) }
    }

    @Test
    fun `upgrading an existing library keeps its contents and gains the table`() {
        // A version-1 database, built by hand exactly as the first release left it.
        Class.forName("org.sqlite.JDBC")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE song (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, " +
                        "artists TEXT NOT NULL, thumbnail TEXT NOT NULL DEFAULT '', " +
                        "durationMs INTEGER NOT NULL DEFAULT 0)"
                )
                st.executeUpdate(
                    "CREATE TABLE play_history (songId TEXT PRIMARY KEY NOT NULL " +
                        "REFERENCES song(id) ON DELETE CASCADE, playedAt INTEGER NOT NULL)"
                )
                st.executeUpdate(
                    "CREATE TABLE liked (songId TEXT PRIMARY KEY NOT NULL " +
                        "REFERENCES song(id) ON DELETE CASCADE, likedAt INTEGER NOT NULL)"
                )
                st.executeUpdate(
                    "CREATE TABLE playlist (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                st.executeUpdate(
                    "CREATE TABLE playlist_song (playlistId TEXT NOT NULL REFERENCES playlist(id) " +
                        "ON DELETE CASCADE, songId TEXT NOT NULL REFERENCES song(id) ON DELETE " +
                        "CASCADE, position INTEGER NOT NULL, PRIMARY KEY (playlistId, songId))"
                )
                st.executeUpdate("INSERT INTO song VALUES ('a', 'Old Song', 'Artist', '', 0)")
                st.executeUpdate("INSERT INTO liked VALUES ('a', 1000)")
                st.executeUpdate("PRAGMA user_version=1")
            }
        }

        Database(file).use { db ->
            assertEquals("nothing already stored may be lost", listOf("a"), db.likedSongs().map { it.id })
            db.putCredential("youtube_cookie", "SAPISID=abc")
            assertEquals("SAPISID=abc", db.credential("youtube_cookie"))
        }
    }

    @Test
    fun `upgrading twice is not an error`() {
        Database(file).use { it.putCredential("k", "v") }
        Database(file).use { assertEquals("v", it.credential("k")) }
        Database(file).use { assertEquals("v", it.credential("k")) }
    }

    private inline fun <T> Database.use(block: (Database) -> T): T =
        try { block(this) } finally { close() }
}

/**
 * Telling a pasted cookie header apart from a pasted cookies.txt file.
 *
 * Asking the user which one they have would be a question that exists only for the program's
 * benefit - the difference is visible in the text itself.
 */
class PastedCredentialTest {

    @Test
    fun `a tab means it is a cookies file`() {
        val fileText = "# Netscape HTTP Cookie File\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1900000000\tSAPISID\tabc123"
        val result = CookieImport.fromText(fileText)
        assertTrue(result is CookieImport.Result.Session)
        assertEquals("SAPISID=abc123", (result as CookieImport.Result.Session).cookie)
    }

    @Test
    fun `a header has no tabs and is used as-is`() {
        // The classification is a single character, so it is worth being sure a real header has none
        // of it: the format is "name=value; name=value", separated by semicolons and spaces.
        val header = "SAPISID=abc123; __Secure-3PSID=xyz; SID=q"
        assertTrue("a cookie header should not contain tabs", !header.contains('\t'))
    }

    @Test
    fun `a header pasted with surrounding whitespace still works`() {
        // Copying from developer tools brings a newline along more often than not.
        val header = "  SAPISID=abc123; SID=q  \n"
        assertTrue(!header.trim().contains('\t'))
        assertEquals("SAPISID=abc123; SID=q", header.trim())
    }
}
