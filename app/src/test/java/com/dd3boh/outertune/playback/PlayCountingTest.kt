/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When listening to a track counts as a play of it. */
class PlayCountingTest {
    @Test
    fun `a track of known length counts from the ratio`() {
        assertTrue(countsAsPlay(playedMs = 60_000, durationSeconds = 200, minRatio = 0.3f))
        assertFalse(countsAsPlay(playedMs = 59_000, durationSeconds = 200, minRatio = 0.3f))
    }

    @Test
    fun `a live stream, with no length, never counts`() {
        assertFalse(countsAsPlay(playedMs = 45 * 60_000L, durationSeconds = -1, minRatio = 0.3f))
        assertFalse(countsAsPlay(playedMs = 45 * 60_000L, durationSeconds = null, minRatio = 0.3f))
    }

    @Test
    fun `a length of zero is unknown, not a play after a millisecond`() {
        assertFalse(countsAsPlay(playedMs = 1, durationSeconds = 0, minRatio = 0.3f))
        assertFalse(countsAsPlay(playedMs = 0, durationSeconds = 0, minRatio = 0.3f))
    }
}
