/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The response curve, checked against the filter it claims to describe.
 *
 * A graph is the one part of an equaliser that cannot be wrong quietly - it is the thing people read
 * to find out what the sliders did. So the curve is not compared to the slider positions but to the
 * measured behaviour of the actual filter bank, which is what it is supposed to be a picture of.
 */
class EqResponseTest {

    private val rate = 44_100

    /** Where on the curve a given frequency lands. */
    private fun indexOf(hz: Double, points: Int = 160): Int {
        val fraction = ln(hz / EqResponse.MIN_HZ) / ln(EqResponse.MAX_HZ / EqResponse.MIN_HZ)
        return (fraction * (points - 1)).toInt().coerceIn(0, points - 1)
    }

    private fun bandsWith(vararg gains: Pair<Float, Float>): List<EqBand> =
        Equalizer.DEFAULT_BANDS.map { band ->
            gains.firstOrNull { it.first == band.freqHz }?.let { band.copy(gainDb = it.second) } ?: band
        }

    @Test
    fun `a flat bank is a flat line`() {
        val curve = EqResponse.curve(Equalizer.DEFAULT_BANDS, rate)
        assertTrue("flat bank was not flat: max ${curve.maxOrNull()}", curve.all { abs(it) < 0.01f })
    }

    @Test
    fun `a boosted band peaks at its own frequency`() {
        val curve = EqResponse.curve(bandsWith(1000f to 8f), rate)
        val peak = curve.indices.maxByOrNull { curve[it] }!!
        val expected = indexOf(1000.0)
        assertTrue("peak at index $peak, expected near $expected", abs(peak - expected) <= 4)
        assertEquals("peak height", 8.0, curve[peak].toDouble(), 0.6)
    }

    @Test
    fun `a cut band dips`() {
        val curve = EqResponse.curve(bandsWith(1000f to -8f), rate)
        val trough = curve.indices.minByOrNull { curve[it] }!!
        assertEquals(-8.0, curve[trough].toDouble(), 0.6)
    }

    @Test
    fun `the curve matches what the filter actually does`() {
        // The check that makes this a response curve rather than a drawing. A tone is put through
        // the real Equalizer and measured; the graph must agree with the measurement.
        val hz = 1000.0
        val eq = Equalizer()
        eq.enabled = true
        eq.setBands(bandsWith(1000f to 6f))

        val measured = measureGainDb(eq, hz)
        val fromCurve = EqResponse.curve(bandsWith(1000f to 6f), rate)[indexOf(hz)]

        assertEquals(
            "graph says ${"%.2f".format(fromCurve)}dB, filter does ${"%.2f".format(measured)}dB",
            measured, fromCurve.toDouble(), 1.0,
        )
    }

    @Test
    fun `overlapping bands add, as the ear hears them`() {
        // The specific thing a graph drawn from slider heights gets wrong. Two bands an octave
        // apart at +6dB do not make two separate +6dB humps with a valley between - they overlap,
        // and the trough between them still sits well above either band's own gain. A picture drawn
        // from the slider heights would show two humps and a dip back to zero, which describes a
        // filter nobody built.
        //
        // The measured value at Q=1 is about 7.9dB, not the 12 that summing the peaks would suggest
        // - each band is well off its centre by then. What matters is that it is comfortably above
        // 6, which is what proves the two are adding rather than being drawn side by side.
        val curve = EqResponse.curve(bandsWith(1000f to 6f, 2000f to 6f), rate)
        val between = curve[indexOf(1414.0)]
        assertTrue("between two +6dB bands the curve reads ${between}dB, expected above 7", between > 7f)
        assertTrue("and it should not exceed their sum", between < 12f)
    }

    @Test
    fun `a band leaves distant frequencies alone`() {
        val curve = EqResponse.curve(bandsWith(1000f to 12f), rate)
        assertTrue("62Hz moved by ${curve[indexOf(62.0)]}dB", abs(curve[indexOf(62.0)]) < 1.5f)
        assertTrue("16kHz moved by ${curve[indexOf(16000.0)]}dB", abs(curve[indexOf(16000.0)]) < 1.5f)
    }

    @Test
    fun `the axis is logarithmic`() {
        // Each octave should occupy the same width, which is what makes the bass readable at all.
        val octaves = listOf(100.0, 200.0, 400.0, 800.0).map { indexOf(it) }
        val steps = octaves.zipWithNext { a, b -> b - a }
        assertTrue("octaves are unevenly spaced: $steps", steps.max() - steps.min() <= 1)
    }

    @Test
    fun `frequencyAt is the inverse of the position`() {
        assertEquals(EqResponse.MIN_HZ, EqResponse.frequencyAt(0f), 0.01)
        assertEquals(EqResponse.MAX_HZ, EqResponse.frequencyAt(1f), 1.0)
        // Halfway along a log axis from 20 to 20000 is the geometric mean, not the arithmetic one.
        assertEquals(632.0, EqResponse.frequencyAt(0.5f), 5.0)
    }

    @Test
    fun `a band above Nyquist does not distort the curve`() {
        // 16kHz sits exactly at Nyquist on 32kHz audio, where the coefficients are not finite.
        val curve = EqResponse.curve(bandsWith(16000f to 10f), 32_000)
        assertTrue("curve went non-finite", curve.all { it.isFinite() })
    }

    /** Puts a tone through the real filter and measures how much louder it came out. */
    private fun measureGainDb(eq: Equalizer, hz: Double): Double {
        val frames = 44_100
        val peak = 32_768f
        fun tone(): ByteArray {
            val out = ByteArray(frames * 2)
            for (f in 0 until frames) {
                val v = (kotlin.math.sin(2.0 * Math.PI * hz * f / rate) * 0.3 * peak).toInt()
                out[f * 2] = ((v shr 8) and 0xFF).toByte()
                out[f * 2 + 1] = (v and 0xFF).toByte()
            }
            return out
        }

        fun rms(bytes: ByteArray): Double {
            var sum = 0.0
            var n = 0
            for (f in 8_000 until frames) {
                val v = ((bytes[f * 2].toInt() shl 8) or (bytes[f * 2 + 1].toInt() and 0xFF)).toShort().toInt()
                sum += (v / peak).toDouble() * (v / peak)
                n++
            }
            return kotlin.math.sqrt(sum / n)
        }

        val original = tone()
        val filtered = original.copyOf()
        eq.reset()
        eq.process(filtered, filtered.size, 16, 1, bigEndian = true, sampleRate = rate)
        return 20.0 * kotlin.math.log10(rms(filtered) / rms(original))
    }
}
