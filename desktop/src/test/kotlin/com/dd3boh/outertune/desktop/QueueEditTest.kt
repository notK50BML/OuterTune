/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reordering and removing, checked against the one thing that matters: what is playing must not
 * change unless it was the entry acted on.
 *
 * This is logic that fails silently. Get it wrong and the queue still renders and still plays - it
 * just moves the wrong row, or jumps to another song mid-listen for no reason the user can connect
 * to what they did. Neither shows up as an error anywhere.
 */
class QueueEditTest {

    private fun song(n: Int) = SongItem(
        id = "id$n",
        title = "Song $n",
        artists = emptyList(),
        thumbnail = "",
    )

    /** A five-song queue in natural order, playing the third. */
    private fun queue(playing: Int = 2, shuffled: Boolean = false, order: List<Int>? = null): QueueState {
        val songs = (0 until 5).map(::song)
        val playOrder = order ?: songs.indices.toList()
        return QueueState(
            songs = songs,
            index = playOrder[playing],
            order = playOrder,
            orderPosition = playing,
            shuffled = shuffled,
        )
    }

    /** The titles in play order, which is what the queue list actually shows. */
    private fun shown(state: QueueState) = state.order.map { state.songs[it].title }

    // ---- moving ----------------------------------------------------------------------------

    @Test
    fun `moving reorders what the list shows`() {
        val moved = QueueEdit.move(queue(), from = 0, to = 4)
        assertEquals(
            listOf("Song 1", "Song 2", "Song 3", "Song 4", "Song 0"),
            shown(moved),
        )
    }

    @Test
    fun `moving an entry from below the current one to above it keeps the same song playing`() {
        // The case that catches a naive implementation. Dragging row 4 to row 0 shifts everything in
        // between down by one, including the row being listened to - so a position left untouched
        // now points at a different song.
        val before = queue(playing = 2)
        val after = QueueEdit.move(before, from = 4, to = 0)
        assertEquals("the playing song changed", before.current?.id, after.current?.id)
        assertEquals(3, after.orderPosition)
    }

    @Test
    fun `moving an entry from above the current one to below it keeps the same song playing`() {
        val before = queue(playing = 2)
        val after = QueueEdit.move(before, from = 0, to = 4)
        assertEquals(before.current?.id, after.current?.id)
        assertEquals(1, after.orderPosition)
    }

    @Test
    fun `moving the playing entry carries the position with it`() {
        val before = queue(playing = 2)
        val after = QueueEdit.move(before, from = 2, to = 0)
        assertEquals(before.current?.id, after.current?.id)
        assertEquals(0, after.orderPosition)
    }

    @Test
    fun `a move that touches neither side of the current entry leaves it alone`() {
        val before = queue(playing = 2)
        val after = QueueEdit.move(before, from = 3, to = 4)
        assertEquals(2, after.orderPosition)
        assertEquals(before.current?.id, after.current?.id)
    }

    @Test
    fun `moving works in play order, not in the order songs were added`() {
        // With shuffle on the two disagree, and a function that takes one and means the other is a
        // bug waiting for the first shuffled queue.
        val shuffledOrder = listOf(3, 1, 4, 0, 2)
        val before = queue(playing = 1, shuffled = true, order = shuffledOrder)
        val after = QueueEdit.move(before, from = 0, to = 4)

        assertEquals(listOf(1, 4, 0, 2, 3), after.order)
        assertEquals("the playing song changed", before.current?.id, after.current?.id)
        assertEquals(0, after.orderPosition)
    }

    @Test
    fun `a move outside the queue does nothing`() {
        val before = queue()
        assertEquals(before, QueueEdit.move(before, from = 0, to = 99))
        assertEquals(before, QueueEdit.move(before, from = -1, to = 2))
        assertEquals(before, QueueEdit.move(before, from = 2, to = 2))
    }

    // ---- removing --------------------------------------------------------------------------

    @Test
    fun `removing an entry takes it out of the list`() {
        val after = QueueEdit.removeAt(queue(), position = 0)
        assertEquals(listOf("Song 1", "Song 2", "Song 3", "Song 4"), shown(after))
        assertEquals(4, after.songs.size)
    }

    @Test
    fun `removing above the current entry keeps the same song playing`() {
        // Both the song list and the order shrink, so the current position has to come down by one
        // *and* every order entry pointing past the removed song has to come down by one. Doing
        // either without the other leaves the queue pointing at the wrong song.
        val before = queue(playing = 2)
        val after = QueueEdit.removeAt(before, position = 0)
        assertEquals("the playing song changed", before.current?.id, after.current?.id)
        assertEquals(1, after.orderPosition)
        assertEquals(after.songs[after.index].id, before.current?.id)
    }

    @Test
    fun `removing below the current entry keeps the same song and position`() {
        val before = queue(playing = 2)
        val after = QueueEdit.removeAt(before, position = 4)
        assertEquals(before.current?.id, after.current?.id)
        assertEquals(2, after.orderPosition)
    }

    @Test
    fun `removing the playing entry promotes the one after it`() {
        val before = queue(playing = 2)
        val after = QueueEdit.removeAt(before, position = 2)
        assertEquals("Song 3", after.current?.title)
        assertEquals(2, after.orderPosition)
    }

    @Test
    fun `removing the playing entry at the end falls back to the new last one`() {
        val before = queue(playing = 4)
        val after = QueueEdit.removeAt(before, position = 4)
        assertEquals("Song 3", after.current?.title)
        assertEquals(3, after.orderPosition)
    }

    @Test
    fun `removing the last remaining entry empties the queue`() {
        val single = QueueState(
            songs = listOf(song(0)),
            index = 0,
            order = listOf(0),
            orderPosition = 0,
            title = "Somewhere",
        )
        val after = QueueEdit.removeAt(single, position = 0)
        assertTrue(after.songs.isEmpty())
        assertNull(after.current)
        // The queue is empty, not a different queue - its settings survive.
        assertEquals("Somewhere", after.title)
    }

    @Test
    fun `removing works in play order too`() {
        val shuffledOrder = listOf(3, 1, 4, 0, 2)
        val before = queue(playing = 2, shuffled = true, order = shuffledOrder)
        // Removing position 0 takes out song 3, so every entry above 3 comes down by one.
        val after = QueueEdit.removeAt(before, position = 0)

        assertEquals(listOf(1, 3, 0, 2), after.order)
        assertEquals("the playing song changed", before.current?.id, after.current?.id)
        assertEquals(after.songs[after.index].id, before.current?.id)
    }

    @Test
    fun `a removal outside the queue does nothing`() {
        val before = queue()
        assertEquals(before, QueueEdit.removeAt(before, position = 99))
        assertEquals(before, QueueEdit.removeAt(before, position = -1))
    }

    @Test
    fun `the order never points outside the song list`() {
        // The invariant everything else depends on. Checked after each of a series of edits rather
        // than once, since it is the accumulation that breaks it.
        var state = queue()
        listOf(
            { QueueEdit.move(state, 0, 3) },
            { QueueEdit.removeAt(state, 1) },
            { QueueEdit.move(state, 2, 0) },
            { QueueEdit.removeAt(state, 0) },
        ).forEach { edit ->
            state = edit()
            assertTrue(
                "order ${state.order} points outside ${state.songs.size} songs",
                state.order.all { it in state.songs.indices },
            )
            assertTrue("duplicate entries in ${state.order}", state.order.distinct().size == state.order.size)
            assertTrue(
                "orderPosition ${state.orderPosition} is outside ${state.order.size} entries",
                state.orderPosition in state.order.indices,
            )
        }
    }
}
