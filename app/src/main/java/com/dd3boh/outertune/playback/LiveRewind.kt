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
 * Live plays [OFFSET_SEGMENTS] segments behind the newest, as every live player does: the next
 * segment is only made as the current one is heard, and reading at the very edge waited on each as
 * it was needed. That point - the live point - is the end of the bar, and it moves with the clock,
 * not with the segments fetched: playing live is 0 behind, and pausing for x seconds is x behind.
 *
 * The player itself cannot seek a live stream: to it the stream is one endless file with no index.
 * So a rewind is a restart at an earlier segment. The screen asks for one with [jumpTo], which
 * notes how far back and seeks the player to 0; the player then opens the stream afresh, and the
 * data source starts that many segments behind the live point. The player's position counts from
 * that first segment.
 */
object LiveRewind {
    /** How far back the bar reaches: an hour, or less if the stream keeps less. */
    const val WINDOW_MS = 60 * 60_000L

    /** Within this of the live point still reads as live: the moment between a seek and its sound. */
    const val EDGE_MS = 1_500L

    /** How many segments behind the newest live plays. */
    private const val OFFSET_SEGMENTS = 3

    private val _live = MutableStateFlow(false)

    /** Whether the stream open in the player is a live one. */
    val live: StateFlow<Boolean> = _live

    @Volatile private var startSeq = -1L
    @Volatile private var headSeq = -1L
    /** The live point's segment when the stream was opened. */
    @Volatile private var anchorSeq = -1L
    /** When the player's position read 0 - set from the first position seen after an open. */
    @Volatile private var anchorAt = -1L
    @Volatile private var segmentMs = 5_000L
    @Volatile private var rewindMs = WINDOW_MS
    @Volatile private var pendingBehindMs: Long? = null

    /** The bar's span: the hour, or what the stream keeps if that is less. */
    val windowMs: Long get() = minOf(WINDOW_MS, rewindMs)

    /**
     * How far behind the live point playback is, given the player's [positionMs].
     *
     * Measured against the clock from the moment playback began, which is learnt from the first
     * position seen after the stream opened: the player buffers a little before it makes a sound,
     * and counting from the open would have read that as being behind.
     */
    fun behindMs(positionMs: Long): Long {
        // Between a rewind and the stream reopening, the player's position is already back at 0
        // while the old start still stands, which read as some far-off distance behind: the bar
        // jumped away and back. Until the reopen, it is where it was sent.
        reopeningTo?.takeIf { System.currentTimeMillis() - reopeningAt < REOPEN_HOLD_MS }?.let { return it }
        if (startSeq < 0) return 0
        val now = System.currentTimeMillis()
        if (anchorAt < 0) {
            // Time spent paused before the first sound is time fallen behind, all the same.
            val paused = pausedBeforeStart + if (pausedSince >= 0) now - pausedSince else 0
            if (positionMs <= 0) return ((anchorSeq - startSeq) * segmentMs - startOffsetMs + paused).coerceIn(0, windowMs)
            anchorAt = now - positionMs - paused
        }
        // Counted from where playback began: startOffsetMs into the starting segment.
        val liveEnd = maxOf(
            (headSeq - OFFSET_SEGMENTS - startSeq) * segmentMs,
            (anchorSeq - startSeq) * segmentMs + (now - anchorAt),
        ) - startOffsetMs
        return (liveEnd - positionMs).coerceIn(0, windowMs)
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
        reopeningTo = target
        reopeningAt = System.currentTimeMillis()
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

    /**
     * Where to start, given the newest segment: the live point, or as far behind it as asked - the
     * segment that moment is in, and how far into that segment it is.
     */
    internal fun startingPoint(head: Long): Pair<Long, Long> {
        val behind = pendingBehindMs ?: 0
        pendingBehindMs = null
        val segmentsBack = (behind + segmentMs - 1) / segmentMs
        val seq = head - OFFSET_SEGMENTS - segmentsBack
        if (seq < 0) return 0L to 0L
        return seq to segmentsBack * segmentMs - behind
    }

    /**
     * Playback starts at segment [seq], [offsetMs] into it - the player's position 0 is that moment -
     * with [head] the newest segment when it was opened.
     */
    internal fun started(seq: Long, head: Long, offsetMs: Long) {
        startSeq = seq
        startOffsetMs = offsetMs
        anchorSeq = (head - OFFSET_SEGMENTS).coerceAtLeast(seq)
        anchorAt = -1
        reopeningTo = null
        pausedBeforeStart = 0
        pausedSince = if (wantsPlay) -1 else System.currentTimeMillis()
    }

    @Volatile private var startOffsetMs = 0L

    @Volatile private var wantsPlay = true
    /** Paused since when, before playback's first sound - see [behindMs]. -1 when not paused. */
    @Volatile private var pausedSince = -1L
    @Volatile private var pausedBeforeStart = 0L

    /** The player's play/pause, from MusicService: needed only until playback has started. */
    fun playWhenReadyChanged(play: Boolean) {
        wantsPlay = play
        if (anchorAt >= 0) return
        val now = System.currentTimeMillis()
        if (!play) {
            if (pausedSince < 0) pausedSince = now
        } else if (pausedSince >= 0) {
            pausedBeforeStart += now - pausedSince
            pausedSince = -1
        }
    }

    /** Where a rewind sent playback, until the stream has reopened there - see [behindMs]. */
    @Volatile private var reopeningTo: Long? = null
    @Volatile private var reopeningAt = 0L

    /** Held no longer than this, in case a reopen never comes: the bar must not freeze. */
    private const val REOPEN_HOLD_MS = 15_000L

    /** How long one segment is, for cutting into it - see LiveStreamDataSource. */
    internal val segmentLengthMs: Long get() = segmentMs

    internal fun sawHead(seq: Long) {
        if (seq > headSeq) headSeq = seq
    }

    /** A stream that is not live was opened: nothing here applies to it. */
    internal fun notLive() {
        _live.value = false
        startSeq = -1
        headSeq = -1
        anchorAt = -1
        reopeningTo = null
        pendingBehindMs = null
    }
    // endregion

    /**
     * The live bar's stretched scale, as the desktop draws it: now at the right end, and the
     * distance back growing as the square of the distance along. Near now a small drag moves a few
     * seconds; the far left still reaches the whole window - halfway is a quarter of it. Full
     * whenever the clock reads LIVE, so the two never disagree.
     */
    fun barFraction(behindMs: Long): Float {
        val window = windowMs
        if (window <= 0 || behindMs < EDGE_MS) return 1f
        return (1 - sqrt(behindMs.coerceIn(0, window).toDouble() / window)).toFloat()
    }

    /** How far behind a place on the live bar stands for - the inverse of [barFraction]. */
    fun behindAt(fraction: Float): Long {
        // The last sliver of the bar is live itself, so letting go at the end is "go live".
        if (fraction >= 0.995f) return 0
        val back = 1.0 - fraction.coerceIn(0f, 1f)
        return (windowMs * back * back).toLong()
    }
}
