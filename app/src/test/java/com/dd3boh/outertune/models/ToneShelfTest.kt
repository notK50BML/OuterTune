/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.ui.component.responseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The bass and treble controls, now that they are shelves rather than a group of bands.
 *
 * The thing worth pinning is the shape. A shelf lifts everything past its corner and levels off,
 * so the curve rises toward the edge of the spectrum and stays there; the band-group shortcut this
 * replaced produced a lump centred on whichever bands it had written to, which is a different
 * filter that happens to be reachable with the same knob.
 */
class ToneShelfTest {

    private val sampleRate = 44100

    private fun indexFor(hz: Float, points: Int): Int {
        val logLow = ln(20f)
        val logSpan = ln(20_000f) - logLow
        return (((ln(hz) - logLow) / logSpan) * (points - 1)).toInt()
    }

    private fun curveFor(settings: EqualizerSettings) = responseCurve(settings.allFilters(), sampleRate)

    @Test
    fun `no tone means no extra filters`() {
        val flat = EqualizerSettings()
        assertEquals(flat.bands, flat.allFilters())
    }

    @Test
    fun `a bass shelf lifts the bottom and leaves the top alone`() {
        val curve = curveFor(EqualizerSettings(bassShelfDb = 8f))
        val at30 = curve[indexFor(30f, curve.size)]
        val at10k = curve[indexFor(10_000f, curve.size)]
        assertTrue("30Hz only rose to $at30, expected near +8", at30 > 6f)
        assertTrue("10kHz moved to $at10k, it should not have", abs(at10k) < 1f)
    }

    @Test
    fun `a treble shelf lifts the top and leaves the bottom alone`() {
        val curve = curveFor(EqualizerSettings(trebleShelfDb = 8f))
        val at30 = curve[indexFor(30f, curve.size)]
        val at12k = curve[indexFor(12_000f, curve.size)]
        assertTrue("12kHz only rose to $at12k, expected near +8", at12k > 6f)
        assertTrue("30Hz moved to $at30, it should not have", abs(at30) < 1f)
    }

    @Test
    fun `a shelf levels off rather than peaking`() {
        // What makes it a shelf and not a wide band: past the corner it stops climbing. A peaking
        // filter in its place would come back down again, which is what the band-group version did
        // and why it read as a lump rather than as a tone control.
        val curve = curveFor(EqualizerSettings(bassShelfDb = 10f))
        val at60 = curve[indexFor(60f, curve.size)]
        val at30 = curve[indexFor(30f, curve.size)]
        val at20 = curve[indexFor(20f, curve.size)]
        assertTrue("the shelf fell away below its corner: 60Hz $at60, 30Hz $at30, 20Hz $at20",
            at30 >= at60 - 0.5f && at20 >= at30 - 0.5f)
    }

    @Test
    fun `cutting works in both directions`() {
        val curve = curveFor(EqualizerSettings(bassShelfDb = -8f))
        assertTrue(curve[indexFor(30f, curve.size)] < -6f)
    }

    @Test
    fun `the band faders are untouched by the tone controls`() {
        // The complaint that prompted this: turning a knob used to move four faders, so the panel
        // showed a curve nobody had dialled in by hand.
        val settings = EqualizerSettings(bassShelfDb = 10f, trebleShelfDb = -4f)
        assertEquals(EqualizerSettings.DEFAULT_BANDS, settings.bands)
        assertEquals(EqualizerSettings.DEFAULT_BANDS.size + 2, settings.allFilters().size)
    }

    @Test
    fun `shelves survive the round trip`() {
        val settings = EqualizerSettings(enabled = true, bassShelfDb = 6.5f, trebleShelfDb = -3.5f)
        val read = EqualizerSettings.parse(settings.toJson()).getOrThrow()
        assertEquals(6.5f, read.bassShelfDb, 0.001f)
        assertEquals(-3.5f, read.trebleShelfDb, 0.001f)
    }

    @Test
    fun `a profile saved before shelves existed reads as having none`() {
        // Those files expressed their tone through the band gains instead, and those are still in
        // them - so flat shelves is the correct reading rather than a loss.
        val old = """{"schemaVersion":2,"enabled":true,"balance":0.0,"bands":[]}"""
        val read = EqualizerSettings.parse(old).getOrThrow()
        assertEquals(0f, read.bassShelfDb, 0.001f)
        assertEquals(0f, read.trebleShelfDb, 0.001f)
    }
}
