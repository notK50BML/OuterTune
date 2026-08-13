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

    @Volatile
    private var compressorSettings: EqualizerSettings.CompressorSettings = EqualizerSettings.CompressorSettings()

    @Volatile
    private var compressorCoefficients: CompressorCoefficients = CompressorCoefficients.BYPASS

    // [channel][band]. Rebuilt only when channel count or band count changes, so ordinary gain/Q
    // tweaks never reset the running filter state (which would produce an audible click).
    private var states: Array<Array<BiquadState>> = arrayOf()

    // One envelope follower per channel - unlike the biquad grid this doesn't depend on the band
    // count, only the channel count, so it's resized independently.
    private var compStates: Array<CompressorState> = arrayOf()

    private var configuredSampleRate = 0

    /** Safe to call from any thread; takes effect on the next buffer without a reconfigure. */
    fun setSettings(settings: EqualizerSettings) {
        val flat = settings.bands.all { !it.enabled || it.gainDb == 0f }
        val compressorOff = !settings.compressor.enabled
        bypass = !settings.enabled || (flat && settings.balance == 0f && compressorOff)
        bands = settings.bands
        leftGain = (1f - settings.balance).coerceIn(0f, 1f)
        rightGain = (1f + settings.balance).coerceIn(0f, 1f)
        compressorSettings = settings.compressor
        recomputeCoefficients()
    }

    private fun recomputeCoefficients() {
        val sr = configuredSampleRate
        if (sr <= 0) return
        coefficients = Array(bands.size) { i -> BiquadCoefficients.forBand(bands[i], sr) }
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
        recomputeCoefficients()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val channelCount = inputAudioFormat.channelCount
        val buffer = replaceOutputBuffer(inputBuffer.remaining())

        if (bypass) {
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        ensureStates(channelCount)
        val coeffs = coefficients
        val compCoeffs = compressorCoefficients
        val compressorEnabled = compressorSettings.enabled
        val encoding = inputAudioFormat.encoding
        val leftGain = this.leftGain
        val rightGain = this.rightGain

        while (inputBuffer.hasRemaining()) {
            for (ch in 0 until channelCount) {
                var sample = when (encoding) {
                    C.ENCODING_PCM_16BIT -> inputBuffer.getShort() / 32768f
                    C.ENCODING_PCM_FLOAT -> inputBuffer.getFloat()
                    else -> 0f
                }

                val channelStates = states[ch]
                for (b in coeffs.indices) {
                    sample = channelStates[b].process(sample, coeffs[b])
                }

                if (compressorEnabled) {
                    sample = compStates[ch].process(sample, compCoeffs)
                }

                sample *= when (ch) {
                    0 -> leftGain
                    1 -> rightGain
                    else -> 1f
                }

                when (encoding) {
                    C.ENCODING_PCM_16BIT ->
                        buffer.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample)
                }
            }
        }
        buffer.flip()
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // A seek or track change is a discontinuity the filter shouldn't carry a memory of - left
        // alone, the old delay-line values would ring into the start of the new audio.
        states.forEach { channel -> channel.forEach { it.reset() } }
        compStates.forEach { it.reset() }
    }

    override fun onReset() {
        states = arrayOf()
        compStates = arrayOf()
        configuredSampleRate = 0
    }
}
