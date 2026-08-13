/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

/**
 * A smoothed snapshot of what's currently playing, published by [VisualizerAnalyzer] at a UI-safe
 * rate (tens of times a second, not once per sample) for a background renderer to react to.
 *
 * Everything is already attack/release-smoothed and clamped to roughly 0..1 on the audio thread -
 * a consumer can use these directly without doing its own filtering.
 */
data class VisualizerFrame(
    /** Low-frequency energy (below ~250Hz). */
    val bass: Float = 0f,
    /** Mid-frequency energy (~250Hz-4kHz). */
    val mid: Float = 0f,
    /** High-frequency energy (above ~4kHz). */
    val treble: Float = 0f,
    /** Spikes briefly on a sudden loudness increase (a beat, a hit, a new phrase starting). */
    val transient: Float = 0f,
)
