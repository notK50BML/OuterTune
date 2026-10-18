/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Caching lyrics for a song the library has no row for - a search result or radio pick that has
 * only just started - failed the lyrics table's foreign key. It is now skipped instead.
 */
class LyricsCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-lyrics-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `lyrics for a song not in the library are skipped, not a foreign key failure`() {
        val db = Database(File(dir, "library.db"))
        db.putLyrics("unknown", "[00:01.00]la", "LrcLib")
        assertNull(db.lyrics("unknown"))
        db.close()
    }

    @Test
    fun `lyrics for a known song are cached`() {
        val db = Database(File(dir, "library.db"))
        db.upsertSong(StoredSong("a", "Song", "Artist"))
        db.putLyrics("a", "[00:01.00]la", "LrcLib")
        assertEquals("[00:01.00]la", db.lyrics("a")?.text)
        db.close()
    }
}
