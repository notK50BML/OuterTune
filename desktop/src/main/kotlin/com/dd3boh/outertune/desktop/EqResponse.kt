/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
                if (band.gainDb != 0f) db += bandGainDb(band, hz, sampleRate)
            }
            out[i] = db.toFloat()
        }
        return out
    }

    /** The whole bank's gain at [hz], in decibels - the sum of every section's. */
    fun gainAtDb(bands: List<EqBand>, hz: Double, sampleRate: Int): Double {
        var db = 0.0
        bands.forEach { band -> if (band.gainDb != 0f) db += bandGainDb(band, hz, sampleRate) }
        return db
    }

    /**
     * The bank's highest point, in dB, anywhere from 10Hz to just under Nyquist - which is what the
     * headroom has to pay for.
     *
     * Not floored at zero, so a curve that only cuts reads below it: that is what tells "cuts only"
     * from "boosts" in [EqAutoGain]. Wider than the graph's 20Hz-20kHz, because the 16Hz band's
     * peak sits under 20Hz. A coarse log scan finds the neighbourhood, and the stretch either side of
     * the best probe is scanned again finely, so a narrow band between two probes is measured at its
     * top rather than on its shoulder.
     */
    fun peakDb(bands: List<EqBand>, sampleRate: Int): Float {
        if (sampleRate <= 0 || bands.none { it.gainDb != 0f }) return 0f
        val logLow = ln(PEAK_LOW_HZ)
        val logHigh = ln(sampleRate / 2.0 * 0.99).coerceAtLeast(logLow + 1.0)
        val step = (logHigh - logLow) / (PEAK_PROBES - 1)
        var peak = Double.NEGATIVE_INFINITY
        var best = 0
        for (i in 0 until PEAK_PROBES) {
            val db = gainAtDb(bands, exp(logLow + step * i), sampleRate)
            if (db > peak) { peak = db; best = i }
        }
        val from = logLow + step * (best - 1).coerceAtLeast(0)
        val to = logLow + step * (best + 1).coerceAtMost(PEAK_PROBES - 1)
        for (i in 0..PEAK_REFINE) {
            val db = gainAtDb(bands, exp(from + (to - from) * i / PEAK_REFINE), sampleRate)
            if (db > peak) peak = db
        }
        return peak.toFloat()
    }

    /**
     * The loudness the bank takes away or adds, in dB: its power averaged over 20Hz-16kHz on a log
     * axis, which is about how music spreads its energy (roughly equal per octave). The phone's
     * `BiquadCoefficients.meanPowerGainDb`, over the same range.
     *
     * Past 16kHz the filters drift back to 0dB near Nyquist, and streams carry next to nothing there
     * anyway, so counting it would only dilute the reading.
     */
    fun meanPowerDb(bands: List<EqBand>, sampleRate: Int): Float {
        if (sampleRate <= 0 || bands.none { it.gainDb != 0f }) return 0f
        val logLow = ln(20.0)
        val logHigh = ln(minOf(16_000.0, sampleRate / 2.0 * 0.99)).coerceAtLeast(logLow + 1.0)
        var power = 0.0
        for (i in 0 until PEAK_PROBES) {
            val hz = exp(logLow + (logHigh - logLow) * i / (PEAK_PROBES - 1))
            power += 10.0.pow(gainAtDb(bands, hz, sampleRate) / 10.0)
        }
        return (10.0 * log10(power / PEAK_PROBES)).toFloat()
    }

    /** Frequency at graph position [fraction] across, for drawing an axis. */
    fun frequencyAt(fraction: Float): Double = MIN_HZ * (MAX_HZ / MIN_HZ).pow(fraction.toDouble())

    /**
     * One section's contribution at [hz], in decibels.
     *
     * The coefficients are computed exactly as [Equalizer] computes them, for every band shape,
     * because a graph derived from a second implementation of the same formula is a graph of a
     * different filter the moment either drifts.
     */
    private fun bandGainDb(band: EqBand, hz: Double, sampleRate: Int): Double {
        if (band.freqHz <= 0f || band.freqHz >= sampleRate / 2f) return 0.0

        val a = 10.0.pow(band.gainDb / 40.0)
        val w0 = 2.0 * PI * band.freqHz / sampleRate
        val cosw0 = cos(w0)
        val alpha = sin(w0) / (2.0 * band.q.coerceAtLeast(0.05f))
        val sqrtTerm = 2.0 * sqrt(a) * alpha

        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (band.type) {
            EqBandType.PEAKING -> {
                b0 = 1 + alpha * a
                b1 = -2 * cosw0
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * cosw0
                a2 = 1 - alpha / a
            }
            EqBandType.LOW_SHELF -> {
                b0 = a * ((a + 1) - (a - 1) * cosw0 + sqrtTerm)
                b1 = 2 * a * ((a - 1) - (a + 1) * cosw0)
                b2 = a * ((a + 1) - (a - 1) * cosw0 - sqrtTerm)
                a0 = (a + 1) + (a - 1) * cosw0 + sqrtTerm
                a1 = -2 * ((a - 1) + (a + 1) * cosw0)
                a2 = (a + 1) + (a - 1) * cosw0 - sqrtTerm
            }
            EqBandType.HIGH_SHELF -> {
                b0 = a * ((a + 1) + (a - 1) * cosw0 + sqrtTerm)
                b1 = -2 * a * ((a - 1) + (a + 1) * cosw0)
                b2 = a * ((a + 1) + (a - 1) * cosw0 - sqrtTerm)
                a0 = (a + 1) - (a - 1) * cosw0 + sqrtTerm
                a1 = 2 * ((a - 1) - (a + 1) * cosw0)
                a2 = (a + 1) - (a - 1) * cosw0 - sqrtTerm
            }
        }
        if (a0 == 0.0 || !a0.isFinite()) return 0.0

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

    private const val PEAK_LOW_HZ = 10.0

    /** The phone measures with as many, enough to land within a tenth of a dB of a Q-10 band. */
    private const val PEAK_PROBES = 512

    /** Probes across the two coarse steps around the best one, when the peak is refined. */
    private const val PEAK_REFINE = 32
}
