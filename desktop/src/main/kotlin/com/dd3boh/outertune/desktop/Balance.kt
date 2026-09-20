/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * Left/right balance: a gain on one channel, and nothing else.
 *
 * The law is the phone's, in `EqualizerAudioProcessor`, deliberately to the digit: at centre both
 * channels pass at unity, and panning one way only pulls the *other* channel down. The alternative -
 * boosting the near side - makes the track louder as it moves off centre, and then the pan gets
 * judged against a louder version of itself and sounds better than it is. It also has nowhere to go
 * at full scale but into the clipper.
 *
 * Channels beyond the first two are left alone. "Balance" has no defined meaning for a stream that
 * is not plain stereo, and quietly attenuating a surround channel because it happens to be indexed
 * odd would be inventing one.
 *
 * Sits after the time stretch and before the write, which puts it after the visualiser's own
 * measurement point for a reason: the spectrum is a mono sum, so panning hard to one side would halve
 * every bar and read as the music getting quieter rather than as it moving.
 */
class Balance {

    /** -1 is hard left, 0 centre, +1 hard right. Anything outside that is clamped on use. */
    @Volatile
    var value: Float = 0f

    fun process(
        bytes: ByteArray,
        length: Int,
        bitsPerSample: Int,
        channels: Int,
        bigEndian: Boolean,
    ) {
        val balance = value.coerceIn(-1f, 1f)
        // The common case, and worth the check: it saves touching every sample in the block to
        // multiply it by one.
        if (balance == 0f || channels != 2) return

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return

        val leftGain = (1f - balance).coerceIn(0f, 1f)
        val rightGain = (1f + balance).coerceIn(0f, 1f)
        val peak = (1L shl (bitsPerSample - 1)).toFloat()
        val maxValue = peak - 1f
        val frames = length / (bytesPerSample * channels)

        var pos = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val gain = if (c == 0) leftGain else rightGain
                val sample = Pcm.readSample(bytes, pos, bytesPerSample, bigEndian).toFloat()
                // Clamped for form's sake rather than need - both gains are at most 1, so nothing
                // here can leave the range it arrived in. It costs one comparison per sample and
                // means this stage cannot become the one that wraps a sample if a gain ever grows.
                val out = (sample * gain).coerceIn(-peak, maxValue).toLong()
                Pcm.writeSample(bytes, pos, bytesPerSample, bigEndian, out)
                pos += bytesPerSample
            }
        }
    }
}
