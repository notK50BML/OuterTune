/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The projection from a tick to "where the host is right now".
 *
 * This is the calculation the entire feature rests on, and it is not one that can be checked by
 * reading it - a sign error here looks exactly like a working implementation until two devices are
 * side by side and one is audibly behind.
 */
class SyncMathTest {

    /** A tick saying: at host-clock 10s, the player was at 60s, playing normally. */
    private fun tick(
        positionMs: Long = 60_000,
        hostClockUs: Long = 10_000_000,
        playing: Boolean = true,
        speed: Float = 1f,
    ) = Protocol.Frame.Tick(positionMs, hostClockUs, playing, speed)

    @Test
    fun `transit time is added to the position`() {
        // The tick describes host-clock 10.000s. The follower reads it at its own 9.000s, and the
        // host's clock is 1.2s ahead - so on the host's clock it is now 10.200s, 200ms after the
        // tick was stamped. The host must therefore have played 200ms more music.
        val result = SyncMath.hostPositionNowMs(
            tick = tick(),
            offsetUs = 1_200_000,
            followerNowUs = 9_000_000,
        )
        assertEquals(60_200L, result)
    }

    @Test
    fun `the offset is what makes the two clocks comparable`() {
        // Same tick and same follower clock as above, but the host is 1.2s *behind* rather than
        // ahead. Without applying the offset, both cases would produce the same answer - and one of
        // them would be 2.4 seconds wrong.
        val result = SyncMath.hostPositionNowMs(
            tick = tick(),
            offsetUs = -1_200_000,
            followerNowUs = 9_000_000,
        )
        assertEquals(57_800L, result)
    }

    @Test
    fun `a paused host is where it says it is`() {
        // Projecting forward through a pause would invent playback that never happened, and the
        // follower would then seek ahead of a host that has not moved.
        val result = SyncMath.hostPositionNowMs(
            tick = tick(playing = false),
            offsetUs = 0,
            followerNowUs = 15_000_000,
        )
        assertEquals(60_000L, result)
    }

    @Test
    fun `the host rate is honoured`() {
        // The host may be nudging its own rate. Assuming 1x while it plays at 1.05 would make the
        // follower drift by 5% of the projection window.
        val result = SyncMath.hostPositionNowMs(
            tick = tick(speed = 2f),
            offsetUs = 0,
            followerNowUs = 11_000_000,
        )
        assertEquals(62_000L, result)
    }

    @Test
    fun `an unbelievable projection is refused rather than acted on`() {
        // A tick should be a fraction of a second old. A projection claiming a minute has passed
        // means the clock offset is wrong, not that the host jumped - and seeking to the answer
        // would land somewhere arbitrary. Ignoring the tick costs one second; acting on it costs a
        // wrong seek.
        assertNull(
            SyncMath.hostPositionNowMs(
                tick = tick(),
                offsetUs = 0,
                followerNowUs = 70_000_000,
            )
        )
        assertNull(
            SyncMath.hostPositionNowMs(
                tick = tick(),
                offsetUs = 0,
                followerNowUs = -70_000_000,
            )
        )
    }

    @Test
    fun `a fresh tick with synchronised clocks is taken at face value`() {
        val result = SyncMath.hostPositionNowMs(
            tick = tick(),
            offsetUs = 0,
            followerNowUs = 10_000_000,
        )
        assertEquals(60_000L, result)
    }
}
