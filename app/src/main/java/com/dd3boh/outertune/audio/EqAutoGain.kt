/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import kotlin.math.max

/**
 * How far the equaliser turns the signal up or down before its filters run - the one rule behind
 * Automatic headroom, "Turn up for cuts" and the normalisation the processor takes over.
 *
 * Kept apart from the processor so it can be tested as a rule rather than through a stream of
 * samples, and so the desktop copy (desktop/.../EqAutoGain.kt) has one small thing to agree with.
 *
 * The rule, from two measurements of the finished curve - its highest point and its loudness-
 * weighted average, both taken off the summed response of every filter together rather than added
 * up band by band:
 *
 * - **Anything boosted** (the curve rises above 0dB anywhere): turned down by that highest point, so
 *   the loudest frequency comes out where it went in and a track mastered to full scale stays under
 *   it. A boost then changes the tone, not the level. No makeup is added on top, even when the curve
 *   also cuts: the loudness-weighted makeup a cut would earn is never more than the room a boost
 *   leaves - an average cannot exceed its own maximum - so the boost's limit always wins. This is
 *   what was wrong before: a curve that both cut and boosted had its makeup added after the
 *   headroom, which put the boosted frequencies over full scale and straight into the limiter.
 * - **Cuts only**: turned up by the loudness the cuts took away, up to [MAX_MAKEUP_DB], when the cut
 *   makeup is on. Measured from the average rather than the peak, because cutting the bass alone
 *   leaves the mids at 0dB and a peak-based makeup would then do nothing. This can lift the uncut
 *   frequencies a little above where they arrived, and on a loud master that is the limiter's to
 *   catch - as it always was.
 * - **Normalisation** is attenuation the track is getting anyway, so it counts as headroom already
 *   paid: a boost that fits inside it costs nothing, and only a larger one turns down further. A cut
 *   curve's makeup is taken off it instead.
 */
object EqAutoGain {

    /** The most a cut-only curve is turned back up, in dB. */
    const val MAX_MAKEUP_DB = 12f

    /**
     * The most a boost is turned down, in dB. Far above anything the sliders can reach together - it
     * is there so a curve that is nonsense (a high-Q pass filter's resonance stacked on every band at
     * full) cannot ask for silence. Anything past it is the limiter's.
     */
    const val MAX_HEADROOM_DB = 30f

    /**
     * Below this the curve counts as not boosting. The response is computed in floats, and a curve of
     * cuts with some bands at zero can read a few thousandths of a decibel over; that is not a boost,
     * and treating it as one would switch the makeup off.
     */
    const val BOOST_EPSILON_DB = 0.05f

    /**
     * The pre-gain in dB - negative turns down.
     *
     * @param peakDb the summed response's highest point, which may be below zero for a curve of cuts.
     * @param meanPowerDb the summed response's loudness-weighted average - see
     *   [BiquadCoefficients.meanPowerGainDb].
     * @param headroomFactor how much of a boost's peak is paid for, 0..1 - the phone's Headroom setting.
     * @param normalisationDb attenuation this track receives anyway, as a positive number.
     */
    fun gainDb(
        peakDb: Float,
        meanPowerDb: Float,
        cutMakeup: Boolean,
        headroomFactor: Float = 1f,
        normalisationDb: Float = 0f,
    ): Float {
        val normalisation = normalisationDb.coerceAtLeast(0f)
        if (peakDb > BOOST_EPSILON_DB) {
            val headroom = peakDb.coerceAtMost(MAX_HEADROOM_DB) * headroomFactor.coerceIn(0f, 1f)
            return -max(headroom, normalisation)
        }
        return makeupDb(peakDb, meanPowerDb, cutMakeup) - normalisation
    }

    /** The cut makeup alone, in dB - what [gainDb] adds for this curve, zero once anything boosts. */
    fun makeupDb(peakDb: Float, meanPowerDb: Float, cutMakeup: Boolean): Float =
        if (!cutMakeup || peakDb > BOOST_EPSILON_DB) 0f else (-meanPowerDb).coerceIn(0f, MAX_MAKEUP_DB)
}
