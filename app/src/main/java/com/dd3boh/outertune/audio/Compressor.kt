/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Coefficients for a simple feed-forward dynamics compressor, recomputed only when the user
 * changes a control - the per-sample hot path ([CompressorState.process]) just reads these.
 */
class CompressorCoefficients(
    val attackCoeff: Float,
    val releaseCoeff: Float,
    val thresholdDb: Float,
    val ratio: Float,
    val makeupGainLinear: Float,
) {
    companion object {
        val BYPASS = CompressorCoefficients(0f, 0f, 0f, 1f, 1f)

        fun from(sampleRateHz: Int, thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupGainDb: Float): CompressorCoefficients {
            if (sampleRateHz <= 0) return BYPASS
            // exp(-1 / (time constant in samples)) is the standard one-pole smoothing coefficient
            // for an attack/release envelope follower - larger coefficient = slower to move.
            val attackCoeff = exp(-1f / (sampleRateHz * (attackMs / 1000f)))
            val releaseCoeff = exp(-1f / (sampleRateHz * (releaseMs / 1000f)))
            return CompressorCoefficients(
                attackCoeff = attackCoeff,
                releaseCoeff = releaseCoeff,
                thresholdDb = thresholdDb,
                ratio = ratio.coerceAtLeast(1f),
                makeupGainLinear = 10f.pow(makeupGainDb / 20f),
            )
        }
    }
}

/**
 * One channel's running envelope-follower state. A peak/RMS-ish rectified level is smoothed with
 * separate attack/release rates, then whatever's above [CompressorCoefficients.thresholdDb] is
 * turned down by [CompressorCoefficients.ratio] : 1 and made back up by the makeup gain - the same
 * feed-forward shape as any simple hardware/software compressor.
 */
class CompressorState {
    private var envelope = 0f

    fun process(x0: Float, c: CompressorCoefficients): Float {
        val rectified = abs(x0)
        envelope = if (rectified > envelope) {
            c.attackCoeff * envelope + (1f - c.attackCoeff) * rectified
        } else {
            c.releaseCoeff * envelope + (1f - c.releaseCoeff) * rectified
        }
        val envDb = 20f * log10(envelope.coerceAtLeast(1e-6f))
        val gainReductionDb = if (envDb > c.thresholdDb) (c.thresholdDb - envDb) * (1f - 1f / c.ratio) else 0f
        val gainLinear = 10f.pow(gainReductionDb / 20f) * c.makeupGainLinear
        val y0 = x0 * gainLinear
        // Same runaway guard as the biquad delay line - a poisoned envelope should never be able
        // to latch into permanently silencing or blowing out the output.
        return if (y0.isFinite()) y0.coerceIn(-16f, 16f) else 0f
    }

    fun reset() {
        envelope = 0f
    }
}
