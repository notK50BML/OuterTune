/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * Stops playback after a while, or at the end of the current song.
 *
 * Two modes rather than one, because they answer different questions. A duration is "I am going to
 * sleep" - the music should stop at roughly that time regardless of what is playing. End-of-song is
 * "let this one finish" - the point is the boundary, and a five-minute timer that cuts off ninety
 * seconds from the end of a track is exactly the thing it is meant to avoid.
 *
 * The clock is a parameter so the behaviour can be tested without waiting in real time. Wall clock
 * rather than a monotonic one, deliberately: a sleep timer is set against the time on the wall, and
 * if the machine suspends for an hour the right thing is to be expired on waking rather than to have
 * another forty minutes to run.
 */
class SleepTimer(private val nowMs: () -> Long = System::currentTimeMillis) {

    /** When to stop, or null if no duration is set. */
    var deadlineMs: Long? = null
        private set

    /** Whether to stop when the current song finishes. */
    var pauseWhenSongEnds: Boolean = false
        private set

    val isActive: Boolean get() = deadlineMs != null || pauseWhenSongEnds

    /** Time left, or zero when there is no deadline or it has passed. */
    fun remainingMs(): Long = deadlineMs?.let { (it - nowMs()).coerceAtLeast(0L) } ?: 0L

    /**
     * Stops in [minutes] from now.
     *
     * Replaces an end-of-song request rather than adding to it. The two are alternatives, and a
     * timer that was both would have to pick one to honour - better that setting one plainly
     * cancels the other than that the user has to guess which won.
     */
    fun start(minutes: Int) {
        deadlineMs = nowMs() + minutes.coerceAtLeast(1) * 60_000L
        pauseWhenSongEnds = false
    }

    /** Stops when the current song ends. */
    fun endOfSong() {
        pauseWhenSongEnds = true
        deadlineMs = null
    }

    fun cancel() {
        deadlineMs = null
        pauseWhenSongEnds = false
    }

    /**
     * Whether playback should stop right now, clearing the timer if so.
     *
     * Clearing here rather than leaving it to the caller is what keeps this from firing twice. It is
     * polled once a second, and a timer that stayed expired would pause playback again the moment
     * anyone pressed play.
     */
    fun dueNow(): Boolean {
        val deadline = deadlineMs ?: return false
        if (nowMs() < deadline) return false
        cancel()
        return true
    }

    /**
     * Whether the song that just finished should be the last one.
     *
     * Asked at the track boundary rather than checked on a timer, because "let this one finish" is
     * about the boundary itself - there is no duration to count down.
     */
    fun songEnded(): Boolean {
        if (!pauseWhenSongEnds) return false
        cancel()
        return true
    }

    companion object {
        /** The choices offered. Short enough to be useful, long enough to actually fall asleep in. */
        val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60, 90)

        /** "42:07", or "1:02:07" once there is an hour to show. */
        fun format(remainingMs: Long): String {
            val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
