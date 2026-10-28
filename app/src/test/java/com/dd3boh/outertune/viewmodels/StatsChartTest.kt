/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.constants.StatMetric
import com.dd3boh.outertune.db.daos.SongPlays
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.ArtistPlayStats
import com.dd3boh.outertune.db.entities.PlayTotals
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.HistoryTally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Stats chart, header and artist row with the YouTube history folded in.
 *
 * The case that went wrong: the history was merged against only the top of the local chart, so a
 * song below it that also had history plays counted differently at 20, 50, 100 and "All" songs.
 */
class StatsChartTest {
    private val sep = SongPlays.ARTIST_SEPARATOR

    private fun local(id: String, plays: Int, seconds: Int = 200, title: String = "Song $id", artist: String = "Someone") =
        SongPlays(
            songId = id,
            title = title,
            duration = seconds,
            artists = artist,
            artistIds = "UC$artist",
            plays = plays,
            playTime = plays * seconds * 1000L,
        )

    private fun history(id: String, plays: Int, seconds: Int? = 200, artist: String = "Someone") = id to HistoryTally.Plays(
        SongItem(
            id = id,
            title = "Song $id",
            artists = listOf(Artist(name = artist, id = "UC$artist")),
            duration = seconds,
            thumbnail = "https://example.invalid/$id.jpg",
        ),
        plays = plays,
    )

    @Test
    fun `a song far down the local chart keeps its own plays when the history adds to it`() {
        // 300 songs played here; "low" is last, with one play here and five in the history.
        val rows = (1..299).map { local("s$it", plays = 10 + it) } + local("low", plays = 1)
        val chart = buildStatsChart(rows, mapOf(history("low", plays = 5)), StatMetric.TIME_LISTENED)

        val low = chart.songs.single { it.song.id == "low" }
        assertEquals(5, low.totalPlays)
        assertEquals(5 * 200_000L, low.totalPlayTime)
        assertTrue("played here, so shown from the library", "low" in chart.localIds)
    }

    @Test
    fun `the header is the whole chart added up`() {
        val rows = listOf(local("a", 3), local("b", 2, seconds = 100))
        val tally = mapOf(history("a", 4), history("c", 2, seconds = 50))
        val chart = buildStatsChart(rows, tally, StatMetric.TIMES_PLAYED)

        // a: 3 here, 4 in the history - one more. c: only in the history.
        assertEquals(PlayTotals(plays = 4 + 2 + 2, playTime = 800_000L + 200_000L + 100_000L), chart.totals)
        assertEquals(chart.totals.plays, chart.songs.sumOf { it.totalPlays })
        assertEquals(chart.totals.playTime, chart.songs.sumOf { it.totalPlayTime })
    }

    @Test
    fun `no history - the chart and header are the device's own`() {
        val chart = buildStatsChart(listOf(local("a", 3), local("b", 5)), emptyMap(), StatMetric.TIMES_PLAYED)
        assertEquals(listOf("b", "a"), chart.songs.map { it.song.id })
        assertEquals(PlayTotals(plays = 8, playTime = 1_600_000L), chart.totals)
    }

    @Test
    fun `a song of unknown length played elsewhere adds plays but takes no time away`() {
        val chart = buildStatsChart(emptyList(), mapOf(history("live", 3, seconds = null)), StatMetric.TIME_LISTENED)
        assertEquals(3, chart.songs.single().totalPlays)
        assertEquals(0L, chart.songs.single().totalPlayTime)
        assertEquals(0L, historyPlayTime(3, historySeconds = -1, localSeconds = -1))
        assertEquals(600_000L, historyPlayTime(3, historySeconds = null, localSeconds = 200))
    }

    @Test
    fun `two ids of one song add up wherever each ranks alone`() {
        val rows = (1..300).map { local("s$it", plays = 10) } +
            local("v1", plays = 6, title = "Same") + local("v2", plays = 6, title = "Same")
        val chart = buildStatsChart(rows, emptyMap(), StatMetric.TIMES_PLAYED)
        assertEquals("Same", chart.songs.first().song.song.title)
        assertEquals(12, chart.songs.first().totalPlays)
        assertEquals("ties go to the lower id", "v1", chart.songs.first().song.id)
    }

    @Test
    fun `ties rank the same whatever order the rows come in`() {
        val rows = listOf(local("c", 2), local("a", 2), local("b", 2))
        val one = buildStatsChart(rows, emptyMap(), StatMetric.TIMES_PLAYED).songs.map { it.song.id }
        val two = buildStatsChart(rows.reversed(), emptyMap(), StatMetric.TIMES_PLAYED).songs.map { it.song.id }
        assertEquals(listOf("a", "b", "c"), one)
        assertEquals(one, two)
    }

    @Test
    fun `the artist row gains the history plays the chart does`() {
        val rows = listOf(local("a", 1, artist = "Here"), local("b", 3, artist = "Other"))
        val tally = mapOf(history("a", 5, artist = "Here"), history("x", 2, artist = "Away"))
        val chart = buildStatsChart(rows, tally, StatMetric.TIMES_PLAYED)
        val localArtists = listOf(
            ArtistPlayStats(ArtistEntity(id = "UCOther", name = "Other"), totalPlayTime = 600_000L, totalPlays = 3),
            ArtistPlayStats(ArtistEntity(id = "UCHere", name = "Here"), totalPlayTime = 200_000L, totalPlays = 1),
        )
        val artists = withHistoryArtists(localArtists, chart.artistExtras, StatMetric.TIMES_PLAYED)

        assertEquals(listOf("UCHere" to 5, "UCOther" to 3, "UCAway" to 2), artists.artists.map { it.artist.id to it.totalPlays })
        assertEquals(setOf("UCAway"), artists.historyOnly)
        assertEquals("Away", artists.artists.last().artist.name)
    }

    @Test
    fun `credited names split back apart`() {
        val row = local("a", 1).copy(artists = "One${sep}Two", artistIds = "UC1${sep}UC2")
        assertEquals(listOf("One", "Two"), row.artistNames)
        assertEquals(listOf("UC1", "UC2"), row.artistIdList)
    }
}
