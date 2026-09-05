/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delay that keeps the bars matched to the sound.
 *
 * Every test here is really the same question asked differently: is the spectrum being shown the one
 * belonging to the audio currently coming out of the speakers, or the one belonging to audio that
 * has merely been decoded? The second is the natural implementation and it is wrong by about a
 * second - which is long enough that the bars visibly anticipate the music.
 */
class VisualizerTapTest {

    private fun levels(vararg v: Float) = v

    @Test
    fun `nothing is shown before the audio it describes is audible`() {
        val tap = VisualizerTap(bands = 3)
        // Decoded and queued to be heard a second from now, at 44100 frames.
        tap.submit(atFrame = 44_100, levels = levels(1f, 1f, 1f))

        // The line has played nothing yet, so nothing has been heard.
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), tap.sample(playedFrame = 0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), tap.sample(playedFrame = 44_099), 1e-6f)

        // And at the moment it becomes audible, it appears.
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), tap.sample(playedFrame = 44_100), 1e-6f)
    }

    @Test
    fun `the most recent audible spectrum wins`() {
        // The decoder runs far ahead of the speakers, so by the time any given frame is heard there
        // are many queued. Showing the oldest audible one would put the display a fixed distance
        // behind and it would never catch up.
        val tap = VisualizerTap(bands = 2)
        tap.submit(0, levels(0.1f, 0.1f))
        tap.submit(1000, levels(0.2f, 0.2f))
        tap.submit(2000, levels(0.3f, 0.3f))

        assertArrayEquals(floatArrayOf(0.3f, 0.3f), tap.sample(playedFrame = 2500), 1e-6f)
    }

    @Test
    fun `a slow consumer stays current instead of falling behind`() {
        // The UI redraws at its own rate, which may be slower than blocks arrive. Each sample should
        // jump to wherever playback actually is.
        val tap = VisualizerTap(bands = 1)
        repeat(100) { tap.submit(it * 1024L, levels(it / 100f)) }

        assertArrayEquals(floatArrayOf(0.5f), tap.sample(playedFrame = 50 * 1024L), 1e-6f)
        assertArrayEquals(floatArrayOf(0.99f), tap.sample(playedFrame = 99 * 1024L), 1e-6f)
    }

    @Test
    fun `the last audible spectrum is held until the next one is due`() {
        // Between blocks there is nothing new to show, and blanking would flicker at the block rate.
        val tap = VisualizerTap(bands = 2)
        tap.submit(0, levels(0.7f, 0.4f))
        tap.submit(10_000, levels(0.2f, 0.9f))

        assertArrayEquals(floatArrayOf(0.7f, 0.4f), tap.sample(500), 1e-6f)
        assertArrayEquals(floatArrayOf(0.7f, 0.4f), tap.sample(9_999), 1e-6f)
        assertArrayEquals(floatArrayOf(0.2f, 0.9f), tap.sample(10_000), 1e-6f)
    }

    @Test
    fun `submitted levels are copied, not referenced`() {
        // The analyser reuses one array between calls - that is deliberate, since it produces forty
        // a second and allocating each one would make it the app's largest source of garbage. So the
        // tap has to copy, or every queued entry would show whatever the newest one happens to hold.
        val tap = VisualizerTap(bands = 2)
        val reused = floatArrayOf(0.5f, 0.5f)
        tap.submit(0, reused)

        reused[0] = 9f
        reused[1] = 9f

        assertArrayEquals(floatArrayOf(0.5f, 0.5f), tap.sample(0), 1e-6f)
    }

    @Test
    fun `the returned array is not corrupted by a later sample`() {
        val tap = VisualizerTap(bands = 2)
        tap.submit(0, levels(0.1f, 0.2f))
        val first = tap.sample(0).copyOf()
        tap.submit(100, levels(0.8f, 0.9f))
        tap.sample(100)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f), first, 1e-6f)
    }

    @Test
    fun `reset clears both the queue and what is showing`() {
        // For a seek or a track change. Without it the bars would keep showing the old track's
        // spectrum until the new one's audio caught up to a frame position that no longer means
        // anything.
        val tap = VisualizerTap(bands = 2)
        tap.submit(0, levels(1f, 1f))
        tap.sample(0)
        tap.submit(5000, levels(1f, 1f))

        tap.reset()

        assertArrayEquals(floatArrayOf(0f, 0f), tap.sample(0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), tap.sample(999_999), 1e-6f)
    }

    @Test
    fun `a consumer that never reads cannot grow the queue without bound`() {
        // A minimised window composes nothing, so nothing samples. That must not accumulate.
        val tap = VisualizerTap(bands = 4)
        repeat(50_000) { tap.submit(it.toLong(), levels(1f, 1f, 1f, 1f)) }

        // Still answers, and with the newest data rather than something from the start.
        val shown = tap.sample(49_999)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), shown, 1e-6f)
    }

    @Test
    fun `an old spectrum dropped by the bound does not resurface`() {
        val tap = VisualizerTap(bands = 1)
        tap.submit(0, levels(0.5f))
        repeat(1000) { tap.submit(1000L + it, levels(0.9f)) }
        // The first entry is long gone; sampling at its frame must not find it.
        val shown = tap.sample(0)
        assertEquals(0f, shown[0], 1e-6f)
    }

    @Test
    fun `a seek backwards does not replay stale spectra`() {
        // After a seek the line is flushed and the frame counter is re-anchored, so queued entries
        // describe audio that was thrown away. reset is what the player calls; this pins that
        // sampling backwards without it would be wrong.
        val tap = VisualizerTap(bands = 1)
        tap.submit(100_000, levels(1f))
        tap.reset()
        tap.submit(10, levels(0.25f))

        assertArrayEquals(floatArrayOf(0.25f), tap.sample(10), 1e-6f)
    }

    @Test
    fun `concurrent submit and sample do not corrupt each other`() {
        // submit runs on the decode coroutine and sample on the UI. This is not a proof, but a
        // consistently-sized, in-range result across many interleavings would not survive an
        // unsynchronised implementation.
        val tap = VisualizerTap(bands = 8)
        val writer = Thread {
            repeat(20_000) { i -> tap.submit(i.toLong(), FloatArray(8) { (i % 100) / 100f }) }
        }
        var bad = 0
        val reader = Thread {
            repeat(20_000) { i ->
                val s = tap.sample(i.toLong())
                if (s.size != 8 || s.any { it < 0f || it > 1f }) bad++
            }
        }
        writer.start(); reader.start()
        writer.join(); reader.join()
        assertEquals("saw $bad malformed samples", 0, bad)
        assertTrue(true)
    }
}
