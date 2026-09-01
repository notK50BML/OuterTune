/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

enum class RepeatMode { OFF, ALL, ONE }

/** The queue as the UI needs to see it: what is in it, and which entry is current. */
data class QueueState(
    val songs: List<SongItem> = emptyList(),
    val index: Int = -1,
    val shuffled: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /**
     * Where each song sits in the current play order, so the queue can be *shown* in play order
     * while [songs] stays in the order it was added. Without this, turning on shuffle would
     * rearrange the list under the user, which loses the album they queued.
     */
    val order: List<Int> = emptyList(),
    val orderPosition: Int = -1,
) {
    val current: SongItem? get() = songs.getOrNull(index)

    /** Repeat makes both directions always available, since either end wraps. */
    val hasPrevious: Boolean get() = repeat != RepeatMode.OFF || orderPosition > 0
    val hasNext: Boolean get() = repeat != RepeatMode.OFF || orderPosition in 0 until order.lastIndex
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
        val previous = state.value
        val order = buildOrder(songs.indices.toList(), previous.shuffled, startIndex)
        state.value = QueueState(
            songs = songs,
            index = startIndex,
            shuffled = previous.shuffled,
            repeat = previous.repeat,
            order = order,
            orderPosition = order.indexOf(startIndex),
        )
        startCurrent()
    }

    fun next() {
        val queue = state.value
        if (queue.repeat == RepeatMode.ONE) {
            // Repeat-one restarts the same track rather than moving, which is the whole point of it.
            startCurrent()
            return
        }
        if (queue.orderPosition >= queue.order.lastIndex) {
            if (queue.repeat == RepeatMode.ALL && queue.order.isNotEmpty()) {
                moveTo(0)
            } else {
                // End of the queue: stop rather than wrap. Wrapping unasked turns a finished queue
                // into an endless one - which is what repeat is for, and it is off.
                player.stop()
            }
            return
        }
        moveTo(queue.orderPosition + 1)
    }

    fun previous() {
        val queue = state.value
        if (queue.orderPosition <= 0) {
            if (queue.repeat != RepeatMode.OFF && queue.order.isNotEmpty()) moveTo(queue.order.lastIndex)
            return
        }
        moveTo(queue.orderPosition - 1)
    }

    /** Jumps to a specific entry, for clicking a row in the queue. */
    fun jumpTo(index: Int) {
        val queue = state.value
        if (index !in queue.songs.indices) return
        val position = queue.order.indexOf(index)
        if (position < 0) return
        moveTo(position)
    }

    /**
     * Turns shuffle on or off without interrupting what is playing.
     *
     * The current track stays current and becomes the head of the new order, so toggling shuffle
     * mid-song changes what comes next rather than jumping somewhere else immediately - which is
     * what the control is actually for.
     */
    fun toggleShuffle() {
        val queue = state.value
        val shuffled = !queue.shuffled
        val order = buildOrder(queue.songs.indices.toList(), shuffled, queue.index)
        state.value = queue.copy(
            shuffled = shuffled,
            order = order,
            orderPosition = order.indexOf(queue.index).coerceAtLeast(0),
        )
    }

    fun cycleRepeat() {
        state.value = state.value.copy(
            repeat = when (state.value.repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )
    }

    private fun moveTo(orderPosition: Int) {
        val queue = state.value
        val index = queue.order.getOrNull(orderPosition) ?: return
        state.value = queue.copy(index = index, orderPosition = orderPosition)
        startCurrent()
    }

    /**
     * The order tracks are played in: as added, or shuffled with [current] pinned to the front.
     *
     * Pinning matters because shuffling the whole list would move the playing track somewhere into
     * the middle of its own queue, so "previous" would then go somewhere it had never been.
     */
    private fun buildOrder(indices: List<Int>, shuffled: Boolean, current: Int): List<Int> =
        if (!shuffled) indices
        else listOf(current) + (indices - current).shuffled()

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
