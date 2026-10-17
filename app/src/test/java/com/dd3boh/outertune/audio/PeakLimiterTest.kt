/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Whether the limiter keeps a boost under full scale *without* adding harmonics - which is the
 * thing the stage it replaced could not do.
 *
 * The comparison is measured rather than claimed. The old stage was tanh applied above a threshold,
 * reproduced exactly below; both are fed the same over-full-scale tone and the third harmonic each
 * produces is read off. A waveshaper adds odd harmonics by its nature, and that is what was heard as
 * grit on every boosted preset. A limiter changes the gain, not the shape, so it should add almost
 * none.
 */
class PeakLimiterTest {

    private val sampleRate = 44100

    /** The stage this replaced, verbatim, so the comparison is against what actually shipped. */
    private fun oldSoftLimit(x: Float): Float {
        val threshold = 0.9f
        val range = 1f - threshold
        val magnitude = abs(x)
        if (magnitude <= threshold) return x
        val shaped = threshold + range * tanh((magnitude - threshold) / range)
        return if (x < 0f) -shaped else shaped
    }

    private fun magnitudeAt(signal: FloatArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (n in signal.indices) {
            val a = 2.0 * PI * hz * n / sampleRate
            re += signal[n] * cos(a)
            im -= signal[n] * sin(a)
        }
        return 2.0 * sqrt(re * re + im * im) / signal.size
    }

    private fun db(x: Double) = if (x <= 1e-12) -240.0 else 20.0 * log10(x)

    /** A tone, mono, as a run of one-channel frames. */
    private fun tone(hz: Double, amplitude: Float, samples: Int = 16384) =
        FloatArray(samples) { amplitude * sin(2.0 * PI * hz * it / sampleRate).toFloat() }

    private fun limited(input: FloatArray, channels: Int = 1): FloatArray {
        val limiter = PeakLimiter().apply { configure(sampleRate, channels) }
        val out = FloatArray(input.size)
        val frame = FloatArray(1)
        for (i in input.indices) {
            frame[0] = input[i]
            limiter.process(frame)
            out[i] = frame[0]
        }
        // Past the look-ahead and the gain settling, so what is measured is the steady state.
        return out.copyOfRange(input.size / 2, input.size)
    }

    // ---- the thing it is for ----------------------------------------------------------------------

    @Test
    fun `an over-full-scale tone comes out under full scale`() {
        val out = limited(tone(1_000.0, amplitude = 1.6f))
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak is over the ceiling ${PeakLimiter.CEILING}", peak <= PeakLimiter.CEILING + 0.002f)
    }

    @Test
    fun `it adds far less distortion than the waveshaper it replaced`() {
        // The measurement the whole change rests on. Same tone, 4dB over full scale, through both.
        // Third harmonic relative to the fundamental is the standard read for odd-order distortion,
        // which is exactly what a symmetric waveshaper produces.
        val input = tone(1_000.0, amplitude = 1.6f)

        val viaLimiter = limited(input)
        val viaOld = input.copyOfRange(input.size / 2, input.size).map { oldSoftLimit(it) }.toFloatArray()

        fun h3RelativeDb(signal: FloatArray) =
            db(magnitudeAt(signal, 3_000.0)) - db(magnitudeAt(signal, 1_000.0))

        val limiterH3 = h3RelativeDb(viaLimiter)
        val oldH3 = h3RelativeDb(viaOld)

        assertTrue(
            "limiter third harmonic at ${"%.1f".format(limiterH3)}dB, old stage at ${"%.1f".format(oldH3)}dB - " +
                "expected the limiter to be at least 30dB cleaner",
            limiterH3 < oldH3 - 30.0,
        )
    }

    @Test
    fun `a boosted bass note is turned down, not modulated`() {
        // The case that sounded worst. A 40Hz note over full scale: with a release shorter than
        // the bass period the gain rose and fell on every cycle, which is amplitude modulation and
        // puts sidebands and odd harmonics all over the note. With the hold the gain sits still, so
        // what comes out is the same sine, quieter.
        val input = tone(40.0, amplitude = 1.8f, samples = 44100)
        val out = limited(input)
        val h3 = db(magnitudeAt(out, 120.0)) - db(magnitudeAt(out, 40.0))
        val sideband = db(magnitudeAt(out, 80.0)) - db(magnitudeAt(out, 40.0))
        assertTrue("third harmonic at ${"%.1f".format(h3)}dB", h3 < -60.0)
        assertTrue("second harmonic at ${"%.1f".format(sideband)}dB", sideband < -60.0)
        assertTrue("peak over the ceiling", out.maxOf { abs(it) } <= PeakLimiter.CEILING + 0.002f)
    }

    @Test
    fun `a signal under the ceiling passes through unchanged`() {
        // Bit-exact apart from the delay. A limiter that touched audio it had no reason to would be
        // colouring every quiet track.
        val input = tone(1_000.0, amplitude = 0.5f, samples = 4096)
        val limiter = PeakLimiter().apply { configure(sampleRate, 1) }
        val lookahead = (sampleRate * PeakLimiter.LOOKAHEAD_MS / 1000f).toInt().let { if (it * 1000f / sampleRate < PeakLimiter.LOOKAHEAD_MS) it + 1 else it }
        val out = FloatArray(input.size)
        val frame = FloatArray(1)
        for (i in input.indices) {
            frame[0] = input[i]
            limiter.process(frame)
            out[i] = frame[0]
        }
        // Compare against the input shifted by however many samples the delay is.
        val shift = (lookahead - 1..lookahead + 1).first { d ->
            (d + 100 until input.size).all { abs(out[it] - input[it - d]) < 1e-6f }
        }
        assertTrue("no delay aligned the output with the input", shift >= 0)
        assertEquals(0f, limiter.reductionDb, 0.0001f)
    }

    // ---- stereo ---------------------------------------------------------------------------------

    @Test
    fun `both channels come down together`() {
        // Linked gain. Limiting each side on its own moves the stereo image every time one side
        // peaks, which is far more audible than the limiting itself.
        val loud = tone(1_000.0, amplitude = 1.8f)
        val quiet = tone(1_000.0, amplitude = 0.3f)
        val limiter = PeakLimiter().apply { configure(sampleRate, 2) }
        val outL = FloatArray(loud.size)
        val outR = FloatArray(loud.size)
        val frame = FloatArray(2)
        for (i in loud.indices) {
            frame[0] = loud[i]
            frame[1] = quiet[i]
            limiter.process(frame)
            outL[i] = frame[0]
            outR[i] = frame[1]
        }
        val half = loud.size / 2
        val ratio = magnitudeAt(outL.copyOfRange(half, outL.size), 1_000.0) /
            magnitudeAt(outR.copyOfRange(half, outR.size), 1_000.0)
        assertEquals("the left/right balance moved", 1.8 / 0.3, ratio, 0.15)
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    @Test
    fun `reset empties the delay line`() {
        // After a seek the delay would otherwise play out 1.5ms of the old position.
        val limiter = PeakLimiter().apply { configure(sampleRate, 1) }
        val frame = FloatArray(1)
        repeat(200) { frame[0] = 0.8f; limiter.process(frame) }
        limiter.reset()
        frame[0] = 0f
        limiter.process(frame)
        assertEquals(0f, frame[0], 0f)
    }

    @Test
    fun `an unconfigured limiter does nothing rather than crashing`() {
        val frame = floatArrayOf(2f, -2f)
        PeakLimiter().process(frame)
        assertEquals(2f, frame[0], 0f)
    }
}
