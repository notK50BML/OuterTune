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

    /** Earliest the rate may be changed again. See the dwell in [correct]. */
    private var rateHeldUntilUs = 0L

    /** Seeks since the gap was last actually closed - the tell for a standing offset. */
    private var consecutiveSeeks = 0

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

        // Returning to normal is always allowed, and is checked before anything that could defer it.
        // Hysteresis: start correcting at HOLD_BAND but do not stop until well inside it, or a gap
        // sitting on the boundary switches the rate on and off every tick.
        val threshold = if (correcting) TIGHT_BAND_MS else HOLD_BAND_MS
        if (magnitude < threshold) {
            consecutiveSeeks = 0
            return stopCorrecting()
        }

        if (magnitude >= SEEK_THRESHOLD_MS) {
            // A gap this large is normally a track change or a join, and one seek fixes it. But if
            // seeking keeps not fixing it, the gap is not drift - it is a standing difference the
            // player's own clock cannot see, most often that the two devices put sound out at
            // different delays. Seeking against that never converges and is heard every time, so
            // after a few attempts it stops and hands the problem to the rate instead, which at
            // least is inaudible. The user-set offset is the real cure; this only stops the app
            // making it worse in the meantime.
            if (consecutiveSeeks < MAX_CONSECUTIVE_SEEKS) {
                consecutiveSeeks++
                correcting = false
                appliedSpeed = 1f
                rateHeldUntilUs = 0L
                settleUntilUs = nowUs() + SETTLE_US
                return Correction.Seek(hostPositionMs)
            }
        } else {
            consecutiveSeeks = 0
        }

        // A rate, once chosen, is kept for a while rather than recomputed every tick.
        //
        // This is the difference between a correction that is heard and one that is not. Every
        // change of rate reconfigures the audio pipeline, and on many devices that is a click or a
        // dropout - so continuously refining the rate as the gap shrinks produces a string of small
        // artefacts, which is precisely the microstutter it was supposed to avoid. Recomputing
        // eight times to close a fifth of a second is eight chances to be heard; three is better,
        // and the gap closes just as surely.
        if (nowUs() < rateHeldUntilUs) return Correction.Hold

        correcting = true
        // Proportional: aim to close the gap over TIME_CONSTANT_MS rather than as fast as possible.
        // A fixed step would overshoot small gaps and crawl at large ones.
        val offset = (errorMs.toDouble() / TIME_CONSTANT_MS).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        val target = (1.0 + offset).toFloat()

        // Still worth nothing if it barely differs from what is already applied.
        if (abs(target - appliedSpeed) < MIN_SPEED_STEP) return Correction.Hold
        appliedSpeed = target
        rateHeldUntilUs = nowUs() + RATE_DWELL_US
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
        rateHeldUntilUs = 0L
        consecutiveSeeks = 0
    }

    /** Back to normal rate, but only emitting a change if one is actually needed. */
    private fun stopCorrecting(): Correction {
        correcting = false
        rateHeldUntilUs = 0L
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

        /**
         * How long a chosen rate is kept before it may be refined.
         *
         * Matched to the time constant, so one rate is expected to do most of the work before it is
         * reconsidered. Shorter and the corrections stack up audibly; much longer and a gap that
         * changes direction takes too long to notice.
         */
        const val RATE_DWELL_US = 3_000_000L

        /**
         * Seeks tolerated before concluding the gap is not something seeking can fix.
         *
         * Two covers the honest cases - a track change, a join mid-song - without letting a standing
         * offset turn into an interruption every couple of seconds forever.
         */
        const val MAX_CONSECUTIVE_SEEKS = 2
    }
}
