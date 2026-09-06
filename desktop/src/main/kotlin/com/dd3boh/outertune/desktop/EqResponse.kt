/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the equaliser actually does to the sound, as a curve.
 *
 * Not an illustration drawn from the slider positions - the real combined response of the filter
 * bank, evaluated from the same coefficients the audio goes through. That distinction matters
 * because the two disagree in exactly the place people look: adjacent bands overlap, so two
 * neighbouring +6dB sliders produce nearly +12dB between them, and a graph drawn from slider heights
 * would show two separate humps where the ear hears one large one.
 *
 * Evaluated by putting the transfer function on the unit circle. For a biquad with coefficients
 * b0,b1,b2,a1,a2 the response at frequency f is
 *
 *     H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²),  z = e^(j·2πf/fs)
 *
 * and the bank's response is the product of its sections, so the decibels add.
 */
object EqResponse {

    /**
     * Gain in decibels at each of [points] frequencies, log-spaced from 20Hz to 20kHz.
     *
     * Log-spaced because the graph is read against a log axis - the same reason the bands are spaced
     * that way. Linear sampling would spend most of its points above 10kHz, where the curve is
     * usually flat, and almost none across the bass, where it is not.
     */
    fun curve(bands: List<EqBand>, sampleRate: Int, points: Int = 160): FloatArray {
        val out = FloatArray(points)
        if (sampleRate <= 0) return out
        val nyquist = sampleRate / 2.0

        for (i in 0 until points) {
            val hz = MIN_HZ * (MAX_HZ / MIN_HZ).pow(i.toDouble() / (points - 1))
            // Above Nyquist there is nothing to say; the filters are pass-through there anyway.
            if (hz >= nyquist) {
                out[i] = out.getOrElse(i - 1) { 0f }
                continue
            }
            var db = 0.0
            bands.forEach { band ->
                if (band.gainDb != 0f) db += peakingGainDb(band, hz, sampleRate)
            }
            out[i] = db.toFloat()
        }
        return out
    }

    /** Frequency at graph position [fraction] across, for drawing an axis. */
    fun frequencyAt(fraction: Float): Double = MIN_HZ * (MAX_HZ / MIN_HZ).pow(fraction.toDouble())

    /**
     * One peaking section's contribution at [hz], in decibels.
     *
     * The coefficients are computed exactly as [Equalizer] computes them, because a graph derived
     * from a second implementation of the same formula is a graph of a different filter the moment
     * either drifts.
     */
    private fun peakingGainDb(band: EqBand, hz: Double, sampleRate: Int): Double {
        if (band.freqHz <= 0f || band.freqHz >= sampleRate / 2f) return 0.0

        val a = 10.0.pow(band.gainDb / 40.0)
        val w0 = 2.0 * PI * band.freqHz / sampleRate
        val cosw0 = cos(w0)
        val alpha = sin(w0) / (2.0 * band.q.coerceAtLeast(0.05f))

        val b0 = 1 + alpha * a
        val b1 = -2 * cosw0
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosw0
        val a2 = 1 - alpha / a

        val w = 2.0 * PI * hz / sampleRate
        // |H(e^jw)| for a normalised biquad. Written out rather than with a complex type: it is two
        // magnitudes and a division, and a complex class here would be more machinery than maths.
        val cosw = cos(w)
        val sinw = sin(w)
        val cos2w = cos(2 * w)
        val sin2w = sin(2 * w)

        val numRe = b0 + b1 * cosw + b2 * cos2w
        val numIm = -(b1 * sinw + b2 * sin2w)
        val denRe = a0 + a1 * cosw + a2 * cos2w
        val denIm = -(a1 * sinw + a2 * sin2w)

        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)
        if (denMag < 1e-12) return 0.0
        return 20.0 * log10(numMag / denMag)
    }

    const val MIN_HZ = 20.0
    const val MAX_HZ = 20_000.0
}
