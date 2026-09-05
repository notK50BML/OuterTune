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
 * The signal chain behind the visualiser, checked against signals whose answer is known in advance.
 *
 * This is worth testing precisely because it cannot be eyeballed. An FFT with a transposed index, a
 * sign-extension that never happens, a byte order taken the wrong way round - none of them fail.
 * They all produce bars that move plausibly in time with the music, and the only way to notice is to
 * already know what the right answer was.
 */
class AudioSpectrumTest {

    // ---- FFT ------------------------------------------------------------------------------

    /** Magnitude of each bin, which is what a spectrum actually reads. */
    private fun magnitudes(samples: DoubleArray): DoubleArray {
        val re = samples.copyOf()
        val im = DoubleArray(samples.size)
        Fft(samples.size).transform(re, im)
        return DoubleArray(samples.size / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    @Test
    fun `a pure tone lands in exactly one bin`() {
        // 8 cycles across 256 samples is bin 8 by construction, with no leakage to measure against -
        // an off-by-one anywhere in the butterflies or the bit reversal moves this.
        val n = 256
        val bin = 8
        val samples = DoubleArray(n) { sin(2.0 * PI * bin * it / n) }

        val mags = magnitudes(samples)
        val loudest = mags.indices.maxByOrNull { mags[it] }!!
        assertEquals(bin, loudest)

        val neighbours = mags.filterIndexed { i, _ -> i != bin }.max()
        assertTrue("energy leaked into other bins: $neighbours", neighbours < mags[bin] * 1e-6)
    }

    @Test
    fun `two tones appear as two peaks`() {
        // A transform that merely produces large numbers in roughly the right place would pass the
        // single-tone test. Superposition is what says it is actually a Fourier transform.
        val n = 512
        val samples = DoubleArray(n) { sin(2.0 * PI * 12 * it / n) + 0.5 * sin(2.0 * PI * 61 * it / n) }
        val mags = magnitudes(samples)

        val peaks = mags.indices.sortedByDescending { mags[it] }.take(2).sorted()
        assertEquals(listOf(12, 61), peaks)
        // And in the right proportion: the second tone is half the amplitude of the first.
        assertEquals(0.5, mags[61] / mags[12], 0.01)
    }

    @Test
    fun `silence stays silent`() {
        val mags = magnitudes(DoubleArray(128))
        assertTrue("silence produced energy: ${mags.max()}", mags.all { it < 1e-12 })
    }

    @Test
    fun `a constant signal is entirely DC`() {
        // A DC offset belongs in bin 0 and nowhere else. If it smears upward, every bar sits on a
        // pedestal that has nothing to do with the music.
        val mags = magnitudes(DoubleArray(64) { 1.0 })
        assertTrue(mags[0] > 63.0)
        assertTrue("DC leaked upward: ${mags.drop(1).max()}", mags.drop(1).all { it < 1e-9 })
    }

    @Test
    fun `a non-power-of-two size is refused rather than silently wrong`() {
        val e = runCatching { Fft(100) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
    }

    // ---- PCM ------------------------------------------------------------------------------

    /** One 16-bit sample, written in the order asked for. */
    private fun bytes16(vararg values: Int, bigEndian: Boolean): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            val hi = ((v shr 8) and 0xFF).toByte()
            val lo = (v and 0xFF).toByte()
            if (bigEndian) { out[i * 2] = hi; out[i * 2 + 1] = lo }
            else { out[i * 2] = lo; out[i * 2 + 1] = hi }
        }
        return out
    }

    @Test
    fun `byte order is honoured in both directions`() {
        // The decoder in use here hands back big-endian samples, which is the opposite of what most
        // examples assume. Reading it the wrong way round does not fail - it produces plausible
        // noise, which on this project already cost a session when a WAV played as static.
        val out = DoubleArray(4)

        val big = bytes16(0x4000, bigEndian = true)
        assertEquals(1, Pcm.toMono(big, 0, big.size, 16, 1, bigEndian = true, out = out))
        assertEquals(0.5, out[0], 1e-4)

        val little = bytes16(0x4000, bigEndian = false)
        assertEquals(1, Pcm.toMono(little, 0, little.size, 16, 1, bigEndian = false, out = out))
        assertEquals(0.5, out[0], 1e-4)

        // And reading big-endian bytes as little-endian must not accidentally agree.
        Pcm.toMono(big, 0, big.size, 16, 1, bigEndian = false, out = out)
        assertTrue("byte order made no difference - the test cannot detect the bug", abs(out[0] - 0.5) > 0.01)
    }

    @Test
    fun `negative samples stay negative`() {
        // Without sign extension a negative sample reads as a large positive one, which rectifies
        // the waveform - and a rectified sine reads as twice its real frequency.
        val out = DoubleArray(4)
        val data = bytes16(0x8000, bigEndian = true)
        Pcm.toMono(data, 0, data.size, 16, 1, bigEndian = true, out = out)
        assertEquals(-1.0, out[0], 1e-6)
    }

    @Test
    fun `channels are averaged, not just the left one taken`() {
        // A track with the bass panned hard to one side would otherwise show a spectrum nobody is
        // hearing.
        val out = DoubleArray(4)
        val stereo = bytes16(0x4000, 0x0000, bigEndian = true)
        assertEquals(1, Pcm.toMono(stereo, 0, stereo.size, 16, 2, bigEndian = true, out = out))
        assertEquals(0.25, out[0], 1e-4)
    }

    @Test
    fun `a partial frame at the end is not half-read`() {
        // The last block of a track is rarely a whole number of frames. Reading past it would walk
        // off the array; reading it as a whole frame would invent a sample.
        val out = DoubleArray(8)
        val threeBytes = byteArrayOf(0x40, 0x00, 0x40)
        assertEquals(1, Pcm.toMono(threeBytes, 0, threeBytes.size, 16, 1, bigEndian = true, out = out))
    }

    @Test
    fun `it never writes more than the destination holds`() {
        val out = DoubleArray(2)
        val many = ByteArray(64)
        assertEquals(2, Pcm.toMono(many, 0, many.size, 16, 1, bigEndian = true, out = out))
    }

    // ---- Bands ----------------------------------------------------------------------------

    /** [count] samples of a sine at [hz]. */
    private fun tone(hz: Double, sampleRate: Int, count: Int) =
        DoubleArray(count) { sin(2.0 * PI * hz * it / sampleRate) }

    @Test
    fun `a low tone lights a low band and a high tone a high one`() {
        val rate = 44_100
        val analyzer = SpectrumAnalyzer(rate, bands = 16)

        val low = analyzer.analyze(tone(80.0, rate, 1024), 1024).copyOf()
        val lowPeak = low.indices.maxByOrNull { low[it] }!!

        analyzer.reset()
        val high = analyzer.analyze(tone(8000.0, rate, 1024), 1024).copyOf()
        val highPeak = high.indices.maxByOrNull { high[it] }!!

        assertTrue("80Hz peaked at band $lowPeak of 16", lowPeak <= 3)
        assertTrue("8kHz peaked at band $highPeak of 16", highPeak >= 11)
    }

    @Test
    fun `the bands are spaced by octave, not by hertz`() {
        // The whole reason for log spacing: on a linear scale almost every audible octave is
        // crammed into the top few bars and the bass has nowhere to move. Doubling the frequency
        // should move the peak by a roughly constant number of bands.
        val rate = 44_100
        val analyzer = SpectrumAnalyzer(rate, bands = 28)
        val peaks = listOf(125.0, 250.0, 500.0, 1000.0, 2000.0).map { hz ->
            analyzer.reset()
            val bars = analyzer.analyze(tone(hz, rate, 1024), 1024)
            bars.indices.maxByOrNull { bars[it] }!!
        }

        val steps = peaks.zipWithNext { a, b -> b - a }
        assertTrue("peaks did not rise with frequency: $peaks", steps.all { it > 0 })
        assertTrue("octave spacing was uneven: $steps", steps.max() - steps.min() <= 2)
    }

    @Test
    fun `silence reads as zero across every band`() {
        val analyzer = SpectrumAnalyzer(44_100, bands = 12)
        val bars = analyzer.analyze(DoubleArray(1024), 1024)
        assertTrue("silence lit a bar: ${bars.toList()}", bars.all { it < 0.01f })
    }

    @Test
    fun `bars rise at once and fall gradually`() {
        // Fast attack, slow release. A bar that tracks the signal in both directions flickers,
        // because band energy is bursty; one that falls slowly leaves something to look at.
        val rate = 44_100
        val analyzer = SpectrumAnalyzer(rate, bands = 16)
        val loud = analyzer.analyze(tone(1000.0, rate, 1024), 1024)
        val peakBand = loud.indices.maxByOrNull { loud[it] }!!
        val peakLevel = loud[peakBand]
        assertTrue("a loud tone should reach a high level, got $peakLevel", peakLevel > 0.5f)

        // Now silence. It must come down, but not to nothing in one frame.
        val afterOne = analyzer.analyze(DoubleArray(1024), 1024)[peakBand]
        assertTrue("did not fall at all: $peakLevel -> $afterOne", afterOne < peakLevel)
        assertTrue("fell to nothing in a single frame: $afterOne", afterOne > peakLevel * 0.5f)

        // And it does eventually reach the floor rather than hanging.
        repeat(80) { analyzer.analyze(DoubleArray(1024), 1024) }
        assertTrue(analyzer.analyze(DoubleArray(1024), 1024)[peakBand] < 0.05f)
    }

    @Test
    fun `a short final block is padded rather than skipped`() {
        // The last block of a track is usually partial. Skipping it freezes the display on the
        // previous frame, which reads as the visualiser having crashed.
        val rate = 44_100
        val analyzer = SpectrumAnalyzer(rate, bands = 12)
        val bars = analyzer.analyze(tone(440.0, rate, 300), 300)
        assertTrue("a partial block produced nothing", bars.any { it > 0.05f })
    }

    @Test
    fun `reset clears the display`() {
        val rate = 44_100
        val analyzer = SpectrumAnalyzer(rate, bands = 12)
        analyzer.analyze(tone(1000.0, rate, 1024), 1024)
        analyzer.reset()
        // Read through a silent frame, since reset is about what is carried into the next track.
        val bars = analyzer.analyze(DoubleArray(1024), 1024)
        assertTrue(bars.all { it < 0.01f })
    }
}
