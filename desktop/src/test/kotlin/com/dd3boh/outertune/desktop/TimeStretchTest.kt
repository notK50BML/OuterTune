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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tempo and pitch, checked by measuring the output rather than by inspecting the code.
 *
 * The whole point of this component is a claim about what comes out - "faster but the same note",
 * "higher but the same length" - and those are claims a test can check directly. So the tone goes in
 * and the frequency and duration come back out, measured, and the assertions are about those.
 */
class TimeStretchTest {

    private val rate = 44_100

    /** One second of a sine, as the pipeline would hand it over: interleaved 16-bit, big-endian. */
    private fun tone(hz: Double, seconds: Double = 1.0, channels: Int = 1): ByteArray {
        val frames = (rate * seconds).toInt()
        val out = ByteArray(frames * channels * 2)
        for (f in 0 until frames) {
            val v = (sin(2.0 * PI * hz * f / rate) * 0.5 * 32767).toInt()
            for (c in 0 until channels) {
                val at = (f * channels + c) * 2
                out[at] = ((v shr 8) and 0xFF).toByte()
                out[at + 1] = (v and 0xFF).toByte()
            }
        }
        return out
    }

    /** Runs audio through in blocks, as the player does - it never arrives all at once. */
    private fun run(stretch: TimeStretch, audio: ByteArray, channels: Int, blockFrames: Int = 1024): ByteArray {
        val blockBytes = blockFrames * channels * 2
        val out = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < audio.size) {
            val take = minOf(blockBytes, audio.size - at)
            val block = audio.copyOfRange(at, at + take)
            out.write(stretch.process(block, take, 16, channels, bigEndian = true, sampleRate = rate))
            at += take
        }
        return out.toByteArray()
    }

    private fun samples(bytes: ByteArray, channels: Int, channel: Int = 0): DoubleArray {
        val frames = bytes.size / (2 * channels)
        return DoubleArray(frames) { f ->
            val at = (f * channels + channel) * 2
            ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort() / 32768.0
        }
    }

    /**
     * The dominant frequency, by picking the strongest bin of a windowed FFT.
     *
     * Refined by interpolating the peak against its neighbours: a bin at 44100/8192 is about 5.4Hz
     * wide, which is far too coarse to tell a semitone apart at low frequencies.
     */
    private fun dominantHz(signal: DoubleArray): Double {
        val n = 8192
        if (signal.size < n) return 0.0
        // Taken from the middle, past the stretcher's first few frames, which are the ones emitted
        // before it has any history to align against.
        val from = (signal.size - n) / 2
        val re = DoubleArray(n) { signal[from + it] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).transform(re, im)

        var peak = 1
        for (i in 2 until n / 2) {
            if (re[i] * re[i] + im[i] * im[i] > re[peak] * re[peak] + im[peak] * im[peak]) peak = i
        }
        fun mag(i: Int) = sqrt(re[i] * re[i] + im[i] * im[i])
        val a = mag(peak - 1)
        val b = mag(peak)
        val c = mag(peak + 1)
        val shift = 0.5 * (a - c) / (a - 2 * b + c).let { if (abs(it) < 1e-12) 1e-12 else it }
        return (peak + shift) * rate / n
    }

    @Test
    fun `untouched audio comes back untouched`() {
        // Not "close enough" - identical. The default path must not run the signal through two
        // interpolators, and the cheapest way to prove it did not is that nothing changed at all.
        val stretch = TimeStretch()
        val audio = tone(440.0, 0.2)
        assertTrue(run(stretch, audio, 1).contentEquals(audio))
    }

    @Test
    fun `faster tempo leaves the note where it was`() {
        val stretch = TimeStretch().apply { tempo = 1.5f }
        val out = samples(run(stretch, tone(440.0, 2.0), 1), 1)
        val hz = dominantHz(out)
        // Half a semitone is about 13Hz here. Anything beyond that is audible as the wrong note.
        assertEquals("tempo change moved the pitch to ${"%.1f".format(hz)}Hz", 440.0, hz, 8.0)
    }

    @Test
    fun `slower tempo leaves the note where it was`() {
        val stretch = TimeStretch().apply { tempo = 0.7f }
        val hz = dominantHz(samples(run(stretch, tone(440.0, 2.0), 1), 1))
        assertEquals("tempo change moved the pitch to ${"%.1f".format(hz)}Hz", 440.0, hz, 8.0)
    }

    @Test
    fun `tempo actually changes the length`() {
        val input = tone(440.0, 2.0)
        val faster = run(TimeStretch().apply { tempo = 1.5f }, input, 1)
        val slower = run(TimeStretch().apply { tempo = 0.75f }, input, 1)

        // Within a few percent: the stretcher works in whole frames and holds back the overlap
        // remainder, so it cannot land exactly on a ratio.
        assertEquals("sped up", input.size / 1.5, faster.size.toDouble(), input.size * 0.05)
        assertEquals("slowed down", input.size / 0.75, slower.size.toDouble(), input.size * 0.05)
    }

    @Test
    fun `an octave up is twice the frequency`() {
        val stretch = TimeStretch().apply { pitchSemitones = 12f }
        val hz = dominantHz(samples(run(stretch, tone(440.0, 2.0), 1), 1))
        assertEquals("expected 880Hz, got ${"%.1f".format(hz)}", 880.0, hz, 15.0)
    }

    @Test
    fun `an octave down is half the frequency`() {
        val stretch = TimeStretch().apply { pitchSemitones = -12f }
        val hz = dominantHz(samples(run(stretch, tone(440.0, 2.0), 1), 1))
        assertEquals("expected 220Hz, got ${"%.1f".format(hz)}", 220.0, hz, 8.0)
    }

    @Test
    fun `a semitone is a semitone`() {
        // The fine end of the control, and the one a coarse implementation gets wrong: 440 to 466 is
        // a 6% change, well inside the slop a sloppy resampler would show.
        val stretch = TimeStretch().apply { pitchSemitones = 1f }
        val hz = dominantHz(samples(run(stretch, tone(440.0, 2.0), 1), 1))
        assertEquals("expected 466.2Hz, got ${"%.1f".format(hz)}", 466.16, hz, 5.0)
    }

    @Test
    fun `pitch shifting does not change the length`() {
        // The part that makes it a pitch shift rather than a speed change. Resampling alone would
        // make an octave up half as long.
        val input = tone(440.0, 2.0)
        val out = run(TimeStretch().apply { pitchSemitones = 12f }, input, 1)
        assertEquals("length changed", input.size.toDouble(), out.size.toDouble(), input.size * 0.06)
    }

    @Test
    fun `tempo and pitch combine independently`() {
        val input = tone(440.0, 2.0)
        val stretch = TimeStretch().apply { tempo = 1.25f; pitchSemitones = 12f }
        val out = run(stretch, input, 1)

        assertEquals("pitch", 880.0, dominantHz(samples(out, 1)), 20.0)
        assertEquals("length", input.size / 1.25, out.size.toDouble(), input.size * 0.06)
    }

    @Test
    fun `stereo channels stay together`() {
        // The failure a per-channel similarity search causes: left and right get nudged to different
        // offsets and the image smears. Identical channels in must stay identical channels out.
        val stretch = TimeStretch().apply { tempo = 1.3f }
        val out = run(stretch, tone(440.0, 1.0, channels = 2), 2)
        val left = samples(out, 2, 0)
        val right = samples(out, 2, 1)

        assertTrue("no output", left.isNotEmpty())
        val worst = left.indices.maxOf { abs(left[it] - right[it]) }
        assertTrue("channels diverged by $worst", worst < 1e-4)
    }

    @Test
    fun `the output does not clip or go non-finite`() {
        val stretch = TimeStretch().apply { tempo = 0.6f; pitchSemitones = -5f }
        // Near full scale, where interpolation overshoot would push past the rail if it were not
        // being clamped.
        val loud = ByteArray(rate * 2) { i ->
            if (i % 2 == 0) {
                ((sin(2.0 * PI * 1000.0 * (i / 2) / rate) * 32000).toInt() shr 8 and 0xFF).toByte()
            } else {
                ((sin(2.0 * PI * 1000.0 * (i / 2) / rate) * 32000).toInt() and 0xFF).toByte()
            }
        }
        val out = samples(run(stretch, loud, 1), 1)
        assertTrue("no output", out.isNotEmpty())
        assertTrue("went non-finite", out.all { it.isFinite() })
        assertTrue("exceeded full scale", out.all { abs(it) <= 1.0 })
    }

    @Test
    fun `silence stays silent`() {
        val stretch = TimeStretch().apply { tempo = 1.4f; pitchSemitones = 3f }
        val out = samples(run(stretch, ByteArray(rate * 2), 1), 1)
        assertTrue("silence became audible", out.all { abs(it) < 1e-6 })
    }

    @Test
    fun `settings stay inside what the algorithm handles`() {
        val stretch = TimeStretch()
        stretch.tempo = 99f
        assertEquals(TimeStretch.MAX_TEMPO, stretch.tempo, 0f)
        stretch.tempo = 0.01f
        assertEquals(TimeStretch.MIN_TEMPO, stretch.tempo, 0f)
        stretch.pitchSemitones = 40f
        assertEquals(TimeStretch.MAX_SEMITONES, stretch.pitchSemitones, 0f)
    }

    @Test
    fun `block size does not change the result`() {
        // The streaming state is the easiest thing here to get wrong, and the symptom would be a
        // click at every block boundary. Two different block sizes must produce the same audio.
        val input = tone(440.0, 1.0)
        val a = samples(run(TimeStretch().apply { tempo = 1.3f }, input, 1, blockFrames = 512), 1)
        val b = samples(run(TimeStretch().apply { tempo = 1.3f }, input, 1, blockFrames = 4096), 1)

        val n = minOf(a.size, b.size)
        assertTrue("no output", n > rate / 2)
        val worst = (0 until n).maxOf { abs(a[it] - b[it]) }
        assertTrue("block size changed the audio by $worst", worst < 1e-3)
    }
}
