/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MultiQueueObject
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

    @Test
    fun `a play goes to the account only when it is the listener's own and signed in`() {
        assertTrue(sharesPlayWithAccount(ownPick = true, isLocal = false, signedIn = true, remotePaused = false))
        // A station's or a Listen Together host's pick would come back from the history into Stats,
        // which leaves it out.
        assertFalse(sharesPlayWithAccount(ownPick = false, isLocal = false, signedIn = true, remotePaused = false))
        assertFalse(sharesPlayWithAccount(ownPick = true, isLocal = true, signedIn = true, remotePaused = false))
        assertFalse(sharesPlayWithAccount(ownPick = true, isLocal = false, signedIn = false, remotePaused = false))
        assertFalse(sharesPlayWithAccount(ownPick = true, isLocal = false, signedIn = true, remotePaused = true))
    }

    private fun queue(isStation: Boolean = false, isFollowing: Boolean = false) = MultiQueueObject(
        id = 1, title = "Queue", queue = mutableListOf(), index = 0, isStation = isStation, isFollowing = isFollowing,
    )

    @Test
    fun `the listener's own queue counts`() {
        assertTrue(isOwnPick(queue()))
        // Nothing to say otherwise - a song started before any queue was loaded.
        assertTrue(isOwnPick(null))
    }

    @Test
    fun `a station's songs and a Listen Together host's do not count`() {
        assertFalse(isOwnPick(queue(isStation = true)))
        // Following someone else's session: every song the host plays reaches this phone too, and
        // used to be counted here as well as on the host.
        assertFalse(isOwnPick(queue(isFollowing = true)))
        assertFalse(isOwnPick(queue(isStation = true, isFollowing = true)))
    }
}
