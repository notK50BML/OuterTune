/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the analyser's bands sit in frequency - what EqGraph draws the spectrum against.
 *
 * The graph once treated these as linear FFT bins, which put the lowest band at about 400Hz and
 * crowded the spectrum into the right of the curve. They are log-spaced from 40Hz.
 */
class SpectrumBandsTest {

    private val rate = 48_000
    private val bands = 28

    @Test
    fun `the bands run from 40Hz to 16kHz`() {
        assertEquals(40.0, SpectrumAnalyzer.bandEdgeHz(0.0, bands, rate), 0.001)
        assertEquals(16_000.0, SpectrumAnalyzer.bandEdgeHz(bands.toDouble(), bands, rate), 0.001)
    }

    @Test
    fun `the bands are evenly spaced in pitch, not in hertz`() {
        val ratios = (0 until bands).map {
            SpectrumAnalyzer.bandEdgeHz(it + 1.0, bands, rate) / SpectrumAnalyzer.bandEdgeHz(it.toDouble(), bands, rate)
        }
        ratios.forEach { assertEquals(ratios.first(), it, 1e-9) }
    }

    @Test
    fun `the lowest band is bass, not the low mids`() {
        assertTrue(SpectrumAnalyzer.bandEdgeHz(0.5, bands, rate) < 60.0)
    }
}
