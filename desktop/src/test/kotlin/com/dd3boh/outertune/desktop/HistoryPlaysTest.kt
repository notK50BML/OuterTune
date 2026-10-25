package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.HistoryTally
import org.junit.Assert.assertEquals
import org.junit.Test

/** Stats with plays from the account's history: added without counting this computer's twice. */
class HistoryPlaysTest {

    private fun item(id: String, seconds: Int = 200) = SongItem(
        id = id, title = id, artists = listOf(Artist(name = "artist", id = null)), album = null,
        duration = seconds, thumbnail = "", explicit = false, endpoint = null,
    )

    private fun local(id: String, plays: Int) = PlayedSong(StoredSong(id, id, "artist"), plays, 1L)

    @Test
    fun `a song played here takes the larger count, not the sum`() {
        val rows = withHistoryPlays(
            listOf(local("shared", 5), local("more-elsewhere", 2)),
            mapOf(
                "shared" to HistoryTally.Plays(item("shared"), 5),
                "more-elsewhere" to HistoryTally.Plays(item("more-elsewhere"), 7),
            ),
        )
        assertEquals(mapOf("shared" to 5, "more-elsewhere" to 7), rows.associate { it.song.id to it.plays })
    }

    @Test
    fun `a song played only elsewhere joins the chart`() {
        val rows = withHistoryPlays(listOf(local("here", 1)), mapOf("phone" to HistoryTally.Plays(item("phone"), 4)))
        assertEquals(mapOf("here" to 1, "phone" to 4), rows.associate { it.song.id to it.plays })
    }

    @Test
    fun `totals gain only what the history adds`() {
        val totals = PlayTotals(plays = 10, songs = 2, listenedMs = 1_000_000).withHistory(
            remote = mapOf(
                "a" to HistoryTally.Plays(item("a", seconds = 100), 6),
                "b" to HistoryTally.Plays(item("b", seconds = 60), 3),
            ),
            localPlays = mapOf("a" to 4),
        )
        // a: 2 more than played here; b: 3, and a song not played here at all.
        assertEquals(15, totals.plays)
        assertEquals(3, totals.songs)
        assertEquals(1_000_000 + 2 * 100_000L + 3 * 60_000L, totals.listenedMs)
    }
}
