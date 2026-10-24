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

/** Unliking and liking again puts a song back where it was - see LibraryStore.toggleLiked. */
class RelikeOrderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-relike").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a song liked again returns to its old place, not the top`() {
        val library = LibraryStore(dir)
        val first = StoredSong("first", "First", "A")
        val second = StoredSong("second", "Second", "B")
        library.toggleLiked(first)
        Thread.sleep(5)
        library.toggleLiked(second)
        assertEquals(listOf("second", "first"), library.liked.value.map { it.id })

        library.toggleLiked(first) // an accidental unlike
        assertEquals(listOf("second"), library.liked.value.map { it.id })
        library.toggleLiked(first) // taken back
        assertEquals(listOf("second", "first"), library.liked.value.map { it.id })
    }
}
