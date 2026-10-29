/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which duplicate artist rows the 24 to 25 migration folds together: the same artist under a Topic
 * row, a placeholder id or a second spelling of its channel - never two channels sharing a name.
 */
class ArtistMergePlanTest {

    private fun row(id: String, name: String, isLocal: Boolean = false) = ArtistMergeRow(id, name, isLocal)

    @Test
    fun `a topic row merges into the artist's channel`() {
        val channel = row("UCaaa", "Nirvana")
        val topic = row("UCtopic", "Nirvana - Topic")
        val merges = planArtistMerges(listOf(topic, channel))
        assertEquals(listOf(ArtistMerge(channel, listOf(topic))), merges)
    }

    @Test
    fun `two channels sharing a name are left apart`() {
        val merges = planArtistMerges(listOf(row("UCaaa", "Nirvana"), row("UCbbb", "Nirvana")))
        assertTrue(merges.isEmpty())
    }

    @Test
    fun `with two channels, topic and placeholder rows are left alone`() {
        val merges = planArtistMerges(
            listOf(
                row("UCaaa", "Nirvana"),
                row("UCbbb", "Nirvana"),
                row("UCtopic", "Nirvana - Topic"),
                row("LAplaceholder", "nirvana"),
            )
        )
        assertTrue(merges.isEmpty())
    }

    @Test
    fun `two spellings of one channel merge even beside another channel`() {
        val plain = row("UCaaa", "Nirvana")
        val prefixed = row("MPLAUCaaa", "Nirvana")
        val merges = planArtistMerges(listOf(prefixed, plain, row("UCbbb", "Nirvana")))
        assertEquals(listOf(ArtistMerge(plain, listOf(prefixed))), merges)
    }

    @Test
    fun `a placeholder row merges into the only channel`() {
        val channel = row("UCaaa", "Nirvana")
        val placeholder = row("LAplaceholder", "Nirvana")
        val merges = planArtistMerges(listOf(placeholder, channel))
        assertEquals(listOf(ArtistMerge(channel, listOf(placeholder))), merges)
    }

    @Test
    fun `rows with no channel at all still merge, a clean name surviving`() {
        val topic = row("LAone", "Nirvana - Topic")
        val clean = row("LAtwo", "Nirvana")
        val merges = planArtistMerges(listOf(topic, clean))
        assertEquals(listOf(ArtistMerge(clean, listOf(topic))), merges)
    }

    @Test
    fun `local and remote rows are never merged`() {
        val merges = planArtistMerges(listOf(row("LAone", "Nirvana", isLocal = true), row("UCaaa", "Nirvana")))
        assertTrue(merges.isEmpty())
    }
}
