/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.PlayTotals
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.HistoryTally
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Stats header's totals with the YouTube history folded in.
 *
 * The case that went wrong: before this device's own per-song counts had loaded, every history
 * play was taken as extra, so the header briefly showed far more plays than the chart under it.
 */
class StatsTotalsTest {
    private fun song(id: String, seconds: Int) = SongItem(
        id = id,
        title = "Song $id",
        artists = listOf(Artist(name = "Someone", id = "UCsomeone")),
        duration = seconds,
        thumbnail = "https://example.invalid/$id.jpg",
    )

    private val local = PlayTotals(plays = 5, playTime = 600_000L)
    private val tally = mapOf("a" to HistoryTally.Plays(song("a", 200), plays = 4))

    @Test
    fun `local counts not loaded yet - the totals are the device's own`() {
        assertEquals(local, withRemotePlays(local, tally, localPlays = null))
    }

    @Test
    fun `only the history plays beyond the device's own are added`() {
        // 3 plays here, 4 in the history: one more, of a 200 s song.
        val totals = withRemotePlays(local, tally, localPlays = mapOf("a" to 3))
        assertEquals(PlayTotals(plays = 6, playTime = 800_000L), totals)
    }

    @Test
    fun `no history - the totals are the device's own`() {
        assertEquals(local, withRemotePlays(local, emptyMap(), localPlays = emptyMap()))
    }
}
