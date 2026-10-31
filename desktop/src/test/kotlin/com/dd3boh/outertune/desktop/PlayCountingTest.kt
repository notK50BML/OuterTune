package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a start of a song becomes a play: time heard, not where the bar was dragged to. */
class PlayCountingTest {

    private fun heard(vararg positions: Long) = HeardTime().apply { positions.forEach(::advance) }.heardMs

    @Test
    fun `listening adds up`() {
        assertEquals(1_000L, heard(0, 250, 500, 750, 1_000))
    }

    @Test
    fun `a seek adds nothing`() {
        // Ten seconds heard, a drag to three minutes, then one more second.
        assertEquals(11_000L, heard(0, 5_000, 10_000, 180_000, 181_000))
    }

    @Test
    fun `going back adds nothing, and listening again after it does`() {
        assertEquals(4_000L, heard(0, 2_000, 0, 2_000))
    }

    @Test
    fun `the first position is where listening starts, not time heard`() {
        // A saved queue resumed two minutes in.
        assertEquals(500L, heard(120_000, 120_500))
    }

    @Test
    fun `a play is the share of the song heard`() {
        assertFalse(countsAsPlay(heardMs = 59_999, durationMs = 200_000, minPercent = 30))
        assertTrue(countsAsPlay(heardMs = 60_000, durationMs = 200_000, minPercent = 30))
    }

    @Test
    fun `a song of no length is never a play`() {
        assertFalse(countsAsPlay(heardMs = 3_600_000, durationMs = 0, minPercent = 30))
    }

    @Test
    fun `the share is kept between one and ninety-nine percent`() {
        assertFalse(countsAsPlay(heardMs = 0, durationMs = 200_000, minPercent = 0))
        assertFalse(countsAsPlay(heardMs = 1_000, durationMs = 200_000, minPercent = 0))
        assertTrue(countsAsPlay(heardMs = 2_000, durationMs = 200_000, minPercent = 0))
        assertTrue(countsAsPlay(heardMs = 198_000, durationMs = 200_000, minPercent = 100))
    }

    private fun queue(fromStation: Boolean = false, following: Boolean = false) = QueueState(
        songs = listOf(com.zionhuang.innertube.models.SongItem(id = "s", title = "S", artists = emptyList(), thumbnail = "", duration = 200)),
        index = 0, order = listOf(0), orderPosition = 0, fromStation = fromStation, following = following,
    )

    @Test
    fun `the listener's own queue counts`() {
        assertFalse(queue().station)
    }

    @Test
    fun `a station's songs and a Listen Together host's do not count`() {
        assertTrue(queue(fromStation = true).station)
        // Following someone else's session: every song the host plays reaches this computer too,
        // and used to be counted here as well as on the host.
        assertTrue(queue(following = true).station)
    }

    @Test
    fun `a remembered queue keeps its station but not the session it was followed in`() {
        val (station, _) = QueueSnapshot.decode(QueueSnapshot.encode(queue(fromStation = true), 0))!!
        assertTrue(station.station)
        // Played again after a restart, the session is long over: the listener's own choice now.
        val (followed, _) = QueueSnapshot.decode(QueueSnapshot.encode(queue(following = true), 0))!!
        assertFalse(followed.station)
    }
}
