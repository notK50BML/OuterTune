/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Rewinding a live stream, as on YouTube - shared by [LiveStreamDataSource], which reads the stream,
 * and the player screens, which draw it.
 *
 * The player itself cannot seek a live stream: to it the stream is one endless file with no index.
 * So a rewind is a restart at an earlier segment. The screen asks for one with [jumpTo], which
 * notes how far back and seeks the player to 0; the player then opens the stream afresh, and the
 * data source starts that many segments behind the newest. The player's position counts from that
 * first segment, so how far behind live playback is follows from the segment it started at, the
 * newest the stream has since reported, and the position - see [behindMs].
 *
 * Pausing simply stops reading, so playing again carries on from where it was, that far behind.
 */
object LiveRewind {
    /** How far back the bar reaches: an hour, or less if the stream keeps less. */
    const val WINDOW_MS = 60 * 60_000L

    /** Within this of the newest segment still reads as live: a segment is a few seconds long. */
    const val EDGE_MS = 15_000L

    private val _live = MutableStateFlow(false)

    /** Whether the stream open in the player is a live one. */
    val live: StateFlow<Boolean> = _live

    @Volatile private var startSeq = -1L
    @Volatile private var headSeq = -1L
    @Volatile private var segmentMs = 5_000L
    @Volatile private var rewindMs = WINDOW_MS
    @Volatile private var pendingBehindMs: Long? = null

    /** The bar's span: the hour, or what the stream keeps if that is less. */
    val windowMs: Long get() = minOf(WINDOW_MS, rewindMs)

    /** How far behind live playback is, given the player's [positionMs]. */
    fun behindMs(positionMs: Long): Long {
        if (startSeq < 0 || headSeq < 0) return 0
        return ((headSeq - startSeq + 1) * segmentMs - positionMs).coerceIn(0, windowMs)
    }

    /**
     * Whether [player] is playing a live stream now: one was opened, and the player has no length
     * for what it is playing. The second half keeps a song played from the cache - which never
     * reaches the data source, so never says it is not live - from being drawn as one.
     */
    fun isLive(player: Player): Boolean = live.value && player.duration == C.TIME_UNSET

    /** How far behind live [player] is now. */
    fun behindMs(player: Player): Long = behindMs(player.currentPosition)

    /**
     * Restarts playback [behindMs] before now - or at now, for anything within [EDGE_MS] - by
     * reopening the stream there. Nothing happens for a move from now to now.
     */
    fun jumpTo(player: Player, behindMs: Long) {
        val target = if (behindMs < EDGE_MS) 0 else behindMs.coerceAtMost(windowMs)
        if (target == 0L && behindMs(player) < EDGE_MS) return
        pendingBehindMs = target
        // To the player an unseekable stream has only its start to seek to, and seeking there
        // reopens it - at the segment just asked for.
        player.seekTo(0)
    }

    // region Called by LiveStreamDataSource
    internal fun opened(segmentMs: Long, rewindMs: Long?) {
        this.segmentMs = segmentMs.coerceIn(1_000L, 10_000L)
        this.rewindMs = rewindMs?.takeIf { it > 0 } ?: WINDOW_MS
        _live.value = true
    }

    /** The segment to start at, given the newest: [head] itself unless a rewind was asked for. */
    internal fun startingSegment(head: Long): Long {
        val behind = pendingBehindMs ?: 0
        pendingBehindMs = null
        return (head - behind / segmentMs).coerceAtLeast(0)
    }

    internal fun started(seq: Long) {
        startSeq = seq
    }

    internal fun sawHead(seq: Long) {
        if (seq > headSeq) headSeq = seq
    }

    /** A stream that is not live was opened: nothing here applies to it. */
    internal fun notLive() {
        _live.value = false
        startSeq = -1
        headSeq = -1
    }
    // endregion

    /**
     * The live bar's stretched scale, as the desktop draws it: now at the right end, and the
     * distance back growing as the square of the distance along. Near now a small drag moves a few
     * seconds; the far left still reaches the whole window - halfway is a quarter of it.
     */
    fun barFraction(behindMs: Long): Float {
        val window = windowMs
        if (window <= 0) return 1f
        return (1 - sqrt(behindMs.coerceIn(0, window).toDouble() / window)).toFloat()
    }

    /** How far behind a place on the live bar stands for - the inverse of [barFraction]. */
    fun behindAt(fraction: Float): Long {
        val back = 1.0 - fraction.coerceIn(0f, 1f)
        return (windowMs * back * back).toLong()
    }
}
