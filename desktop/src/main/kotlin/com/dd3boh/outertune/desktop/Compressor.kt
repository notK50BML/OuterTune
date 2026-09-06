/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Coefficients for a feed-forward dynamics compressor.
 *
 * Recomputed only when a control moves - the per-sample hot path ([CompressorState.process]) does
 * nothing but read these. Ported from the Android app's `audio/Compressor.kt`, which is pure Kotlin
 * and needed no changes; the two should stay in step, since a track that sounds different on the
 * desktop than on the phone with the same settings is a bug in one of them.
 */
class CompressorCoefficients(
    val attackCoeff: Float,
    val releaseCoeff: Float,
    /** Threshold as a linear amplitude, so the hot path never has to take a logarithm. */
    val thresholdLinear: Float,
    /** 1 - 1/ratio: the exponent that expresses the whole dB-domain knee as one linear power. */
    val slope: Float,
    val makeupGainLinear: Float,
) {
    companion object {
        val BYPASS = CompressorCoefficients(0f, 0f, 1f, 0f, 1f)

        fun from(
            sampleRateHz: Int,
            thresholdDb: Float,
            ratio: Float,
            attackMs: Float,
            releaseMs: Float,
            makeupGainDb: Float,
        ): CompressorCoefficients {
            if (sampleRateHz <= 0) return BYPASS
            // exp(-1 / (time constant in samples)) is the standard one-pole smoothing coefficient
            // for an attack/release envelope follower - larger coefficient = slower to move.
            val attackCoeff = exp(-1f / (sampleRateHz * (attackMs.coerceAtLeast(0.1f) / 1000f)))
            val releaseCoeff = exp(-1f / (sampleRateHz * (releaseMs.coerceAtLeast(1f) / 1000f)))
            val safeRatio = ratio.coerceAtLeast(1f)
            return CompressorCoefficients(
                attackCoeff = attackCoeff,
                releaseCoeff = releaseCoeff,
                // Precomputed here, once per control change, rather than per sample. The gain
                // computer below is algebraically identical to the dB-domain form:
                //   10^(((Tdb - envDb) * k) / 20)  ==  (Tlin / env)^k,  k = 1 - 1/ratio
                // which removes a log10 from every sample and leaves a single pow that is only
                // reached while actually compressing.
                thresholdLinear = 10f.pow(thresholdDb / 20f),
                slope = 1f - 1f / safeRatio,
                makeupGainLinear = 10f.pow(makeupGainDb / 20f),
            )
        }
    }
}

/**
 * One channel's running envelope-follower state.
 *
 * A rectified level is smoothed with separate attack and release rates, then whatever is above
 * [CompressorCoefficients.thresholdLinear] is turned down along [CompressorCoefficients.slope] and
 * made back up by the makeup gain - the same feed-forward shape as any simple compressor.
 */
class CompressorState {
    private var envelope = 0f

    private companion object {
        const val ANTI_DENORMAL = 1e-25f
    }

    fun process(x0: Float, c: CompressorCoefficients): Float {
        val rectified = abs(x0)
        val smoothed = if (rectified > envelope) {
            c.attackCoeff * envelope + (1f - c.attackCoeff) * rectified
        } else {
            c.releaseCoeff * envelope + (1f - c.releaseCoeff) * rectified
        }
        // Denormal flush. The release curve is a one-pole decay, so on a fade-out the envelope
        // spends a long time ringing down through very small values before it reaches zero, and
        // denormal arithmetic is slow enough on some processors to matter on the audio thread.
        envelope = if (smoothed < ANTI_DENORMAL) 0f else smoothed

        // Below the threshold a compressor is doing nothing but makeup gain, and that is the case
        // the great majority of samples are in. Taking the branch means the pow is paid only while
        // gain reduction is actually happening rather than on every sample of every channel.
        val gainLinear = if (envelope > c.thresholdLinear) {
            (c.thresholdLinear / envelope).pow(c.slope) * c.makeupGainLinear
        } else {
            c.makeupGainLinear
        }
        val y0 = x0 * gainLinear
        // A poisoned envelope should never be able to latch into permanently silencing or blowing
        // out the output.
        return if (y0.isFinite()) y0.coerceIn(-16f, 16f) else 0f
    }

    /** How much the follower is currently pulling down, in decibels. Zero when it is not. */
    fun reductionDb(c: CompressorCoefficients): Float {
        if (envelope <= c.thresholdLinear) return 0f
        return 20f * kotlin.math.log10((c.thresholdLinear / envelope).pow(c.slope))
    }

    fun reset() {
        envelope = 0f
    }
}

/**
 * The compressor as the playback pipeline sees it: settings in, 16-bit PCM changed in place.
 *
 * Per channel state, because a compressor with one shared envelope across channels is a different
 * effect - it ducks both sides when either gets loud, which is sometimes wanted and is not what the
 * controls here describe.
 *
 * Works in place. Unlike the time stretcher this does not change the block's length, so there is no
 * reason to allocate a second copy of every block in the hot path.
 */
class Compressor {

    var enabled: Boolean = false

    var thresholdDb: Float = DEFAULT_THRESHOLD_DB
        set(value) { field = value.coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB); dirty = true }

    var ratio: Float = DEFAULT_RATIO
        set(value) { field = value.coerceIn(MIN_RATIO, MAX_RATIO); dirty = true }

    var attackMs: Float = DEFAULT_ATTACK_MS
        set(value) { field = value.coerceIn(MIN_ATTACK_MS, MAX_ATTACK_MS); dirty = true }

    var releaseMs: Float = DEFAULT_RELEASE_MS
        set(value) { field = value.coerceIn(MIN_RELEASE_MS, MAX_RELEASE_MS); dirty = true }

    var makeupGainDb: Float = DEFAULT_MAKEUP_DB
        set(value) { field = value.coerceIn(MIN_MAKEUP_DB, MAX_MAKEUP_DB); dirty = true }

    private var coefficients = CompressorCoefficients.BYPASS
    private var states = emptyArray<CompressorState>()
    private var configuredRate = 0
    private var dirty = true

    /**
     * The gain reduction being applied right now, in decibels, as a negative number.
     *
     * Read by the UI for a meter. Written from the audio thread and read from the UI thread without
     * synchronisation, which is fine for exactly this: it is a single float, it is only ever
     * displayed, and a reading one block out of date is not visible on a meter that is redrawn
     * sixty times a second anyway.
     */
    @Volatile
    var currentReductionDb: Float = 0f
        private set

    fun process(
        bytes: ByteArray,
        length: Int,
        bitsPerSample: Int,
        channels: Int,
        bigEndian: Boolean,
        sampleRate: Int,
    ) {
        if (!enabled || channels < 1 || sampleRate <= 0 || bitsPerSample != 16) {
            currentReductionDb = 0f
            return
        }
        if (dirty || configuredRate != sampleRate || states.size != channels) {
            coefficients = CompressorCoefficients.from(
                sampleRateHz = sampleRate,
                thresholdDb = thresholdDb,
                ratio = ratio,
                attackMs = attackMs,
                releaseMs = releaseMs,
                makeupGainDb = makeupGainDb,
            )
            if (states.size != channels) states = Array(channels) { CompressorState() }
            configuredRate = sampleRate
            dirty = false
        }

        val frames = length / (2 * channels)
        var worst = 0f
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val at = (f * channels + c) * 2
                val raw = if (bigEndian) {
                    ((bytes[at].toInt() shl 8) or (bytes[at + 1].toInt() and 0xFF)).toShort()
                } else {
                    ((bytes[at + 1].toInt() shl 8) or (bytes[at].toInt() and 0xFF)).toShort()
                }
                val out = states[c].process(raw / 32768f, coefficients)
                // Clamped rather than wrapped: makeup gain can push a loud passage past full scale,
                // and wrapping turns that into a full-amplitude click rather than a moment of
                // flat-topping.
                val scaled = (out * 32767f).coerceIn(-32768f, 32767f).toInt()
                if (bigEndian) {
                    bytes[at] = ((scaled shr 8) and 0xFF).toByte()
                    bytes[at + 1] = (scaled and 0xFF).toByte()
                } else {
                    bytes[at] = (scaled and 0xFF).toByte()
                    bytes[at + 1] = ((scaled shr 8) and 0xFF).toByte()
                }
            }
            // Sampled once per frame off channel zero rather than tracked per sample. The meter
            // needs the worst of the block, not every value that led to it.
            if (f % 64 == 0) {
                val reduction = states[0].reductionDb(coefficients)
                if (reduction < worst) worst = reduction
            }
        }
        currentReductionDb = worst
    }

    fun reset() {
        states.forEach { it.reset() }
        currentReductionDb = 0f
    }

    companion object {
        const val MIN_THRESHOLD_DB = -60f
        const val MAX_THRESHOLD_DB = 0f
        const val DEFAULT_THRESHOLD_DB = -18f

        const val MIN_RATIO = 1f
        const val MAX_RATIO = 20f
        const val DEFAULT_RATIO = 3f

        /**
         * Attack range, in milliseconds.
         *
         * Down to a tenth of a millisecond, which is fast enough to catch a transient before it
         * passes, and up to a third of a second, which is slow enough to let one through on purpose.
         */
        const val MIN_ATTACK_MS = 0.1f
        const val MAX_ATTACK_MS = 300f
        const val DEFAULT_ATTACK_MS = 10f

        const val MIN_RELEASE_MS = 10f
        const val MAX_RELEASE_MS = 2000f
        const val DEFAULT_RELEASE_MS = 150f

        const val MIN_MAKEUP_DB = 0f
        const val MAX_MAKEUP_DB = 24f
        const val DEFAULT_MAKEUP_DB = 0f
    }
}
