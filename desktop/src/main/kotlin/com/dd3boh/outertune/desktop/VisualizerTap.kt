/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.util.ArrayDeque

/**
 * Keeps the visualiser showing what is being heard, rather than what is being decoded.
 *
 * This exists because of a gap that is easy to miss and impossible to unsee. Audio is written to the
 * output line well before it is played - the line holds a buffer, typically most of a second - so a
 * spectrum computed at the moment a frame is decoded describes music that has not reached the
 * speakers yet. Drawn directly, the bars lead the sound by about a second: they jump before the
 * kick, and settle before the note ends. It looks less like a visualiser and more like a fault.
 *
 * The same gap is already handled for the position readout in [DesktopPlayer], which reads the
 * line's own frame counter instead of counting what it has written, and for the same reason.
 *
 * So spectra are computed when the audio is decoded, tagged with the frame they belong to, and held
 * until the line reports having played that far. The queue is the delay.
 *
 * Not thread-safe by accident: [submit] is called from the decode coroutine and [sample] from the
 * UI, so both take the lock. The critical sections are a few array copies.
 */
class VisualizerTap(private val bands: Int) {

    private class Frame(val atFrame: Long, val levels: FloatArray)

    private val lock = Any()
    private val queue = ArrayDeque<Frame>()
    private val current = FloatArray(bands)

    /**
     * Offers a spectrum, to be shown once playback reaches [atFrame].
     *
     * @param atFrame the output line's frame position at which this audio becomes audible - that is,
     *   the line's position when it was written, since the line plays what it holds in order.
     */
    fun submit(atFrame: Long, levels: FloatArray) {
        synchronized(lock) {
            // Copied, because the analyser reuses its array between calls - keeping the reference
            // would make every queued entry show whatever the latest one happens to contain.
            queue.addLast(Frame(atFrame, levels.copyOf()))
            // A bound, in case the UI is not consuming: a window that is minimised or a frame that
            // never renders must not turn into an unbounded queue of a few hundred bytes each.
            while (queue.size > MAX_QUEUED) queue.removeFirst()
        }
    }

    /**
     * The spectrum for what is audible now, given the line's current frame position.
     *
     * Everything already played is discarded and the most recent of it wins, so a UI that renders
     * more slowly than audio is decoded stays current instead of falling progressively further
     * behind.
     */
    fun sample(playedFrame: Long): FloatArray {
        synchronized(lock) {
            var taken: Frame? = null
            while (queue.isNotEmpty() && queue.peekFirst().atFrame <= playedFrame) {
                taken = queue.removeFirst()
            }
            taken?.levels?.copyInto(current)
            return current
        }
    }

    /** Forgets everything queued, for a seek, a track change or a stop. */
    fun reset() {
        synchronized(lock) {
            queue.clear()
            current.fill(0f)
        }
    }

    private companion object {
        /**
         * Roughly ten seconds of frames at 1024 samples each.
         *
         * Comfortably more than any output line buffers, so a correct alignment is never dropped,
         * and small enough that a stalled consumer cannot grow this without bound.
         */
        const val MAX_QUEUED = 512
    }
}
