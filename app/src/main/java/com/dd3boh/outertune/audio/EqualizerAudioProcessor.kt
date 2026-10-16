/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import com.dd3boh.outertune.models.EqualizerSettings
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * A cascaded-biquad parametric equalizer, inserted into [DefaultAudioSink]'s processor chain
 * alongside the existing silence-skipping/speed processors (see MusicService.createRenderersFactory).
 *
 * Pure Kotlin arithmetic - no native library, negligible size cost - and cheap enough that a full
 * 16-band stereo cascade is nowhere near what a phone CPU needs real-time audio to keep up with.
 *
 * [setSettings] is called from the UI thread whenever a slider moves; [queueInput] runs on
 * ExoPlayer's internal playback thread. Coefficients are published as a single immutable array
 * behind a `@Volatile` reference so the audio thread always sees either the old or the fully-new
 * set, never a half-updated one - there is no lock on the hot path.
 */
class EqualizerAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var bands: List<EqualizerSettings.EqBand> = EqualizerSettings.DEFAULT_BANDS

    @Volatile
    private var bypass: Boolean = true

    @Volatile
    private var coefficients: Array<BiquadCoefficients> = arrayOf()

    // Standard attenuate-the-far-side balance law, not a boost: at center both are 1, panning one
    // way only pulls the other channel down. Channels beyond the first two (anything not a plain
    // stereo stream) are left alone - "balance" has no defined meaning for them.
    @Volatile
    private var leftGain: Float = 1f

    @Volatile
    private var rightGain: Float = 1f

    // A cascade of overlapping peaking filters doesn't add its bands' dB values independently -
    // several adjacent bands boosted at once (a "Bass Boost" or "Loudness" curve) sum at the
    // frequencies where their responses overlap, easily clearing 0dBFS on a track that was
    // already close to it. That summed-over-full-scale signal is exactly what was reaching the
    // hard clip below and coming out as crackle on transients. Backing off the input by half of
    // however much total boost is in play - before the cascade even runs - keeps the boosted
    // result close to where the unboosted signal already was, the same "preamp" a hardware/
    // Winamp-style EQ needs whenever any band goes above zero.
    @Volatile
    private var preGainLinear: Float = 1f

    /**
     * How much of the measured peak is actually taken off, 0..1 - see [EqHeadroom].
     *
     * A separate control from the curve because it is not part of one: the same curve can be
     * wanted with full protection on a loud master and none on a quiet one, and folding it into
     * the profile would make every saved profile carry a copy of a global preference.
     */
    @Volatile
    private var headroomFactor: Float = 1f

    /**
     * Attenuation this track is going to receive anyway, in dB, as a positive number.
     *
     * Loudness normalisation turns most streams down - YouTube Music content by six to ten
     * decibels - and that attenuation is headroom: room between the signal and full scale that a
     * boost can be spent into for nothing. It was being applied after this processor, at the
     * player's volume, where the equaliser could not reach it, so a boost paid for headroom the
     * track had already been given.
     *
     * Applied here instead, and removed from the volume stage by the caller, so the total gain is
     * unchanged for a flat curve and a boost up to this size now costs nothing at all.
     */
    @Volatile
    private var normalisationDb: Float = 0f

    @Volatile
    private var compressorSettings: EqualizerSettings.CompressorSettings = EqualizerSettings.CompressorSettings()

    @Volatile
    private var compressorCoefficients: CompressorCoefficients = CompressorCoefficients.BYPASS

    // [channel][band]. Rebuilt only when channel count or band count changes, so ordinary gain/Q
    // tweaks never reset the running filter state (which would produce an audible click).
    private var states: Array<Array<BiquadState>> = arrayOf()

    // Audio-thread-only. The coefficients actually applied, as a flat [band * 5] array, ramped
    // towards whatever [coefficients] currently holds rather than snapped to it.
    //
    // Not resetting the filter state on a gain change (above) avoids one click but not the other:
    // substituting a whole new coefficient set between two consecutive samples is itself a step
    // discontinuity in the filter's response, and a slider dragged across its range fires one per
    // update. That is the zipper noise heard while adjusting a band. Interpolating over ~20ms
    // turns each of those steps into a smooth traversal instead.
    private var activeCoeffs: FloatArray = FloatArray(0)
    private var targetCoeffs: FloatArray = FloatArray(0)
    private var rampFramesLeft: Int = 0

    /** Identity check only - detects that [setSettings] published a new array since last buffer. */
    private var lastSeenTarget: Array<BiquadCoefficients>? = null

    // One envelope follower per channel - unlike the biquad grid this doesn't depend on the band
    // count, only the channel count, so it's resized independently.
    private var compStates: Array<CompressorState> = arrayOf()

    /**
     * Feature extraction for the audio-reactive visualiser - the same tap point as the EQ itself,
     * since both need to see the actual samples as they pass through. Gated by [visualizerEnabled]
     * rather than always running: decoding every sample to feed it is measurably more work than the
     * bulk [ByteBuffer.put] the bypass path otherwise takes, so it's only worth paying while
     * something is actually on-screen to consume it.
     */
    val visualizer = VisualizerAnalyzer()

    /**
     * High-frequency restoration, run before everything else here.
     *
     * It lives inside this processor rather than beside it in the chain because a separate
     * AudioProcessor would mean decoding every sample out of a ByteBuffer and encoding it back a
     * second time, for a stage that is a handful of multiplies. This one already has the samples
     * as floats.
     *
     * Before the cascade, not after: it is putting back what the codec removed, and the equaliser
     * should then shape the restored signal the same way it shapes everything else. Running it
     * afterwards would feed the shaper whatever the equaliser had just boosted, which is a
     * different effect that happens to use the same code.
     */
    val exciter = HarmonicExciter()

    /** Where the current stream's content stops, from its format - see [HarmonicExciter]. */
    @Volatile
    private var exciterCutoffHz: Float? = null

    @Volatile
    var visualizerEnabled: Boolean = false

    private var configuredSampleRate = 0

    /**
     * Points the exciter at the stream now playing. Safe to call from any thread.
     *
     * A null cutoff switches it off, which is what a lossless or unrecognised stream gets - see
     * [HarmonicExciter.cutoffForMimeType] for why guessing is worse than declining.
     */
    fun setExciter(enabled: Boolean, amount: Float, cutoffHz: Float?) {
        exciter.enabled = enabled
        exciter.amount = amount
        exciterCutoffHz = cutoffHz
        configureExciter()
    }

    /**
     * Re-plans the exciter's bands for the current stream.
     *
     * Deliberately does not touch [HarmonicExciter.enabled], which is the user's setting and not
     * this function's to decide. An earlier version switched it off whenever a stream had no gap
     * to fill - which worked, until a lossless track played and left it off: the setting had been
     * consumed rather than applied, and the next lossy track came through flat with the switch
     * still reading on. Whether there is anything to do is what the exciter's own `active` flag
     * already answers, and [HarmonicExciter.isActive] requires both.
     */
    private fun configureExciter() {
        val cutoff = exciterCutoffHz
        val channels = inputAudioFormat.channelCount
        if (cutoff == null || configuredSampleRate <= 0 || channels <= 0) {
            exciter.configure(0, 0, 0f) // clears active without disturbing the setting
            return
        }
        exciter.configure(configuredSampleRate, channels, cutoff)
    }

    /**
     * Hands over the attenuation this track would otherwise receive at the volume stage.
     *
     * Zero when the caller is still applying it there - see [normalisationDb]. The two must never
     * both apply it, which is why this is pushed rather than read: only the caller knows which of
     * them is doing it.
     */
    fun setNormalisation(db: Float) {
        val clamped = db.coerceAtLeast(0f)
        if (clamped == normalisationDb) return
        normalisationDb = clamped
        recomputeCoefficients()
    }

    /** Safe to call from any thread; takes effect on the next buffer without a reconfigure. */
    fun setHeadroom(factor: Float) {
        val clamped = factor.coerceIn(0f, 1f)
        if (clamped == headroomFactor) return
        headroomFactor = clamped
        recomputeCoefficients()
    }

    /** Safe to call from any thread; takes effect on the next buffer without a reconfigure. */
    fun setSettings(settings: EqualizerSettings) {
        // "Flat" has to mean "no enabled band changes the signal", and for a low/high-pass that is
        // never true regardless of gain: they have no gain parameter at all, so gainDb sits at 0
        // and a `gainDb == 0f` test called them inactive. The processor then bypassed itself
        // wholesale and bulk-copied the buffer, so a curve made only of pass filters - one built by
        // hand, or an AutoEq profile using LP/HP bands - was computed correctly by
        // BiquadCoefficients.forBand (which already excludes them from its own 0dB shortcut) and
        // then never actually run. Mirror that exclusion here.
        val flat = settings.allFilters().all { band ->
            !band.enabled || when (band.type) {
                EqualizerSettings.FilterType.PEAKING,
                EqualizerSettings.FilterType.LOW_SHELF,
                EqualizerSettings.FilterType.HIGH_SHELF -> band.gainDb == 0f

                EqualizerSettings.FilterType.LOW_PASS,
                EqualizerSettings.FilterType.HIGH_PASS -> false
            }
        }
        val compressorOff = !settings.compressor.enabled
        bypass = !settings.enabled || (flat && settings.balance == 0f && compressorOff)
        // allFilters, not bands: the tone shelves are part of what the filter bank runs, and a
        // processor built from bands alone would silently ignore both tone controls.
        bands = settings.allFilters()
        leftGain = (1f - settings.balance).coerceIn(0f, 1f)
        rightGain = (1f + settings.balance).coerceIn(0f, 1f)
        compressorSettings = settings.compressor

        // The pre-gain is now measured off the finished cascade rather than estimated from the
        // slider positions - see BiquadCoefficients.peakGainDb, and recomputeCoefficients below,
        // which is where it has to happen because the response depends on the sample rate.
        recomputeCoefficients()
    }

    private fun recomputeCoefficients() {
        val sr = configuredSampleRate
        if (sr <= 0) return
        val built = Array(bands.size) { i -> BiquadCoefficients.forBand(bands[i], sr) }
        coefficients = built

        // Exactly as much attenuation as the curve's own peak, so a signal that arrived at full
        // scale leaves at full scale instead of somewhere above it. Only boosts are compensated:
        // a curve that only cuts is already quieter than what it was given, and attenuating it
        // further would just make every cut-only preset sound like a volume drop.
        //
        // Capped at the band range, so even a single band at maximum is fully accounted for. A
        // curve asking for +14dB genuinely does have to come down by 14dB somewhere; the only
        // alternative is to clip it, which is the thing this exists to stop.
        // The larger of the two, not their sum. The normalisation attenuation is headroom the
        // boost can be spent into: a +8dB curve on a track already coming down 8dB needs no
        // further attenuation at all, and taking both would turn the band down by the 8dB it was
        // just turned up by. Only a boost larger than the headroom costs anything.
        val peakDb = BiquadCoefficients.peakGainDb(built, sr).coerceIn(0f, MAX_HEADROOM_DB)
        val attenuationDb = max(peakDb * headroomFactor, normalisationDb)
        preGainLinear = 10f.pow(-attenuationDb / 20f)

        val comp = compressorSettings
        compressorCoefficients = if (!comp.enabled) CompressorCoefficients.BYPASS else CompressorCoefficients.from(
            sampleRateHz = sr,
            thresholdDb = comp.thresholdDb,
            ratio = comp.ratio,
            attackMs = comp.attackMs,
            releaseMs = comp.releaseMs,
            makeupGainDb = comp.makeupGainDb,
        )
    }

    private fun ensureStates(channelCount: Int) {
        if (states.size != channelCount || states.getOrNull(0)?.size != bands.size) {
            states = Array(channelCount) { Array(bands.size) { BiquadState() } }
        }
        if (compStates.size != channelCount) {
            compStates = Array(channelCount) { CompressorState() }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredSampleRate = inputAudioFormat.sampleRate
        visualizer.configure(configuredSampleRate)
        recomputeCoefficients()
        // After the sample rate is known, since the exciter's whole band plan is derived from it.
        configureExciter()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val channelCount = inputAudioFormat.channelCount
        val buffer = replaceOutputBuffer(inputBuffer.remaining())
        val encoding = inputAudioFormat.encoding
        val feedVisualizer = visualizerEnabled

        // The bypass path bulk-copies the buffer, which the exciter cannot do its work from -
        // so an active exciter is itself a reason to take the long path, independently of whether
        // any band is doing anything.
        // A pending normalisation is a third reason not to take the bulk-copy path: the caller
        // has stopped applying it at the volume stage and is relying on this processor to, so
        // copying the buffer through untouched would play the track at full level.
        if (bypass && !exciter.isActive() && normalisationDb == 0f) {
            // The visualiser reacts to whatever's actually playing, independent of whether the EQ
            // itself is doing anything to it - so it still needs a look at the samples even on the
            // path that otherwise just bulk-copies the buffer through untouched.
            if (feedVisualizer) {
                val startPosition = inputBuffer.position()
                while (inputBuffer.hasRemaining()) {
                    var frameSum = 0f
                    for (ch in 0 until channelCount) {
                        frameSum += when (encoding) {
                            C.ENCODING_PCM_16BIT -> inputBuffer.getShort() / 32768f
                            C.ENCODING_PCM_FLOAT -> inputBuffer.getFloat()
                            else -> 0f
                        }
                    }
                    visualizer.feed(frameSum / channelCount)
                }
                inputBuffer.position(startPosition)
            }
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        ensureStates(channelCount)
        syncCoefficientTarget()
        val coeffs = activeCoeffs
        val compCoeffs = compressorCoefficients
        val compressorEnabled = compressorSettings.enabled
        val preGain = preGainLinear
        val leftGain = this.leftGain
        val rightGain = this.rightGain

        while (inputBuffer.hasRemaining()) {
            // Advanced once per frame, not per channel, so both channels stay on the same filter
            // for a given instant. The step is 1/remaining rather than a fixed fraction, so the
            // last frame of the ramp lands exactly on the target instead of asymptotically near it.
            if (rampFramesLeft > 0) {
                val step = 1f / rampFramesLeft
                for (i in coeffs.indices) {
                    coeffs[i] += (targetCoeffs[i] - coeffs[i]) * step
                }
                rampFramesLeft--
            }

            var frameSum = 0f
            for (ch in 0 until channelCount) {
                var sample = when (encoding) {
                    C.ENCODING_PCM_16BIT -> inputBuffer.getShort() / 32768f
                    C.ENCODING_PCM_FLOAT -> inputBuffer.getFloat()
                    else -> 0f
                }

                // First, on the signal as the decoder produced it. See the field's own doc.
                sample = exciter.process(sample, ch)

                // Backed off before the cascade even sees it, not after - the whole point is
                // giving the boosted bands room to sum without already having clipped.
                sample *= preGain

                val channelStates = states[ch]
                // bands/coefficients are two separate @Volatile fields, written one after the
                // other by setSettings() on the UI thread while this reads them mid-write from the
                // audio thread - a profile switch that changes the band count (importing a custom
                // profile saved with a different band count than the one currently loaded) can be
                // caught between the two writes, so channelStates (sized off the newer bands) and
                // coeffs (still the older size) briefly disagree. Bounding by the smaller of the
                // two degrades that one buffer's worth of processing instead of indexing past
                // whichever array is still short and crashing the playback thread outright.
                val bandCount = minOf(coeffs.size / COEFFS_PER_BAND, channelStates.size)
                for (b in 0 until bandCount) {
                    val o = b * COEFFS_PER_BAND
                    sample = channelStates[b].process(
                        sample, coeffs[o], coeffs[o + 1], coeffs[o + 2], coeffs[o + 3], coeffs[o + 4]
                    )
                }

                if (compressorEnabled) {
                    sample = compStates[ch].process(sample, compCoeffs)
                }

                if (feedVisualizer) frameSum += sample

                sample *= when (ch) {
                    0 -> leftGain
                    1 -> rightGain
                    else -> 1f
                }

                val limited = softLimit(sample)

                when (encoding) {
                    // Rounded, not truncated. toInt() truncates towards zero, which is a
                    // consistently-signed error rather than a symmetric one, so it behaves as
                    // distortion correlated with the waveform instead of as noise - audible on
                    // quiet passages as a gritty edge. The clamp costs nothing and makes the
                    // conversion safe by construction: a value that reached +32768 would wrap to
                    // full-scale *negative* as a Short, which is about the loudest click a sample
                    // can make.
                    C.ENCODING_PCM_16BIT ->
                        buffer.putShort((limited * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                    C.ENCODING_PCM_FLOAT -> buffer.putFloat(limited)
                }
            }
            if (feedVisualizer) visualizer.feed(frameSum / channelCount)
        }
        buffer.flip()
    }

    /**
     * Picks up a coefficient set published by [setSettings] and starts interpolating towards it.
     *
     * A change in band count can't be interpolated - the arrays don't line up element for element -
     * so that one snaps, which is the same thing the filter-state rebuild in [ensureStates] already
     * has to do for it.
     */
    private fun syncCoefficientTarget() {
        val target = coefficients
        if (target === lastSeenTarget) return
        lastSeenTarget = target

        val flat = FloatArray(target.size * COEFFS_PER_BAND)
        for (b in target.indices) {
            val c = target[b]
            val o = b * COEFFS_PER_BAND
            flat[o] = c.b0
            flat[o + 1] = c.b1
            flat[o + 2] = c.b2
            flat[o + 3] = c.a1
            flat[o + 4] = c.a2
        }

        if (activeCoeffs.size != flat.size) {
            activeCoeffs = flat
            targetCoeffs = flat.copyOf()
            rampFramesLeft = 0
        } else {
            targetCoeffs = flat
            rampFramesLeft = (configuredSampleRate * RAMP_MS / 1000).coerceAtLeast(1)
        }
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // A seek or track change is a discontinuity the filter shouldn't carry a memory of - left
        // alone, the old delay-line values would ring into the start of the new audio.
        states.forEach { channel -> channel.forEach { it.reset() } }
        compStates.forEach { it.reset() }
        // Same reasoning as the band filters: the audio on the far side of a seek is unrelated, so
        // carrying the exciter's delay line across it rings the old passage into the new one.
        exciter.reset()
        // Nothing to glide over across a discontinuity either: the audio on the far side of it is
        // unrelated, so the new coefficients may as well be fully in effect for its first sample.
        if (rampFramesLeft > 0) {
            targetCoeffs.copyInto(activeCoeffs)
            rampFramesLeft = 0
        }
        visualizer.reset()
    }

    override fun onReset() {
        states = arrayOf()
        compStates = arrayOf()
        activeCoeffs = FloatArray(0)
        targetCoeffs = FloatArray(0)
        rampFramesLeft = 0
        lastSeenTarget = null
        configuredSampleRate = 0
        visualizer.reset()
    }

    private companion object {
        const val COEFFS_PER_BAND = 5

        /** Long enough to be inaudible as a step, short enough to feel immediate on a slider. */
        const val RAMP_MS = 20

        /**
         * The most attenuation the pre-gain will apply, in dB.
         *
         * [EqualizerSettings.MAX_GAIN_DB], so a single band at the top of its range is fully
         * compensated. It used to be 12, which was below what the app's own presets actually
         * reach - "Small Speakers" peaks at +14.5dB - so the loudest curves stayed over the line
         * no matter how good the estimate was.
         */
        const val MAX_HEADROOM_DB = 15f

        /**
         * Below this the signal is passed through bit-exact; above it, it is compressed smoothly
         * into the remaining headroom. See [softLimit].
         *
         * Raised from 0.75 once the pre-gain became exact. 0.75 is -2.5dBFS, which on a modern
         * master is not a peak at all - it is most of the waveform - so the limiter was shaping
         * the signal continuously rather than catching the occasional overshoot, and doing it on
         * material the pre-gain had already failed to make room for. With the headroom correct,
         * what is left to catch is inter-sample overshoot and transients that briefly exceed the
         * steady-state response, and -0.9dBFS is where that belongs.
         */
        const val LIMIT_THRESHOLD = 0.9f
        const val LIMIT_RANGE = 1f - LIMIT_THRESHOLD
    }

    /**
     * Soft-knee ceiling. Transparent below [LIMIT_THRESHOLD], asymptotic to 1.0 above it.
     *
     * This replaced a bare `tanh(sample)` on every sample. tanh is a fine saturator but it is not
     * the identity anywhere: tanh(0.5) is 0.462 and tanh(0.7) is 0.604, so ordinary programme
     * material sitting at a normal listening level was being pulled down by 8-14% - a continuous,
     * waveform-dependent nonlinearity across the whole signal rather than something that engages
     * only on peaks. That is odd-harmonic distortion on everything, worst on bass-heavy material
     * where the high-amplitude fundamental throws its harmonics up into the midrange, and it is
     * audible as exactly the gritty crackle a boosted band was blamed for: the pre-gain leaves a
     * boosted band's output riding right around the level where tanh starts bending hard.
     *
     * Composing tanh with an offset instead keeps the saturation but confines it to the top of the
     * range. The pieces meet with matching value *and* matching slope at the threshold (tanh'(0)
     * is 1, and the 1/[LIMIT_RANGE] inside cancels the [LIMIT_RANGE] outside), so there is no
     * discontinuity in the transfer curve for the ear to find as the signal crosses it.
     */
    private fun softLimit(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= LIMIT_THRESHOLD) return x
        val shaped = LIMIT_THRESHOLD + LIMIT_RANGE * tanh((magnitude - LIMIT_THRESHOLD) / LIMIT_RANGE)
        return if (x < 0f) -shaped else shaped
    }
}
