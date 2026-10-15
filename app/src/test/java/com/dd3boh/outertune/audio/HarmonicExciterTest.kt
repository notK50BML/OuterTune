/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether the exciter puts energy where it is supposed to, and - the part that actually matters -
 * whether it puts any anywhere else.
 *
 * A waveshaper generates harmonics of everything it is given, harmonics above Nyquist included,
 * and those fold back down as inharmonic noise spread across the whole spectrum. That is the
 * failure mode for this entire approach: not "it does nothing" but "it adds grit to the midrange",
 * which is both worse than doing nothing and hard to attribute by ear to the feature that caused
 * it.
 *
 * So these measure the spectrum rather than listening to it. The content is checked where it
 * belongs, and then checked for absence in the two places folding would put it.
 */
class HarmonicExciterTest {

    private val sampleRate = 44100
    private val cutoff = 16_000f

    /** Magnitude of one frequency in [signal], by direct evaluation - no FFT needed for one bin. */
    private fun magnitudeAt(signal: FloatArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (n in signal.indices) {
            val angle = 2.0 * PI * hz * n / sampleRate
            re += signal[n] * cos(angle)
            im -= signal[n] * sin(angle)
        }
        return 2.0 * sqrt(re * re + im * im) / signal.size
    }

    private fun db(x: Double) = if (x <= 1e-12) -240.0 else 20.0 * log10(x)

    private fun tone(hz: Double, amplitude: Float = 0.5f, samples: Int = 8192) =
        FloatArray(samples) { amplitude * sin(2.0 * PI * hz * it / sampleRate).toFloat() }

    private fun exciter(
        amount: Float = 1f,
        evenness: Float = HarmonicExciter.DEFAULT_EVENNESS,
    ) = HarmonicExciter().apply {
        enabled = true
        this.amount = amount
        this.evenness = evenness
        assertTrue("configure refused a stream it should accept", configure(sampleRate, 1, cutoff))
    }

    private fun run(exciter: HarmonicExciter, input: FloatArray): FloatArray {
        // The first stretch is the filters settling from zero state; measuring through it would
        // read the transient as spectral content that is not there in steady state.
        val out = FloatArray(input.size)
        for (i in input.indices) out[i] = exciter.process(input[i], channel = 0)
        return out.copyOfRange(input.size / 2, input.size)
    }

    // ---- that it does the thing ----------------------------------------------------------------

    @Test
    fun `a tone below the cutoff gains a second harmonic above it`() {
        // The whole purpose: content at 9kHz, which survived the codec, producing content at 18kHz,
        // which did not. Measured against the same tone through a bypassed exciter, so what is
        // being compared is the effect and not the tone's own leakage.
        val input = tone(9_000.0)
        val excited = run(exciter(), input)

        val reference = run(HarmonicExciter(), input) // never configured: inert
        val generated = db(magnitudeAt(excited, 18_000.0))
        val baseline = db(magnitudeAt(reference, 18_000.0))

        assertTrue(
            "no second harmonic appeared: $generated dB against a baseline of $baseline dB",
            generated > baseline + 20.0,
        )
    }

    @Test
    fun `the fundamental is left alone`() {
        // It mixes the generated band on top of the original rather than replacing anything, so
        // the music itself must come through at the level it arrived. A high-pass that leaked
        // would thin out everything below the cutoff, which is all of the music.
        val input = tone(9_000.0)
        val excited = run(exciter(), input)
        val reference = run(HarmonicExciter(), input)

        val before = db(magnitudeAt(reference, 9_000.0))
        val after = db(magnitudeAt(excited, 9_000.0))
        assertEquals("the fundamental moved from $before to $after", before, after, 0.5)
    }

    // ---- that it does not do the other thing ---------------------------------------------------

    @Test
    fun `nothing is generated below the cutoff`() {
        // Where intermodulation and the shaper's own low-order products would land. This is on top
        // of real music, so it is far more audible than anything above 16kHz, and it is what the
        // output high-pass exists to remove.
        val input = tone(9_000.0)
        val excited = run(exciter(), input)
        val reference = run(HarmonicExciter(), input)

        for (probe in listOf(500.0, 2_000.0, 4_500.0, 6_000.0, 12_000.0)) {
            val added = db(magnitudeAt(excited, probe))
            val baseline = db(magnitudeAt(reference, probe))
            assertTrue(
                "generated ${added}dB at ${probe}Hz, below the cutoff (baseline $baseline)",
                added < baseline + 3.0 || added < -70.0,
            )
        }
    }

    @Test
    fun `a tone at the top of the input band does not alias`() {
        // The case the band planning exists for. 11025Hz is sampleRate/4, the highest frequency
        // the exciter will feed its shaper, and its second harmonic lands exactly on Nyquist. A
        // design that excited anything above this would fold that harmonic back down into the
        // audible range as an inharmonic tone - which is the characteristic sound of a badly built
        // exciter, and is heard as static rather than as brightness.
        val input = tone(11_025.0)
        val excited = run(exciter(), input)
        val reference = run(HarmonicExciter(), input)

        // Where the third harmonic (33075Hz) would fold to: 44100 - 33075 = 11025, and its
        // neighbours. Checked across the region rather than at one bin, since the fold lands
        // wherever the harmonic is.
        for (probe in listOf(1_000.0, 3_000.0, 5_000.0, 8_000.0, 11_000.0)) {
            val added = magnitudeAt(excited, probe)
            val baseline = magnitudeAt(reference, probe)
            assertTrue(
                "aliased content at ${probe}Hz: ${db(added)}dB against ${db(baseline)}dB",
                db(added) < db(baseline) + 3.0 || db(added) < -60.0,
            )
        }
    }

    @Test
    fun `content already above the cutoff is not excited again`() {
        // A tone at 18kHz is in the band being filled, not in the band being read, so it must not
        // feed the shaper - otherwise a stream whose cutoff was guessed too low would compound its
        // own output every time it came round.
        val input = tone(18_000.0)
        val excited = run(exciter(), input)
        val reference = run(HarmonicExciter(), input)

        val before = db(magnitudeAt(reference, 18_000.0))
        val after = db(magnitudeAt(excited, 18_000.0))
        assertEquals("an 18kHz tone was amplified from $before to $after", before, after, 1.0)
    }

    // ---- that it does enough of it ---------------------------------------------------------------

    /**
     * Noise brickwalled at the cutoff - what a lossy stream actually looks like, as opposed to the
     * loud single tones above.
     */
    private fun lossyNoise(samples: Int = 16384): FloatArray {
        val rng = kotlin.random.Random(42)
        val raw = FloatArray(samples) { (rng.nextFloat() * 2f - 1f) * 0.3f }
        val lp = BiquadCoefficients.forBand(
            com.dd3boh.outertune.models.EqualizerSettings.EqBand(
                freqHz = cutoff,
                q = 0.7071f,
                type = com.dd3boh.outertune.models.EqualizerSettings.FilterType.LOW_PASS,
            ),
            sampleRate,
        )
        val states = Array(6) { BiquadState() }
        return FloatArray(samples) { i ->
            var x = raw[i]
            for (state in states) x = state.process(x, lp)
            x
        }
    }

    /** Mean energy across a band, which is the honest measure for noise - one bin of it is noise. */
    private fun bandDb(signal: FloatArray, lowHz: Double, highHz: Double): Double {
        var total = 0.0
        var bins = 0
        var hz = lowHz
        while (hz <= highHz) {
            val m = magnitudeAt(signal, hz)
            total += m * m
            bins++
            hz += 250.0
        }
        return if (bins == 0 || total <= 0.0) -240.0 else 10.0 * log10(total / bins)
    }

    @Test
    fun `realistic band-limited content is excited by a usable amount`() {
        // The test the tone cases could not be. A saturator's harmonic output grows as the square
        // of its input, and a 0.5-amplitude tone is nothing like the top octave of real music -
        // measured on brickwalled noise that band sits near -55dBFS, where an undriven shaper is
        // indistinguishable from a wire.
        //
        // The first version of this had no drive stage at all. It passed every tone test above and
        // generated, on this signal, 2.6dB - which is the measurement floor, not an effect.
        val whole = lossyNoise()
        val reference = whole.copyOfRange(whole.size / 2, whole.size)
        val excited = run(exciter(amount = HarmonicExciter.DEFAULT_AMOUNT), whole)

        val before = bandDb(reference, 16_500.0, 21_000.0)
        val after = bandDb(excited, 16_500.0, 21_000.0)
        assertTrue(
            "only ${"%.1f".format(after - before)}dB was generated into the empty band",
            after - before > 10.0,
        )

        // And it has to land somewhere plausible relative to the music, not just be loud. Natural
        // air sits below the presence region, never level with it.
        val presence = bandDb(excited, 8_000.0, 15_000.0)
        assertTrue(
            "the restored band is ${"%.1f".format(after - presence)}dB against 8-15kHz, which is too loud to be air",
            after - presence < -5.0,
        )
    }

    @Test
    fun `the strength settings actually differ`() {
        // Three settings that measured identical to four decimal places is exactly how the missing
        // drive stage showed up: not as an error, but as a control that did nothing.
        val whole = lossyNoise()
        val levels = listOf(0.3f, 0.5f, 0.8f).map { amount ->
            bandDb(run(exciter(amount = amount), whole), 16_500.0, 21_000.0)
        }
        assertTrue("subtle and balanced measured the same: $levels", levels[1] - levels[0] > 1.5)
        assertTrue("balanced and strong measured the same: $levels", levels[2] - levels[1] > 1.5)
    }

    @Test
    fun `the music below the cutoff is not touched at any strength`() {
        // Where the damage would be if the drive were too high: intermodulation products land on
        // top of real music, which is far more audible than anything above 16kHz.
        val whole = lossyNoise()
        val reference = whole.copyOfRange(whole.size / 2, whole.size)
        val before = bandDb(reference, 1_000.0, 5_000.0)
        listOf(0.3f, 0.5f, 0.8f).forEach { amount ->
            val after = bandDb(run(exciter(amount = amount), whole), 1_000.0, 5_000.0)
            assertEquals("the midrange moved at amount $amount", before, after, 0.2)
        }
    }

    // ---- when it must refuse --------------------------------------------------------------------

    @Test
    fun `a stream with no gap is refused`() {
        val exciter = HarmonicExciter()
        assertTrue(
            "a 16kHz cutoff at 44.1kHz has a gap and should be accepted",
            exciter.configure(44_100, 2, 16_000f),
        )
        // At or above Nyquist there is nothing above the cutoff to fill.
        assertTrue(!exciter.configure(44_100, 2, 22_050f))
        assertTrue(!exciter.configure(44_100, 2, 30_000f))
        // And a cutoff close enough to Nyquist that the gap is narrower than the error in knowing
        // where the cutoff is.
        assertTrue(!exciter.configure(44_100, 2, 21_000f))
    }

    @Test
    fun `a multichannel stream does not index past its filters`() {
        // This crashed. The filter arrays were fixed at two channels while the bounds check
        // compared against the stream's real channel count, so the first six-channel file indexed
        // a two-element array - on the audio thread, where an exception takes playback with it.
        //
        // Six because that is 5.1, which arrives from a local file rather than from YouTube, and
        // is exactly the case nobody tests by accident.
        val exciter = HarmonicExciter().apply {
            enabled = true
            amount = 1f
            assertTrue(configure(sampleRate, 6, cutoff))
        }
        val input = tone(9_000.0, samples = 512)
        for (channel in 0 until 6) {
            for (sample in input) exciter.process(sample, channel)
        }
    }

    @Test
    fun `a channel beyond the stream is passed through rather than crashing`() {
        val exciter = exciter() // configured for one channel
        assertEquals(0.5f, exciter.process(0.5f, channel = 7), 0f)
        assertEquals(0.5f, exciter.process(0.5f, channel = -1), 0f)
    }

    @Test
    fun `a stream with no gap does not switch the setting off`() {
        // The setting is the user's and survives a track that cannot use it. An earlier version
        // cleared `enabled` whenever a stream had no gap to fill, so one lossless track left the
        // feature off and every lossy track afterwards played flat with the switch still reading
        // on - the setting had been consumed rather than applied.
        val exciter = HarmonicExciter().apply {
            enabled = true
            amount = 1f
            assertTrue(configure(sampleRate, 2, cutoff))
        }
        assertTrue(exciter.isActive())

        // A lossless track: no cutoff, so nothing to plan.
        assertTrue(!exciter.configure(sampleRate, 2, 22_050f))
        assertTrue("it went inactive, which is right", !exciter.isActive())
        assertTrue("but the setting itself was cleared", exciter.enabled)

        // And a lossy one again.
        assertTrue(exciter.configure(sampleRate, 2, cutoff))
        assertTrue("the setting did not come back", exciter.isActive())
    }

    @Test
    fun `an inactive exciter passes samples through untouched`() {
        // Bit-exact, not approximately: the bypass path is what plays for every lossless file and
        // every user who leaves this off, which is most of them.
        val off = HarmonicExciter().apply { configure(sampleRate, 1, cutoff) }
        val input = tone(1_000.0, samples = 512)
        for (i in input.indices) {
            assertEquals(input[i], off.process(input[i], 0), 0f)
        }
    }

    @Test
    fun `zero amount is also a bypass`() {
        val zero = exciter(amount = 0f)
        val input = tone(9_000.0, samples = 512)
        for (i in input.indices) {
            assertEquals(input[i], zero.process(input[i], 0), 0f)
        }
    }

    // ---- choosing a cutoff -----------------------------------------------------------------------

    @Test
    fun `lossless and Opus are left alone`() {
        // Nothing to put back in the first case, and nothing worth putting back in the second -
        // Opus brickwalls at 20kHz, which at 48kHz leaves a gap narrower than anyone can hear.
        assertNull(HarmonicExciter.cutoffForMimeType("audio/flac", null))
        assertNull(HarmonicExciter.cutoffForMimeType("audio/alac", null))
        assertNull(HarmonicExciter.cutoffForMimeType("audio/webm; codecs=opus", 160_000))
        assertNull(HarmonicExciter.cutoffForMimeType("audio/wav", null))
    }

    @Test
    fun `an unknown format is left alone rather than guessed at`() {
        // Guessing wrong means generating harmonics into a band that already has music in it,
        // which is damage rather than a missed opportunity.
        assertNull(HarmonicExciter.cutoffForMimeType(null, 128_000))
        assertNull(HarmonicExciter.cutoffForMimeType("audio/something-new", 128_000))
    }

    @Test
    fun `a lower bitrate means a lower cutoff`() {
        val low = HarmonicExciter.cutoffForMimeType("audio/mp4a-latm", 96_000)!!
        val mid = HarmonicExciter.cutoffForMimeType("audio/mp4a-latm", 128_000)!!
        val high = HarmonicExciter.cutoffForMimeType("audio/mp4a-latm", 256_000)!!
        assertTrue("$low should be below $mid", low < mid)
        assertTrue("$mid should be below $high", mid < high)
    }
}
