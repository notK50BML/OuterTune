/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

/**
 * Turns ExoPlayer's coarse position into a smooth one.
 *
 * `player.currentPosition` only advances every few hundred milliseconds - stated in this codebase at
 * `ui/component/Lyrics.kt:249` and worked around there the same way. Read directly it is a staircase,
 * and a staircase on both ends of a sync link is the largest error in the whole feature: the host
 * reports a position up to 250ms stale, the follower compares against its own equally stale reading,
 * and the two errors do not cancel. That is enough on its own to trip a correction that was not
 * needed.
 *
 * So the last position the player actually reported is latched together with the moment it was
 * reported, and time since is added on. The player's own ticks correct the estimate every few hundred
 * milliseconds, so it cannot drift further than one tick, and a seek re-latches immediately.
 *
 * Used identically by host and follower. That symmetry is the point - measuring the two ends
 * differently would introduce a bias no amount of clock synchronisation could remove.
 *
 * Takes the clock as a parameter rather than reading it, so the behaviour can be tested without
 * waiting in real time.
 */
class PositionAnchor(private val nowUs: () -> Long) {

    private var latchedPositionMs = 0L
    private var latchedAtUs = 0L
    private var started = false

    /**
     * Feeds in a reading from the player.
     *
     * Only re-latches when the value actually changed. Called at 250ms against a player that moves
     * every ~300ms, most calls repeat the previous value, and treating a repeat as fresh would peg
     * the estimate to whenever it was last asked rather than when the player last moved - which is
     * precisely the staircase this exists to remove.
     */
    fun update(positionMs: Long) {
        if (!started || positionMs != latchedPositionMs) {
            latchedPositionMs = positionMs
            latchedAtUs = nowUs()
            started = true
        }
    }

    /** Re-latches unconditionally, for a seek - where the position may legitimately repeat. */
    fun reset(positionMs: Long) {
        latchedPositionMs = positionMs
        latchedAtUs = nowUs()
        started = true
    }

    /**
     * The position now, interpolated from the last latch.
     *
     * @param playing when false the position is frozen: a paused player does not advance, and
     *   letting the clock run would make a paused follower appear to fall behind and be "corrected".
     * @param speed playback rate, so the estimate stays right while drift is being corrected by
     *   nudging the rate rather than by seeking.
     */
    fun positionMs(playing: Boolean, speed: Float = 1f): Long {
        if (!started) return 0L
        if (!playing) return latchedPositionMs
        val elapsedUs = nowUs() - latchedAtUs
        // Rounded, not truncated. A Float rate is not the decimal it looks like - 1.02f is really
        // 1.0199999809, so a second of playback multiplied by it comes to 1019.99998ms and
        // truncating throws away a millisecond. That is small once and a second of accumulated
        // error every quarter of an hour, in the very state where the position is being watched
        // most closely.
        return latchedPositionMs + Math.round(elapsedUs / 1000.0 * speed)
    }

    /** The raw latch, for a host that must send the pair rather than an interpolated value. */
    fun latched(): Pair<Long, Long> = latchedPositionMs to latchedAtUs
}
