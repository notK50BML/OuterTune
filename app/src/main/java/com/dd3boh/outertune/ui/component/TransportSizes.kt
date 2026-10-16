/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.ui.unit.dp

/**
 * How big the five transport controls are, wherever they appear.
 *
 * Three tiers, and the tiers are the point: play/pause is the one being aimed at in a hurry, skip
 * is used often, shuffle and repeat are set once and left. Sizing them equally makes the row read
 * as five of the same thing and makes the one that matters no easier to hit than the others.
 *
 * Shared because they were not: the equaliser's row ran 34/38/44 and the queue's ran 32/32/36, so
 * the same five controls were visibly different sizes depending on which sheet you had open. The
 * numbers here are the equaliser's, which were the better-proportioned pair.
 */
object TransportSize {
    /** Shuffle, repeat, and the optional seek buttons - set once, then left alone. */
    val Secondary = 34.dp

    /** Previous and next. */
    val Skip = 38.dp

    /** Play/pause. */
    val Primary = 44.dp
}
