/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.dd3boh.outertune.models.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The whole processor against the two complaints: a bass boost and the gain control distorting.
 *
 * Every case is a full-scale tone, because that is where a boost has nowhere to go, and each is
 * judged two ways - the output's peak against the limiter's ceiling, and the energy in its harmonics
 * against the same signal hard-clipped, which is what the boost or the gain used to turn into.
 */
class OutputSafetyTest {

    private val rate = 44_100

    /** One second of a 60Hz tone - a whole number of cycles, so the DFT below needs no window. */
    private val bassHz = 60.0

    private fun run(settings: EqualizerSettings, hz: Double, amplitude: Double, seconds: Int = 2): FloatArray {
        val processor = EqualizerAudioProcessor()
        processor.setSettings(settings)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val out = ArrayList<Float>(rate * seconds)
        val frames = rate / 10
        repeat(seconds * 10) { block ->
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) {
                val n = block * frames + i
                val v = (sin(2 * PI * hz * n / rate) * amplitude * 32767).toInt().toShort()
                input.putShort(v)
                input.putShort(v)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output
            while (output.remaining() >= 4) {
                out.add(output.getShort() / 32768f)
                output.getShort()
            }
        }
        // The last second: past the look-ahead, the filters settling and the limiter's first dip.
        return out.takeLast(rate).toFloatArray()
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

    /** Harmonics two to six against the fundamental, in dB - total harmonic distortion. */
    private fun thdDb(signal: FloatArray, hz: Double): Double {
        val fundamental = magnitudeAt(signal, hz)
        val harmonics = (2..6).sumOf { val m = magnitudeAt(signal, hz * it); m * m }
        return 20.0 * log10(sqrt(harmonics) / fundamental)
    }

    private fun hardClipped(gainDb: Double): FloatArray = FloatArray(rate) {
        val boosted = sin(2 * PI * bassHz * it / rate) * Math.pow(10.0, gainDb / 20.0)
        boosted.coerceIn(-1.0, 1.0).toFloat()
    }

    private val enabled = EqualizerSettings.DEFAULT.copy(enabled = true)

    // ---- the bass boost ---------------------------------------------------------------------------

    @Test
    fun `a full-scale bass note through a 12dB bass boost stays under the ceiling, cleanly`() {
        val out = run(enabled.copy(bassShelfDb = 12f), bassHz, amplitude = 1.0)
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak over the ceiling ${PeakLimiter.CEILING}", peak <= PeakLimiter.CEILING + 1f / 32768f)

        val ours = thdDb(out, bassHz)
        val clipped = thdDb(hardClipped(12.0), bassHz)
        assertTrue("THD ${"%.1f".format(ours)}dB - expected under -60dB", ours < -60.0)
        assertTrue("hard clipping only at ${"%.1f".format(clipped)}dB - the comparison means nothing", clipped > -20.0)
    }

    @Test
    fun `the same boost spread over the bands is just as clean`() {
        val boosted = enabled.withPresetGains(listOf(12f, 12f, 12f, 8f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        val out = run(boosted, bassHz, amplitude = 1.0)
        assertTrue(out.maxOf { abs(it) } <= PeakLimiter.CEILING + 1f / 32768f)
        assertTrue(thdDb(out, bassHz) < -60.0)
    }

    // ---- the gain -------------------------------------------------------------------------------

    @Test
    fun `twelve decibels of makeup gain on a full-scale tone is turned down, not clipped`() {
        // Threshold at full scale and a ratio of one: the compressor is nothing but its makeup
        // gain, which is how it is used as a volume boost.
        val gain = enabled.copy(
            compressor = EqualizerSettings.CompressorSettings(
                enabled = true, thresholdDb = 0f, ratio = 1f, makeupGainDb = 12f,
            ),
        )
        val out = run(gain, bassHz, amplitude = 1.0)
        assertTrue(out.maxOf { abs(it) } <= PeakLimiter.CEILING + 1f / 32768f)
        val ours = thdDb(out, bassHz)
        assertTrue("THD ${"%.1f".format(ours)}dB", ours < -60.0)
        assertTrue(thdDb(hardClipped(12.0), bassHz) > ours + 40.0)
    }

    // ---- the automatic gain ---------------------------------------------------------------------

    @Test
    fun `a boost turns the rest of the music down by the curve's peak`() {
        // A 1kHz tone, untouched by a bass shelf, comes out lower by about the shelf's height: the
        // boost changes the balance, not the level of the loudest part.
        val quiet = 0.25
        val flat = run(enabled, 1_000.0, quiet).maxOf { abs(it) }
        val boosted = run(enabled.copy(bassShelfDb = 12f), 1_000.0, quiet).maxOf { abs(it) }
        val changeDb = 20 * log10(boosted / flat)
        assertTrue("1kHz moved ${"%.1f".format(changeDb)}dB with a 12dB bass shelf", changeDb in -12.6..-10.5)
    }

    @Test
    fun `a curve that only cuts is still turned back up`() {
        val quiet = 0.25
        val flat = run(enabled, 1_000.0, quiet).maxOf { abs(it) }
        val cut = run(enabled.copy(bassShelfDb = -12f), 1_000.0, quiet).maxOf { abs(it) }
        assertTrue("a bass cut did not turn the mids up", 20 * log10(cut / flat) > 1.0)
    }

    @Test
    fun `the rule - cuts make up, boosts turn down, and a mix never goes over`() {
        // Cuts only: the loudness taken away comes back, capped.
        assertEquals(4f, EqAutoGain.gainDb(peakDb = -1f, meanPowerDb = -4f, cutMakeup = true), 0.001f)
        assertEquals(EqAutoGain.MAX_MAKEUP_DB, EqAutoGain.gainDb(-20f, -30f, true), 0.001f)
        assertEquals(0f, EqAutoGain.gainDb(peakDb = 0f, meanPowerDb = -4f, cutMakeup = false), 0.001f)
        // Boosts: down by the peak, whatever the average.
        assertEquals(-9f, EqAutoGain.gainDb(peakDb = 9f, meanPowerDb = 3f, cutMakeup = true), 0.001f)
        // Mixed - mostly cut, one small boost: not the +5 the cuts alone would earn, and not
        // anything that leaves the boosted frequency above where it arrived.
        val mixed = EqAutoGain.gainDb(peakDb = 2f, meanPowerDb = -5f, cutMakeup = true)
        assertTrue("mixed curve gain $mixed", mixed + 2f <= 0f)
        // Normalisation is headroom already paid: a boost inside it costs nothing more.
        assertEquals(-8f, EqAutoGain.gainDb(peakDb = 6f, meanPowerDb = 2f, cutMakeup = true, normalisationDb = 8f), 0.001f)
        assertEquals(-5f, EqAutoGain.gainDb(peakDb = -1f, meanPowerDb = -3f, cutMakeup = true, normalisationDb = 8f), 0.001f)
    }

    @Test
    fun `the peak is measured off the summed curve, and a curve of cuts reads below zero`() {
        fun cascade(s: EqualizerSettings) = s.allFilters().map { BiquadCoefficients.forBand(it, rate) }.toTypedArray()
        // Peaking filters return to 0dB at the very ends, so a curve cut everywhere still reads at
        // about zero there - but never over it, and so it counts as cuts and is made up.
        val cuts = cascade(enabled.withPresetGains(List(12) { -6f }))
        val cutsPeak = BiquadCoefficients.peakResponseDb(cuts, rate)
        assertTrue("cut curve peaked at $cutsPeak", cutsPeak <= EqAutoGain.BOOST_EPSILON_DB)
        val cutsMean = BiquadCoefficients.meanPowerGainDb(cuts, rate)
        assertTrue(EqAutoGain.gainDb(cutsPeak, cutsMean, cutMakeup = true) > 3f)
        // Two neighbours at +6 overlap into more than either.
        val pair = cascade(enabled.withPresetGains(listOf(0f, 0f, 0f, 0f, 6f, 6f, 0f, 0f, 0f, 0f, 0f, 0f)))
        assertTrue(BiquadCoefficients.peakResponseDb(pair, rate) > 6.5f)
    }

    // ---- the limiter on its own -----------------------------------------------------------------

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
        // Just into the knee it is touched by a sliver, not pinned.
        val justIn = PeakLimiter.THRESHOLD * 1.06f // half a decibel in
        assertTrue(20 * log10(PeakLimiter.gainFor(justIn)) > -0.1f)
    }
}
