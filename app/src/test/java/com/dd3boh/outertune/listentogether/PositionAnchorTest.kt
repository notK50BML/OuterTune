/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The interpolator, driven with a fake clock and a deliberately coarse player.
 *
 * The whole point of this class is what it does *between* the player's updates, and that is only
 * observable if the clock can be advanced without the player being updated - which is exactly what
 * a real test cannot arrange and a fake one can.
 */
class PositionAnchorTest {

    private class FakeClock(var us: Long = 0) {
        fun advanceMs(ms: Long) { us += ms * 1000 }
    }

    @Test
    fun `position advances between player updates`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        anchor.update(10_000)

        clock.advanceMs(150)
        // The player has said nothing since, but 150ms of music has been heard.
        assertEquals(10_150, anchor.positionMs(playing = true))
    }

    @Test
    fun `a repeated reading does not restart the clock`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        anchor.update(10_000)

        // A coarse player repeats its last value for a few hundred ms. Treating each repeat as fresh
        // would pin the estimate to the last poll rather than the last real movement - the exact
        // staircase this class exists to remove.
        clock.advanceMs(100); anchor.update(10_000)
        clock.advanceMs(100); anchor.update(10_000)
        assertEquals(10_200, anchor.positionMs(playing = true))
    }

    @Test
    fun `a real player tick re-anchors and removes accumulated error`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        anchor.update(0)
        clock.advanceMs(300)
        // The player finally reports, and reports slightly less than was estimated.
        anchor.update(280)
        assertEquals(280, anchor.positionMs(playing = true))
        clock.advanceMs(100)
        assertEquals(380, anchor.positionMs(playing = true))
    }

    @Test
    fun `a paused player does not advance`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        anchor.update(45_000)
        clock.advanceMs(5_000)
        // Letting the clock run while paused would make a paused follower look like it had fallen
        // five seconds behind, and provoke a correction that would be entirely wrong.
        assertEquals(45_000, anchor.positionMs(playing = false))
    }

    @Test
    fun `speed is honoured while the rate is being nudged`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        anchor.update(0)
        clock.advanceMs(1_000)
        assertEquals(1_020, anchor.positionMs(playing = true, speed = 1.02f))
        assertEquals(980, anchor.positionMs(playing = true, speed = 0.98f))
    }

    @Test
    fun `reset re-anchors even when the position is unchanged`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        anchor.update(30_000)
        clock.advanceMs(500)

        // A seek back to the same position is a real event that update() cannot see, because the
        // value did not change. Without reset the estimate would keep the old anchor and report
        // half a second of music that was never played.
        anchor.reset(30_000)
        assertEquals(30_000, anchor.positionMs(playing = true))
    }

    @Test
    fun `before any update it reports zero rather than nonsense`() {
        val clock = FakeClock(us = 999_999_999)
        val anchor = PositionAnchor { clock.us }
        // Interpolating from an unset anchor against a large clock would give an enormous position.
        assertEquals(0, anchor.positionMs(playing = true))
    }

    @Test
    fun `the latched pair is what a host should transmit`() {
        val clock = FakeClock(us = 5_000_000)
        val anchor = PositionAnchor { clock.us }
        anchor.update(12_345)
        clock.advanceMs(200)
        val (position, atUs) = anchor.latched()
        // The pair must describe the moment the player actually moved, not the moment it was asked -
        // that is what lets the other end correct for the delay in between.
        assertEquals(12_345, position)
        assertEquals(5_000_000, atUs)
    }

    @Test
    fun `error stays bounded by one player tick over a long run`() {
        val clock = FakeClock()
        val anchor = PositionAnchor { clock.us }
        var truth = 0L
        var worst = 0L
        // A player that reports every 300ms, polled every 50ms, for a minute.
        repeat(1200) { step ->
            clock.advanceMs(50)
            truth += 50
            if (step % 6 == 0) anchor.update(truth)
            worst = maxOf(worst, abs(anchor.positionMs(playing = true) - truth))
        }
        assertTrue("worst error was ${worst}ms", worst <= 5)
    }
}
