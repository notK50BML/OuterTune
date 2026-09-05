/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equaliser, measured rather than listened to.
 *
 * A filter that is subtly wrong still produces sound - usually sound that is merely a bit off, which
 * is indistinguishable from the setting having worked. So every test here puts a tone of known
 * frequency through and measures how much came out, in decibels, against what was asked for.
 */
class EqualizerTest {

    private val rate = 44_100
    private val bits = 16
    private val peak = (1 shl (bits - 1)).toFloat()

    /** [seconds] of a sine at [hz] as interleaved big-endian PCM. */
    private fun tone(hz: Double, channels: Int = 1, frames: Int = 44_100, amplitude: Double = 0.3): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var pos = 0
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * hz * f / rate) * amplitude * peak).toInt()
            for (c in 0 until channels) {
                out[pos] = ((v shr 8) and 0xFF).toByte()
                out[pos + 1] = (v and 0xFF).toByte()
                pos += 2
            }
        }
        return out
    }

    /** RMS of one channel, skipping the start so the filter's ring-in is not measured. */
    private fun rms(bytes: ByteArray, channels: Int = 1, channel: Int = 0, skipFrames: Int = 8_000): Double {
        var sum = 0.0
        var n = 0
        val frames = bytes.size / (2 * channels)
        for (f in skipFrames until frames) {
            val at = (f * channels + channel) * 2
            val v = ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort().toInt()
            sum += (v / peak).toDouble() * (v / peak)
            n++
        }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }

    /** How much the equaliser changed a tone at [hz], in dB. */
    private fun gainAt(hz: Double, eq: Equalizer, channels: Int = 1): Double {
        val original = tone(hz, channels)
        val filtered = original.copyOf()
        eq.reset()
        eq.process(filtered, filtered.size, bits, channels, bigEndian = true, sampleRate = rate)
        return 20.0 * log10(rms(filtered, channels) / rms(original, channels))
    }

    private fun eqWith(vararg gains: Pair<Float, Float>): Equalizer {
        val eq = Equalizer()
        eq.enabled = true
        eq.setBands(Equalizer.DEFAULT_BANDS.map { band ->
            gains.firstOrNull { it.first == band.freqHz }?.let { band.copy(gainDb = it.second) } ?: band
        })
        return eq
    }

    @Test
    fun `a boosted band actually boosts, by about the amount asked for`() {
        val eq = eqWith(1000f to 6f)
        val gain = gainAt(1000.0, eq)
        assertEquals("asked for +6dB at 1kHz, measured ${"%.2f".format(gain)}", 6.0, gain, 1.0)
    }

    @Test
    fun `a cut band cuts`() {
        val eq = eqWith(1000f to -6f)
        val gain = gainAt(1000.0, eq)
        assertEquals("asked for -6dB at 1kHz, measured ${"%.2f".format(gain)}", -6.0, gain, 1.0)
    }

    @Test
    fun `a band leaves distant frequencies alone`() {
        // The point of a peaking filter is that it is local. One that quietly lifts everything is a
        // volume control with extra steps, and would pass the test above.
        val eq = eqWith(1000f to 12f)
        val faraway = gainAt(62.0, eq)
        assertTrue("62Hz moved by ${"%.2f".format(faraway)}dB from a 1kHz band", abs(faraway) < 1.5)
    }

    @Test
    fun `flat bands change nothing at all`() {
        val eq = eqWith()
        val original = tone(440.0)
        val filtered = original.copyOf()
        eq.process(filtered, filtered.size, bits, 1, bigEndian = true, sampleRate = rate)
        assertTrue("a flat equaliser altered the samples", original.contentEquals(filtered))
    }

    @Test
    fun `disabled changes nothing even with gains set`() {
        val eq = eqWith(1000f to 12f)
        eq.enabled = false
        val original = tone(1000.0)
        val filtered = original.copyOf()
        eq.process(filtered, filtered.size, bits, 1, bigEndian = true, sampleRate = rate)
        assertTrue(original.contentEquals(filtered))
    }

    @Test
    fun `stereo channels are filtered independently, not through one shared filter`() {
        // The easiest thing to get wrong here. A biquad remembers two samples of history, so one
        // filter run across an interleaved stream feeds left's history into right's output and back
        // again - a comb filter smeared across the stereo image, not a subtle degradation.
        //
        // Detected by filtering a stereo signal where only one channel carries a tone: with shared
        // state, the silent channel comes out no longer silent.
        val frames = 44_100
        val bytes = ByteArray(frames * 2 * 2)
        var pos = 0
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * 1000.0 * f / rate) * 0.3 * peak).toInt()
            bytes[pos] = ((v shr 8) and 0xFF).toByte()
            bytes[pos + 1] = (v and 0xFF).toByte()
            bytes[pos + 2] = 0
            bytes[pos + 3] = 0
            pos += 4
        }

        val eq = eqWith(1000f to 12f)
        eq.process(bytes, bytes.size, bits, 2, bigEndian = true, sampleRate = rate)

        val right = rms(bytes, channels = 2, channel = 1)
        assertTrue("the silent channel picked up ${"%.6f".format(right)} - filter state is shared", right < 1e-4)
        assertTrue("the loud channel was not filtered", rms(bytes, channels = 2, channel = 0) > 0.1)
    }

    @Test
    fun `both channels of a stereo signal are boosted equally`() {
        val eq = eqWith(1000f to 6f)
        val bytes = tone(1000.0, channels = 2)
        eq.process(bytes, bytes.size, bits, 2, bigEndian = true, sampleRate = rate)
        val left = rms(bytes, channels = 2, channel = 0)
        val right = rms(bytes, channels = 2, channel = 1)
        assertEquals("channels came out at different levels", left, right, left * 0.02)
    }

    @Test
    fun `byte order is honoured on the way out as well as in`() {
        // Reading big-endian and writing little-endian is not an error, it is noise - and it would
        // pass any test that only checked the samples had changed.
        val eq = eqWith(1000f to 6f)
        val bytes = tone(1000.0, frames = 4_000)
        eq.process(bytes, bytes.size, bits, 1, bigEndian = true, sampleRate = rate)
        // Read back the way it was written, the result is a clean boosted tone: successive samples
        // stay close together. Read back the wrong way, neighbouring samples jump the full range.
        var jumps = 0
        for (f in 1_000 until 3_000) {
            val a = ((bytes[f * 2].toInt() shl 8) or (bytes[f * 2 + 1].toInt() and 0xFF)).toShort().toInt()
            val b = ((bytes[(f - 1) * 2].toInt() shl 8) or (bytes[(f - 1) * 2 + 1].toInt() and 0xFF)).toShort().toInt()
            if (abs(a - b) > 8_000) jumps++
        }
        assertEquals("output is not a continuous waveform - byte order is inconsistent", 0, jumps)
    }

    @Test
    fun `a loud signal with a big boost clips rather than wrapping`() {
        // An integer that overflows wraps from loudest positive to loudest negative, which is heard
        // as a violent crack rather than as distortion. Clamping is the difference between "this is
        // too loud" and "something is broken".
        val eq = eqWith(1000f to 15f)
        val bytes = tone(1000.0, frames = 20_000, amplitude = 0.95)
        eq.process(bytes, bytes.size, bits, 1, bigEndian = true, sampleRate = rate)

        var wraps = 0
        for (f in 5_000 until 19_999) {
            val a = ((bytes[f * 2].toInt() shl 8) or (bytes[f * 2 + 1].toInt() and 0xFF)).toShort().toInt()
            val b = ((bytes[(f + 1) * 2].toInt() shl 8) or (bytes[(f + 1) * 2 + 1].toInt() and 0xFF)).toShort().toInt()
            // A wrap is a full-scale sign flip between neighbouring samples.
            if (a > 30_000 && b < -30_000) wraps++
            if (a < -30_000 && b > 30_000) wraps++
        }
        assertEquals("output wrapped instead of clipping", 0, wraps)
    }

    @Test
    fun `a band at or above Nyquist is ignored instead of exploding`() {
        // Real case: the 16kHz band on 32kHz audio sits exactly at Nyquist, where the coefficients
        // are not finite. Silently passing through is right; NaN across the whole output is not.
        val eq = eqWith(16000f to 10f)
        val bytes = tone(4000.0, frames = 8_000)
        eq.process(bytes, bytes.size, bits, 1, bigEndian = true, sampleRate = 32_000)
        assertTrue("output went silent or NaN", rms(bytes, skipFrames = 2_000) > 0.05)
    }

    @Test
    fun `changing a gain takes effect`() {
        val eq = eqWith()
        assertTrue(abs(gainAt(1000.0, eq)) < 0.5)
        eq.setGain(Equalizer.DEFAULT_FREQUENCIES.indexOf(1000f), 8f)
        assertTrue("setGain had no effect", gainAt(1000.0, eq) > 6.0)
    }

    @Test
    fun `the presets are the right shape and actually differ`() {
        Equalizer.PRESETS.forEach { (name, gains) ->
            assertEquals("$name has the wrong number of bands", Equalizer.DEFAULT_FREQUENCIES.size, gains.size)
        }
        assertNotEquals(Equalizer.PRESETS["Flat"], Equalizer.PRESETS["Bass boost"])
        assertTrue("bass boost should lift the low bands", Equalizer.PRESETS["Bass boost"]!!.first() > 0f)
        assertTrue("treble boost should lift the high bands", Equalizer.PRESETS["Treble boost"]!!.last() > 0f)
    }

    @Test
    fun `frequencies match the Android app so presets would mean the same thing`() {
        // Pinned deliberately. These two implementations do not share a module yet, and the whole
        // reason a preset could ever move between them is that the band centres agree.
        assertEquals(
            listOf(16f, 31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 16000f),
            Equalizer.DEFAULT_FREQUENCIES,
        )
    }
}
