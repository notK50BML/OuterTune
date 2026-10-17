/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Keeps the output under full scale by turning it down, rather than by bending it.
 *
 * What was here before was `tanh` applied to every sample above a threshold - a waveshaper, which
 * changes the shape of the wave and therefore adds harmonics that were not in the music. That is
 * what distortion *is*. It engaged on every boost that exceeded full scale, which on the Half and
 * None headroom settings was every boost, and on Automatic was the ringing a filter produces on a
 * transient - quieter, but there.
 *
 * A limiter does something else: it watches the level and lowers the *gain* when a peak arrives,
 * then raises it again afterwards. The waveform keeps its shape and is simply quieter for a moment,
 * which for a few milliseconds around a transient is not something an ear notices. That is how a
 * player can let a boost exceed full scale without grit, and it is what every serious one does.
 *
 * **Look-ahead** is the part that makes it transparent rather than merely gentler. The output is
 * delayed by [LOOKAHEAD_MS], and the limiter sees each peak that long before it is played - so the
 * gain has already come down by the time the peak arrives, instead of reacting to it after the
 * first few samples have already gone over. 1.5ms of latency on a music player is nothing.
 *
 * The gain is linked across channels. Limiting left and right independently moves the stereo image
 * every time one side peaks, which is far more audible than the limiting itself.
 */
class PeakLimiter {

    private var channels = 0
    private var lookahead = 0

    /** Delayed samples, one ring per channel, all sharing [position]. */
    private var delay: Array<FloatArray> = emptyArray()

    /** The loudest channel of each frame in the window, aligned with [delay]. */
    private var peaks = FloatArray(0)
    private var position = 0
    private var windowPeak = 0f

    private var gain = 1f
    private var attack = 1f
    private var release = 1f

    /** Gain reduction currently applied, in dB, as a negative number. For a meter, or a test. */
    val reductionDb: Float
        get() = if (gain >= 1f) 0f else 20f * kotlin.math.log10(gain)

    fun configure(sampleRate: Int, channelCount: Int) {
        if (sampleRate <= 0 || channelCount <= 0) {
            channels = 0
            return
        }
        val samples = (sampleRate * LOOKAHEAD_MS / 1000f).roundToInt().coerceAtLeast(1)
        if (channelCount != channels || samples != lookahead) {
            channels = channelCount
            lookahead = samples
            delay = Array(channelCount) { FloatArray(samples) }
            peaks = FloatArray(samples)
        }
        // Converging to within a thousandth of the target across exactly the look-ahead window,
        // so the gain is down by the time the peak that caused it comes out of the delay.
        attack = 1f - 0.001f.pow(1f / samples)
        release = 1f - exp(-1f / (sampleRate * RELEASE_MS / 1000f))
        reset()
    }

    /**
     * Back to silence and unity. After a seek, a format change, or a stretch where the processor
     * was bypassed - the delay line would otherwise play out audio from before the gap.
     */
    fun reset() {
        delay.forEach { it.fill(0f) }
        peaks.fill(0f)
        position = 0
        windowPeak = 0f
        gain = 1f
    }

    /**
     * One frame, in place: [frame] goes in as this instant's samples and comes out as the frame
     * from [LOOKAHEAD_MS] ago, limited.
     */
    fun process(frame: FloatArray) {
        if (channels == 0 || frame.size < channels) return

        var framePeak = 0f
        for (ch in 0 until channels) framePeak = max(framePeak, abs(frame[ch]))

        // The window as it stands with this frame in it and the outgoing one not yet removed - one
        // frame longer than the look-ahead, which errs on the side of limiting slightly early rather
        // than slightly late.
        if (framePeak > windowPeak) windowPeak = framePeak

        val target = if (windowPeak > CEILING) CEILING / windowPeak else 1f
        gain += (target - gain) * (if (target < gain) attack else release)

        // Out with the oldest frame, in with this one.
        for (ch in 0 until channels) {
            val incoming = frame[ch]
            // A hard clamp as the last line, never the first. With the look-ahead doing its job it
            // only ever trims the thousandth the attack had not quite closed, which is far below
            // anything audible - it is here so that "never over full scale" is a guarantee rather
            // than a tendency.
            frame[ch] = (delay[ch][position] * gain).coerceIn(-1f, 1f)
            delay[ch][position] = incoming
        }
        val outgoingPeak = peaks[position]
        peaks[position] = framePeak
        position = (position + 1) % lookahead

        // The frame that just left may have been the loudest in the window. Only then is a rescan
        // needed - which keeps this to a comparison per frame most of the time, and the full scan
        // to the moments right after a peak.
        if (outgoingPeak >= windowPeak) {
            var m = 0f
            for (p in peaks) if (p > m) m = p
            windowPeak = m
        }
    }

    companion object {
        /** How far ahead the limiter sees, in milliseconds - and so how far behind the output runs. */
        const val LOOKAHEAD_MS = 1.5f

        /** How quickly the gain recovers once a peak has passed. */
        const val RELEASE_MS = 60f

        /**
         * The level the output is held under: half a decibel below full scale.
         *
         * Not full scale itself. Rounding to sixteen bits, and the reconstruction filter in the DAC
         * after it, can both produce values slightly above the samples they are given - inter-sample
         * peaks - and half a decibel is the usual allowance for them.
         */
        val CEILING = 10f.pow(-0.5f / 20f)
    }
}
