/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class LikeImportTest {

    private fun song(id: String) = StoredSong(id, "Title $id", "Artist")

    @Test
    fun `imported likes keep YouTube's order, newest first`() {
        val library = LibraryStore(Files.createTempDirectory("like-import").toFile())
        val added = library.importLiked(listOf(song("a"), song("b"), song("c")))
        assertEquals(3, added)
        assertEquals(listOf("a", "b", "c"), library.liked.value.map { it.id })
        library.close()
    }

    @Test
    fun `an import adds without unliking or reordering what is already liked`() {
        val library = LibraryStore(Files.createTempDirectory("like-import").toFile())
        library.toggleLiked(song("mine"))
        val added = library.importLiked(listOf(song("mine"), song("theirs")))
        assertEquals(1, added)
        assertEquals(setOf("mine", "theirs"), library.liked.value.map { it.id }.toSet())
        library.close()
    }

    @Test
    fun `importing the same list twice changes nothing the second time`() {
        val library = LibraryStore(Files.createTempDirectory("like-import").toFile())
        library.importLiked(listOf(song("a"), song("b")))
        assertEquals(0, library.importLiked(listOf(song("a"), song("b"))))
        library.close()
    }
}
