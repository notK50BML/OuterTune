/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A copy of the phone's limiter (app/.../audio/PeakLimiter.kt) - the desktop is a separate module
 * with no dependency on the app, and this is small enough that duplicating it beats a shared module.
 *
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
 * first few samples have already gone over. 5ms of latency on a music player is nothing.
 *
 * **Soft knee.** The gain does not switch from "nothing" to "everything" at one level. It starts
 * easing down at [THRESHOLD], half the [KNEE_DB] below the ceiling, and reaches full limiting the
 * same distance above it - so a peak that only just reaches the knee is touched by a fraction of a
 * decibel rather than pinned. Below [THRESHOLD] it is bit-exact.
 *
 * **True peak.** What is measured is not only each sample but an estimate of the waveform halfway
 * between each pair of them, because the DAC's reconstruction filter draws a curve through the
 * samples and that curve can overshoot them - most of all on boosted, near-full-scale material. A
 * four-point interpolation finds most of that overshoot for a handful of multiplies, and what it
 * misses is well inside the decibel between the ceiling and full scale.
 *
 * The gain is linked across channels. Limiting left and right independently moves the stereo image
 * every time one side peaks, which is far more audible than the limiting itself.
 */
class PeakLimiter {

    private var channels = 0
    private var lookahead = 0
    private var configuredRate = 0

    /** Delayed samples, one ring per channel, all sharing [position]. */
    private var delay: Array<FloatArray> = emptyArray()

    /** The last three samples of each channel, oldest first, for the in-between estimate. */
    private var history = FloatArray(0)

    /**
     * The loudest channel of each of the last [hold] frames - the [lookahead] still waiting in the
     * delay plus those just played. Longer than the delay on purpose: see [HOLD_MS].
     */
    private var hold = 0
    private var position = 0
    private var windowPeak = 0f

    /**
     * The window's maximum, kept as a monotonic queue: each entry is a frame peak and the frame it
     * arrived on, in decreasing order of peak. The front is always the window's loudest frame, and
     * each frame is added and removed once, so finding the maximum costs the same per sample however
     * long the hold is. Rescanning the window instead would cost [hold] reads on every sample of a
     * decaying note, which on a phone is a meaningful slice of a core.
     */
    private var queuePeaks = FloatArray(0)
    private var queueFrames = LongArray(0)
    private var queueHead = 0
    private var queueSize = 0
    private var frameCount = 0L

    private var gain = 1f
    private var attack = 1f
    private var release = 1f

    /** The window peak [target] was last worked out for, so the logarithm is not taken per sample. */
    private var targetPeak = -1f
    private var target = 1f

    /** Gain reduction currently applied, in dB, as a negative number. For a meter, or a test. */
    val reductionDb: Float
        get() = if (gain >= 1f) 0f else 20f * log10(gain)

    /**
     * Sets the limiter up for a format. Only a change of rate or channel count clears it: the
     * desktop equaliser calls this whenever a band moves, and emptying the delay line each time
     * dropped five milliseconds of music on every slider step.
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        if (sampleRate <= 0 || channelCount <= 0) {
            channels = 0
            return
        }
        val samples = (sampleRate * LOOKAHEAD_MS / 1000f).roundToInt().coerceAtLeast(1)
        val holdSamples = (sampleRate * HOLD_MS / 1000f).roundToInt().coerceAtLeast(samples)
        val changed = channelCount != channels || sampleRate != configuredRate ||
            samples != lookahead || holdSamples != hold
        if (changed) {
            channels = channelCount
            configuredRate = sampleRate
            lookahead = samples
            hold = holdSamples
            delay = Array(channelCount) { FloatArray(samples) }
            history = FloatArray(channelCount * 3)
            queuePeaks = FloatArray(holdSamples + 1)
            queueFrames = LongArray(holdSamples + 1)
        }
        // Converging to within a thousandth of the target across exactly the look-ahead window,
        // so the gain is down by the time the peak that caused it comes out of the delay.
        attack = 1f - 0.001f.pow(1f / samples)
        release = 1f - exp(-1f / (sampleRate * RELEASE_MS / 1000f))
        if (changed) reset()
    }

    /**
     * Back to silence and unity. After a seek, a format change, or a stretch where the processor
     * was bypassed - the delay line would otherwise play out audio from before the gap.
     */
    fun reset() {
        delay.forEach { it.fill(0f) }
        history.fill(0f)
        position = 0
        queueHead = 0
        queueSize = 0
        frameCount = 0L
        windowPeak = 0f
        gain = 1f
        targetPeak = -1f
        target = 1f
    }

    /**
     * One frame, in place: [frame] goes in as this instant's samples and comes out as the frame
     * from [LOOKAHEAD_MS] ago, limited.
     */
    fun process(frame: FloatArray) {
        if (channels == 0 || frame.size < channels) return

        var framePeak = 0f
        for (ch in 0 until channels) {
            val x = frame[ch]
            val h = ch * 3
            val x0 = history[h]
            val x1 = history[h + 1]
            val x2 = history[h + 2]
            // Halfway between the previous two samples, from the four around them: the four-point
            // Lagrange midpoint, about what a meter oversampling by two would read there. A sample
            // or two late, which the five-millisecond look-ahead absorbs without noticing.
            val between = (9f * (x1 + x2) - x0 - x) * 0.0625f
            framePeak = max(framePeak, max(abs(x), abs(between)))
            history[h] = x1
            history[h + 1] = x2
            history[h + 2] = x
        }

        // Into the window: anything at the back no louder than this frame can never be the
        // maximum again while this frame is in the window, so it goes.
        val capacity = queuePeaks.size
        while (queueSize > 0) {
            val back = (queueHead + queueSize - 1) % capacity
            if (queuePeaks[back] > framePeak) break
            queueSize--
        }
        val slot = (queueHead + queueSize) % capacity
        queuePeaks[slot] = framePeak
        queueFrames[slot] = frameCount
        queueSize++
        // Out of the window: whatever arrived more than [hold] frames ago.
        while (queueFrames[queueHead] <= frameCount - hold) {
            queueHead = (queueHead + 1) % capacity
            queueSize--
        }
        frameCount++
        windowPeak = queuePeaks[queueHead]

        // The window's peak changes far less often than every sample, so the knee is only worked
        // out again when it does.
        if (windowPeak != targetPeak) {
            targetPeak = windowPeak
            target = gainFor(windowPeak)
        }
        gain += (target - gain) * (if (target < gain) attack else release)

        // Out with the oldest frame, in with this one.
        for (ch in 0 until channels) {
            val incoming = frame[ch]
            // A hard clamp as the last line, never the first. The ceiling is a decibel under full
            // scale and the attack closes all but a thousandth of the gap across the look-ahead, so
            // this does not meet a real sample - it is here so that "never over full scale" is a
            // guarantee rather than a tendency, even for a runaway upstream.
            val out = delay[ch][position] * gain
            frame[ch] = if (out.isFinite()) out.coerceIn(-1f, 1f) else 0f
            delay[ch][position] = incoming
        }
        position = (position + 1) % lookahead
    }

    companion object {
        /**
         * How far ahead the limiter sees, in milliseconds - and so how far behind the output runs.
         * Long enough that the gain comes down over a few milliseconds rather than one, which is
         * the difference between a dip and a click; short enough that nobody hears the delay.
         */
        const val LOOKAHEAD_MS = 5f

        /**
         * How long the gain stays down after the loudest recent peak before it may recover.
         *
         * The fix for bass boosts distorting. With release alone, the gain started climbing back
         * the moment a peak passed - and on a 40Hz note the next peak is only 25ms away, so the
         * gain rose and fell on every cycle of the bass. That is amplitude modulation at the bass
         * frequency, which is heard as a gritty, flapping distortion, and it is exactly what a big
         * bass boost hitting the limiter sounded like. Held for longer than the slowest bass
         * cycle, the gain sits still through a sustained note and only recovers once it is over.
         */
        const val HOLD_MS = 60f

        /** How quickly the gain recovers once the hold has passed. */
        const val RELEASE_MS = 150f

        /**
         * The level the output is held under, in dBFS: one decibel below full scale.
         *
         * Not full scale itself. Rounding to sixteen bits, the reconstruction filter in the DAC, and
         * any lossy encode further along - a Bluetooth codec - can all produce values above the
         * samples they were given: inter-sample peaks. A decibel is the usual allowance. It used to
         * be half that, measured on the samples alone.
         */
        const val CEILING_DB = -1f

        /** The soft knee's width in dB, centred on the ceiling: it starts half of this below it. */
        const val KNEE_DB = 2f

        /** The output's ceiling as a linear amplitude. */
        val CEILING = 10f.pow(CEILING_DB / 20f)

        /** Where limiting starts, as a linear amplitude. Anything quieter is left exactly as it is. */
        val THRESHOLD = 10f.pow((CEILING_DB - KNEE_DB / 2f) / 20f)

        /**
         * The gain that brings a peak of [peak] under the ceiling, through the soft knee.
         *
         * The textbook quadratic knee at an infinite ratio: the output follows the input up to
         * [THRESHOLD], bends over across [KNEE_DB], and from the top of the knee on is held at the
         * ceiling. The pieces meet with the same level and the same slope, so nothing in the gain
         * jumps as a peak moves into or through the knee.
         */
        internal fun gainFor(peak: Float): Float {
            if (peak <= THRESHOLD) return 1f
            val levelDb = 20f * log10(peak)
            val reductionDb = if (levelDb >= CEILING_DB + KNEE_DB / 2f) {
                levelDb - CEILING_DB
            } else {
                val into = levelDb - (CEILING_DB - KNEE_DB / 2f)
                into * into / (2f * KNEE_DB)
            }
            return 10f.pow(-reductionDb / 20f)
        }
    }
}
