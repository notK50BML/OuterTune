/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedQueuesTest {

    private fun song(i: Int) = SongItem(id = "s$i", title = "Song $i", artists = emptyList(), thumbnail = "", duration = 200)
    private fun queue(title: String, n: Int) =
        QueueState(songs = (0 until n).map(::song), index = 1, order = (0 until n).toList(), orderPosition = 1, title = title)

    @Test
    fun savedQueuesRoundTrip() {
        val saved = listOf(SavedQueue(queue("Mix", 3), 42_000L), SavedQueue(queue("Album", 5), 0L))
        val back = QueueSnapshot.decodeAll(QueueSnapshot.encodeAll(saved))
        assertEquals(listOf("Mix", "Album"), back.map { it.state.title })
        assertEquals(42_000L, back[0].positionMs)
        assertEquals(5, back[1].state.songs.size)
        assertEquals(1, back[0].state.index)
    }

    @Test
    fun nothingStoredIsNothingSaved() {
        assertEquals(emptyList<SavedQueue>(), QueueSnapshot.decodeAll(""))
        assertEquals(emptyList<SavedQueue>(), QueueSnapshot.decodeAll("not json"))
    }
}
