/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** The queue as the UI needs to see it: what is in it, and which entry is current. */
data class QueueState(
    val songs: List<SongItem> = emptyList(),
    val index: Int = -1,
) {
    val current: SongItem? get() = songs.getOrNull(index)
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index in 0 until songs.lastIndex
}

/**
 * Owns the queue and drives [DesktopPlayer] from it.
 *
 * The player deliberately knows nothing about queues - it plays one song and reports when that song
 * ends. Keeping the two apart means the rule for what plays next lives in one place, and the player
 * stays a thing that can be tested by asking it to play a song.
 *
 * Advancing is driven by the player's `onFinished`, which fires only when a track reaches its own
 * end. Stopping or replacing a track deliberately does not advance: those are the user leaving the
 * song, and moving the queue on for them would be the opposite of what they asked.
 */
class PlayerQueue(
    private val player: DesktopPlayer,
    private val scope: CoroutineScope,
    private val onPlayed: (SongItem) -> Unit = {},
) {
    val state = MutableStateFlow(QueueState())

    init {
        player.onFinished = { next() }
    }

    /**
     * Replaces the queue with [songs] and starts at [startIndex].
     *
     * The whole list is taken rather than just the chosen song, so that clicking a search result
     * queues everything after it - which is what makes a result list behave like an album rather
     * than a series of one-song sessions.
     */
    fun play(songs: List<SongItem>, startIndex: Int) {
        if (startIndex !in songs.indices) return
        state.value = QueueState(songs, startIndex)
        startCurrent()
    }

    fun next() {
        val queue = state.value
        if (!queue.hasNext) {
            // End of the queue: stop rather than wrap. Wrapping without being asked turns a finished
            // queue into an endless one.
            player.stop()
            return
        }
        state.value = queue.copy(index = queue.index + 1)
        startCurrent()
    }

    fun previous() {
        val queue = state.value
        if (!queue.hasPrevious) return
        state.value = queue.copy(index = queue.index - 1)
        startCurrent()
    }

    /** Jumps to a specific entry, for clicking a row in the queue. */
    fun jumpTo(index: Int) {
        val queue = state.value
        if (index !in queue.songs.indices) return
        state.value = queue.copy(index = index)
        startCurrent()
    }

    fun clear() {
        player.stop()
        state.value = QueueState()
    }

    private fun startCurrent() {
        val song = state.value.current ?: return
        onPlayed(song)
        player.play(scope, song.id, song.title)
    }
}
