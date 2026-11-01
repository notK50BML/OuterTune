/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The player's processing pass against the two complaints: a bass boost and the gain distorting.
 *
 * Each case is a full-scale tone, where a boost has nowhere to go, judged two ways - the peak
 * against the limiter's ceiling, and the energy in its harmonics against the same signal hard-
 * clipped, which is what the gain used to become when the compressor clamped its own output.
 */
class OutputSafetyTest {

    private val rate = 44_100
    private val bassHz = 60.0

    /** Two seconds of a tone as mono big-endian 16-bit, through [eq] (and [compressor]) in blocks. */
    private fun run(
        eq: Equalizer,
        hz: Double,
        amplitude: Double,
        compressor: Compressor? = null,
        normalisationDb: Float = 0f,
    ): FloatArray {
        val frames = rate * 2
        val bytes = ByteArray(frames * 2)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * hz * f / rate) * amplitude * 32767).toInt()
            bytes[f * 2] = ((v shr 8) and 0xFF).toByte()
            bytes[f * 2 + 1] = (v and 0xFF).toByte()
        }
        val block = 4096
        var at = 0
        while (at < bytes.size) {
            val chunk = bytes.copyOfRange(at, minOf(at + block, bytes.size))
            eq.process(chunk, chunk.size, 16, 1, bigEndian = true, sampleRate = rate, compressor = compressor, normalisationDb = normalisationDb)
            chunk.copyInto(bytes, at)
            at += block
        }
        // The last second, an exact number of cycles of either tone.
        return FloatArray(rate) {
            val i = (frames - rate + it) * 2
            ((bytes[i].toInt() shl 8) or (bytes[i + 1].toInt() and 0xFF)).toShort() / 32768f
        }
    }

    private fun magnitudeAt(signal: FloatArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (n in signal.indices) {
            val a = 2.0 * PI * hz * n / rate
            re += signal[n] * cos(a)
            im -= signal[n] * sin(a)
        }
        return 2.0 * sqrt(re * re + im * im) / signal.size
    }

    /** Harmonics two to six against the fundamental, in dB. */
    private fun thdDb(signal: FloatArray, hz: Double): Double {
        val harmonics = (2..6).sumOf { val m = magnitudeAt(signal, hz * it); m * m }
        return 20.0 * log10(sqrt(harmonics) / magnitudeAt(signal, hz))
    }

    private fun hardClipped(gainDb: Double) = FloatArray(rate) {
        (sin(2 * PI * bassHz * it / rate) * 10.0.pow(gainDb / 20.0)).coerceIn(-1.0, 1.0).toFloat()
    }

    private fun eq(vararg gains: Pair<Float, Float>) = Equalizer().apply {
        enabled = true
        setBands(Equalizer.DEFAULT_BANDS.map { band ->
            gains.firstOrNull { it.first == band.freqHz }?.let { band.copy(gainDb = it.second) } ?: band
        })
    }

    private val ceiling = PeakLimiter.CEILING + 1f / 32768f

    @Test
    fun `a full-scale bass note through a 12dB bass boost stays under the ceiling, cleanly`() {
        // The Bass dial's way of doing it: every band up to 250Hz raised together.
        val out = run(eq(16f to 12f, 31f to 12f, 62f to 12f, 125f to 12f, 250f to 12f), bassHz, 1.0)
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak over the ceiling", peak <= ceiling)
        val ours = thdDb(out, bassHz)
        val clipped = thdDb(hardClipped(12.0), bassHz)
        assertTrue("THD ${"%.1f".format(ours)}dB - expected under -60dB", ours < -60.0)
        assertTrue("hard clipping only at ${"%.1f".format(clipped)}dB", clipped > -20.0)
    }

    @Test
    fun `twelve decibels of compressor gain is turned down, not clipped`() {
        // The case that distorted worst: the compressor ran after the limiter and clamped its own
        // makeup gain flat. A threshold at full scale and a ratio of one leave nothing but the gain.
        val compressor = Compressor().apply {
            enabled = true; thresholdDb = 0f; ratio = 1f; makeupGainDb = 12f
        }
        val out = run(Equalizer(), bassHz, 1.0, compressor = compressor)
        assertTrue(out.maxOf { abs(it) } <= ceiling)
        val ours = thdDb(out, bassHz)
        assertTrue("THD ${"%.1f".format(ours)}dB", ours < -60.0)
        assertTrue(thdDb(hardClipped(12.0), bassHz) > ours + 40.0)
    }

    @Test
    fun `a boost turns the rest of the music down by the curve's peak`() {
        val flat = run(eq(1000f to 0.0001f), 4_000.0, 0.25).maxOf { abs(it) }
        val boosted = eq(31f to 12f)
        val level = run(boosted, 4_000.0, 0.25).maxOf { abs(it) }
        val changeDb = 20 * log10(level / flat)
        assertEquals("4kHz should come down by the 31Hz band's height", -12.0, changeDb.toDouble(), 0.6)
        assertEquals(12f, boosted.headroomDb, 0.3f)
    }

    @Test
    fun `a curve that only cuts is still turned back up, and one that also boosts is not`() {
        val cut = eq(31f to -9f, 62f to -9f, 125f to -9f)
        run(cut, 1_000.0, 0.25)
        assertTrue("a bass cut should be made up, headroom ${cut.headroomDb}", cut.headroomDb < -0.5f)

        // The same cuts with one small boost: no makeup, and turned down by the boost instead -
        // the boosted frequency must not end above where it arrived.
        val mixed = eq(31f to -9f, 62f to -9f, 125f to -9f, 4000f to 2f)
        run(mixed, 1_000.0, 0.25)
        // Just under 2dB rather than 2: the bass cuts' skirts reach as far as 4kHz.
        val peak = EqResponse.peakDb(mixed.bands(), rate)
        assertTrue("mixed curve peak $peak", peak in 1.9f..2.0f)
        assertEquals("mixed curve should turn down by exactly its peak", peak, mixed.headroomDb, 0.01f)
    }

    @Test
    fun `normalisation is spent as headroom by a boost that fits inside it`() {
        val boosted = eq(1000f to 6f)
        run(boosted, 1_000.0, 0.25, normalisationDb = 8f)
        assertEquals(8f, boosted.headroomDb, 0.05f)
    }

    @Test
    fun `the limiter is bit-exact below its threshold`() {
        val limiter = PeakLimiter().apply { configure(rate, 1) }
        val amplitude = PeakLimiter.THRESHOLD * 0.98f
        val input = FloatArray(8192) { amplitude * sin(2 * PI * 1_000.0 * it / rate).toFloat() }
        val out = FloatArray(input.size)
        val frame = FloatArray(1)
        for (i in input.indices) {
            frame[0] = input[i]
            limiter.process(frame)
            out[i] = frame[0]
        }
        val delay = (1..400).first { d -> (d + 100 until input.size).all { out[it] == input[it - d] } }
        assertTrue(delay > 0)
        assertEquals(0f, limiter.reductionDb, 0f)
    }

    @Test
    fun `the knee is soft - continuous, and never over the ceiling`() {
        assertEquals(1f, PeakLimiter.gainFor(PeakLimiter.THRESHOLD), 0f)
        var previousOut = 0f
        var level = PeakLimiter.THRESHOLD * 0.9f
        while (level < 8f) {
            val out = level * PeakLimiter.gainFor(level)
            assertTrue("output $out at input $level is over the ceiling", out <= PeakLimiter.CEILING + 1e-5f)
            assertTrue("output fell as input rose at $level", out >= previousOut - 1e-5f)
            previousOut = out
            level *= 1.002f
        }
    }
}
