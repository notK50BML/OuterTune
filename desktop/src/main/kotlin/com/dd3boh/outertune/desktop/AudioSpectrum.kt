/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radix-2 Cooley-Tukey FFT, in place.
 *
 * Table-driven rather than calling trig per butterfly. A 1024-point transform runs roughly forty
 * times a second here, and computing sin/cos inside the inner loop would be about half a million
 * trig calls a second for a decoration - the table costs 4KB once and removes all of them.
 *
 * Twiddles are held as Double and applied in Double. Accumulating a rotation in Float across ten
 * stages drifts enough to smear a pure tone across neighbouring bins, which is exactly the artefact
 * the tests below would otherwise be measuring instead of the signal.
 */
class Fft(val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two, was $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }

    /** Transforms [re]/[im] in place. Both must be [size] long. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "arrays must both be $size long" }

        // Bit-reversal permutation. The butterflies below assume their inputs are already in this
        // order; doing it as a separate pass is what makes the transform in-place.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (n in 0 until half) {
                    val wRe = cosTable[k]
                    val wIm = sinTable[k]
                    val a = i + n
                    val b = a + half
                    val vRe = re[b] * wRe - im[b] * wIm
                    val vIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - vRe
                    im[b] = im[a] - vIm
                    re[a] += vRe
                    im[a] += vIm
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Turns the decoder's raw PCM into mono floats in -1..1.
 *
 * Byte order is a parameter and not an assumption. jaad hands back big-endian samples, which is the
 * opposite of what almost every example assumes, and getting it wrong does not fail - it produces
 * plausible-looking noise. That exact mistake already cost a debugging session on this project when
 * a WAV written with a little-endian header played as static, so the format is taken from the
 * decoder every time rather than remembered.
 */
object Pcm {

    /**
     * Writes up to [out].size mono samples and returns how many were written.
     *
     * Channels are averaged rather than taking the left one. A track mixed with the bass hard to one
     * side would otherwise show a spectrum that is not what anybody is hearing.
     */
    fun toMono(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        bitsPerSample: Int,
        channels: Int,
        bigEndian: Boolean,
        out: DoubleArray,
    ): Int {
        if (channels < 1) return 0
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample < 1) return 0
        val frameBytes = bytesPerSample * channels
        if (frameBytes < 1) return 0

        val frames = min(length / frameBytes, out.size)
        val scale = 1.0 / (1L shl (bitsPerSample - 1))

        var pos = offset
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                sum += readSample(bytes, pos, bytesPerSample, bigEndian) * scale
                pos += bytesPerSample
            }
            out[f] = sum / channels
        }
        return frames
    }

    /** Sign-extended little- or big-endian integer sample of [bytesPerSample] bytes. */
    private fun readSample(bytes: ByteArray, at: Int, bytesPerSample: Int, bigEndian: Boolean): Long {
        var value = 0L
        if (bigEndian) {
            for (i in 0 until bytesPerSample) {
                value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            }
        } else {
            for (i in bytesPerSample - 1 downTo 0) {
                value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
            }
        }
        // Sign-extend from the sample's own width. Without this a negative sample reads as a large
        // positive one and the waveform is rectified into something twice the frequency.
        val shift = 64 - bytesPerSample * 8
        return (value shl shift) shr shift
    }
}

/**
 * PCM in, bar heights out.
 *
 * Three decisions here are perceptual rather than mathematical, and each one is the difference
 * between a display that tracks the music and one that technically works.
 *
 * **Bands are spaced logarithmically.** Pitch is logarithmic - each octave doubles - so linear FFT
 * bins put six of the seven audible octaves into the top quarter of the display and squash
 * everything a listener would call "the bass" into the first bar. Log spacing gives each octave the
 * same width, which is what makes the bars move with the music rather than with the cymbals.
 *
 * **Magnitude is converted to decibels.** Loudness is logarithmic too. On a linear scale a quiet
 * passage is indistinguishable from silence and a loud one pins every bar to the top.
 *
 * **Bars rise instantly and fall slowly.** A bar that tracks the signal in both directions flickers,
 * because the energy in any one band is bursty. Fast attack keeps transients sharp - a kick should
 * appear on the frame it happens - while a slow release leaves something to look at between them.
 */
class SpectrumAnalyzer(
    private val sampleRate: Int,
    val bands: Int = 28,
    fftSize: Int = 1024,
    /** Fraction of the gap closed per frame when falling. Lower is slower. */
    private val fallRate: Float = 0.18f,
) {

    private val fft = Fft(fftSize)
    private val size = fftSize
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    /**
     * Hann window, applied before every transform.
     *
     * An FFT assumes its input repeats forever. A frame of audio does not join up with itself at the
     * ends, and that discontinuity is broadband - it appears as energy at every frequency, filling
     * in the gaps between real peaks until the display is a uniform hedge. Tapering the ends to zero
     * removes it.
     */
    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / (size - 1)) }

    private val levels = FloatArray(bands)

    /** Which FFT bin each band starts at. */
    private val edges: IntArray = run {
        val nyquist = sampleRate / 2.0
        val low = 40.0
        val high = min(16_000.0, nyquist * 0.98)
        IntArray(bands + 1) { b ->
            val hz = low * (high / low).pow(b.toDouble() / bands)
            (hz / nyquist * (size / 2)).toInt().coerceIn(0, size / 2 - 1)
        }
    }

    /**
     * Folds one block of mono samples into the bars and returns them.
     *
     * The returned array is reused between calls - it is read straight into a drawing pass forty
     * times a second and allocating a new one each time would make this the app's largest source of
     * garbage for no benefit.
     */
    fun analyze(mono: DoubleArray, count: Int): FloatArray {
        val usable = min(count, size)
        for (i in 0 until usable) {
            re[i] = mono[i] * window[i]
            im[i] = 0.0
        }
        // Zero-padded rather than skipped when a block is short. The last block of a track is
        // usually partial, and skipping it freezes the display on the second-to-last frame.
        for (i in usable until size) {
            re[i] = 0.0
            im[i] = 0.0
        }

        fft.transform(re, im)

        for (b in 0 until bands) {
            val from = edges[b]
            val to = max(edges[b + 1], from + 1)
            var peak = 0.0
            for (bin in from until min(to, size / 2)) {
                val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin]) / (size / 2)
                if (mag > peak) peak = mag
            }
            // Peak rather than mean across the band. A mean divides a real peak by however many bins
            // happen to be in that band, so the wide upper bands read as permanently quiet.
            val db = 20.0 * log10(peak + 1e-9)
            val target = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()

            levels[b] = if (target >= levels[b]) target else {
                levels[b] + (target - levels[b]) * fallRate
            }
        }
        return levels
    }

    /** Drops every bar to zero, for a stop or a track change. */
    fun reset() {
        levels.fill(0f)
    }

    private companion object {
        /** Quietest level shown. Below this is silence as far as the display is concerned. */
        const val FLOOR_DB = -70.0
    }
}

/** Whether a block of samples is audible at all - used to idle the display rather than draw noise. */
fun DoubleArray.hasSignal(count: Int): Boolean {
    for (i in 0 until min(count, size)) if (abs(this[i]) > 1e-4) return true
    return false
}
