/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.models.EqualizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The numbers behind the response curve.
 *
 * Only the maths is tested, not the drawing - but the maths is the part that can be wrong in a way
 * nobody would notice, because a plausible-looking curve over the wrong filter still looks like a
 * curve. What makes that unlikely here is that the points come from the same
 * [com.dd3boh.outertune.audio.BiquadCoefficients] the audio thread uses, so these mostly pin that
 * the wiring stays that way.
 */
class EqResponseCurveTest {

    private val sampleRate = 44100

    private fun bands(vararg gains: Float) = EqualizerSettings.DEFAULT_BANDS.mapIndexed { i, band ->
        band.copy(gainDb = gains.getOrElse(i) { 0f })
    }

    /** Index into the curve for a frequency, matching responseCurve's own log spacing. */
    private fun indexFor(hz: Float, points: Int): Int {
        val logLow = ln(20f)
        val logSpan = ln(20_000f) - logLow
        return (((ln(hz) - logLow) / logSpan) * (points - 1)).toInt()
    }

    @Test
    fun `a flat curve is flat`() {
        val curve = responseCurve(bands(), sampleRate)
        assertTrue(curve.isNotEmpty())
        curve.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test
    fun `a boosted band raises the curve at its own frequency`() {
        // 1kHz is band 6, clear of its neighbours, so the curve there should be close to the gain
        // asked for rather than to the overlap of several bands.
        val curve = responseCurve(bands(0f, 0f, 0f, 0f, 0f, 0f, 8f), sampleRate)
        val at1k = curve[indexFor(1_000f, curve.size)]
        assertTrue("1kHz measured $at1k, expected near +8", abs(at1k - 8f) < 0.6f)
    }

    @Test
    fun `a cut lowers it`() {
        val curve = responseCurve(bands(0f, 0f, 0f, 0f, 0f, 0f, -8f), sampleRate)
        assertTrue(curve[indexFor(1_000f, curve.size)] < -7f)
    }

    @Test
    fun `a band leaves the far end of the spectrum alone`() {
        val curve = responseCurve(bands(0f, 0f, 0f, 0f, 0f, 0f, 10f), sampleRate)
        assertTrue("a 1kHz band moved 20Hz", abs(curve.first()) < 1f)
        assertTrue("a 1kHz band moved 20kHz", abs(curve.last()) < 1f)
    }

    @Test
    fun `adjacent boosts sum into one larger hump`() {
        // The reason the graph exists at all: two neighbouring bands at +6 do not make two +6
        // bumps, and no arrangement of slider positions can show that.
        val single = responseCurve(bands(0f, 0f, 0f, 0f, 0f, 6f), sampleRate)
        val pair = responseCurve(bands(0f, 0f, 0f, 0f, 0f, 6f, 6f), sampleRate)
        val at500 = indexFor(500f, single.size)
        assertTrue(
            "two adjacent boosts measured ${pair[at500]} against one at ${single[at500]}",
            pair[at500] > single[at500] + 1f,
        )
    }

    @Test
    fun `a low-pass shows up even though it has no gain`() {
        // The case a "skip bands with no gain" shortcut gets wrong: a pass filter has no gainDb at
        // all, so a curve that only drew bands with one would draw a flat line over a filter that
        // is removing half the spectrum.
        val lowPass = listOf(
            EqualizerSettings.EqBand(
                freqHz = 1_000f,
                gainDb = 0f,
                type = EqualizerSettings.FilterType.LOW_PASS,
            )
        )
        val curve = responseCurve(lowPass, sampleRate)
        assertTrue("20Hz should pass", abs(curve.first()) < 1f)
        assertTrue("16kHz should be well down, measured ${curve[indexFor(16_000f, curve.size)]}",
            curve[indexFor(16_000f, curve.size)] < -20f)
    }

    @Test
    fun `nothing above Nyquist drops off a cliff`() {
        // A 22.05kHz file has Nyquist at 11kHz, inside the plotted range. The formulae are not
        // defined past it, and a curve that fell to the floor there would draw a cliff that is not
        // a filter anybody applied.
        val curve = responseCurve(bands(0f, 0f, 0f, 0f, 0f, 0f, 6f), 22_050)
        val lastFew = curve.takeLast(20)
        lastFew.forEach { assertTrue("the curve fell off past Nyquist: $it", it > -1f) }
    }

    // ---- the vertical scale -----------------------------------------------------------------------

    @Test
    fun `the plot uses the faders' own scale`() {
        // The whole point of the curve sitting under the faders: a band at +6 puts its thumb 70% of
        // the way up a +/-15dB track, and the curve has to be 70% of the way up the plot at the same
        // place. A plot scaled to its own contents drew +3 and +12 as the same hump and stopped
        // following the faders at all.
        assertEquals(EqualizerSettings.MAX_GAIN_DB, rangeFor(floatArrayOf(3f)), 0.0001f)
        assertEquals(EqualizerSettings.MAX_GAIN_DB, rangeFor(floatArrayOf(12f)), 0.0001f)
        assertEquals(EqualizerSettings.MAX_GAIN_DB, rangeFor(FloatArray(0)), 0.0001f)
    }

    @Test
    fun `a larger boost draws a taller curve`() {
        // Which only holds while the scale stays put - exactly what continuous rescaling broke.
        assertEquals(rangeFor(floatArrayOf(3f)), rangeFor(floatArrayOf(12f)), 0.0001f)
    }

    @Test
    fun `the scale holds still across a whole drag`() {
        val scales = generateSequence(0f) { it + 0.1f }.takeWhile { it <= 15f }
            .map { rangeFor(floatArrayOf(it)) }.toSet()
        assertEquals("the scale changed mid-drag: $scales", 1, scales.size)
    }

    @Test
    fun `a curve beyond the faders' range is still fully drawn`() {
        // Several adjacent bands at the top, or a band plus a shelf, can sum past 15dB. The plot
        // grows rather than clipping it off the top.
        assertTrue(rangeFor(floatArrayOf(20f)) >= 20f)
    }
}
