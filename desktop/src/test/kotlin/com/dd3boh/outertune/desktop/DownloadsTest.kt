/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Downloads: the index and the files agreeing, and what happens when they do not.
 *
 * The disagreement cases are the point. Someone clearing out a folder is a perfectly reasonable
 * thing to do, and a half-written file from an interrupted download is not rare - both must leave
 * the app correct rather than certain it has a song it cannot play.
 */
class DownloadsTest {

    private lateinit var root: File
    private lateinit var database: Database
    private lateinit var downloads: Downloads

    @Before
    fun setUp() {
        root = Files.createTempDirectory("outertune-downloads").toFile()
        database = Database(File(root, "library.db"))
        downloads = Downloads(File(root, "downloads"), database)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private val audio = ByteArray(2048) { (it % 251).toByte() }

    @Test
    fun `nothing is downloaded to begin with`() {
        assertFalse(downloads.has("abc"))
        assertNull(downloads.read("abc"))
        assertTrue(downloads.ids().isEmpty())
        assertEquals(0L, downloads.totalBytes())
    }

    @Test
    fun `a saved song comes back byte for byte`() {
        assertTrue(downloads.save("abc", audio).isSuccess)
        assertTrue(downloads.has("abc"))
        assertArrayEquals(audio, downloads.read("abc"))
    }

    @Test
    fun `a saved song is listed and counted`() {
        downloads.save("abc", audio)
        downloads.save("def", audio)
        assertEquals(setOf("abc", "def"), downloads.ids().toSet())
        assertEquals(audio.size * 2L, downloads.totalBytes())
    }

    @Test
    fun `deleting removes both the file and the record`() {
        downloads.save("abc", audio)
        downloads.delete("abc")
        assertFalse(downloads.has("abc"))
        assertFalse(downloads.fileFor("abc").exists())
        assertTrue(downloads.ids().isEmpty())
    }

    @Test
    fun `a record whose file has gone is not reported as downloaded`() {
        // Someone cleared out the folder. The app should download it again on the next request, not
        // insist it already has a song it cannot play.
        downloads.save("abc", audio)
        assertTrue(downloads.fileFor("abc").delete())

        assertFalse(downloads.has("abc"))
        assertNull(downloads.read("abc"))
        assertTrue("the stale record should be cleared", downloads.ids().isEmpty())
    }

    @Test
    fun `a stale record is cleared rather than left to fail again`() {
        downloads.save("abc", audio)
        downloads.fileFor("abc").delete()
        downloads.has("abc")
        // Directly against the database: the row itself should be gone, not merely filtered out.
        assertNull(database.download("abc"))
    }

    @Test
    fun `a partial file is never mistaken for a download`() {
        // What an interrupted save leaves behind. It must not be visible as the finished article,
        // because a truncated MP4 fails to decode and is far harder to diagnose than a missing one.
        File(downloads.fileFor("abc").parentFile, "abc.part").writeBytes(audio.copyOf(100))
        assertFalse(downloads.has("abc"))
    }

    @Test
    fun `saving again replaces what was there`() {
        downloads.save("abc", audio)
        val replacement = ByteArray(64) { 7 }
        downloads.save("abc", replacement)
        assertArrayEquals(replacement, downloads.read("abc"))
        assertEquals(64L, downloads.totalBytes())
    }

    @Test
    fun `deleting everything leaves nothing behind`() {
        downloads.save("abc", audio)
        downloads.save("def", audio)
        downloads.deleteAll()
        assertTrue(downloads.ids().isEmpty())
        assertEquals(0L, downloads.totalBytes())
        assertFalse(downloads.fileFor("abc").exists())
        assertFalse(downloads.fileFor("def").exists())
    }

    @Test
    fun `downloads survive a restart`() {
        downloads.save("abc", audio)
        database.close()

        val reopened = Database(File(root, "library.db"))
        val afterRestart = Downloads(File(root, "downloads"), reopened)
        assertTrue(afterRestart.has("abc"))
        assertArrayEquals(audio, afterRestart.read("abc"))
        reopened.close()
        database = Database(File(root, "library.db"))
    }

    @Test
    fun `sizes are reported in units people use`() {
        assertEquals("512 bytes", Downloads.formatSize(512))
        assertEquals("2 KB", Downloads.formatSize(2048))
        assertEquals("5 MB", Downloads.formatSize(5 * 1_048_576L))
        assertEquals("1.5 GB", Downloads.formatSize((1.5 * 1_073_741_824).toLong()))
    }
}
