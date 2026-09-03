/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The offset estimator, driven with simulated exchanges.
 *
 * Simulating is the only way to test this honestly: the property that matters is how close the
 * estimate lands to a *known* true offset under conditions of a known shape, and that is not
 * observable when measuring two real clocks, where the true answer is exactly what is unknown.
 */
class ClockSyncTest {

    /**
     * One exchange against a host whose clock is [trueOffsetUs] ahead.
     *
     * [outboundUs] and [inboundUs] are separate so path asymmetry can be simulated, which is the
     * error the algorithm cannot correct and the one worth measuring.
     */
    private fun ClockSync.exchange(
        followerNowUs: Long,
        trueOffsetUs: Long,
        outboundUs: Long,
        inboundUs: Long,
        hostProcessingUs: Long = 100,
    ): Boolean {
        val t1 = followerNowUs
        val t2 = t1 + outboundUs + trueOffsetUs
        val t3 = t2 + hostProcessingUs
        val t4 = t3 - trueOffsetUs + inboundUs
        return offer(t1, t2, t3, t4)
    }

    @Test
    fun `a symmetric path recovers the offset exactly`() {
        val sync = ClockSync()
        val truth = 5_000_000L
        var now = 0L
        repeat(8) {
            sync.exchange(now, truth, outboundUs = 4_000, inboundUs = 4_000)
            now += 200_000
        }
        // With no asymmetry there is no error to recover from - anything but an exact answer here
        // would mean the formula itself is wrong.
        assertEquals(truth, sync.offsetUs!!)
    }

    @Test
    fun `an asymmetric path errs by half the asymmetry, and no more`() {
        val sync = ClockSync()
        val truth = 1_000_000L
        var now = 0L
        // 20ms out, 4ms back: a 16ms asymmetry, which NTP's formula splits evenly and so
        // mis-attributes by 8ms. That is the theoretical floor, and the test asserts it is reached
        // rather than exceeded.
        repeat(12) {
            sync.exchange(now, truth, outboundUs = 20_000, inboundUs = 4_000)
            now += 200_000
        }
        val error = abs(sync.offsetUs!! - truth)
        assertTrue("error was ${error}us", error <= 8_100)
    }

    @Test
    fun `the lowest-delay sample decides, so one clean exchange beats many slow ones`() {
        val sync = ClockSync()
        val truth = 2_000_000L
        var now = 0L
        // Slow and lopsided samples first, then one fast symmetric one. Averaging would land
        // somewhere between; taking the minimum-delay sample should converge on the clean one.
        repeat(6) {
            sync.exchange(now, truth, outboundUs = 60_000, inboundUs = 10_000)
            now += 200_000
        }
        val errorBefore = abs(sync.offsetUs!! - truth)
        repeat(6) {
            sync.exchange(now, truth, outboundUs = 2_000, inboundUs = 2_000)
            now += 200_000
        }
        val errorAfter = abs(sync.offsetUs!! - truth)
        assertTrue("clean samples should improve the estimate: $errorBefore -> $errorAfter",
            errorAfter < errorBefore)
        assertTrue("error was ${errorAfter}us", errorAfter < 5_000)
    }

    @Test
    fun `a wildly slow sample is rejected once a baseline exists`() {
        val sync = ClockSync()
        var now = 0L
        repeat(6) {
            sync.exchange(now, 0L, outboundUs = 5_000, inboundUs = 5_000)
            now += 200_000
        }
        val before = sync.sampleCount
        val accepted = sync.exchange(now, 0L, outboundUs = 900_000, inboundUs = 900_000)
        assertFalse("a sample nearly two seconds slower should be rejected", accepted)
        assertEquals(before, sync.sampleCount)
    }

    @Test
    fun `outlier rejection stays off until there is something to compare against`() {
        // Rejecting against a median of one sample would let the first arrival define what is
        // normal, and a slow first sample would then reject every good one after it.
        val sync = ClockSync()
        assertTrue(sync.exchange(0, 0L, outboundUs = 400_000, inboundUs = 400_000))
        assertTrue(sync.exchange(1_000_000, 0L, outboundUs = 3_000, inboundUs = 3_000))
    }

    @Test
    fun `a negative delay is refused`() {
        // Only possible if a clock moved mid-exchange. There is nothing to recover from it.
        val sync = ClockSync()
        assertFalse(sync.offer(t1 = 1000, t2 = 0, t3 = 5000, t4 = 1100))
        assertNull(sync.offsetUs)
    }

    @Test
    fun `reset forgets everything`() {
        val sync = ClockSync()
        repeat(5) { sync.exchange(it * 200_000L, 3_000_000L, 5_000, 5_000) }
        assertTrue(sync.offsetUs != null)
        sync.reset()
        assertNull(sync.offsetUs)
        assertNull(sync.bestDelayUs)
        assertEquals(0, sync.sampleCount)
    }

    @Test
    fun `the window is bounded`() {
        val sync = ClockSync()
        repeat(100) { sync.exchange(it * 200_000L, 1_000L, 5_000, 5_000) }
        assertTrue("kept ${sync.sampleCount}", sync.sampleCount <= 16)
    }

    @Test
    fun `a negative offset works, so it does not matter which device is ahead`() {
        val sync = ClockSync()
        val truth = -7_500_000L
        repeat(8) { sync.exchange(it * 200_000L, truth, 4_000, 4_000) }
        assertEquals(truth, sync.offsetUs!!)
    }
}
