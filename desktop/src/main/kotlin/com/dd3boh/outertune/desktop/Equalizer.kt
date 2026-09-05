/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One peaking band: centre frequency, gain, and how wide it reaches.
 *
 * Frequencies match the Android app's `EqualizerSettings.DEFAULT_FREQUENCIES` exactly, so a set of
 * gains means the same thing on both. That is deliberate - it is what makes it possible to move
 * presets between them later without anything having to be converted or guessed at.
 */
data class EqBand(
    val freqHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1.0f,
)

/**
 * A second-order IIR section, and the sample history it needs.
 *
 * Deliberately the same arrangement as the Android module's `audio/Biquad.kt`, down to the two
 * pieces of hardening below, which were both learned there rather than reasoned about here.
 *
 * These two files should share a module rather than agree by inspection. They do not yet because
 * the Android one reaches `EqualizerSettings`, which parses with `org.json` - built into Android and
 * an extra artifact here, and one that clashes with the platform copy if added carelessly. Worth
 * doing; not worth doing halfway. See HANDOVER.md.
 */
private class Biquad {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f

    /**
     * Sets the coefficients for a peaking filter at [freqHz].
     *
     * The RBJ audio-EQ cookbook forms, which is what almost every equaliser uses and what the
     * Android side uses, so the two produce the same curve for the same numbers.
     */
    fun setPeaking(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int) {
        // A band above Nyquist has no meaning and its coefficients blow up, so it is made a
        // pass-through instead. Real case: the 16kHz band on 32kHz audio sits exactly at Nyquist.
        if (sampleRate <= 0 || freqHz <= 0f || freqHz >= sampleRate / 2f) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            return
        }
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2.0 * Math.PI * freqHz / sampleRate
        val cosw0 = cos(w0).toFloat()
        val alpha = (sin(w0) / (2.0 * q.coerceAtLeast(0.05f))).toFloat()

        val nb0 = 1f + alpha * a
        val nb1 = -2f * cosw0
        val nb2 = 1f - alpha * a
        val na0 = 1f + alpha / a
        val na1 = -2f * cosw0
        val na2 = 1f - alpha / a

        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    fun process(x0: Float): Float {
        val raw = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

        // A filter fed one absurd sample - a corrupt frame, a format edge case - can ring up to
        // NaN and then never recover, because every later sample multiplies by that poisoned state.
        // The bound is far above any real signal, so it only trips on an actual runaway, and it has
        // to cover the returned sample too: otherwise one NaN still reaches the output before the
        // state catches up.
        val y0 = if (raw.isFinite()) raw.coerceIn(-16f, 16f) else 0f

        x2 = x1; x1 = x0
        y2 = y1
        // Flushed once the ring-down reaches denormal territory. A fade-out or a quiet passage
        // leaves an IIR decaying through ever smaller values, and denormal arithmetic is handled
        // off the fast path on a lot of hardware - so a silent passage can cost more CPU than a
        // loud one, which is exactly backwards.
        y1 = if (y0 > -1e-15f && y0 < 1e-15f) 0f else y0
        return y0
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}

/**
 * A bank of peaking filters, applied to interleaved PCM in place.
 *
 * **Each channel gets its own filters.** A biquad is stateful - it remembers two input and two
 * output samples - so running one filter across an interleaved stream would feed left's history into
 * right's output and back again. That is not a subtle degradation; it is a comb filter across the
 * stereo image, and it is the single easiest thing to get wrong here.
 *
 * Applied in place, on the buffer already destined for the output line, so there is no extra copy of
 * every block in the hot path.
 */
class Equalizer {

    @Volatile
    private var bands: List<EqBand> = DEFAULT_BANDS

    @Volatile
    var enabled: Boolean = false

    /** [channels] filter chains, each [bands] long. Rebuilt when the format or the bands change. */
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var configuredRate = 0
    private var configuredChannels = 0

    fun bands(): List<EqBand> = bands

    /** Replaces the bands. Takes effect on the next block. */
    fun setBands(newBands: List<EqBand>) {
        bands = newBands
        // Forces a rebuild on the next block rather than mutating coefficients under the audio
        // thread mid-buffer, which would apply half of one curve and half of another.
        configuredRate = 0
    }

    fun setGain(index: Int, gainDb: Float) {
        val updated = bands.toMutableList()
        if (index !in updated.indices) return
        updated[index] = updated[index].copy(gainDb = gainDb)
        setBands(updated)
    }

    fun reset() {
        filters.forEach { chain -> chain.forEach { it.reset() } }
    }

    private fun configure(sampleRate: Int, channels: Int) {
        val current = bands
        filters = Array(channels) { Array(current.size) { Biquad() } }
        for (chain in filters) {
            chain.forEachIndexed { i, filter ->
                val band = current[i]
                filter.setPeaking(band.freqHz, band.gainDb, band.q, sampleRate)
            }
        }
        configuredRate = sampleRate
        configuredChannels = channels
    }

    /**
     * Filters one block of interleaved PCM, writing the result back over [bytes].
     *
     * Byte order is a parameter for the same reason it is everywhere else here: the decoder in use
     * returns big-endian, and samples must be read *and written back* the same way round. Reading
     * one way and writing the other is not an error, it is noise.
     */
    fun process(
        bytes: ByteArray,
        length: Int,
        bitsPerSample: Int,
        channels: Int,
        bigEndian: Boolean,
        sampleRate: Int,
    ) {
        if (!enabled || channels < 1 || sampleRate <= 0) return
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return

        if (configuredRate != sampleRate || configuredChannels != channels) {
            configure(sampleRate, channels)
        }
        // Nothing to do if every band is flat. Worth checking: it is the common case, and it saves
        // running the whole cascade over every sample to arrive back where it started.
        if (bands.none { it.gainDb != 0f }) return

        val peak = (1L shl (bitsPerSample - 1)).toFloat()
        val maxValue = peak - 1f
        val frames = length / (bytesPerSample * channels)

        var pos = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val sample = readSample(bytes, pos, bytesPerSample, bigEndian) / peak
                var value = sample
                for (filter in filters[c]) value = filter.process(value)
                // Clamped, not wrapped. A boosted band can exceed full scale, and an integer that
                // overflows wraps from loudest positive to loudest negative - which is heard as a
                // violent crack rather than as distortion.
                val out = (value * peak).coerceIn(-peak, maxValue).toLong()
                writeSample(bytes, pos, bytesPerSample, bigEndian, out)
                pos += bytesPerSample
            }
        }
    }

    private fun readSample(bytes: ByteArray, at: Int, width: Int, bigEndian: Boolean): Float {
        var value = 0L
        if (bigEndian) {
            for (i in 0 until width) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        } else {
            for (i in width - 1 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        }
        val shift = 64 - width * 8
        return ((value shl shift) shr shift).toFloat()
    }

    private fun writeSample(bytes: ByteArray, at: Int, width: Int, bigEndian: Boolean, value: Long) {
        if (bigEndian) {
            for (i in 0 until width) {
                bytes[at + i] = ((value shr (8 * (width - 1 - i))) and 0xFF).toByte()
            }
        } else {
            for (i in 0 until width) {
                bytes[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
            }
        }
    }

    companion object {
        /** The Android app's own band centres, so gains mean the same thing on both. */
        val DEFAULT_FREQUENCIES = listOf(
            16f, 31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 16000f,
        )

        val DEFAULT_BANDS: List<EqBand> = DEFAULT_FREQUENCIES.map { EqBand(freqHz = it) }

        /** Gains only, over the default frequencies - matching the Android app's preset names. */
        val PRESETS: Map<String, List<Float>> = mapOf(
            "Flat" to List(12) { 0f },
            "Bass boost" to listOf(6f, 6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 5f),
            "Vocal" to listOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f, -1f),
            "Loudness" to listOf(5f, 5f, 4f, 2f, 0f, -1f, 0f, 1f, 3f, 4f, 4f, 3f),
        )
    }
}
