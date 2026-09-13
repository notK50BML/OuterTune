/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer, driven by a clock the test moves by hand.
 *
 * Every claim here is about time passing, and waiting for real seconds to test a sixty-minute timer
 * is not a test anyone would run.
 */
class SleepTimerTest {

    private var now = 1_000_000L
    private val timer = SleepTimer { now }

    private fun advance(minutes: Int) {
        now += minutes * 60_000L
    }

    @Test
    fun `nothing is set to begin with`() {
        assertFalse(timer.isActive)
        assertNull(timer.deadlineMs)
        assertFalse(timer.dueNow())
        assertFalse(timer.songEnded())
        assertEquals(0L, timer.remainingMs())
    }

    @Test
    fun `a timer counts down`() {
        timer.start(30)
        assertTrue(timer.isActive)
        assertEquals(30 * 60_000L, timer.remainingMs())
        advance(10)
        assertEquals(20 * 60_000L, timer.remainingMs())
    }

    @Test
    fun `it is not due before its deadline`() {
        timer.start(30)
        advance(29)
        assertFalse(timer.dueNow())
        assertTrue(timer.isActive)
    }

    @Test
    fun `it is due once the deadline passes`() {
        timer.start(30)
        advance(30)
        assertTrue(timer.dueNow())
    }

    @Test
    fun `it only fires once`() {
        // The case that matters, because this is polled every second. A timer left expired would
        // pause playback again the instant anyone pressed play.
        timer.start(15)
        advance(20)
        assertTrue(timer.dueNow())
        assertFalse("the timer fired twice", timer.dueNow())
        assertFalse(timer.isActive)
    }

    @Test
    fun `cancelling stops it firing`() {
        timer.start(5)
        timer.cancel()
        advance(10)
        assertFalse(timer.dueNow())
        assertFalse(timer.isActive)
    }

    @Test
    fun `end of song fires at the boundary and not on the clock`() {
        timer.endOfSong()
        assertTrue(timer.isActive)
        advance(120)
        // No duration to count down, so no amount of waiting makes it due.
        assertFalse("a time-based check fired an end-of-song timer", timer.dueNow())
        assertTrue(timer.songEnded())
    }

    @Test
    fun `end of song only fires once`() {
        timer.endOfSong()
        assertTrue(timer.songEnded())
        assertFalse("it should not stop the next song too", timer.songEnded())
        assertFalse(timer.isActive)
    }

    @Test
    fun `a song ending does not stop a duration timer early`() {
        // "Stop in thirty minutes" means thirty minutes, not "at the end of whatever is on".
        timer.start(30)
        assertFalse(timer.songEnded())
        assertTrue("the duration timer was cleared by a track change", timer.isActive)
    }

    @Test
    fun `the two modes replace each other`() {
        // They are alternatives. A timer that was both would have to pick one to honour, and the
        // user would have to guess which.
        timer.start(30)
        timer.endOfSong()
        assertNull(timer.deadlineMs)
        assertTrue(timer.pauseWhenSongEnds)

        timer.start(30)
        assertFalse(timer.pauseWhenSongEnds)
        assertEquals(30 * 60_000L, timer.remainingMs())
    }

    @Test
    fun `remaining never goes negative`() {
        // It is shown to the user every second; a countdown that runs past zero into minus numbers
        // is a countdown that looks broken.
        timer.start(1)
        advance(10)
        assertEquals(0L, timer.remainingMs())
    }

    @Test
    fun `a sleeping machine wakes up expired rather than with time left`() {
        // Wall clock, deliberately. A timer set for twenty minutes before the lid closed should be
        // over when it opens an hour later, not have another twenty minutes to run.
        timer.start(20)
        now += 60 * 60_000L
        assertTrue(timer.dueNow())
    }

    @Test
    fun `a duration below a minute is treated as a minute`() {
        timer.start(0)
        assertEquals(60_000L, timer.remainingMs())
    }

    @Test
    fun `the readout counts in minutes, and in hours once there are any`() {
        assertEquals("0:30", SleepTimer.format(30_000))
        assertEquals("1:05", SleepTimer.format(65_000))
        assertEquals("42:07", SleepTimer.format(42 * 60_000L + 7_000))
        assertEquals("1:02:07", SleepTimer.format(3_600_000 + 2 * 60_000L + 7_000))
        assertEquals("0:00", SleepTimer.format(-5_000))
    }

    @Test
    fun `every preset is a usable length`() {
        assertTrue(SleepTimer.PRESET_MINUTES.isNotEmpty())
        assertTrue(SleepTimer.PRESET_MINUTES.all { it > 0 })
        assertEquals(
            "presets should be offered shortest first",
            SleepTimer.PRESET_MINUTES.sorted(),
            SleepTimer.PRESET_MINUTES,
        )
    }
}
