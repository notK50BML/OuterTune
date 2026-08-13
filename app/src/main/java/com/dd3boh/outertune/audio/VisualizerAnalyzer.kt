/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs

/**
 * Cheap, no-FFT feature extraction for the audio-reactive visualiser, fed one downmixed sample at
 * a time from [EqualizerAudioProcessor.queueInput].
 *
 * No frequency-domain analysis: a bass/mid/treble split via two cascaded one-pole filters, plus a
 * fast-envelope-vs-slow-envelope ratio for transient/onset detection, both standard "cheap DSP"
 * techniques. This runs on the audio thread, so every step here is an O(1) float operation - nothing
 * that allocates or can block. [frames] is only written to at a throttled rate (about 30 times a
 * second, not once per sample) since that's plenty for anything a screen redraws, and it's a
 * [MutableStateFlow] specifically because setting `.value` is non-suspending and safe from any
 * thread, unlike `emit`.
 */
class VisualizerAnalyzer {
    val frames = MutableStateFlow(VisualizerFrame())

    // Two-stage band split: a bass low-pass, then a mid low-pass applied to whatever the bass
    // stage didn't keep - the remainder above that is treble. Three bands from two one-pole
    // filters, no FFT.
    private var bassLp = 0f
    private var midLp = 0f

    // Attack/release-smoothed magnitude per band - the actual "energy" a consumer reads.
    private var bassEnv = 0f
    private var midEnv = 0f
    private var trebleEnv = 0f

    // A fast envelope and a much slower one over the whole (unsplit) signal; a transient is the
    // fast one suddenly running ahead of the slow one.
    private var shortEnv = 0f
    private var longEnv = 0f

    private var bassAlpha = 0f
    private var midAlpha = 0f
    private var attackAlpha = 0f
    private var releaseAlpha = 0f
    private var shortAlpha = 0f
    private var longAlpha = 0f

    private var sampleCounter = 0
    private var samplesPerPublish = 1

    fun configure(sampleRateHz: Int) {
        if (sampleRateHz <= 0) return
        bassAlpha = onePoleAlpha(sampleRateHz, BASS_CUTOFF_HZ)
        midAlpha = onePoleAlpha(sampleRateHz, MID_CUTOFF_HZ)
        attackAlpha = timeConstantAlpha(sampleRateHz, ENVELOPE_ATTACK_MS)
        releaseAlpha = timeConstantAlpha(sampleRateHz, ENVELOPE_RELEASE_MS)
        shortAlpha = timeConstantAlpha(sampleRateHz, TRANSIENT_SHORT_MS)
        longAlpha = timeConstantAlpha(sampleRateHz, TRANSIENT_LONG_MS)
        samplesPerPublish = (sampleRateHz / PUBLISH_RATE_HZ).coerceAtLeast(1)
    }

    /** [sample] is already downmixed to mono - one call per output audio frame, not per channel. */
    fun feed(sample: Float) {
        bassLp += bassAlpha * (sample - bassLp)
        val aboveBass = sample - bassLp
        midLp += midAlpha * (aboveBass - midLp)
        val treble = aboveBass - midLp

        bassEnv = smoothEnvelope(bassEnv, abs(bassLp))
        midEnv = smoothEnvelope(midEnv, abs(midLp))
        trebleEnv = smoothEnvelope(trebleEnv, abs(treble))

        val overall = abs(sample)
        shortEnv += shortAlpha * (overall - shortEnv)
        longEnv += longAlpha * (overall - longEnv)

        sampleCounter++
        if (sampleCounter < samplesPerPublish) return
        sampleCounter = 0

        frames.value = VisualizerFrame(
            bass = (bassEnv * BASS_GAIN).coerceIn(0f, 1f),
            mid = (midEnv * MID_GAIN).coerceIn(0f, 1f),
            treble = (trebleEnv * TREBLE_GAIN).coerceIn(0f, 1f),
            transient = ((shortEnv - longEnv) * TRANSIENT_GAIN).coerceIn(0f, 1f),
        )
    }

    private fun smoothEnvelope(previous: Float, magnitude: Float): Float {
        val alpha = if (magnitude > previous) attackAlpha else releaseAlpha
        return previous + alpha * (magnitude - previous)
    }

    /** A seek/track change is silence the visualiser should snap back to, not carry over. */
    fun reset() {
        bassLp = 0f
        midLp = 0f
        bassEnv = 0f
        midEnv = 0f
        trebleEnv = 0f
        shortEnv = 0f
        longEnv = 0f
        sampleCounter = 0
        frames.value = VisualizerFrame()
    }

    companion object {
        private const val BASS_CUTOFF_HZ = 250f
        private const val MID_CUTOFF_HZ = 4000f

        private const val ENVELOPE_ATTACK_MS = 15f
        private const val ENVELOPE_RELEASE_MS = 250f

        private const val TRANSIENT_SHORT_MS = 12f
        private const val TRANSIENT_LONG_MS = 700f

        private const val PUBLISH_RATE_HZ = 30

        // Typical program material sits well under full scale in each of these bands, so a flat
        // multiplier brings the usual range up into something a 0..1-expecting renderer can use -
        // tuned by ear against real tracks, not derived from anything.
        private const val BASS_GAIN = 6f
        private const val MID_GAIN = 8f
        private const val TREBLE_GAIN = 10f
        private const val TRANSIENT_GAIN = 5f

        /** Exponential-smoothing alpha for a one-pole low-pass at [cutoffHz], at [sampleRateHz]. */
        private fun onePoleAlpha(sampleRateHz: Int, cutoffHz: Float): Float {
            val rc = 1f / (2f * PI.toFloat() * cutoffHz)
            val dt = 1f / sampleRateHz
            return dt / (rc + dt)
        }

        private fun timeConstantAlpha(sampleRateHz: Int, ms: Float): Float {
            val samples = sampleRateHz * (ms / 1000f)
            return (1f / samples).coerceIn(0f, 1f)
        }
    }
}
