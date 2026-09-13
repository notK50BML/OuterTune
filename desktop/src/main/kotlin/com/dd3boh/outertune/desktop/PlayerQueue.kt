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
    /**
     * What this queue is, for the collapsed queue handle - "Liked songs", an album, a playlist.
     *
     * Where the songs came from is the one thing the handle can usefully say in a single line, and
     * it is what the Android player shows there. Naming the source is more use than naming the next
     * song, which is already about to be visible anyway.
     */
    val title: String = "Queue",
) {
    val current: SongItem? get() = songs.getOrNull(index)

    /** Repeat makes both directions always available, since either end wraps. */
    val hasPrevious: Boolean get() = repeat != RepeatMode.OFF || orderPosition > 0
    val hasNext: Boolean get() = repeat != RepeatMode.OFF || orderPosition in 0 until order.lastIndex
}

/**
 * The arithmetic behind reordering and removing, with no player attached.
 *
 * Separated out for the same reason the artist credit offsets were: it is the kind of logic that
 * fails silently. Get it wrong and the queue still renders, still plays, and simply moves the wrong
 * entry - or quietly changes which song is current while one is playing, which is the worst outcome
 * because the music jumps for no reason the user can connect to what they did.
 *
 * Everything here works in *play-order positions* - what the queue list actually shows - rather than
 * in indices into [QueueState.songs]. Those two are the same only until shuffle is turned on, and a
 * function that takes one and means the other is a bug waiting for the first shuffled queue.
 */
internal object QueueEdit {

    /**
     * Moves the entry at play-order position [from] to [to].
     *
     * The current song stays current. Its position in the order shifts whenever the move steps over
     * it, so it is found again by its song index rather than assumed to be where it was - dragging
     * an entry from below the current one to above it moves everything in between by one, including
     * the thing being listened to.
     */
    fun move(state: QueueState, from: Int, to: Int): QueueState {
        if (from !in state.order.indices || to !in state.order.indices || from == to) return state
        val currentSong = state.order.getOrNull(state.orderPosition)
        val order = state.order.toMutableList()
        order.add(to, order.removeAt(from))
        val position = currentSong?.let(order::indexOf) ?: -1
        return state.copy(
            order = order,
            orderPosition = if (position >= 0) position else state.orderPosition,
        )
    }

    /**
     * Removes the entry at play-order position [position].
     *
     * Two things have to move together. The song leaves [QueueState.songs], so every order entry
     * pointing past it has to come down by one; and the order loses a slot, so a current position
     * after the removal has to come down by one as well. Doing either without the other leaves the
     * queue pointing at the wrong song.
     *
     * Removing the entry that is playing leaves [QueueState.orderPosition] where it was, so the song
     * that moved up into that slot becomes current - which is what "remove this one" means when the
     * thing removed is the thing playing. The caller is responsible for noticing that the current
     * song changed and starting it.
     */
    fun removeAt(state: QueueState, position: Int): QueueState {
        if (position !in state.order.indices) return state
        val songIndex = state.order[position]

        val songs = state.songs.toMutableList().apply { removeAt(songIndex) }
        if (songs.isEmpty()) {
            return QueueState(shuffled = state.shuffled, repeat = state.repeat, title = state.title)
        }

        val order = state.order
            .filterIndexed { index, _ -> index != position }
            .map { if (it > songIndex) it - 1 else it }

        val orderPosition = when {
            position < state.orderPosition -> state.orderPosition - 1
            else -> state.orderPosition
        }.coerceIn(0, order.lastIndex)

        return state.copy(
            songs = songs,
            order = order,
            orderPosition = orderPosition,
            // Derived rather than carried over: the song this position points at may have a
            // different index now that one was taken out from under it.
            index = order[orderPosition],
        )
    }
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
    fun play(songs: List<SongItem>, startIndex: Int, title: String = "Queue") {
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
            title = title,
        )
        startCurrent()
    }

    /**
     * Puts [song] straight after whatever is playing.
     *
     * Inserted into the play order rather than appended to it, so with shuffle on it is still the
     * next thing heard - appending would drop it at a random point, which is not what "add to queue"
     * means to anyone who just chose a song.
     *
     * Every later index shifts by one, so the order list has to be rewritten rather than just having
     * an entry pushed into it.
     */
    fun playNext(song: SongItem) {
        val previous = state.value
        if (previous.songs.isEmpty()) {
            play(listOf(song), 0, previous.title)
            return
        }
        val insertAt = previous.index + 1
        val songs = previous.songs.toMutableList().apply { add(insertAt, song) }
        val order = previous.order.map { if (it >= insertAt) it + 1 else it }.toMutableList()
        order.add(previous.orderPosition + 1, insertAt)
        state.value = previous.copy(songs = songs, order = order)
    }

    /** Drag-to-reorder, in the play order the queue list shows. */
    fun move(from: Int, to: Int) {
        state.value = QueueEdit.move(state.value, from, to)
    }

    /**
     * Takes one entry out of the queue.
     *
     * Restarts playback only when the song that was playing is the one removed. Comparing the
     * current song before and after is what decides that, rather than comparing positions - a
     * removal above the current entry changes its position without changing what is playing, and
     * restarting there would interrupt a song for a change that did not touch it.
     */
    fun removeAt(position: Int) {
        val before = state.value.current
        val after = QueueEdit.removeAt(state.value, position)
        state.value = after
        if (after.songs.isEmpty()) {
            player.stop()
            return
        }
        if (after.current?.id != before?.id) startCurrent()
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
