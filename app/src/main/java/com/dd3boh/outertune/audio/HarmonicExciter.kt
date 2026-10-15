/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import com.dd3boh.outertune.models.EqualizerSettings
import kotlin.math.abs

/**
 * Puts back the top octave a lossy codec threw away, by generating harmonics of what survived.
 *
 * ## Why this rather than DSRE
 *
 * DSRE - the tool this started from - takes the surviving top band and *frequency-shifts* copies of
 * it upward with a Hilbert transform, eight times, with exponential decay. Two things are wrong
 * with that here, and neither is a matter of taste.
 *
 * The first is practical: it is written offline. `filtfilt` is zero-phase, which means non-causal -
 * it runs the filter forwards over the whole array and then backwards over the whole array - and
 * `scipy.hilbert` is an FFT over the whole file. Neither exists for a player that has the next
 * 20ms and nothing else.
 *
 * The second is musical. Frequency shifting is not pitch scaling. A violin's partials at 4, 8 and
 * 12kHz shifted up by 6kHz land at 10, 14 and 18kHz - which is no longer a harmonic series of
 * anything, and the ear hears inharmonic content above a note as a separate metallic artefact
 * rather than as the note being brighter. Generating *harmonics* keeps the relationship: content
 * at 9kHz produces content at 18kHz, which is the octave above it, and that is what "the same
 * instrument with its air back" actually consists of.
 *
 * So the engine here is a harmonic exciter, the approach Calf's and ffmpeg's `aexciter` take
 * (Markus Schmidt and Christian Holschuh, LGPL-2.1+ - the shape of the chain is theirs; the
 * waveshaper below is written from the maths rather than ported, and the band planning is specific
 * to this problem). It is per-sample, allocation-free, and needs no transform.
 *
 * ## Where the bands come from, and why aliasing decides them
 *
 * A waveshaper generates harmonics of everything it is fed, including harmonics above Nyquist -
 * and those do not vanish, they fold back down as inharmonic noise across the whole spectrum. That
 * is the failure mode, it sounds like grit or static, and it is the reason the input band is
 * planned rather than chosen by ear:
 *
 * - **Input band: [cutoff/2, sampleRate/4].** The lower edge is half the codec's brickwall, so the
 *   second harmonic of the lowest excited content lands exactly at the cutoff - the bottom of the
 *   empty region. The upper edge is a quarter of the sample rate, so the second harmonic of the
 *   *highest* excited content lands exactly at Nyquist. Nothing the second harmonic produces can
 *   therefore alias, which is what makes this safe at 44.1kHz without oversampling.
 * - **Output band: [cutoff, Nyquist * 0.95].** The high-pass discards the fundamental the shaper
 *   was fed and the low-order intermodulation it produced underneath it, leaving only what belongs
 *   in the gap. The low-pass is the backstop for the third harmonic and above, which *would* alias
 *   - hence also [MAX_DRIVE], which keeps the shaper in the region where the second harmonic
 *   dominates rather than driving it into hard saturation where the high orders take over.
 *
 * None of that holds if the cutoff is wrong, which is why [configure] is given one rather than
 * guessing: a cutoff set above `sampleRate/2` leaves no gap to fill and the whole thing disables
 * itself instead of generating noise into a band that is already occupied.
 */
class HarmonicExciter {

    /** Nothing to do until [configure] has been given a sample rate and a codec cutoff. */
    @Volatile
    private var active = false

    @Volatile
    var enabled: Boolean = false

    /**
     * How hard the shaper is driven, 0..1.
     *
     * Not a volume. It sets how much of the excited band is converted into harmonics, and the
     * audible range is small: past about a third of the way up, the third and fifth harmonics
     * start to matter and those are the ones that alias.
     */
    @Volatile
    var amount: Float = DEFAULT_AMOUNT

    /**
     * How much even harmonic the shaper produces relative to odd, 0..1.
     *
     * Zero is a symmetric shaper: odd harmonics only, so the first thing generated is the third -
     * which is an octave and a fifth up, bright and a little hard. One is strongly asymmetric: the
     * second harmonic, a plain octave, which is the one that reads as "air" rather than as
     * distortion. It matters more than it sounds like it should, because the second harmonic is
     * also the only one guaranteed not to alias.
     */
    @Volatile
    var evenness: Float = DEFAULT_EVENNESS

    // Two cascaded sections at each corner rather than one. A single biquad rolls off at 12dB per
    // octave, which leaves a good deal of the fundamental still in the shaper's input and a good
    // deal of the shaper's low-frequency mess still in its output - and that mess sits directly on
    // top of real music, where it is far more audible than anything above 16kHz.
    private val inputHighPass = Array(2) { Array(2) { BiquadState() } }
    private val inputLowPass = Array(2) { Array(2) { BiquadState() } }
    private val outputHighPass = Array(2) { Array(2) { BiquadState() } }
    private val outputLowPass = Array(2) { Array(2) { BiquadState() } }

    private var inputHighPassCoeffs = BiquadCoefficients.IDENTITY
    private var inputLowPassCoeffs = BiquadCoefficients.IDENTITY
    private var outputHighPassCoeffs = BiquadCoefficients.IDENTITY
    private var outputLowPassCoeffs = BiquadCoefficients.IDENTITY

    private var channels = 0

    /**
     * Plans the bands for a stream whose content stops at [cutoffHz].
     *
     * Returns whether there is anything to do. False when the stream is already full-bandwidth -
     * a lossless file, or a codec whose cutoff is at or above what the sample rate can carry - in
     * which case there is no gap, and synthesising into an occupied band is strictly damage.
     */
    fun configure(sampleRateHz: Int, channelCount: Int, cutoffHz: Float): Boolean {
        active = false
        if (sampleRateHz <= 0 || channelCount <= 0) return false

        val nyquist = sampleRateHz / 2f
        // Needs real room above the cutoff to be worth anything: below this the generated band is
        // narrower than the error in knowing where the cutoff is.
        if (cutoffHz <= 0f || cutoffHz >= nyquist - MIN_GAP_HZ) return false

        val inputLowHz = cutoffHz / 2f
        val inputHighHz = sampleRateHz / 4f
        // Degenerate once the cutoff passes half of Nyquist: the input band inverts, and a
        // high-pass above a low-pass is a filter that passes nothing while still costing every
        // sample it is given.
        if (inputHighHz <= inputLowHz * INPUT_BAND_MIN_RATIO) return false

        inputHighPassCoeffs = passFilter(inputLowHz, sampleRateHz, high = true)
        inputLowPassCoeffs = passFilter(inputHighHz, sampleRateHz, high = false)
        outputHighPassCoeffs = passFilter(cutoffHz, sampleRateHz, high = true)
        outputLowPassCoeffs = passFilter(nyquist * CEILING_FRACTION, sampleRateHz, high = false)

        if (channels != channelCount) {
            channels = channelCount
        }
        reset()
        active = true
        return true
    }

    private fun passFilter(freqHz: Float, sampleRateHz: Int, high: Boolean) =
        BiquadCoefficients.forBand(
            EqualizerSettings.EqBand(
                freqHz = freqHz,
                gainDb = 0f,
                // Butterworth. Two of these in series is 4th order with a mild 1.3dB bump at the
                // corner rather than the 6dB peak two Q=1 sections would stack up to.
                q = BUTTERWORTH_Q,
                type = if (high) EqualizerSettings.FilterType.HIGH_PASS else EqualizerSettings.FilterType.LOW_PASS,
            ),
            sampleRateHz,
        )

    fun reset() {
        inputHighPass.forEach { chain -> chain.forEach { it.reset() } }
        inputLowPass.forEach { chain -> chain.forEach { it.reset() } }
        outputHighPass.forEach { chain -> chain.forEach { it.reset() } }
        outputLowPass.forEach { chain -> chain.forEach { it.reset() } }
    }

    /** Whether [process] will do anything at all, so a caller can skip decoding samples for it. */
    fun isActive(): Boolean = active && enabled && amount > 0f

    /**
     * One sample of one channel, in and out. Returns [sample] unchanged when inactive.
     *
     * Returns the *mixed* result rather than only the generated part: the dry signal has to be in
     * here anyway for the mix, and returning it means the caller does not have to remember to add
     * it back - which is the kind of thing that is forgotten once and then heard as the music
     * disappearing.
     */
    fun process(sample: Float, channel: Int): Float {
        if (!isActive() || channel >= channels) return sample

        // Band-limited to exactly what can be doubled without folding - see the class doc.
        var excited = sample
        for (section in inputHighPass[channel]) excited = section.process(excited, inputHighPassCoeffs)
        for (section in inputLowPass[channel]) excited = section.process(excited, inputLowPassCoeffs)

        excited = shape(excited)

        for (section in outputHighPass[channel]) excited = section.process(excited, outputHighPassCoeffs)
        for (section in outputLowPass[channel]) excited = section.process(excited, outputLowPassCoeffs)

        return sample + excited * amount * WET_TRIM
    }

    /**
     * The nonlinearity, and the whole source of the new content.
     *
     * A soft saturator, `x -> (1+k)x / (1+k|x|)`, which is symmetric and therefore produces odd
     * harmonics - third, fifth, seventh. Adding a bias before it and subtracting the bias's own
     * output afterwards breaks that symmetry and brings in the even harmonics, the second most of
     * all. [evenness] is how much bias.
     *
     * Subtracting `shape(bias)` matters and is easy to leave out: without it the shaper has a DC
     * offset proportional to the bias, which the output high-pass would mostly remove but which
     * would modulate with the signal on the way there. It is one multiply to simply not have the
     * problem.
     *
     * `k` is fixed rather than driven by [amount]. Amount scales how much of the result is mixed
     * in; letting it also drive the shaper harder would mean turning the effect up changed its
     * character from second-harmonic to fifth-harmonic, which is the point where an exciter stops
     * adding air and starts adding fizz - and where the aliasing analysis in the class doc stops
     * holding.
     */
    private fun shape(x: Float): Float {
        val bias = evenness.coerceIn(0f, 1f) * MAX_BIAS
        val biased = x + bias
        return softSaturate(biased) - softSaturate(bias)
    }

    private fun softSaturate(x: Float): Float {
        val k = MAX_DRIVE
        return (1f + k) * x / (1f + k * abs(x))
    }

    companion object {
        /**
         * Where a stream's content stops, for the codecs YouTube Music serves.
         *
         * These are the encoders' own low-pass points, not measurements of any one file - which is
         * the right source for them, because the encoder applies the same brickwall regardless of
         * what the music does. A file whose content happens to stop lower than this is simply
         * quiet up there, and exciting an empty band produces nothing, which is the correct
         * outcome rather than a failure.
         *
         * Null means "do not touch it": lossless, or an unrecognised format where guessing wrong
         * means generating harmonics into a band that already has music in it.
         */
        fun cutoffForMimeType(mimeType: String?, bitrateBps: Int?): Float? {
            val mime = mimeType?.lowercase() ?: return null
            return when {
                // Lossless, and anything that might be. Nothing to put back.
                "flac" in mime || "alac" in mime || "wav" in mime || "pcm" in mime -> null
                // Opus brickwalls at 20kHz at every rate it is used at here, which leaves almost
                // no gap at 48kHz - so there is nothing worth doing and this says so rather than
                // generating 20-22kHz nobody can hear.
                "opus" in mime -> null
                "mp4a" in mime || "aac" in mime || "m4a" in mime -> when {
                    bitrateBps == null -> 16_000f
                    bitrateBps >= 250_000 -> 19_000f
                    bitrateBps >= 190_000 -> 18_000f
                    bitrateBps >= 120_000 -> 16_000f
                    else -> 14_000f
                }
                "mpeg" in mime || "mp3" in mime -> when {
                    bitrateBps == null -> 16_000f
                    bitrateBps >= 300_000 -> 19_000f
                    bitrateBps >= 190_000 -> 18_000f
                    bitrateBps >= 120_000 -> 16_000f
                    else -> 15_000f
                }
                else -> null
            }
        }

        const val DEFAULT_AMOUNT = 0.5f
        const val DEFAULT_EVENNESS = 0.8f

        /**
         * How hard the saturator bends, fixed.
         *
         * Chosen so the second harmonic is 25-30dB below the fundamental at full-scale input and
         * the third is another 15dB below that - audible as brightness, and far enough down that
         * the third harmonic's aliased image stays below the noise floor of the material this runs
         * on, which is lossy audio and has a floor to spare.
         */
        private const val MAX_DRIVE = 0.6f

        /** The most DC offset [shape] will introduce, at [evenness] = 1. */
        private const val MAX_BIAS = 0.35f

        /**
         * Applied to the generated band before mixing.
         *
         * The band being restored is one nobody can hear on its own and most people cannot hear at
         * all past their thirties. It is there to change the character of what sits below it, and
         * at anything approaching equal level it stops doing that and becomes a hiss.
         */
        private const val WET_TRIM = 0.25f

        /** Below this much room above the cutoff, there is no band worth filling. */
        private const val MIN_GAP_HZ = 1_500f

        /** The input band has to be at least this wide, as a ratio, to be worth filtering for. */
        private const val INPUT_BAND_MIN_RATIO = 1.2f

        /** How far below Nyquist the output is capped, as a safety margin against the third harmonic. */
        private const val CEILING_FRACTION = 0.95f

        private const val BUTTERWORTH_Q = 0.7071f
    }
}
