/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongPlayStats
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stats counting one song once, whatever ids it was played under. */
class SameSongMergeTest {

    private fun stats(id: String, title: String, artist: String, plays: Int, timeMs: Long = plays * 1000L) =
        SongPlayStats(
            song = Song(
                song = SongEntity(id = id, title = title, localPath = null),
                artists = listOf(ArtistEntity(id = "a-$artist", name = artist)),
            ),
            totalPlayTime = timeMs,
            totalPlays = plays,
        )

    @Test
    fun `the same title by the same artist is one song`() {
        val merged = mergeSameSongs(
            listOf(
                stats("single", "So Cynical (Badum)", "LE SSERAFIM", 300),
                stats("album", "So Cynical (Badum)", "LE SSERAFIM", 43),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(343, merged.single().totalPlays)
        assertEquals(343_000L, merged.single().totalPlayTime)
        assertEquals("the version played most stands for it", "single", merged.single().song.id)
    }

    @Test
    fun `case and surrounding spaces do not split a song`() {
        val merged = mergeSameSongs(listOf(stats("a", "Hello", "Adele", 3), stats("b", " hello ", "ADELE", 2)))
        assertEquals(listOf(5), merged.map { it.totalPlays })
    }

    @Test
    fun `the same title by someone else stays separate`() {
        val merged = mergeSameSongs(listOf(stats("a", "DAHLIA", "i-dle", 5), stats("b", "DAHLIA", "X JAPAN", 4)))
        assertEquals(2, merged.size)
    }

    @Test
    fun `a different version is a different song`() {
        val merged = mergeSameSongs(
            listOf(stats("a", "CRAZY", "LE SSERAFIM", 5), stats("b", "CRAZY (English ver.)", "LE SSERAFIM", 4))
        )
        assertEquals(2, merged.size)
    }
}
