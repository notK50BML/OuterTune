package com.zionhuang.innertube

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HistoryPage
import com.zionhuang.innertube.utils.HistoryTally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HistoryTallyTest {

    // A Wednesday.
    private val today = LocalDate.of(2026, 10, 21)

    private fun song(id: String) = SongItem(
        id = id, title = id, artists = listOf(Artist(name = "a", id = null)), album = null,
        duration = 180, thumbnail = "", explicit = false, endpoint = null,
    )

    private fun section(title: String, vararg ids: String) = HistoryPage.HistorySection(title, ids.map(::song))

    @Test
    fun `relative sections are dated from today`() {
        assertEquals(1L, HistoryTally.maxAgeDays("Today", today))
        assertEquals(2L, HistoryTally.maxAgeDays("Yesterday", today))
        assertEquals(3L, HistoryTally.maxAgeDays("This week", today))
        assertEquals(10L, HistoryTally.maxAgeDays("Last week", today))
        assertEquals(21L, HistoryTally.maxAgeDays("This month", today))
    }

    @Test
    fun `month sections are dated from their first day`() {
        assertEquals(51L, HistoryTally.maxAgeDays("September 2026", today))
        assertEquals(51L, HistoryTally.maxAgeDays("September", today))
        // December has not come yet this year, so a bare "December" is last year's.
        assertEquals(325L, HistoryTally.maxAgeDays("December", today))
    }

    @Test
    fun `a title that cannot be dated is not guessed at`() {
        assertNull(HistoryTally.maxAgeDays("Earlier", today))
        assertNull(HistoryTally.maxAgeDays("", today))
    }

    @Test
    fun `a week counts only what is inside the week`() {
        val history = listOf(
            section("Today", "a", "b"),
            section("This week", "a"),
            section("Last week", "c"),
            section("September 2026", "d"),
        )
        val week = HistoryTally.tally(history, periodDays = 7, today = today)
        assertEquals(mapOf("a" to 2, "b" to 1), week.mapValues { it.value.plays })
        val all = HistoryTally.tally(history, periodDays = null, today = today)
        assertEquals(mapOf("a" to 2, "b" to 1, "c" to 1, "d" to 1), all.mapValues { it.value.plays })
    }

    @Test
    fun `a section that cannot be dated counts for all time only`() {
        val history = listOf(section("Earlier", "x"))
        assertEquals(emptyMap<String, Int>(), HistoryTally.tally(history, 30, today).mapValues { it.value.plays })
        assertEquals(mapOf("x" to 1), HistoryTally.tally(history, null, today).mapValues { it.value.plays })
    }

    @Test
    fun `history adds only what local plays do not already cover`() {
        // Shared plays are in both - the larger, not the sum.
        assertEquals(0, HistoryTally.extraPlays(localPlays = 5, historyPlays = 3))
        assertEquals(0, HistoryTally.extraPlays(localPlays = 4, historyPlays = 4))
        assertEquals(6, HistoryTally.extraPlays(localPlays = 0, historyPlays = 6))
        assertEquals(2, HistoryTally.extraPlays(localPlays = 3, historyPlays = 5))
    }
}
