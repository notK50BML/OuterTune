/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** [DownloadLimiter]: three at once, one fewer per throttle, and back up after clean downloads. */
class DownloadLimiterTest {

    @Test
    fun `reads YouTube's refusals as throttling, and nothing else`() {
        assertTrue(DownloadLimiter.looksRateLimited("stream fetch returned HTTP 429"))
        assertTrue(DownloadLimiter.looksRateLimited("stream fetch returned HTTP 403"))
        assertTrue(DownloadLimiter.looksRateLimited("NewPipe: extraction failed - Sign in to confirm you're not a bot"))
        assertFalse(DownloadLimiter.looksRateLimited("A live stream has no end to download."))
        assertFalse(DownloadLimiter.looksRateLimited("no AAC stream; offered itags [251]"))
        assertFalse(DownloadLimiter.looksRateLimited(null))
    }

    @Test
    fun `a throttle takes a slot, never below one, and clean downloads give it back`() = runBlocking {
        val limiter = DownloadLimiter(max = 3, recoverAfter = 2)
        assertEquals(3, limiter.limit.value)
        repeat(5) { limiter.record(success = false, rateLimited = true) }
        assertEquals(1, limiter.limit.value)
        // An ordinary failure says nothing about throttling.
        limiter.record(success = false, rateLimited = false)
        assertEquals(1, limiter.limit.value)
        repeat(2) { limiter.record(success = true, rateLimited = false) }
        assertEquals(2, limiter.limit.value)
        repeat(10) { limiter.record(success = true, rateLimited = false) }
        assertEquals(3, limiter.limit.value)
    }

    @Test
    fun `never runs more than the limit at once`() = runBlocking {
        val limiter = DownloadLimiter(max = 3)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        (1..9).map {
            async {
                limiter.run {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    delay(30)
                    running.decrementAndGet()
                    Result.success(Unit)
                }
            }
        }.awaitAll()
        assertEquals(3, peak.get())
    }

    @Test
    fun `a throttled song is tried again after the pause`() = runBlocking {
        // Five seconds pass every time the limiter looks at the clock, so its 15-second pause is
        // over after a few checks rather than in real time - a clock that stood still never ended it.
        var now = 0L
        val limiter = DownloadLimiter(max = 3, clock = { now.also { now += 5_000L } })
        var calls = 0
        val result = limiter.run {
            calls++
            if (calls == 1) Result.failure(IllegalStateException("stream fetch returned HTTP 429")) else Result.success(Unit)
        }
        assertTrue(result.isSuccess)
        assertEquals(2, calls)
        assertEquals(2, limiter.limit.value)
    }
}
