/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * Works out how far a follower's clock is from the host's, from ping/pong round trips.
 *
 * Ported unchanged from the Android app's `listentogether/ClockSync.kt` - pure arithmetic with no
 * platform dependency, so there was nothing to adapt. Keep the two in step.
 *
 * The algorithm is NTP's, because the problem is NTP's. Four timestamps per exchange - the
 * follower's send, the host's receive, the host's send, the follower's receive - give both the
 * offset between the clocks and the round trip, and the offset is only trustworthy to the extent
 * the trip was symmetric:
 *
 *     offset = ((t2 - t1) + (t3 - t4)) / 2
 *     delay  = (t4 - t1) - (t3 - t2)
 *
 * Two details matter more than the formula.
 *
 * **The lowest-delay sample wins, not the average.** Asymmetry between the outbound and return path
 * is the only error the offset calculation cannot see through, and asymmetry grows with delay - a
 * sample that got through quickly had little room to be lopsided. Averaging mixes good samples with
 * bad ones and lands between them; taking the minimum-delay sample takes the least contaminated
 * measurement available. This is what NTP does and what makes it work over the open internet.
 *
 * **The result is smoothed, and re-measured from scratch after a reconnect.** One anomalous sample
 * should not step the estimate, so accepted offsets are eased toward rather than assigned. But a
 * reconnect means the radio situation has changed, and carrying an old estimate across it would be
 * worse than having none - so [reset] exists and the caller is expected to use it.
 *
 * Pure arithmetic, no clock reading of its own. That is deliberate: it is the piece most worth
 * testing, and it can only be tested cheaply if it does not fetch the time itself.
 */
class ClockSync {

    private val samples = ArrayDeque<Sample>()
    private var smoothedOffsetUs: Long? = null

    /** Rejections since the last accepted sample - the tell for a network that has changed for good. */
    private var consecutiveRejections = 0

    data class Sample(val offsetUs: Long, val delayUs: Long)

    /** The current best estimate of (host clock - follower clock), or null before any sample. */
    val offsetUs: Long? get() = smoothedOffsetUs

    /** Round trip of the sample the current estimate came from - a rough quality indicator. */
    var bestDelayUs: Long? = null
        private set

    val sampleCount: Int get() = samples.size

    /**
     * Folds one completed exchange into the estimate.
     *
     * @param t1 follower's clock when the ping was sent
     * @param t2 host's clock when it read the ping
     * @param t3 host's clock when it wrote the pong
     * @param t4 follower's clock when the pong arrived
     * @return true if the sample was used, false if it was rejected as an outlier
     */
    fun offer(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        val delay = (t4 - t1) - (t3 - t2)
        // A negative delay is impossible and means a clock moved under us mid-exchange. There is
        // nothing to salvage from such a sample.
        if (delay < 0) return false

        val offset = ((t2 - t1) + (t3 - t4)) / 2

        // Reject a sample that took far longer than what has been seen. Not the mean - one very slow
        // sample would drag the mean up and start admitting other slow ones.
        //
        // Only accepted samples enter the window, so the median describes the accepted history and
        // nothing else. That is what makes rejection safe against a brief spike and dangerous
        // against a lasting change: a follower that walks out of range of the router, and stays
        // there, produces samples that are all worse than the old median forever. Every one would
        // be rejected, the window would never roll over, and the estimate would stay pinned to
        // conditions that no longer exist - while crystal drift kept accumulating against it, with
        // the UI still reporting a healthy session. So a run of rejections is itself evidence that
        // what counts as normal has moved, and the window is dropped so it can be measured again.
        val median = medianDelay()
        if (median != null && samples.size >= MIN_SAMPLES_FOR_OUTLIER_TEST && delay > median * OUTLIER_FACTOR) {
            if (++consecutiveRejections < MAX_CONSECUTIVE_REJECTIONS) return false
            reset()
        }
        consecutiveRejections = 0

        samples.addLast(Sample(offset, delay))
        while (samples.size > WINDOW) samples.removeFirst()

        val best = samples.minByOrNull { it.delayUs } ?: return false
        bestDelayUs = best.delayUs
        smoothedOffsetUs = smoothedOffsetUs?.let { current ->
            // Eased rather than assigned, so a single lucky-looking sample cannot step the clock.
            current + ((best.offsetUs - current) * SMOOTHING).toLong()
        } ?: best.offsetUs
        return true
    }

    /**
     * Throws away everything measured so far.
     *
     * For use on reconnect. An offset measured over the previous connection describes conditions
     * that no longer apply, and starting from it would take many samples to unlearn - longer than
     * simply measuring again.
     */
    fun reset() {
        samples.clear()
        smoothedOffsetUs = null
        bestDelayUs = null
        consecutiveRejections = 0
    }

    private fun medianDelay(): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.map { it.delayUs }.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        /** Samples kept. Enough for a stable minimum without remembering conditions long past. */
        const val WINDOW = 16

        /** Below this the median is not yet meaningful, so nothing is rejected as an outlier. */
        const val MIN_SAMPLES_FOR_OUTLIER_TEST = 4

        /** How far past the median a delay may be before the sample is discarded. */
        const val OUTLIER_FACTOR = 3

        /**
         * Rejections in a row before the window is assumed to describe a network that is gone.
         *
         * High enough that a burst of interference is still ridden out - at one sample every two
         * seconds, this is the better part of ten seconds of consistently worse round trips before
         * anything is thrown away.
         */
        const val MAX_CONSECUTIVE_REJECTIONS = 5

        /** How much of a new best sample is taken. Low enough to be steady, high enough to track. */
        const val SMOOTHING = 0.35
    }
}
