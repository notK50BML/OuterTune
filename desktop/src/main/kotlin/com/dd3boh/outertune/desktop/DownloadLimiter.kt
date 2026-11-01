/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How many songs download at once: [max], and fewer while YouTube is pushing back.
 *
 * Most of a download's time is not the audio - that comes in one ranged request - but working out
 * where it is: NewPipe loading the watch page and running the player's script to unlock the link.
 * One song after another left the connection idle through all of that. Three at a time overlaps one
 * song's lookup with another's transfer.
 *
 * More at once is also more likely to be throttled, so the limit adapts. A failure that reads as
 * YouTube refusing (429, 403, "confirm you're not a bot") takes one slot away and pauses everything
 * briefly, and that song is tried again after the pause. Each clean download counts toward getting
 * a slot back. It never goes below one, so a stubborn throttle slows downloading rather than
 * stopping it.
 *
 * Shared by playlist downloads and single songs, so together they never exceed the limit.
 */
/** Songs downloading at once when YouTube is not throttling. */
const val MAX_PARALLEL_DOWNLOADS = 3

class DownloadLimiter(
    private val max: Int = MAX_PARALLEL_DOWNLOADS,
    /** Clean downloads in a row before a slot taken away is given back. */
    private val recoverAfter: Int = 8,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var running = 0
    private var cleanStreak = 0
    private var strikes = 0
    private var pausedUntil = 0L

    private val _limit = MutableStateFlow(max)
    /** How many may run at once right now. Below [max] means YouTube has been throttling. */
    val limit: StateFlow<Int> = _limit.asStateFlow()

    /**
     * Runs [download], waiting for a slot first, and tries it again (up to [attempts] times in all)
     * when it fails in a way that reads as being rate limited.
     */
    suspend fun run(attempts: Int = 3, download: suspend () -> Result<Unit>): Result<Unit> {
        var last: Result<Unit> = Result.failure(IllegalStateException("not attempted"))
        repeat(attempts) {
            acquire()
            last = try {
                download()
            } finally {
                lock.withLock { running-- }
            }
            val limited = last.exceptionOrNull()?.let { looksRateLimited(it.message) } == true
            record(success = last.isSuccess, rateLimited = limited)
            if (!limited) return last
        }
        return last
    }

    private suspend fun acquire() {
        while (true) {
            val wait = lock.withLock {
                val now = clock()
                when {
                    now < pausedUntil -> pausedUntil - now
                    running < _limit.value -> { running++; return }
                    else -> POLL_MS
                }
            }
            delay(wait.coerceIn(10, POLL_MS))
        }
    }

    /** The bookkeeping after each attempt. Separate from [run] so it can be tested without a network. */
    internal suspend fun record(success: Boolean, rateLimited: Boolean) = lock.withLock {
        when {
            rateLimited -> {
                strikes++
                cleanStreak = 0
                _limit.value = (_limit.value - 1).coerceAtLeast(1)
                // Longer each time it happens in a row: 15s, 30s, then a minute at most.
                pausedUntil = clock() + (PAUSE_MS * strikes).coerceAtMost(MAX_PAUSE_MS)
            }
            success -> {
                strikes = 0
                cleanStreak++
                if (cleanStreak >= recoverAfter && _limit.value < max) {
                    _limit.value++
                    cleanStreak = 0
                }
            }
            // An ordinary failure - a removed video, no AAC stream - says nothing about throttling.
            else -> Unit
        }
    }

    companion object {
        private const val POLL_MS = 250L
        private const val PAUSE_MS = 15_000L
        private const val MAX_PAUSE_MS = 60_000L

        /**
         * Whether a download's failure is YouTube refusing rather than this song being unavailable:
         * the status codes a throttle answers with, and the bot check NewPipe reports.
         */
        fun looksRateLimited(message: String?): Boolean {
            val text = message?.lowercase() ?: return false
            return listOf("429", "http 403", "too many requests", "rate limit", "rate-limit", "not a bot", "sign in to confirm")
                .any { it in text }
        }
    }
}
