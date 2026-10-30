/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mini player's scroll gestures. Down stops the music, so most of these are about what must
 * not count: a single tick, a slow drift, a scroll that began somewhere else.
 */
class MiniPlayerScrollTest {

    private val streaks = ScrollStreaks()
    private val gesture = MiniPlayerScrollGesture(streaks)

    /** As the window does: every scroll noted on the way in, then handed to the bar. */
    private fun overBar(timeMs: Long, delta: Float): MiniPlayerScrollAction? {
        streaks.note(timeMs)
        return gesture.feed(timeMs, delta)
    }

    private fun elsewhere(timeMs: Long) = streaks.note(timeMs)

    @Test
    fun `two notches down dismiss and two up expand`() {
        assertNull(overBar(1_000, 1f))
        assertEquals(MiniPlayerScrollAction.Dismiss, overBar(1_080, 1f))
        assertNull(overBar(5_000, -1f))
        assertEquals(MiniPlayerScrollAction.Expand, overBar(5_100, -1f))
    }

    @Test
    fun `a single tick does nothing`() {
        assertNull(overBar(1_000, 1f))
        // Long after: a new streak, which starts counting again from nothing.
        assertNull(overBar(3_000, 1f))
    }

    @Test
    fun `a trackpad gets there in small steps`() {
        val results = (0 until 12).map { overBar(1_000L + it * 16, 0.2f) }
        assertEquals(listOf(MiniPlayerScrollAction.Dismiss), results.filterNotNull())
    }

    @Test
    fun `too slow is not one gesture`() {
        assertNull(overBar(1_000, 1f))
        assertNull(overBar(1_500, 0.5f))
        // Inside the quiet gap, so still the same streak - but past the window for reaching the threshold.
        assertNull(overBar(2_000, 1f))
    }

    @Test
    fun `turning back starts the count again`() {
        assertNull(overBar(1_000, 1f))
        assertNull(overBar(1_050, -1f))
        assertNull(overBar(1_100, 1f))
        assertNull(overBar(1_150, -1f))
    }

    @Test
    fun `a scroll that began on the list above does nothing`() {
        elsewhere(1_000)
        elsewhere(1_040)
        // The pointer drifts down onto the bar while the wheel is still going.
        assertNull(overBar(1_080, 1f))
        assertNull(overBar(1_120, 1f))
        assertNull(overBar(1_160, 1f))
        // Once the wheel has rested, a scroll that starts on the bar counts again.
        assertNull(overBar(2_000, 1f))
        assertEquals(MiniPlayerScrollAction.Dismiss, overBar(2_050, 1f))
    }

    @Test
    fun `the rest of a scroll that acted is spent`() {
        assertNull(overBar(1_000, -1f))
        assertEquals(MiniPlayerScrollAction.Expand, overBar(1_050, -1f))
        assertEquals(true, streaks.spent)
        assertNull(overBar(1_100, -1f))
        assertNull(overBar(1_150, -1f))
        // The window swallows these while spent; a fresh scroll is not.
        streaks.note(2_000)
        assertEquals(false, streaks.spent)
    }
}
