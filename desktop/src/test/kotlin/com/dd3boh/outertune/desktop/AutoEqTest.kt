/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AutoEQ parser, against real file content.
 *
 * The sample below is the shape oratory1990's ParametricEQ.txt files actually have - a preamp line,
 * a low shelf, several peaking bands, a high shelf. Nothing here touches the network: the parse is
 * the part that can be wrong quietly, and a test that needed GitHub to be reachable would be a test
 * nobody runs.
 */
class AutoEqTest {

    private val sample = """
        Preamp: -6.8 dB
        Filter 1: ON LSC Fc 105 Hz Gain 4.9 dB Q 0.70
        Filter 2: ON PK Fc 21 Hz Gain 2.1 dB Q 0.42
        Filter 3: ON PK Fc 1050 Hz Gain -3.4 dB Q 1.85
        Filter 4: ON PK Fc 3300 Hz Gain 5.2 dB Q 2.60
        Filter 5: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
    """.trimIndent()

    @Test
    fun `every filter line becomes a band`() {
        val bands = AutoEq.parseParametricEq(sample)!!
        assertEquals(5, bands.size)
    }

    @Test
    fun `shelves are recognised as shelves`() {
        val bands = AutoEq.parseParametricEq(sample)!!
        assertEquals(EqBandType.LOW_SHELF, bands[0].type)
        assertEquals(EqBandType.PEAKING, bands[1].type)
        assertEquals(EqBandType.HIGH_SHELF, bands[4].type)
    }

    @Test
    fun `frequency gain and q are read off correctly`() {
        val band = AutoEq.parseParametricEq(sample)!![3]
        assertEquals(3300f, band.freqHz, 0.01f)
        assertEquals(5.2f, band.gainDb, 0.01f)
        assertEquals(2.6f, band.q, 0.01f)
    }

    @Test
    fun `the preamp line is not folded into the bands`() {
        // The specific mistake this parser exists to not repeat. A -6.8dB preamp is one global gain
        // stage; subtracting it from every band would turn each 0dB band into a real cut at its own
        // centre and leave a comb of dips rather than a level change.
        val bands = AutoEq.parseParametricEq(sample)!!
        assertEquals("gains were shifted by the preamp", 4.9f, bands[0].gainDb, 0.01f)
        assertEquals(-3.4f, bands[2].gainDb, 0.01f)
    }

    @Test
    fun `pass filters are dropped rather than reinterpreted`() {
        // Reading a high-pass as a peaking filter would put a bump exactly where the file asked for
        // a rolloff, which is worse than not applying it at all.
        val withPass = sample + "\nFilter 6: ON HPQ Fc 30 Hz Gain 0.0 dB Q 0.70"
        assertEquals(5, AutoEq.parseParametricEq(withPass)!!.size)
    }

    @Test
    fun `unparseable lines are skipped, not fatal`() {
        val messy = "# a comment\n\nnonsense here\n" + sample + "\nFilter 9: OFF PK Fc 500 Hz"
        val bands = AutoEq.parseParametricEq(messy)!!
        assertEquals(5, bands.size)
    }

    @Test
    fun `a file with no filters gives nothing rather than an empty curve`() {
        // Null rather than an empty list, so a failed fetch cannot silently flatten the equaliser.
        assertNull(AutoEq.parseParametricEq("Preamp: -3.0 dB"))
        assertNull(AutoEq.parseParametricEq(""))
    }

    @Test
    fun `absurd values are clamped`() {
        val extreme = """
            Filter 1: ON PK Fc 99000 Hz Gain 90.0 dB Q 200.00
            Filter 2: ON PK Fc 1 Hz Gain -90.0 dB Q 0.01
        """.trimIndent()
        val bands = AutoEq.parseParametricEq(extreme)!!
        assertEquals(AutoEq.MAX_FREQ_HZ, bands[0].freqHz, 0.01f)
        assertEquals(AutoEq.MAX_GAIN_DB, bands[0].gainDb, 0.01f)
        assertEquals(AutoEq.MAX_Q, bands[0].q, 0.01f)
        assertEquals(AutoEq.MIN_FREQ_HZ, bands[1].freqHz, 0.01f)
        assertEquals(AutoEq.MIN_GAIN_DB, bands[1].gainDb, 0.01f)
    }

    @Test
    fun `a parsed curve produces the response it describes`() {
        // End to end: the point of parsing a shelf as a shelf is that the correction keeps going
        // below its corner frequency instead of falling back to flat. A peaking filter at 105Hz
        // would be near zero by 30Hz; the low shelf must still be lifting there.
        val bands = AutoEq.parseParametricEq(sample)!!
        val curve = EqResponse.curve(bands, 44_100)
        fun at(hz: Double): Float {
            val fraction = kotlin.math.ln(hz / EqResponse.MIN_HZ) /
                kotlin.math.ln(EqResponse.MAX_HZ / EqResponse.MIN_HZ)
            return curve[(fraction * (curve.size - 1)).toInt().coerceIn(0, curve.size - 1)]
        }
        assertTrue("30Hz reads ${at(30.0)}dB, the low shelf is not holding", at(30.0) > 3.5f)
        assertTrue("3.3kHz reads ${at(3300.0)}dB, expected a boost", at(3300.0) > 4f)
        assertTrue("1kHz reads ${at(1050.0)}dB, expected a cut", at(1050.0) < -2f)
        assertTrue("16kHz reads ${at(16000.0)}dB, the high shelf is not holding", at(16000.0) < -1.5f)
    }
}
