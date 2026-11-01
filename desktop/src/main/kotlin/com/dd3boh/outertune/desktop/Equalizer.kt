/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.roundToLong
import kotlin.math.log10
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
    val type: EqBandType = EqBandType.PEAKING,
)

/**
 * What shape a band is.
 *
 * Peaking alone was enough while every band came from this app's own twelve-slider layout. It is not
 * enough for a correction curve fetched from AutoEQ: those almost always open with a low shelf to
 * set the bass level and close with a high shelf, because a headphone's error at the extremes is a
 * tilt across a whole region rather than a bump at one frequency. Approximating a shelf with a
 * peaking filter gets the centre right and then falls away again exactly where the correction was
 * supposed to keep going.
 */
enum class EqBandType { PEAKING, LOW_SHELF, HIGH_SHELF }

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

    /**
     * Sets the coefficients for a low or high shelf at [freqHz].
     *
     * RBJ cookbook again, and the two shelves are the same expression with the sign of the
     * `(A - 1) * cos(w0)` terms flipped and the numerator and denominator swapped - which is why
     * they are written together rather than as two functions that would drift apart.
     *
     * The shelf slope is taken from [q] the way the cookbook's S parameter relates to it, so a
     * ParametricEQ.txt Q means here what it means in Equalizer APO and in AutoEQ's own plots.
     */
    fun setShelf(freqHz: Float, gainDb: Float, q: Float, sampleRate: Int, low: Boolean) {
        if (sampleRate <= 0 || freqHz <= 0f || freqHz >= sampleRate / 2f) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freqHz / sampleRate
        val cosw0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q.coerceAtLeast(0.05f))
        // The cookbook's shelf term. Guarded because A + 1 - (A - 1)cos(w0) can go very slightly
        // negative under rounding at extreme gains, and a NaN here would poison the filter for the
        // rest of the track.
        val sqrtTerm = 2.0 * kotlin.math.sqrt(a) * alpha

        val na0: Double; val na1: Double; val na2: Double
        val nb0: Double; val nb1: Double; val nb2: Double
        if (low) {
            nb0 = a * ((a + 1) - (a - 1) * cosw0 + sqrtTerm)
            nb1 = 2 * a * ((a - 1) - (a + 1) * cosw0)
            nb2 = a * ((a + 1) - (a - 1) * cosw0 - sqrtTerm)
            na0 = (a + 1) + (a - 1) * cosw0 + sqrtTerm
            na1 = -2 * ((a - 1) + (a + 1) * cosw0)
            na2 = (a + 1) + (a - 1) * cosw0 - sqrtTerm
        } else {
            nb0 = a * ((a + 1) + (a - 1) * cosw0 + sqrtTerm)
            nb1 = -2 * a * ((a - 1) + (a + 1) * cosw0)
            nb2 = a * ((a + 1) + (a - 1) * cosw0 - sqrtTerm)
            na0 = (a + 1) - (a - 1) * cosw0 + sqrtTerm
            na1 = 2 * ((a - 1) - (a + 1) * cosw0)
            na2 = (a + 1) - (a - 1) * cosw0 - sqrtTerm
        }

        if (na0 == 0.0 || !na0.isFinite()) {
            b0 = 1f; b1 = 0f; b2 = 0f; a1 = 0f; a2 = 0f
            return
        }
        b0 = (nb0 / na0).toFloat()
        b1 = (nb1 / na0).toFloat()
        b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat()
        a2 = (na2 / na0).toFloat()
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
 * A bank of peaking filters, applied to interleaved PCM in place - with the compressor and the
 * output limiter after it, in the same pass.
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

    /** [channels] filter chains, each [bands] long. Rebuilt when the format or the band count change. */
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var configuredRate = 0
    private var configuredChannels = 0

    /**
     * Set when the bands or a setting change, so the next block works the filters and the gain out
     * again. A flag rather than zeroing [configuredRate], which is also what [sampleRate] reports.
     */
    @Volatile
    private var dirty = true

    /** Whether the curve was doing anything, and the normalisation, when last configured. */
    private var configuredCurve = false
    private var configuredNormalisationDb = 0f

    /**
     * How far the input is turned up or down before the bands, as a linear factor.
     *
     * The loudest point of the curve, cancelled in advance. Without it a +6dB bass boost on a track
     * already mastered to full scale has nowhere to go but past it, and the clamp at the end cut
     * every bass peak flat - which is the harsh, crackling distortion a boost produced. This is
     * what the phone's Automatic headroom does, by the same rule: see [EqAutoGain].
     */
    private var preGain = 1f

    /**
     * The pre-gain actually being applied, walked to [preGain] over [RAMP_MS] when it changes, so a
     * slider move is a glide in level rather than a step.
     */
    private var appliedPreGain = 1f
    private var preGainStep = 0f
    private var preGainFramesLeft = 0

    /**
     * Whether a curve that only cuts is turned back up by the loudness it took away - the
     * counterpart of the headroom above, and the phone's "Turn up for cuts". A curve that boosts
     * anywhere is never turned up: see [EqAutoGain].
     */
    @Volatile
    var cutMakeup: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            dirty = true // rebuilt with the new pre-gain on the next block
        }

    /** The headroom currently applied, in dB, as a positive number - for tests and a meter. */
    internal val headroomDb: Float get() = -20f * log10(preGain)

    /**
     * The last stage before the samples become integers again, and the only thing between the
     * processing and full scale. See [PeakLimiter] for why it turns the gain down rather than
     * reshaping the wave.
     */
    private val limiter = PeakLimiter()
    private var frame = FloatArray(0)

    /** One block as floats, interleaved, between the bands, the compressor and the limiter. */
    private var block = FloatArray(0)
    private var wasFlat = true

    fun bands(): List<EqBand> = bands

    /**
     * The rate the filters are currently built for, or 44100 before anything has played.
     *
     * The response curve is frequency-dependent on this, so a graph drawn against an assumed rate
     * would be subtly wrong at the top end for anything that is not CD audio.
     */
    val sampleRate: Int get() = configuredRate.takeIf { it > 0 } ?: 44_100

    /** Replaces the bands. Takes effect on the next block. */
    fun setBands(newBands: List<EqBand>) {
        bands = newBands
        // Forces a rebuild on the next block rather than mutating coefficients under the audio
        // thread mid-buffer, which would apply half of one curve and half of another.
        dirty = true
    }

    fun setGain(index: Int, gainDb: Float) {
        val updated = bands.toMutableList()
        if (index !in updated.indices) return
        updated[index] = updated[index].copy(gainDb = gainDb)
        setBands(updated)
    }

    fun reset() {
        filters.forEach { chain -> chain.forEach { it.reset() } }
        limiter.reset()
        appliedPreGain = preGain
        preGainFramesLeft = 0
    }

    private fun configure(sampleRate: Int, channels: Int, curve: Boolean, normalisationDb: Float) {
        val current = bands
        // The filters keep their history when only their coefficients change. Building new ones
        // on every slider step started each band from silence mid-song, which is a click.
        val reuse = sampleRate == configuredRate && curve == configuredCurve &&
            filters.size == channels && filters.all { it.size == current.size }
        if (!reuse) filters = Array(channels) { Array(current.size) { Biquad() } }
        for (chain in filters) {
            chain.forEachIndexed { i, filter ->
                val band = current[i]
                when (band.type) {
                    EqBandType.PEAKING -> filter.setPeaking(band.freqHz, band.gainDb, band.q, sampleRate)
                    EqBandType.LOW_SHELF ->
                        filter.setShelf(band.freqHz, band.gainDb, band.q, sampleRate, low = true)
                    EqBandType.HIGH_SHELF ->
                        filter.setShelf(band.freqHz, band.gainDb, band.q, sampleRate, low = false)
                }
            }
        }
        configuredRate = sampleRate
        configuredChannels = channels
        configuredCurve = curve
        configuredNormalisationDb = normalisationDb
        dirty = false

        // Measured off the summed response of every band together, not added up band by band -
        // two neighbouring +6dB bands make nearly +12dB between them. With the curve off, only the
        // normalisation is left to apply.
        val gainDb = if (curve) {
            EqAutoGain.gainDb(
                peakDb = EqResponse.peakDb(current, sampleRate),
                meanPowerDb = EqResponse.meanPowerDb(current, sampleRate),
                cutMakeup = cutMakeup,
                normalisationDb = normalisationDb,
            )
        } else {
            -normalisationDb
        }
        val target = 10f.pow(gainDb / 20f)
        if (target != preGain) {
            preGain = target
            preGainFramesLeft = (sampleRate * RAMP_MS / 1000).coerceAtLeast(1)
            preGainStep = (target - appliedPreGain) / preGainFramesLeft
        }
        limiter.configure(sampleRate, channels)
        if (frame.size != channels) frame = FloatArray(channels)
    }

    /**
     * Equalises, compresses and limits one block of interleaved PCM, writing the result back over
     * [bytes]. Returns whether it ran - and so whether [normalisationDb] has been applied, which the
     * caller must then not apply again.
     *
     * One pass, in floats, from the decoded samples to the limiter, and back to integers once at
     * the very end. The compressor used to run afterwards as a stage of its own, on the 16-bit
     * output of this one, after the limiter: its makeup gain could then only be clamped, and was.
     *
     * [normalisationDb] is attenuation the track would get at the volume stage anyway, taken here
     * instead so a boost can spend it as headroom - the phone does the same.
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
        compressor: Compressor? = null,
        normalisationDb: Float = 0f,
    ): Boolean {
        if (channels < 1 || sampleRate <= 0) return false
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return false

        // Nothing to do if every band is flat and nothing else is on. Worth checking: it is the
        // common case, and it saves running the whole cascade over every sample to arrive back
        // where it started.
        val curve = enabled && bands.any { it.gainDb != 0f }
        val compressing = compressor?.enabled == true
        if (!curve && !compressing) {
            wasFlat = true
            // Keeps the compressor's meter honest once it has been switched off.
            compressor?.processFloat(block, 0, channels, sampleRate)
            return false
        }
        val normalisation = normalisationDb.coerceAtLeast(0f)
        if (dirty || configuredRate != sampleRate || configuredChannels != channels ||
            curve != configuredCurve || normalisation != configuredNormalisationDb
        ) {
            configure(sampleRate, channels, curve, normalisation)
        }
        // Coming back from flat, the limiter's delay line still holds audio from before. Cleared so
        // a fragment of the past is not played into the present - and there is no level to glide
        // from either, since nothing was being applied a moment ago.
        if (wasFlat) {
            limiter.reset()
            appliedPreGain = preGain
            preGainFramesLeft = 0
            wasFlat = false
        }

        val peak = (1L shl (bitsPerSample - 1)).toFloat()
        val maxValue = peak - 1f
        val frames = length / (bytesPerSample * channels)
        val samples = frames * channels
        if (block.size < samples) block = FloatArray(samples)

        var pos = 0
        for (f in 0 until frames) {
            if (preGainFramesLeft > 0) {
                appliedPreGain += preGainStep
                if (--preGainFramesLeft == 0) appliedPreGain = preGain
            }
            val base = f * channels
            for (c in 0 until channels) {
                var value = Pcm.readSample(bytes, pos, bytesPerSample, bigEndian).toFloat() / peak * appliedPreGain
                if (curve) for (filter in filters[c]) value = filter.process(value)
                block[base + c] = value
                pos += bytesPerSample
            }
        }

        // After the bands, as on the phone, and before the limiter, so its makeup gain is turned
        // down when it overshoots rather than flattened.
        compressor?.processFloat(block, frames, channels, sampleRate)

        pos = 0
        for (f in 0 until frames) {
            val base = f * channels
            for (c in 0 until channels) frame[c] = block[base + c]
            // The whole frame at once: the limiter's gain is linked, so one side peaking turns both
            // down together and the stereo image stays put.
            limiter.process(frame)
            for (c in 0 until channels) {
                // Rounded and clamped, not truncated or wrapped. An integer that overflows wraps
                // from loudest positive to loudest negative, which is heard as a violent crack.
                val out = (frame[c] * peak).roundToLong().coerceIn(-peak.toLong(), maxValue.toLong())
                Pcm.writeSample(bytes, pos, bytesPerSample, bigEndian, out)
                pos += bytesPerSample
            }
        }
        return true
    }

    companion object {
        /** How long a change of pre-gain takes, in milliseconds - the phone's coefficient ramp. */
        const val RAMP_MS = 20

        /** The Android app's own band centres, so gains mean the same thing on both. */
        val DEFAULT_FREQUENCIES = listOf(
            16f, 31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 12000f, 16000f,
        )

        val DEFAULT_BANDS: List<EqBand> = DEFAULT_FREQUENCIES.map { EqBand(freqHz = it) }

        /**
         * Gains over [DEFAULT_FREQUENCIES], by name.
         *
         * Sixteen rather than the five this started with, because a preset row is only useful if
         * the thing someone wants is plausibly in it - with five, every one of them is a starting
         * point to be dragged away from, which is the same as having none.
         *
         * Deliberately modest: nothing here moves a band more than 6dB. Preset curves that look
         * dramatic on a graph mostly just make the track louder, and then everything gets compared
         * against a louder version of itself and judged better.
         */
        val PRESETS: Map<String, List<Float>> = mapOf(
            "Flat" to List(12) { 0f },
            "Bass boost" to listOf(6f, 6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass cut" to listOf(-6f, -6f, -5f, -3f, -1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 5f),
            "Treble cut" to listOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -2f, -3f, -4f, -5f, -5f),
            "Vocal" to listOf(-2f, -2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f, -1f),
            "Loudness" to listOf(5f, 5f, 4f, 2f, 0f, -1f, 0f, 1f, 3f, 4f, 4f, 3f),
            "Rock" to listOf(5f, 4f, 3f, 1f, -1f, -1f, 0f, 2f, 3f, 4f, 4f, 3f),
            "Pop" to listOf(-1f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f, -1f, -1f),
            "Jazz" to listOf(4f, 3f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f, 3f, 3f),
            "Classical" to listOf(4f, 3f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 3f, 3f, 4f),
            "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 0f, 1f, 3f, 4f, 4f, 4f),
            "Hip-hop" to listOf(6f, 5f, 4f, 2f, 1f, -1f, 1f, -1f, 2f, 3f, 3f, 3f),
            "Acoustic" to listOf(4f, 4f, 3f, 1f, 2f, 1f, 2f, 3f, 3f, 2f, 2f, 1f),
            "Late night" to listOf(3f, 3f, 2f, 1f, 2f, 3f, 3f, 2f, 1f, 0f, -1f, -2f),
            "Podcast" to listOf(-4f, -4f, -2f, 0f, 3f, 4f, 4f, 3f, 1f, 0f, -2f, -3f),
        )
    }
}
