/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import com.dd3boh.outertune.models.EqualizerSettings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A second-order IIR filter section, normalized so the denominator's leading term is 1 (a0
 * divided out already) - [BiquadState.process] can then apply it with no division on the audio
 * thread.
 *
 * Coefficients from the Audio EQ Cookbook (Robert Bristow-Johnson). One of these per band; a
 * whole equalizer is these cascaded in series, one call to [BiquadState.process] per band per
 * sample.
 */
class BiquadCoefficients(
    val b0: Float,
    val b1: Float,
    val b2: Float,
    val a1: Float,
    val a2: Float,
) {
    companion object {
        /** Passes its input through unchanged - what a disabled or 0dB band becomes. */
        val IDENTITY = BiquadCoefficients(1f, 0f, 0f, 0f, 0f)

        fun forBand(band: EqualizerSettings.EqBand, sampleRateHz: Int): BiquadCoefficients {
            if (!band.enabled || sampleRateHz <= 0) return IDENTITY
            // A shelf/peaking band at 0dB gain is mathematically already the identity filter, but
            // computing that through the general formula risks a divide-by-a-hair-above-zero on
            // some inputs - short-circuiting is both faster and exactly correct.
            if ((band.type == EqualizerSettings.FilterType.PEAKING ||
                        band.type == EqualizerSettings.FilterType.LOW_SHELF ||
                        band.type == EqualizerSettings.FilterType.HIGH_SHELF) &&
                band.gainDb == 0f
            ) return IDENTITY

            val nyquist = sampleRateHz / 2f
            // A band configured above Nyquist (e.g. a 16kHz band on a 22.05kHz-sampled file) has no
            // frequency left to act on - cos/sin of that angle wrap around into nonsense, not
            // silence. Clamp just under Nyquist instead of letting it produce an unstable filter.
            val freq = band.freqHz.coerceIn(1f, nyquist * 0.999f)

            val a = 10f.pow(band.gainDb / 40f)
            val w0 = (2.0 * Math.PI * freq / sampleRateHz).toFloat()
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)
            val q = band.q.coerceIn(EqualizerSettings.MIN_Q, EqualizerSettings.MAX_Q)
            val alpha = sinw0 / (2f * q)

            return when (band.type) {
                EqualizerSettings.FilterType.PEAKING -> {
                    val b0 = 1f + alpha * a
                    val b1 = -2f * cosw0
                    val b2 = 1f - alpha * a
                    val a0 = 1f + alpha / a
                    val a1 = -2f * cosw0
                    val a2 = 1f - alpha / a
                    normalize(b0, b1, b2, a0, a1, a2)
                }

                EqualizerSettings.FilterType.LOW_SHELF -> {
                    val sqrtA = sqrt(a)
                    val b0 = a * ((a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha)
                    val b1 = 2f * a * ((a - 1f) - (a + 1f) * cosw0)
                    val b2 = a * ((a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha)
                    val a0 = (a + 1f) + (a - 1f) * cosw0 + 2f * sqrtA * alpha
                    val a1 = -2f * ((a - 1f) + (a + 1f) * cosw0)
                    val a2 = (a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha
                    normalize(b0, b1, b2, a0, a1, a2)
                }

                EqualizerSettings.FilterType.HIGH_SHELF -> {
                    val sqrtA = sqrt(a)
                    val b0 = a * ((a + 1f) + (a - 1f) * cosw0 + 2f * sqrtA * alpha)
                    val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosw0)
                    val b2 = a * ((a + 1f) + (a - 1f) * cosw0 - 2f * sqrtA * alpha)
                    val a0 = (a + 1f) - (a - 1f) * cosw0 + 2f * sqrtA * alpha
                    val a1 = 2f * ((a - 1f) - (a + 1f) * cosw0)
                    val a2 = (a + 1f) - (a - 1f) * cosw0 - 2f * sqrtA * alpha
                    normalize(b0, b1, b2, a0, a1, a2)
                }

                EqualizerSettings.FilterType.LOW_PASS -> {
                    val b0 = (1f - cosw0) / 2f
                    val b1 = 1f - cosw0
                    val b2 = (1f - cosw0) / 2f
                    val a0 = 1f + alpha
                    val a1 = -2f * cosw0
                    val a2 = 1f - alpha
                    normalize(b0, b1, b2, a0, a1, a2)
                }

                EqualizerSettings.FilterType.HIGH_PASS -> {
                    val b0 = (1f + cosw0) / 2f
                    val b1 = -(1f + cosw0)
                    val b2 = (1f + cosw0) / 2f
                    val a0 = 1f + alpha
                    val a1 = -2f * cosw0
                    val a2 = 1f - alpha
                    normalize(b0, b1, b2, a0, a1, a2)
                }
            }
        }

        private fun normalize(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float): BiquadCoefficients {
            // a0 is never zero for these forms (it's a sum of positive terms plus a small alpha
            // term), but guard anyway rather than let a pathological Q/freq combination divide by
            // near-zero and blow the delay line up to NaN.
            val safeA0 = if (abs(a0) < 1e-9f) 1e-9f else a0
            return BiquadCoefficients(b0 / safeA0, b1 / safeA0, b2 / safeA0, a1 / safeA0, a2 / safeA0)
        }
    }
}

/**
 * One band's running delay-line state for one audio channel. A stereo N-band EQ holds a
 * [channelCount] x [bandCount] grid of these.
 */
class BiquadState {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    private companion object {
        /** See [process] - anything below this is inaudible, and denormals start far below it. */
        const val ANTI_DENORMAL = 1e-25f
    }

    fun process(x0: Float, b0: Float, b1: Float, b2: Float, a1: Float, a2: Float): Float {
        val rawY0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        // A filter that's briefly fed absurd input (a corrupt frame, a format edge case) can ring
        // up to NaN/Infinity and then never recover - every future sample multiplies by that same
        // poisoned state. The clamp is far above any real signal (even several cascaded +15dB
        // boosts stays well under this), so it only ever trips on an actual runaway - and it has
        // to cover the sample this function returns too, not just the fed-back state, or a single
        // NaN sample still reaches the output before the state catches up.
        val clamped = if (rawY0.isFinite()) rawY0.coerceIn(-16f, 16f) else 0f
        // Flush the delay line once it decays into denormal territory. When a track fades out, or
        // during any near-silent passage, an IIR's feedback path rings down through progressively
        // smaller values and eventually spends a long stretch in denormals - and scalar float
        // arithmetic on denormals is handled off the fast path on a good deal of ARM hardware,
        // costing orders of magnitude more per operation than the same maths on normal floats.
        // With a 16-band cascade running per channel per sample that is enough extra work to miss
        // the audio deadline, which is heard as crackle - and heard *per band*, since the bands
        // that ring longest (low frequency, high Q) sit in denormals longest. The threshold is
        // ~-500dBFS: far below anything audible, far above the denormal range it exists to avoid.
        val y0 = if (abs(clamped) < ANTI_DENORMAL) 0f else clamped
        x2 = x1
        x1 = if (abs(x0) < ANTI_DENORMAL) 0f else x0
        y2 = y1
        y1 = y0
        return y0
    }

    fun process(x0: Float, c: BiquadCoefficients): Float =
        process(x0, c.b0, c.b1, c.b2, c.a1, c.a2)

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}
