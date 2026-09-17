/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [QueueState.upcoming] says is coming next.
 *
 * Worth pinning because getting it wrong is invisible. `upcoming` exists only to decide which song
 * to fetch ahead of time, so naming the wrong one does not break the queue or produce an error - it
 * downloads a song nobody asked for, and then the track that actually plays next still stalls for
 * its own download. The feature looks implemented and simply never helps, which is the hardest kind
 * of wrong to notice.
 *
 * So every branch is checked against what `PlayerQueue.next()` would really do, including the two
 * that are easy to get backwards: repeat-one replays the *current* track, and the end of a queue
 * with repeat off has nothing coming at all rather than wrapping to the start.
 */
class UpcomingTrackTest {

    private fun song(n: Int) = SongItem(
        id = "id$n",
        title = "Song $n",
        artists = emptyList(),
        thumbnail = "",
    )

    /** A five-song queue, playing whichever play-order position is asked for. */
    private fun queue(
        playing: Int = 2,
        repeat: RepeatMode = RepeatMode.OFF,
        order: List<Int>? = null,
    ): QueueState {
        val songs = (0 until 5).map(::song)
        val playOrder = order ?: songs.indices.toList()
        return QueueState(
            songs = songs,
            index = playOrder[playing],
            order = playOrder,
            orderPosition = playing,
            repeat = repeat,
        )
    }

    @Test
    fun `mid-queue, the next entry in play order is what is coming`() {
        assertEquals("Song 3", queue(playing = 2).upcoming?.title)
    }

    @Test
    fun `at the end with repeat off, nothing is coming`() {
        // next() stops here rather than wrapping. Prefetching the first track would download
        // something that is never going to play.
        assertNull(queue(playing = 4).upcoming)
    }

    @Test
    fun `at the end with repeat all, the queue wraps to the front`() {
        assertEquals("Song 0", queue(playing = 4, repeat = RepeatMode.ALL).upcoming?.title)
    }

    @Test
    fun `repeat one names the current track, because that is what plays next`() {
        // The one branch that looks like a mistake and is not: with repeat-one, next() restarts
        // this track, so this track is genuinely what comes next and is worth having ready.
        val state = queue(playing = 2, repeat = RepeatMode.ONE)
        assertEquals("Song 2", state.upcoming?.title)
        assertEquals(state.current?.id, state.upcoming?.id)
    }

    @Test
    fun `repeat one wins even at the end of the queue`() {
        // Order matters in the when: the end-of-queue branch must not get there first.
        assertEquals("Song 4", queue(playing = 4, repeat = RepeatMode.ONE).upcoming?.title)
    }

    @Test
    fun `shuffled, it follows the play order rather than the song list`() {
        // The distinction that breaks everything if missed: order[] holds indices into songs[], and
        // reading songs[orderPosition + 1] instead would name whatever happened to be added next.
        val shuffled = listOf(3, 1, 4, 0, 2)
        val state = queue(playing = 1, order = shuffled)
        assertEquals("Song 1", state.current?.title)
        assertEquals("Song 4", state.upcoming?.title)
    }

    @Test
    fun `shuffled with repeat all wraps to the head of the play order, not to song zero`() {
        val shuffled = listOf(3, 1, 4, 0, 2)
        val state = queue(playing = 4, order = shuffled, repeat = RepeatMode.ALL)
        assertEquals("Song 3", state.upcoming?.title)
    }

    @Test
    fun `an empty queue has nothing coming`() {
        assertNull(QueueState().upcoming)
        assertNull(QueueState(repeat = RepeatMode.ALL).upcoming)
        assertNull(QueueState(repeat = RepeatMode.ONE).upcoming)
    }

    @Test
    fun `a single-song queue only has something coming when it repeats`() {
        val one = QueueState(songs = listOf(song(0)), index = 0, order = listOf(0), orderPosition = 0)
        assertNull(one.upcoming)
        assertEquals("Song 0", one.copy(repeat = RepeatMode.ALL).upcoming?.title)
        assertEquals("Song 0", one.copy(repeat = RepeatMode.ONE).upcoming?.title)
    }
}
