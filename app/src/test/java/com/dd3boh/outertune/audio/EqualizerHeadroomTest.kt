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
import kotlin.math.abs

/**
 * Whether a preset can still push a full-scale track past full scale.
 *
 * This is the test that would have caught the distortion. The equaliser compensates for its own
 * boosts by attenuating the signal before the cascade runs, and the amount it took off used to be
 * an estimate - the worst band weighted at 0.8, the sum of the rest at 0.1. Measured against the
 * real response, that estimate was short on every boosting preset the app ships, by up to 3.5dB.
 *
 * Nothing announced that. The soft limiter caught the overshoot and the app kept playing, so the
 * only symptom was that boosted presets sounded gritty - which is indistinguishable from "that is
 * what a bass boost sounds like" unless somebody measures it.
 *
 * So this measures it, on every preset, every time.
 */
class EqualizerHeadroomTest {

    private val sampleRates = listOf(44100, 48000)

    private fun cascadeFor(gains: List<Float>, sampleRate: Int): Array<BiquadCoefficients> {
        val bands = EqualizerSettings.DEFAULT_BANDS.mapIndexed { i, band ->
            band.copy(gainDb = gains.getOrElse(i) { 0f })
        }
        return Array(bands.size) { BiquadCoefficients.forBand(bands[it], sampleRate) }
    }

    /** What the processor does: attenuate by the measured peak, capped. */
    private fun netPeakDb(gains: List<Float>, sampleRate: Int): Float {
        val cascade = cascadeFor(gains, sampleRate)
        val peak = BiquadCoefficients.peakGainDb(cascade, sampleRate)
        val headroom = peak.coerceIn(0f, 15f)
        return peak - headroom
    }

    @Test
    fun `no built-in preset ends up above full scale`() {
        // The headroom cap means this is not automatic: a preset whose peak exceeds the cap would
        // come out positive here, and the fix for that is a tamer preset, not a bigger cap.
        for (sampleRate in sampleRates) {
            EqualizerSettings.PRESETS.forEach { (name, gains) ->
                val net = netPeakDb(gains, sampleRate)
                assertTrue(
                    "\"$name\" peaks $net dB over full scale at ${sampleRate}Hz after headroom",
                    net <= 0.01f,
                )
            }
        }
    }

    @Test
    fun `the old estimate really was short, which is why this is measured`() {
        // Pins the reason for the change rather than only its effect. If someone reverts to the
        // estimate, this is the test that says what it costs - and it names the two presets that
        // were worst, so the failure is legible rather than a number out of context.
        fun estimated(gains: List<Float>): Float {
            val positive = gains.map { maxOf(it, 0f) }
            return minOf((positive.maxOrNull() ?: 0f) * 0.8f + positive.sum() * 0.1f, 12f)
        }

        listOf("Bass Boost", "Treble Boost", "Small Speakers").forEach { name ->
            val gains = EqualizerSettings.PRESETS.getValue(name)
            val measured = BiquadCoefficients.peakGainDb(cascadeFor(gains, 44100), 44100)
            assertTrue(
                "\"$name\": the estimate ${estimated(gains)} already covered the real peak $measured",
                estimated(gains) < measured - 1f,
            )
        }
    }

    @Test
    fun `a flat curve asks for no headroom`() {
        // The case that must cost nothing. A preset of all zeroes builds a cascade of identities,
        // and attenuating it would make switching the equaliser on a volume drop.
        for (sampleRate in sampleRates) {
            val peak = BiquadCoefficients.peakGainDb(cascadeFor(List(12) { 0f }, sampleRate), sampleRate)
            assertEquals("flat should measure 0dB, measured $peak", 0f, peak, 0.01f)
        }
    }

    @Test
    fun `a curve that only cuts asks for no headroom`() {
        // Already quieter than what it was handed. Taking more off would turn every cut-only
        // preset into a volume drop on top of the cut it was asked for.
        val cuts = listOf(-6f, -5f, -4f, -3f, -2f, -1f, 0f, -1f, -2f, -3f, -4f, -5f)
        val peak = BiquadCoefficients.peakGainDb(cascadeFor(cuts, 44100), 44100)
        assertEquals("a cut-only curve measured $peak dB of boost", 0f, peak, 0.01f)
    }

    @Test
    fun `one band on its own measures the gain it was given`() {
        // The sanity check the whole measurement rests on: with nothing to overlap, the peak of
        // the cascade is just that band's gain. If this drifts, every other number here is wrong
        // in a way no amount of listening would localise.
        for (gain in listOf(3f, 6f, 9f, 12f, 15f)) {
            val gains = MutableList(12) { 0f }.also { it[6] = gain } // 1kHz, clear of its neighbours
            val peak = BiquadCoefficients.peakGainDb(cascadeFor(gains, 44100), 44100)
            assertTrue(
                "a lone +$gain dB band measured $peak",
                abs(peak - gain) < 0.15f,
            )
        }
    }

    @Test
    fun `adjacent bands sum to more than either but less than both`() {
        // Why an estimate was never going to work. Three neighbouring boosts overlap, so the
        // result is neither the largest of them nor their total - and where it lands between the
        // two depends on the spacing, which a single weighting cannot know.
        val clustered = MutableList(12) { 0f }.also { it[1] = 6f; it[2] = 6f; it[3] = 6f }
        val peak = BiquadCoefficients.peakGainDb(cascadeFor(clustered, 44100), 44100)
        assertTrue("three adjacent +6 bands measured $peak, expected more than 6", peak > 7f)
        assertTrue("three adjacent +6 bands measured $peak, expected less than 18", peak < 18f)
    }

    @Test
    fun `bands far apart barely add at all`() {
        // The other end of the same point, and the one the old estimate's total-boost term got
        // wrong in the opposite direction: 31Hz and 12kHz do not overlap, so a curve boosting
        // both peaks at roughly one of them, not at their sum.
        val spread = MutableList(12) { 0f }.also { it[1] = 6f; it[10] = 6f }
        val peak = BiquadCoefficients.peakGainDb(cascadeFor(spread, 44100), 44100)
        assertTrue("two distant +6 bands measured $peak, expected close to 6", peak < 7.5f)
    }
}
