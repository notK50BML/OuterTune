/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Coefficients for a simple feed-forward dynamics compressor, recomputed only when the user
 * changes a control - the per-sample hot path ([CompressorState.process]) just reads these.
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

        fun from(sampleRateHz: Int, thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupGainDb: Float): CompressorCoefficients {
            if (sampleRateHz <= 0) return BYPASS
            // exp(-1 / (time constant in samples)) is the standard one-pole smoothing coefficient
            // for an attack/release envelope follower - larger coefficient = slower to move.
            val attackCoeff = exp(-1f / (sampleRateHz * (attackMs / 1000f)))
            val releaseCoeff = exp(-1f / (sampleRateHz * (releaseMs / 1000f)))
            val safeRatio = ratio.coerceAtLeast(1f)
            return CompressorCoefficients(
                attackCoeff = attackCoeff,
                releaseCoeff = releaseCoeff,
                // Precomputed here, once per control change, rather than per sample. The gain
                // computer below is algebraically identical to the dB-domain form this replaced:
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
 * One channel's running envelope-follower state. A peak/RMS-ish rectified level is smoothed with
 * separate attack/release rates, then whatever's above [CompressorCoefficients.thresholdLinear] is
 * turned down along [CompressorCoefficients.slope] and made back up by the makeup gain - the same
 * feed-forward shape as any simple hardware/software compressor.
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
        // Same denormal flush as the biquad delay line, and for the same reason: the release curve
        // is a one-pole decay, so on a fade-out the envelope spends a long time ringing down
        // through very small values before it reaches zero on its own.
        envelope = if (smoothed < ANTI_DENORMAL) 0f else smoothed

        // Below the threshold a compressor is doing nothing but makeup gain, and that is the case
        // the great majority of samples are in. Taking the branch means the pow is paid only while
        // gain reduction is actually happening, instead of on every sample of every channel - the
        // old form computed a log10 and a pow unconditionally, which at 48kHz stereo is a few
        // hundred thousand transcendental calls a second on the audio thread, and missing the
        // deadline for them is heard as crackle whenever the compressor is switched on.
        val gainLinear = if (envelope > c.thresholdLinear) {
            (c.thresholdLinear / envelope).pow(c.slope) * c.makeupGainLinear
        } else {
            c.makeupGainLinear
        }
        val y0 = x0 * gainLinear
        // Same runaway guard as the biquad delay line - a poisoned envelope should never be able
        // to latch into permanently silencing or blowing out the output.
        return if (y0.isFinite()) y0.coerceIn(-16f, 16f) else 0f
    }

    fun reset() {
        envelope = 0f
    }
}
