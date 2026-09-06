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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The compressor, checked against what a compressor is supposed to do.
 *
 * The claims are all about levels - "a signal above the threshold comes out quieter by the ratio",
 * "one below it is untouched" - so the tests measure levels rather than inspecting state.
 */
class CompressorTest {

    private val rate = 44_100

    /** A steady tone at a given level in dBFS, interleaved 16-bit big-endian. */
    private fun tone(db: Float, seconds: Double = 1.0, channels: Int = 1, hz: Double = 220.0): ByteArray {
        val amplitude = Math.pow(10.0, db / 20.0)
        val frames = (rate * seconds).toInt()
        val out = ByteArray(frames * channels * 2)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * hz * f / rate) * amplitude * 32767).toInt()
            for (c in 0 until channels) {
                val at = (f * channels + c) * 2
                out[at] = ((v shr 8) and 0xFF).toByte()
                out[at + 1] = (v and 0xFF).toByte()
            }
        }
        return out
    }

    /** Level of the back half, in dBFS - past the attack, where the envelope has settled. */
    private fun settledDb(bytes: ByteArray, channels: Int = 1, channel: Int = 0): Double {
        val frames = bytes.size / (2 * channels)
        var sum = 0.0
        var n = 0
        for (f in frames / 2 until frames) {
            val at = (f * channels + channel) * 2
            val v = ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort() / 32768.0
            sum += v * v
            n++
        }
        // Referenced to a full-scale sine rather than to full scale, so "0dB" means a sine at the
        // rails and the numbers line up with the level the tone was asked for.
        return 20 * log10(sqrt(sum / n) * sqrt(2.0))
    }

    private fun compressor(block: Compressor.() -> Unit) = Compressor().apply {
        enabled = true
        block()
    }

    private fun run(c: Compressor, audio: ByteArray, channels: Int = 1): ByteArray {
        val copy = audio.copyOf()
        c.process(copy, copy.size, 16, channels, bigEndian = true, sampleRate = rate)
        return copy
    }

    @Test
    fun `disabled changes nothing`() {
        val audio = tone(-6f)
        val c = Compressor().apply { enabled = false; ratio = 20f; thresholdDb = -40f }
        assertTrue(run(c, audio).contentEquals(audio))
    }

    @Test
    fun `a signal below the threshold is left alone`() {
        val c = compressor { thresholdDb = -12f; ratio = 4f }
        val out = settledDb(run(c, tone(-24f)))
        assertEquals("quiet signal was altered", -24.0, out, 0.4)
    }

    @Test
    fun `a signal above the threshold comes down by the ratio`() {
        // 12dB over a -30dB threshold at 4:1 should end up 3dB over, so -27dB.
        val c = compressor { thresholdDb = -30f; ratio = 4f; attackMs = 5f; releaseMs = 50f }
        val out = settledDb(run(c, tone(-18f)))
        assertEquals("expected about -27dBFS", -27.0, out, 1.0)
    }

    @Test
    fun `a higher ratio compresses harder`() {
        val gentle = settledDb(run(compressor { thresholdDb = -30f; ratio = 2f; attackMs = 5f }, tone(-18f)))
        val hard = settledDb(run(compressor { thresholdDb = -30f; ratio = 10f; attackMs = 5f }, tone(-18f)))
        assertTrue("10:1 ($hard) was not below 2:1 ($gentle)", hard < gentle - 2)
    }

    @Test
    fun `a ratio of one is no compression`() {
        val c = compressor { thresholdDb = -40f; ratio = 1f; attackMs = 5f }
        assertEquals(-18.0, settledDb(run(c, tone(-18f))), 0.4)
    }

    @Test
    fun `makeup gain lifts the whole signal`() {
        val c = compressor { thresholdDb = 0f; ratio = 1f; makeupGainDb = 6f }
        // Threshold at 0dBFS means nothing is compressed, so this measures makeup gain alone.
        assertEquals(-24.0 + 6.0, settledDb(run(c, tone(-24f))), 0.5)
    }

    @Test
    fun `attack time decides how fast it clamps down`() {
        // The distinguishing behaviour: a slow attack lets the first moments of a loud passage
        // through at full level, which is what "punch" means and what a fast attack removes.
        val loud = tone(-6f, seconds = 0.05)
        fun firstMs(attack: Float): Double {
            val out = run(compressor { thresholdDb = -30f; ratio = 8f; attackMs = attack }, loud)
            val frames = rate / 1000 // one millisecond
            var peak = 0.0
            for (f in 0 until frames) {
                val v = ((out[f * 2].toInt() shl 8) or (out[f * 2 + 1].toInt() and 0xFF)).toShort() / 32768.0
                peak = maxOf(peak, abs(v))
            }
            return peak
        }
        assertTrue("a 100ms attack did not pass more than a 0.2ms one", firstMs(100f) > firstMs(0.2f) * 1.5)
    }

    @Test
    fun `release time decides how fast it lets go`() {
        // Loud then quiet. With a slow release the compressor is still holding the signal down when
        // the quiet part arrives, so the quiet part comes out quieter.
        val loud = tone(-6f, seconds = 0.3)
        val quiet = tone(-30f, seconds = 0.3)
        fun afterLoud(release: Float): Double {
            val c = compressor { thresholdDb = -24f; ratio = 8f; attackMs = 1f; releaseMs = release }
            run(c, loud)
            return settledDb(run(c, quiet))
        }
        val fast = afterLoud(10f)
        val slow = afterLoud(2000f)
        assertTrue("slow release ($slow) did not hold the level down below fast ($fast)", slow < fast - 1)
    }

    @Test
    fun `channels compress independently`() {
        // A shared envelope would duck the quiet channel whenever the loud one peaked. Here one
        // channel is loud and the other quiet; the quiet one must come out untouched.
        val frames = rate
        val audio = ByteArray(frames * 2 * 2)
        for (f in 0 until frames) {
            val loud = (sin(2.0 * PI * 220.0 * f / rate) * 0.5 * 32767).toInt()
            val quiet = (sin(2.0 * PI * 220.0 * f / rate) * 0.02 * 32767).toInt()
            audio[f * 4] = ((loud shr 8) and 0xFF).toByte()
            audio[f * 4 + 1] = (loud and 0xFF).toByte()
            audio[f * 4 + 2] = ((quiet shr 8) and 0xFF).toByte()
            audio[f * 4 + 3] = (quiet and 0xFF).toByte()
        }
        val out = run(compressor { thresholdDb = -20f; ratio = 8f; attackMs = 2f }, audio, channels = 2)

        val right = settledDb(out, channels = 2, channel = 1)
        // settledDb already converts RMS back to sine amplitude, so the expected value is the
        // amplitude itself: 20*log10(0.02) is -34dB, well under the -20dB threshold.
        assertEquals("the quiet channel was ducked", 20 * log10(0.02), right, 0.6)
        assertTrue("the loud channel was not compressed", settledDb(out, 2, 0) < -6.0)
    }

    @Test
    fun `the reduction meter reports what is happening`() {
        val c = compressor { thresholdDb = -30f; ratio = 4f; attackMs = 2f }
        run(c, tone(-6f))
        assertTrue("meter read ${c.currentReductionDb}, expected real reduction", c.currentReductionDb < -8f)

        val quiet = compressor { thresholdDb = -6f; ratio = 4f }
        run(quiet, tone(-40f))
        assertEquals("meter reported reduction on a quiet signal", 0f, quiet.currentReductionDb, 0.01f)
    }

    @Test
    fun `output never clips or goes non-finite`() {
        val c = compressor { thresholdDb = -40f; ratio = 1f; makeupGainDb = 24f }
        val out = run(c, tone(-1f))
        val frames = out.size / 2
        for (f in 0 until frames) {
            val v = ((out[f * 2].toInt() shl 8) or (out[f * 2 + 1].toInt() and 0xFF)).toShort()
            assertTrue("sample $f wrapped to $v", v.toInt() in -32768..32767)
        }
        // Heavy makeup on an already-loud signal should flat-top at the rail, not wrap round to the
        // opposite one - which would be heard as a buzz rather than as distortion.
        assertTrue("expected the signal to reach the rails", settledDb(out) > -4.0)
    }

    @Test
    fun `settings stay inside their ranges`() {
        val c = Compressor()
        c.ratio = 500f
        assertEquals(Compressor.MAX_RATIO, c.ratio, 0f)
        c.ratio = 0.1f
        assertEquals(Compressor.MIN_RATIO, c.ratio, 0f)
        c.attackMs = 0f
        assertEquals(Compressor.MIN_ATTACK_MS, c.attackMs, 0f)
        c.thresholdDb = 20f
        assertEquals(Compressor.MAX_THRESHOLD_DB, c.thresholdDb, 0f)
    }

    @Test
    fun `silence stays silent`() {
        val c = compressor { thresholdDb = -50f; ratio = 10f; makeupGainDb = 12f }
        val out = run(c, ByteArray(rate * 2))
        assertTrue("silence became audible", out.all { it.toInt() == 0 })
    }
}
