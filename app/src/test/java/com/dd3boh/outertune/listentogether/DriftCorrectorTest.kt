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
 * The correction ladder.
 *
 * Almost all of this is about what the corrector refuses to do. Seeking on every disagreement is the
 * obvious implementation and it is unusable - the interesting properties are the deadband, the
 * hysteresis, and the settle window, all of which exist to suppress a correction that would be worse
 * than the drift.
 */
class DriftCorrectorTest {

    private class FakeClock(var us: Long = 0) {
        fun advanceMs(ms: Long) { us += ms * 1000 }
    }

    private fun corrector(clock: FakeClock) = DriftCorrector { clock.us }

    @Test
    fun `a gap too small to hear is left alone`() {
        val c = corrector(FakeClock())
        // Inside the precedence window: two sources this close are heard as one, so a correction
        // could only make things worse.
        assertEquals(Correction.Hold, c.correct(20, playing = true, hostPositionMs = 1000))
        assertEquals(Correction.Hold, c.correct(-20, playing = true, hostPositionMs = 1000))
    }

    @Test
    fun `being behind speeds up and being ahead slows down`() {
        val behind = corrector(FakeClock()).correct(120, playing = true, hostPositionMs = 5000)
        assertTrue("expected a nudge, got $behind", behind is Correction.Nudge)
        assertTrue((behind as Correction.Nudge).speed > 1f)

        val ahead = corrector(FakeClock()).correct(-120, playing = true, hostPositionMs = 5000)
        assertTrue((ahead as Correction.Nudge).speed < 1f)
    }

    @Test
    fun `the rate change stays subliminal`() {
        val c = corrector(FakeClock())
        // A gap just under the seek threshold asks for more correction than is allowed. The clamp is
        // what keeps the tempo change from being noticed as the song running fast.
        val nudge = c.correct(249, playing = true, hostPositionMs = 5000) as Correction.Nudge
        assertTrue("speed was ${nudge.speed}", nudge.speed <= 1.05f)
    }

    @Test
    fun `a gap that is already an obvious echo is seeked, not nudged`() {
        val c = corrector(FakeClock())
        // Closing this by nudging would take seconds of audibly wrong tempo, during which the echo
        // is still there. The seek is both quicker and less noticeable.
        assertEquals(
            Correction.Seek(90_000),
            c.correct(900, playing = true, hostPositionMs = 90_000),
        )
    }

    @Test
    fun `a seek is not immediately followed by another`() {
        val clock = FakeClock()
        val c = corrector(clock)
        c.correct(900, playing = true, hostPositionMs = 90_000)

        // Right after seeking, the player still reports where it was. Believing that reading gives a
        // second seek, which gives a third - the seek loop this window exists to break.
        clock.advanceMs(200)
        assertEquals(Correction.Hold, c.correct(900, playing = true, hostPositionMs = 90_000))
        clock.advanceMs(500)
        assertEquals(Correction.Hold, c.correct(900, playing = true, hostPositionMs = 90_000))

        // Once the player has settled, a real gap is acted on again.
        clock.advanceMs(2_000)
        assertTrue(c.correct(900, playing = true, hostPositionMs = 90_000) is Correction.Seek)
    }

    @Test
    fun `a gap sitting on the boundary does not flap`() {
        val c = corrector(FakeClock())
        // Below the band, nothing happens however many times it is asked.
        repeat(5) { assertEquals(Correction.Hold, c.correct(29, playing = true, hostPositionMs = 0)) }

        // Once over, correcting starts...
        assertTrue(c.correct(31, playing = true, hostPositionMs = 0) is Correction.Nudge)

        // ...and dropping back just under the entry threshold must not immediately abandon it.
        // A single threshold for both directions would switch the rate on and off every tick.
        val next = c.correct(29, playing = true, hostPositionMs = 0)
        assertTrue("should still be correcting, got $next", next != Correction.Nudge(1f))
    }

    @Test
    fun `pausing restores the normal rate`() {
        val c = corrector(FakeClock())
        c.correct(150, playing = true, hostPositionMs = 0)
        // A nudge left applied across a pause would resume at the wrong rate, and there is no drift
        // accumulating while stopped to justify keeping it.
        assertEquals(Correction.Nudge(1f), c.correct(150, playing = false, hostPositionMs = 0))
        assertEquals(Correction.Hold, c.correct(150, playing = false, hostPositionMs = 0))
    }

    @Test
    fun `the rate is not re-set for a change too small to matter`() {
        val c = corrector(FakeClock())
        assertTrue(c.correct(31, playing = true, hostPositionMs = 0) is Correction.Nudge)
        // Each speed change reconfigures the audio pipeline, which on some devices is audible. A
        // fractionally different rate is not worth that.
        assertEquals(Correction.Hold, c.correct(30, playing = true, hostPositionMs = 0))
    }

    @Test
    fun `reset forgets a correction in progress`() {
        val c = corrector(FakeClock())
        c.correct(150, playing = true, hostPositionMs = 0)
        c.reset()
        // After a track change the old error describes nothing. Without the reset the corrector
        // would still believe it was mid-correction and apply the tight exit threshold to a fresh
        // song.
        assertEquals(Correction.Hold, c.correct(20, playing = true, hostPositionMs = 0))
    }

    @Test
    fun `a real gap closes silently and the rate returns to normal`() {
        // The case the whole design is for: a follower a fifth of a second behind, corrected without
        // a single seek and without the rate ever leaving the inaudible range.
        val clock = FakeClock()
        val c = corrector(clock)
        var host = 200.0
        var follower = 0.0
        var speed = 1f
        var seeks = 0
        var worstSpeed = 1f

        repeat(60) {
            when (val correction = c.correct(
                errorMs = (host - follower).toLong(),
                playing = true,
                hostPositionMs = host.toLong(),
            )) {
                is Correction.Nudge -> speed = correction.speed
                is Correction.Seek -> seeks++
                Correction.Hold -> Unit
            }
            worstSpeed = maxOf(worstSpeed, speed)
            clock.advanceMs(1_000)
            host += 1_000
            follower += 1_000 * speed
        }

        assertEquals("should never have needed to seek", 0, seeks)
        assertTrue("ended ${abs(host - follower)}ms apart", abs(host - follower) < 30)
        assertTrue("peak rate was $worstSpeed", worstSpeed <= 1.05f)
        assertEquals("rate should be back to normal once converged", 1f, speed)
    }

    @Test
    fun `a follower that has run ahead also converges`() {
        val clock = FakeClock()
        val c = corrector(clock)
        var host = 0.0
        var follower = 180.0
        var speed = 1f

        repeat(60) {
            when (val correction = c.correct(
                errorMs = (host - follower).toLong(),
                playing = true,
                hostPositionMs = host.toLong(),
            )) {
                is Correction.Nudge -> speed = correction.speed
                is Correction.Seek -> Unit
                Correction.Hold -> Unit
            }
            clock.advanceMs(1_000)
            host += 1_000
            follower += 1_000 * speed
        }

        assertTrue("ended ${abs(host - follower)}ms apart", abs(host - follower) < 30)
        assertEquals(1f, speed)
    }
}
