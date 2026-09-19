/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The equaliser's curves, written down and read back.
 *
 * This is the only part of the equaliser that outlives the process, which makes it the only part
 * where a mistake is permanent: a curve that encodes wrongly is not a glitch to be re-tapped, it is
 * work that was already done and is now gone - and an AutoEQ correction is minutes of searching for
 * one specific pair of headphones, not a preset anyone could rebuild by ear.
 *
 * So the round trip is asserted on the awkward shapes rather than on a flat curve: shelves, a Q that
 * is not 1, frequencies that are not round numbers, and names containing the characters the format
 * itself uses as separators.
 */
class EqProfilesTest {

    private val correction = listOf(
        EqBand(freqHz = 105f, gainDb = -3.4f, q = 0.7f, type = EqBandType.LOW_SHELF),
        EqBand(freqHz = 1050f, gainDb = 2.25f, q = 1.41f, type = EqBandType.PEAKING),
        EqBand(freqHz = 9800f, gainDb = 4.5f, q = 0.62f, type = EqBandType.HIGH_SHELF),
    )

    @Test
    fun `a curve survives the round trip exactly`() {
        // Fields rather than twelve gains, which is the whole reason for the format: storing gains
        // alone would turn a correction into a set of numbers applied to the wrong frequencies, and
        // it would still look like a curve afterwards.
        assertEquals(correction, EqProfiles.decodeBands(EqProfiles.encodeBands(correction)))
    }

    @Test
    fun `the default twelve-band curve survives`() {
        val bands = Equalizer.DEFAULT_BANDS.mapIndexed { i, b -> b.copy(gainDb = i - 6f) }
        assertEquals(bands, EqProfiles.decodeBands(EqProfiles.encodeBands(bands)))
    }

    @Test
    fun `profiles survive with their names`() {
        val profiles = listOf(
            EqProfile("Rock", correction),
            EqProfile("HD 600", Equalizer.DEFAULT_BANDS),
        )
        assertEquals(profiles, EqProfiles.decode(EqProfiles.encode(profiles)))
    }

    @Test
    fun `a name containing the separators survives`() {
        // The separators are a tab and a newline, and the name is typed by a person. Escaping rather
        // than refusing the character: a name is a label, and rejecting one because of how the
        // storage happens to be punctuated is the format showing through to someone who cannot see
        // it. Without the escape this name would split into two records, one of them unparseable.
        val awkward = EqProfile("my\tcurve\nv2 \\ final", correction)
        val read = EqProfiles.decode(EqProfiles.encode(listOf(awkward)))
        assertEquals(listOf(awkward), read)
    }

    // ---- what must not get through ------------------------------------------------------------

    @Test
    fun `a malformed curve reads as nothing rather than as a broken filter`() {
        // These reach the audio thread. setPeaking divides by Q and by the sample rate, so a zero or
        // a NaN out of a corrupt file is not a wrong-sounding curve, it is a filter that outputs
        // silence or noise - and the failure would appear as "the app has no sound" long after the
        // file that caused it was written.
        assertNull(EqProfiles.decodeBands("1000,0,0,PEAKING"))
        assertNull(EqProfiles.decodeBands("0,0,1,PEAKING"))
        assertNull(EqProfiles.decodeBands("-100,0,1,PEAKING"))
        assertNull(EqProfiles.decodeBands("NaN,0,1,PEAKING"))
        assertNull(EqProfiles.decodeBands("1000,NaN,1,PEAKING"))
        assertNull(EqProfiles.decodeBands("Infinity,0,1,PEAKING"))
    }

    @Test
    fun `a curve that is not a curve reads as nothing`() {
        assertNull(EqProfiles.decodeBands(""))
        assertNull(EqProfiles.decodeBands("   "))
        assertNull(EqProfiles.decodeBands("1000,0,1"))
        assertNull(EqProfiles.decodeBands("1000,0,1,SOMETHING"))
        assertNull(EqProfiles.decodeBands("not a curve at all"))
        // One bad band discards the whole curve rather than silently dropping a band, which would
        // leave a correction with a hole in it that still looks like a correction.
        assertNull(EqProfiles.decodeBands("1000,0,1,PEAKING;broken"))
    }

    @Test
    fun `a newer schema reads as empty rather than as a guess`() {
        // Its curves are not readable here, and guessing would apply the wrong ones. Reading empty
        // loses the overrides for this run but not the file - nothing rewrites it until a Save.
        assertTrue(EqProfiles.decode("v2\nRock\t1000,0,1,PEAKING").isEmpty())
        assertTrue(EqProfiles.decode("garbage").isEmpty())
        assertTrue(EqProfiles.decode("").isEmpty())
    }

    @Test
    fun `one unreadable profile does not take the others with it`() {
        val text = EqProfiles.encode(listOf(EqProfile("Rock", correction))) + "\nBroken\tnonsense\n\t\n"
        assertEquals(listOf(EqProfile("Rock", correction)), EqProfiles.decode(text))
    }

    // ---- factory curves -----------------------------------------------------------------------

    @Test
    fun `every built-in preset has a factory curve`() {
        // The panel falls back to this whenever a name has no saved override, so a preset without
        // one is a chip that does nothing when tapped.
        Equalizer.PRESETS.keys.forEach { name ->
            assertNotNull("$name has no factory curve", EqProfiles.factoryDefault(name))
        }
    }

    @Test
    fun `a factory curve uses the app's own band centres`() {
        val rock = EqProfiles.factoryDefault("Rock")!!
        assertEquals(Equalizer.DEFAULT_FREQUENCIES, rock.map { it.freqHz })
        assertEquals(Equalizer.PRESETS.getValue("Rock"), rock.map { it.gainDb })
    }

    @Test
    fun `a name nobody defined has no factory curve`() {
        assertNull(EqProfiles.factoryDefault("HD 600"))
    }

    // ---- the compressor -----------------------------------------------------------------------

    @Test
    fun `the compressor's dials survive the round trip`() {
        val prefs = CompressorPrefs(
            enabled = true,
            thresholdDb = -18.5f,
            ratio = 3.25f,
            attackMs = 2.5f,
            releaseMs = 180f,
            makeupGainDb = 4.5f,
        )
        assertEquals(prefs, EqProfiles.decodeCompressor(EqProfiles.encodeCompressor(prefs)))
    }

    @Test
    fun `a malformed compressor reads as nothing`() {
        assertNull(EqProfiles.decodeCompressor(""))
        assertNull(EqProfiles.decodeCompressor("true,-18,4,5,200"))
        assertNull(EqProfiles.decodeCompressor("yes,-18,4,5,200,3"))
        // NaN is the one value Compressor's own coercion cannot rescue: coerceIn leaves it NaN, and
        // a NaN threshold turns every comparison in the envelope follower false.
        assertNull(EqProfiles.decodeCompressor("true,NaN,4,5,200,3"))
        assertNull(EqProfiles.decodeCompressor("true,-18,Infinity,5,200,3"))
    }

    @Test
    fun `an out-of-range dial is left for the compressor to clamp`() {
        // Deliberately not rejected here. Every one of these is coerced into range on the way into
        // Compressor, so a silly value lands as the nearest legal one - refusing the whole snapshot
        // would throw away five good dials over one bad one.
        val read = EqProfiles.decodeCompressor("true,-500,999,0,99999,60")
        assertNotNull(read)
        val compressor = Compressor()
        read!!.applyTo(compressor)
        assertEquals(Compressor.MIN_THRESHOLD_DB, compressor.thresholdDb, 0.001f)
        assertEquals(Compressor.MAX_RATIO, compressor.ratio, 0.001f)
        assertEquals(Compressor.MIN_ATTACK_MS, compressor.attackMs, 0.001f)
        assertEquals(Compressor.MAX_RELEASE_MS, compressor.releaseMs, 0.001f)
        assertEquals(Compressor.MAX_MAKEUP_DB, compressor.makeupGainDb, 0.001f)
    }

    @Test
    fun `a snapshot of a compressor restores it`() {
        val original = Compressor().apply {
            enabled = true
            thresholdDb = -22f
            ratio = 6f
            attackMs = 12f
            releaseMs = 250f
            makeupGainDb = 2f
        }
        val restored = Compressor()
        EqProfiles.decodeCompressor(EqProfiles.encodeCompressor(CompressorPrefs.of(original)))!!
            .applyTo(restored)

        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.thresholdDb, restored.thresholdDb, 0.001f)
        assertEquals(original.ratio, restored.ratio, 0.001f)
        assertEquals(original.attackMs, restored.attackMs, 0.001f)
        assertEquals(original.releaseMs, restored.releaseMs, 0.001f)
        assertEquals(original.makeupGainDb, restored.makeupGainDb, 0.001f)
    }
}
