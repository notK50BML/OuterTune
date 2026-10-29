/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing a queue keeps the current song where it is, shuffled or not. */
class QueueBoardEditsTest {
    private fun song(id: String) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = 0, genre = null)

    /** A queue of [ids] in this order, playing [current]. */
    private fun plain(vararg ids: String, current: String): MultiQueueObject {
        val songs = ids.mapIndexed { i, id -> song(id).also { it.shuffleIndex = i } }.toMutableList()
        return MultiQueueObject(1, "q", songs, false, ids.indexOf(current), index = 0)
    }

    /** A shuffled queue stored as [ids], played in [order], playing [current]. */
    private fun shuffled(ids: List<String>, order: List<String>, current: String): MultiQueueObject {
        val songs = ids.map { id -> song(id).also { it.shuffleIndex = order.indexOf(id) } }.toMutableList()
        return MultiQueueObject(1, "q", songs, true, ids.indexOf(current), index = 0)
    }

    private fun MultiQueueObject.playOrder() = getCurrentQueueShuffled().map { it.id }
    private fun MultiQueueObject.current() = queue[queuePos].id

    @Test
    fun `inserting before the current song in a shuffled queue keeps it current`() {
        // played as c, a, d, b - playing d, at shuffled position 2
        val q = shuffled(listOf("a", "b", "c", "d"), listOf("c", "a", "d", "b"), current = "d")
        QueueBoard.insertSongs(q, 1, listOf(song("x"), song("y")))
        assertEquals(listOf("c", "x", "y", "a", "d", "b"), q.playOrder())
        assertEquals("d", q.current())
        assertEquals(4, q.getQueuePosShuffled())
    }

    @Test
    fun `inserting after the current song in a shuffled queue keeps it current`() {
        val q = shuffled(listOf("a", "b", "c"), listOf("b", "c", "a"), current = "b")
        QueueBoard.insertSongs(q, 1, listOf(song("x")))
        assertEquals(listOf("b", "x", "c", "a"), q.playOrder())
        assertEquals("b", q.current())
    }

    @Test
    fun `undo of a played song puts it back before the current one`() {
        // the swipe removed "a" while "c" played; Undo puts it back at 0
        val q = shuffled(listOf("b", "c", "d"), listOf("b", "c", "d"), current = "c")
        QueueBoard.insertSongs(q, 0, listOf(song("a")))
        assertEquals(listOf("a", "b", "c", "d"), q.playOrder())
        assertEquals("c", q.current())
    }

    @Test
    fun `inserting into a plain queue shifts the current song only when before it`() {
        val q = plain("a", "b", "c", current = "b")
        QueueBoard.insertSongs(q, 1, listOf(song("x")))
        assertEquals(listOf("a", "x", "b", "c"), q.playOrder())
        assertEquals("b", q.current())

        QueueBoard.insertSongs(q, Int.MAX_VALUE, listOf(song("y")))
        assertEquals(listOf("a", "x", "b", "c", "y"), q.playOrder())
        assertEquals("b", q.current())
    }

    @Test
    fun `removing a song before the current one in a shuffled queue keeps it current`() {
        val q = shuffled(listOf("a", "b", "c", "d"), listOf("d", "b", "a", "c"), current = "a")
        assertTrue(QueueBoard.removeSongAt(q, 0))
        assertEquals(listOf("b", "a", "c"), q.playOrder())
        assertEquals("a", q.current())
        assertEquals(1, q.getQueuePosShuffled())
    }

    @Test
    fun `removing the current song moves on to the next, not the one after`() {
        val q = shuffled(listOf("a", "b", "c", "d"), listOf("d", "b", "a", "c"), current = "b")
        assertTrue(QueueBoard.removeSongAt(q, 1))
        assertEquals(listOf("d", "a", "c"), q.playOrder())
        assertEquals("a", q.current())

        val p = plain("a", "b", "c", "d", current = "b")
        assertTrue(QueueBoard.removeSongAt(p, 1))
        assertEquals("c", p.current())
    }

    @Test
    fun `removing the current last song falls back to the one before it`() {
        val q = plain("a", "b", "c", current = "c")
        assertTrue(QueueBoard.removeSongAt(q, 2))
        assertEquals("b", q.current())
    }

    @Test
    fun `removing out of range does nothing`() {
        val q = plain("a", "b", current = "a")
        assertFalse(QueueBoard.removeSongAt(q, 5))
        assertEquals(listOf("a", "b"), q.playOrder())
    }

    @Test
    fun `moving songs in a shuffled queue keeps the current song current`() {
        val q = shuffled(listOf("a", "b", "c", "d"), listOf("d", "b", "a", "c"), current = "a")
        // the current song itself
        QueueBoard.moveSongIn(q, 2, 0)
        assertEquals(listOf("a", "d", "b", "c"), q.playOrder())
        assertEquals("a", q.current())
        // a song across it
        QueueBoard.moveSongIn(q, 3, 0)
        assertEquals(listOf("c", "a", "d", "b"), q.playOrder())
        assertEquals("a", q.current())
        assertEquals(1, q.getQueuePosShuffled())
    }

    @Test
    fun `moving songs in a plain queue keeps the current song current`() {
        val q = plain("a", "b", "c", "d", current = "c")
        QueueBoard.moveSongIn(q, 0, 3)
        assertEquals(listOf("b", "c", "d", "a"), q.playOrder())
        assertEquals("c", q.current())
        assertEquals(1, q.queuePos)
    }

    @Test
    fun `a radio page adds only songs the queue does not have, each once`() {
        val existing = listOf(song("a"), song("b"))
        val page = listOf(song("b"), song("c"), song("c"), song("d"))
        assertEquals(listOf("c", "d"), QueueBoard.newSongsFor(existing, page).map { it.id })
        assertTrue(QueueBoard.newSongsFor(existing, emptyList()).isEmpty())
    }
}
