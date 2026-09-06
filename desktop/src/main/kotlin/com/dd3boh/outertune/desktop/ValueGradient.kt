/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The colour a control takes from where its value sits in its range.
 *
 * Yellow at the bottom, green through the middle, blue at the top. That is a whole row of controls
 * saying what they are set to before any of their labels are read - a strip of equaliser bands reads
 * as a shape, and a knob that has been turned up is a different colour from one that has not.
 *
 * This is deliberately the reverse of the Android app's `valueGradientColor`, which runs blue at the
 * low end to yellow at the high end. The two should agree eventually; the desktop follows the
 * direction that was actually asked for, and flipping the phone to match is a one-line change to
 * that function whenever that is wanted.
 *
 * The same three colours drive the response graph, where the fraction is a point's height rather
 * than a control's value - so a curve boosting the treble goes blue where it lifts, for the same
 * reason and by the same rule.
 */
object ValueGradient {

    val LOW = Color(0xFFFFD500)
    val MID = Color(0xFF66DD44)
    val HIGH = Color(0xFF2196F3)

    /** [fraction] from 0 at the bottom of the range to 1 at the top. */
    fun at(fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return if (f < 0.5f) lerp(LOW, MID, f / 0.5f) else lerp(MID, HIGH, (f - 0.5f) / 0.5f)
    }

    /** Where [value] sits in [range], as a colour. */
    fun forValue(value: Float, range: ClosedFloatingPointRange<Float>): Color {
        val span = range.endInclusive - range.start
        if (span == 0f) return MID
        return at((value - range.start) / span)
    }
}
