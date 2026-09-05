/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import kotlin.math.abs

/** What a follower should do about the gap between itself and the host. */
sealed interface Correction {
    /** Nothing to do - either close enough, or already moving the right way at the right rate. */
    data object Hold : Correction

    /** Set playback speed to this, to close or hold the gap. 1f means "back to normal". */
    data class Nudge(val speed: Float) : Correction

    /** Jump. Only for a gap too large to close any other way. */
    data class Seek(val positionMs: Long) : Correction
}

/**
 * Decides how a follower catches up to the host.
 *
 * The naive answer - seek whenever the positions differ - is unusable. Position readings are noisy
 * by a few tens of milliseconds even after [PositionAnchor], so a bare comparison seeks constantly,
 * and every seek is an audible gap. The result is worse than the drift it was correcting.
 *
 * So corrections are graded by how bad the gap actually sounds:
 *
 * **Under [HOLD_BAND_MS]** nothing happens. Two sources within about 30ms of each other are heard as
 * one by the precedence effect - this is the same window that makes stereo work - so there is
 * nothing to fix, and fixing it would only add artefacts.
 *
 * **Up to [SEEK_THRESHOLD_MS]** the playback rate is nudged by a few percent until the gap closes.
 * Media3 time-stretches rather than resampling, so pitch is unchanged and a few percent of tempo is
 * not perceptible on its own. This is what makes the common case - slow drift between two devices -
 * correct itself with nothing audible at all.
 *
 * **Beyond that** it seeks, because a gap that large is already an obvious echo, and closing it by
 * nudging would take longer than tolerating it. A brief seek is the lesser cost.
 *
 * Two pieces of state stop it oscillating: hysteresis, so a gap sitting exactly on the boundary does
 * not flip in and out of correcting every tick, and a settle window after a seek, during which the
 * player's own position readings are not yet trustworthy.
 *
 * Pure arithmetic with an injected clock, for the same reason as the rest of this package: it is
 * where the behaviour lives, and it can only be checked properly if it can be driven faster than
 * real time.
 */
class DriftCorrector(private val nowUs: () -> Long) {

    private var correcting = false
    private var appliedSpeed = 1f
    private var settleUntilUs = 0L

    /**
     * @param errorMs how far ahead the host is. Positive means the follower is behind and must speed
     *   up; negative means it has run ahead and must ease off.
     * @param playing false while paused - a paused follower is not drifting, and nudging the rate of
     *   a stopped player achieves nothing.
     * @param hostPositionMs where to land if a seek is needed.
     */
    fun correct(errorMs: Long, playing: Boolean, hostPositionMs: Long): Correction {
        if (!playing) {
            // Leaving a nudge applied across a pause would resume at the wrong rate, and the gap it
            // was correcting is no longer accumulating anyway.
            return stopCorrecting()
        }

        // After a seek the player reports positions that are stale or mid-flight for a moment.
        // Acting on them produces a second seek, which produces a third - the classic seek loop, and
        // the reason this window exists rather than trusting the first reading back.
        if (nowUs() < settleUntilUs) return Correction.Hold

        val magnitude = abs(errorMs)

        if (magnitude >= SEEK_THRESHOLD_MS) {
            correcting = false
            appliedSpeed = 1f
            settleUntilUs = nowUs() + SETTLE_US
            return Correction.Seek(hostPositionMs)
        }

        // Hysteresis: start correcting at HOLD_BAND, but do not stop until well inside it. Using one
        // threshold for both would mean a gap hovering at the boundary switches the rate on and off
        // every tick, which is both pointless and audible.
        val threshold = if (correcting) TIGHT_BAND_MS else HOLD_BAND_MS
        if (magnitude < threshold) return stopCorrecting()

        correcting = true
        // Proportional: aim to close the gap over TIME_CONSTANT_MS rather than as fast as possible.
        // A fixed step would overshoot small gaps and crawl at large ones.
        val offset = (errorMs.toDouble() / TIME_CONSTANT_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        val target = (1.0 + offset).toFloat()

        // Only report a change worth making. Every speed change reconfigures the audio pipeline, and
        // on some devices that is audible, so re-setting a rate that is already right for the sake
        // of a rounding difference is a real cost for no benefit.
        if (abs(target - appliedSpeed) < MIN_SPEED_STEP) return Correction.Hold
        appliedSpeed = target
        return Correction.Nudge(target)
    }

    /**
     * Forgets the correction state.
     *
     * For a track change or a reconnect, where the previous error describes a situation that no
     * longer exists and carrying it over would correct against nothing.
     */
    fun reset() {
        correcting = false
        appliedSpeed = 1f
        settleUntilUs = 0L
    }

    /** Back to normal rate, but only emitting a change if one is actually needed. */
    private fun stopCorrecting(): Correction {
        correcting = false
        if (appliedSpeed == 1f) return Correction.Hold
        appliedSpeed = 1f
        return Correction.Nudge(1f)
    }

    private companion object {
        /**
         * Below this, a gap is not worth correcting.
         *
         * Roughly the precedence-effect window: two sources closer together than this are heard as
         * one, so there is nothing audible to fix.
         */
        const val HOLD_BAND_MS = 30

        /** Once correcting, keep going until this tight. The hysteresis that prevents flapping. */
        const val TIGHT_BAND_MS = 10

        /**
         * Beyond this, seek rather than nudge.
         *
         * A quarter of a second is a plain echo, and at the maximum nudge it would take five seconds
         * of audibly-wrong tempo to close. The seek is quicker and, oddly, less noticeable.
         */
        const val SEEK_THRESHOLD_MS = 250

        /** How long to spend closing a gap. Slow enough to be inaudible, quick enough to matter. */
        const val TIME_CONSTANT_MS = 3_000

        /** Largest rate change. Beyond a few percent, tempo change stops being subliminal. */
        const val MAX_NUDGE = 0.05

        /** Do not re-set the rate for a change smaller than this. */
        const val MIN_SPEED_STEP = 0.002f

        /** How long the player's position is untrustworthy after a seek. */
        const val SETTLE_US = 1_500_000L
    }
}
