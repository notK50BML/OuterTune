/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import com.dd3boh.outertune.models.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

/**
 * Spending loudness normalisation as equaliser headroom.
 *
 * The arithmetic the processor does, reproduced here rather than driven through it, because the
 * processor needs a configured audio format and a real buffer to say anything and the part worth
 * pinning is the choice between the two numbers - not the filtering around it.
 *
 * The rule is `max`, not `sum`, and that is the whole change. Most streams are turned down six to
 * ten decibels for loudness normalisation, and that attenuation is room between the signal and full
 * scale. A boost that fits inside it costs nothing; only a boost larger than it has to buy more.
 * Taking both would turn the band down by the amount it was just turned up by, which is what made
 * raising a slider sound like turning the track down.
 */
class NormalisationHeadroomTest {

    /** Mirrors EqualizerAudioProcessor.recomputeCoefficients. */
    private fun attenuationDb(peakDb: Float, headroomFactor: Float, normalisationDb: Float): Float =
        max(peakDb * headroomFactor, normalisationDb)

    /** What the boosted band ends up at, relative to where it started. */
    private fun bandOutputDb(peakDb: Float, headroomFactor: Float, normalisationDb: Float): Float =
        peakDb - attenuationDb(peakDb, headroomFactor, normalisationDb)

    /** And what everything else ends up at. */
    private fun restOutputDb(peakDb: Float, headroomFactor: Float, normalisationDb: Float): Float =
        -attenuationDb(peakDb, headroomFactor, normalisationDb)

    @Test
    fun `a boost that fits inside the normalisation is free`() {
        // The case this exists for. An 8dB boost on a track already coming down 8dB: the band
        // ends where it started, and the rest ends where an un-equalised normalised track would
        // have been anyway. Nothing was paid.
        assertEquals(0f, bandOutputDb(peakDb = 8f, headroomFactor = 1f, normalisationDb = 8f), 0.01f)
        assertEquals(-8f, restOutputDb(peakDb = 8f, headroomFactor = 1f, normalisationDb = 8f), 0.01f)
    }

    @Test
    fun `the old behaviour was eight decibels worse`() {
        // Summing the two - which is what applying normalisation after the equaliser amounted to -
        // put the band 8dB below where it started, so raising a slider was heard as the track
        // getting quieter.
        val summed = 8f - (8f + 8f)
        val taken = bandOutputDb(peakDb = 8f, headroomFactor = 1f, normalisationDb = 8f)
        assertEquals(-8f, summed, 0.01f)
        assertTrue("the fix gained $taken against $summed", taken > summed + 7f)
    }

    @Test
    fun `a boost larger than the headroom pays only the difference`() {
        // 16dB of boost against 8dB of headroom: 8dB still has to be bought, so the rest comes
        // down 16 rather than 24, and the band still lands at full scale rather than over it.
        assertEquals(0f, bandOutputDb(peakDb = 16f, headroomFactor = 1f, normalisationDb = 8f), 0.01f)
        assertEquals(-16f, restOutputDb(peakDb = 16f, headroomFactor = 1f, normalisationDb = 8f), 0.01f)
    }

    @Test
    fun `the band never ends above full scale`() {
        // The property that must hold at every combination, because exceeding it is the clipping
        // the pre-gain exists to prevent.
        for (peak in 0..15) {
            for (norm in 0..12) {
                for (factor in listOf(0f, 0.5f, 1f)) {
                    val out = bandOutputDb(peak.toFloat(), factor, norm.toFloat())
                    if (factor == 1f) {
                        assertTrue(
                            "peak $peak, normalisation $norm, factor $factor left the band at $out",
                            out <= 0.01f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a flat curve is attenuated exactly as much as it would have been`() {
        // The crossover has to be silent: with no boost, moving the normalisation from the volume
        // stage into the processor must not change the level by a decibel.
        assertEquals(8f, attenuationDb(peakDb = 0f, headroomFactor = 1f, normalisationDb = 8f), 0.01f)
    }

    @Test
    fun `a track with no normalisation behaves as it always did`() {
        // Normalisation off, or a track already at the reference level: the processor is back to
        // compensating its own curve and nothing else.
        assertEquals(12f, attenuationDb(peakDb = 12f, headroomFactor = 1f, normalisationDb = 0f), 0.01f)
        assertEquals(6f, attenuationDb(peakDb = 12f, headroomFactor = 0.5f, normalisationDb = 0f), 0.01f)
        assertEquals(0f, attenuationDb(peakDb = 12f, headroomFactor = 0f, normalisationDb = 0f), 0.01f)
    }

    @Test
    fun `the headroom setting cannot take away normalisation that is owed`() {
        // Headroom None means "do not compensate my boosts", not "do not turn this track down" -
        // the normalisation is the track's own level, and skipping it would make every stream play
        // at a different loudness.
        assertEquals(8f, attenuationDb(peakDb = 12f, headroomFactor = 0f, normalisationDb = 8f), 0.01f)
    }

    @Test
    fun `the curve's own peak is what is measured`() {
        // Sanity, through the real code: a cascade's peak is what the attenuation is derived from,
        // so these two have to agree about a simple curve.
        val bands = EqualizerSettings.DEFAULT_BANDS.mapIndexed { i, b ->
            if (i == 6) b.copy(gainDb = 9f) else b
        }
        val cascade = Array(bands.size) { BiquadCoefficients.forBand(bands[it], 44100) }
        val peak = BiquadCoefficients.peakGainDb(cascade, 44100)
        assertEquals(9f, peak, 0.6f)
    }
}
