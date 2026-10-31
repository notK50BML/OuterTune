/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Stats page's choices: times played or time listened deciding the chart's order and numbers,
 * as on the phone, and every chip remembered so the page opens as it was left.
 */
class StatsChoicesTest {

    private lateinit var dir: File
    private lateinit var db: Database

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ot-stats-test").toFile()
        db = Database(File(dir, "library.db"))
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        dir.deleteRecursively()
    }

    private class FakeStore(initial: Map<String, String> = emptyMap()) : SettingsStore {
        val values = initial.toMutableMap()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }
    }

    /** [plays] plays of a song [seconds] long. */
    private fun play(id: String, seconds: Int, plays: Int) = repeat(plays) {
        db.recordPlay(StoredSong(id, "Song $id", "Artist $id"), atMs = 1_000L + it, durationMs = seconds * 1000L)
    }

    @Test
    fun `the chart ranks by plays or by time listened`() {
        play("short", seconds = 60, plays = 5)
        play("long", seconds = 400, plays = 2)

        val byPlays = db.songChart(0, limit = 10)
        assertEquals(listOf("short", "long"), byPlays.top.map { it.song.id })

        // A long song heard twice beats a short one heard five times, by time.
        val byTime = db.songChart(0, limit = 10, byTime = true)
        assertEquals(listOf("long", "short"), byTime.top.map { it.song.id })
        assertEquals(listOf(800_000L, 300_000L), byTime.top.map { it.listenedMs })
        assertEquals(listOf(2, 5), byTime.top.map { it.plays })
    }

    @Test
    fun `the chart's length is the one chosen`() {
        (1..30).forEach { play("s$it", seconds = 100 + it, plays = 1) }
        assertEquals(20, db.songChart(0, limit = 20).top.size)
        assertEquals(30, db.songChart(0, limit = Int.MAX_VALUE).top.size)
        // The Songs tile counts the whole chart whatever its length.
        assertEquals(30, db.songChart(0, limit = 20).songs)
    }

    @Test
    fun `the same song under two ids adds up its time too`() {
        val merged = mergeSameSongs(
            listOf(
                PlayedSong(StoredSong("single", "Same", "Artist"), plays = 2, lastPlayedAt = 1, listenedMs = 400_000),
                PlayedSong(StoredSong("album", "Same", "Artist"), plays = 1, lastPlayedAt = 2, listenedMs = 210_000),
            )
        )
        assertEquals(610_000L, merged.single().listenedMs)
        assertEquals(3, merged.single().plays)
    }

    @Test
    fun `the chips start where they always have, and time listened as on the phone`() {
        val settings = Settings(FakeStore())
        assertEquals(StatsPeriod.Month, settings.statsPeriod)
        assertEquals(StatsMetric.TimeListened, settings.statsMetric)
        assertEquals(50, settings.statsSongLimit)
    }

    @Test
    fun `the chips are remembered`() {
        val store = FakeStore()
        Settings(store).apply {
            statsPeriod = StatsPeriod.All
            statsMetric = StatsMetric.TimesPlayed
            statsSongLimit = 50
        }
        // What the next start of the app reads.
        val again = Settings(store)
        assertEquals(StatsPeriod.All, again.statsPeriod)
        assertEquals(StatsMetric.TimesPlayed, again.statsMetric)
        assertEquals(50, again.statsSongLimit)
    }

    @Test
    fun `a stored chip that no longer exists is the default`() {
        val settings = Settings(FakeStore(mapOf("stats.period" to "Fortnight", "stats.metric" to "Skips")))
        assertEquals(StatsPeriod.Month, settings.statsPeriod)
        assertEquals(StatsMetric.TimeListened, settings.statsMetric)
    }

    @Test
    fun `Stats finds the window's own settings by their store`() {
        val store = FakeStore()
        val window = Settings(store)
        assertSame(window, Settings.of(store))
    }

    @Test
    fun `listening time reads as people say it`() {
        assertEquals("3 h 20 min", formatListening(200 * 60_000L))
        assertEquals("12 min", formatListening(12 * 60_000L))
        // Not "0 min" beside a short song heard once.
        assertEquals("45 s", formatListening(45_000))
    }
}
