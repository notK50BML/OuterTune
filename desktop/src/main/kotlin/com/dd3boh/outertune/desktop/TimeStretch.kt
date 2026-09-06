/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tempo and pitch, changed independently.
 *
 * Playing a file faster by reading it faster raises its pitch, which is the chipmunk effect and not
 * what anyone means by "play this a bit quicker". Separating the two takes two stages:
 *
 *  - **WSOLA** ([Wsola]) stretches time and leaves pitch alone. It cuts the signal into overlapping
 *    frames and overlap-adds them at a different spacing than it took them. Doing that naively
 *    produces a warble, because the frames no longer line up on the waveform's own periods; WSOLA's
 *    whole contribution is to nudge each frame by up to a few milliseconds to the position that best
 *    matches what came before, so the periods stay aligned.
 *  - **Resampling** ([Resampler]) reads the stretched signal at a different rate, which changes
 *    pitch and tempo together.
 *
 * Composing them gives either one alone: resample by the pitch ratio `P` and that has also changed
 * tempo by `P`, so stretch by `T / P` first and the tempo comes out at `T`.
 *
 * Both stages are streaming. The audio arrives one decoded block at a time and blocks are not frame
 * aligned, so everything here carries its position and its overlap remainder between calls.
 */
class TimeStretch {

    /**
     * Playback tempo, 1.0 being unchanged.
     *
     * Bounded well inside what WSOLA handles gracefully. Beyond about double or half, the frames
     * being overlapped are so far apart in the original that no alignment makes them continuous, and
     * it starts to sound like a bad tape edit rather than a fast song.
     */
    var tempo: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_TEMPO, MAX_TEMPO)
        }

    /** Pitch offset in semitones, 0 being unchanged. */
    var pitchSemitones: Float = 0f
        set(value) {
            field = value.coerceIn(MIN_SEMITONES, MAX_SEMITONES)
        }

    /**
     * Whether this changes anything.
     *
     * Checked so the untouched case costs nothing. At default settings the audio should reach the
     * output line as the decoder produced it, not as a byte-for-byte-equal copy that has been
     * through two interpolation stages - "equal" would depend on floating point rounding, and it is
     * not worth being nearly-right about the common case.
     */
    val active: Boolean get() = tempo != 1f || pitchSemitones != 0f

    private val pitchRatio: Double get() = 2.0.pow(pitchSemitones / 12.0)

    private var wsola: Wsola? = null
    private var resampler: Resampler? = null
    private var configuredRate = 0
    private var configuredChannels = 0

    /**
     * Puts one decoded block through, returning the processed audio.
     *
     * Returns a new array rather than working in place: the output is a different length from the
     * input by definition, so there is nowhere to put it. When nothing is being changed, the input
     * is handed straight back.
     */
    fun process(
        bytes: ByteArray,
        length: Int,
        bitsPerSample: Int,
        channels: Int,
        bigEndian: Boolean,
        sampleRate: Int,
    ): ByteArray {
        if (!active || channels < 1 || sampleRate <= 0 || bitsPerSample != 16 || length <= 0) {
            return if (length == bytes.size) bytes else bytes.copyOf(length)
        }
        if (configuredRate != sampleRate || configuredChannels != channels) {
            wsola = Wsola(channels, sampleRate)
            resampler = Resampler(channels)
            configuredRate = sampleRate
            configuredChannels = channels
        }

        val stretcher = wsola ?: return bytes
        val interpolator = resampler ?: return bytes

        val frames = length / (2 * channels)
        val input = Pcm.deinterleave(bytes, frames, channels, bigEndian)

        // Stretch by tempo/pitch, then resample by pitch: the resampling stage multiplies both, so
        // the stretch has to pre-divide the tempo by the same amount for tempo to land where asked.
        val stretched = stretcher.process(input, (tempo / pitchRatio).toFloat())
        val resampled = interpolator.process(stretched, pitchRatio)

        return Pcm.interleave(resampled, channels, bigEndian)
    }

    /** Forgets everything carried between blocks. Called on a seek or a track change. */
    fun reset() {
        wsola = null
        resampler = null
        configuredRate = 0
        configuredChannels = 0
    }

    companion object {
        const val MIN_TEMPO = 0.5f
        const val MAX_TEMPO = 2f
        const val MIN_SEMITONES = -12f
        const val MAX_SEMITONES = 12f
    }
}

/**
 * Waveform-similarity overlap-add: changes how long the signal lasts, not what it sounds like.
 *
 * Output frames are emitted at a fixed spacing of [Wsola.HOP] samples. Input frames are taken at a
 * spacing of `HOP * speed`, which is what does the stretching - read the input faster than you write
 * it and the result is shorter. The similarity search is the part that makes it music rather than
 * a stutter: each input frame may start up to [Wsola.SEARCH] samples either side of where the fixed
 * spacing says it should, and it takes whichever of those positions best continues the previous
 * frame. On a periodic signal that is the position exactly one period along, which is why the pitch
 * survives.
 *
 * All channels move together. Searching per channel would find slightly different offsets for left
 * and right and smear the stereo image, so the offset is chosen from channel zero and applied to
 * every channel.
 */
private class Wsola(private val channels: Int, sampleRate: Int) {

    /**
     * Half a frame, and the spacing between output frames.
     *
     * Scaled to the sample rate so the frame lasts the same time regardless of format - around 23ms,
     * which is long enough to hold a couple of periods of a low male voice and short enough that the
     * signal does not change much across one.
     */
    private val hop = (sampleRate * FRAME_MS / 1000).coerceAtLeast(64)

    /** How far either side of the ideal position a frame may be nudged - about 12ms. */
    private val search = (hop / 2).coerceAtLeast(16)

    private val fadeIn = FloatArray(hop) { 0.5f * (1f - cos(PI * it / hop).toFloat()) }

    /** Input not yet consumed, per channel. */
    private var pending = Array(channels) { FloatArray(0) }

    /** The second half of the last frame emitted, waiting to be overlapped with the next. */
    private var tail: Array<FloatArray>? = null

    /**
     * The fixed analysis grid: where the next frame would start if nothing were nudged.
     *
     * Advances by exactly one analysis hop per output frame regardless of where the similarity
     * search actually lands, which is what pins the stretch ratio to the value asked for.
     */
    private var ideal = 0.0

    fun process(input: Array<FloatArray>, speed: Float): Array<FloatArray> {
        for (c in 0 until channels) pending[c] = pending[c] + input[c]
        val available = pending[0].size

        val analysisHop = hop * speed.toDouble()
        val out = Array(channels) { FloatArrayBuilder() }

        // A frame needs to reach `start + 2 * hop`, and `start` may be `search` past `ideal`.
        while (ideal + search + 2 * hop <= available) {
            val centre = ideal.roundToInt()
            val previous = tail
            val start = if (previous == null) centre else bestOffset(previous[0], centre)

            if (previous == null) {
                // Nothing to overlap with yet, so the first hop is emitted as it stands.
                for (c in 0 until channels) out[c].add(pending[c], start, hop)
            } else {
                for (c in 0 until channels) {
                    val src = pending[c]
                    val blended = FloatArray(hop)
                    for (i in 0 until hop) {
                        // The two Hann halves sum to one, so a steady signal keeps its level
                        // through the crossfade instead of dipping.
                        blended[i] = previous[c][i] * (1f - fadeIn[i]) + src[start + i] * fadeIn[i]
                    }
                    out[c].add(blended, 0, hop)
                }
            }

            tail = Array(channels) { c -> pending[c].copyOfRange(start + hop, start + 2 * hop) }
            // Advanced along its own fixed grid, NOT from the position just chosen. The nudge is a
            // correction to one frame, not a move of the read head: folding it back into `ideal`
            // lets the corrections accumulate, and since they are bounded only by the search radius
            // the ratio drifts away from what was asked. Measured, that turned a request for 1.5x
            // into 2.02x, and a half-speed stretch - where the radius equals the whole hop - into
            // forty-four times the audio, because consumption per frame fell to nearly nothing.
            ideal += analysisHop
        }

        // Drop what can no longer be reached. The search may look `search` samples back from
        // `ideal`, so that much has to stay.
        val keepFrom = (ideal.toInt() - search).coerceAtLeast(0)
        if (keepFrom > 0) {
            for (c in 0 until channels) pending[c] = pending[c].copyOfRange(keepFrom, pending[c].size)
            ideal -= keepFrom
        }

        return Array(channels) { out[it].toArray() }
    }

    /**
     * The position near [centre] whose next [hop] samples best continue [previous].
     *
     * Normalised cross-correlation rather than plain: without the normalisation the search prefers
     * whichever candidate is simply loudest, so it drifts towards transients and away from the
     * position that actually lines up.
     *
     * Searched outward from [centre], keeping a candidate only on a strictly better score. That
     * ordering is not cosmetic - it is what makes the stretch happen at all. A periodic signal has a
     * matching position every period, so across a search window a couple of hundred samples wide
     * there are ten or more equally perfect candidates. Scanning left to right and taking the first
     * maximum picks the leftmost every time, which pulls the analysis position back by nearly the
     * search radius on every frame and very nearly cancels the advance. A 440Hz tone asked to play
     * half again as fast came out 4% shorter.
     *
     * Searching outward instead means ties resolve to the smallest nudge, which is also what WSOLA
     * wants on its own terms: the offset is a correction to keep periods aligned, not a free choice.
     */
    private fun bestOffset(previous: FloatArray, centre: Int): Int {
        val src = pending[0]
        val lo = (centre - search).coerceAtLeast(0)
        val hi = (centre + search).coerceAtMost(src.size - 2 * hop)
        if (hi <= lo) return lo.coerceIn(0, (src.size - 2 * hop).coerceAtLeast(0))

        fun score(k: Int): Float {
            var dot = 0f
            var energy = 0f
            var i = 0
            // Every fourth sample. At 44.1kHz the window spans about a thousand positions and the
            // correlation surface is smooth over a few samples, so a full scan spends four times the
            // arithmetic to land in the same place.
            while (i < hop) {
                val v = src[k + i]
                dot += previous[i] * v
                energy += v * v
                i += 4
            }
            return if (energy > 1e-9f) dot / sqrt(energy) else 0f
        }

        val start = centre.coerceIn(lo, hi)
        var bestAt = start
        var best = score(start)
        var step = 4
        while (step <= search) {
            for (k in intArrayOf(start - step, start + step)) {
                if (k in lo..hi) {
                    val s = score(k)
                    if (s > best) {
                        best = s
                        bestAt = k
                    }
                }
            }
            step += 4
        }
        return bestAt
    }

    private companion object {
        const val FRAME_MS = 23
    }
}

/**
 * Reads a signal at a different rate, which moves pitch and tempo together.
 *
 * Cubic rather than linear interpolation. Linear is a low-pass filter with a droop that reaches
 * several decibels near Nyquist, so a resampled cymbal audibly dulls; the Catmull-Rom spline here is
 * a couple of multiplies more and flat enough across the audible band not to be heard.
 *
 * Position is fractional and carried between blocks, along with the three samples of history the
 * spline needs, so block boundaries leave no discontinuity.
 */
private class Resampler(private val channels: Int) {

    /**
     * Input not yet read, with one sample of lead-in so the spline always has a point behind it.
     *
     * A single zero at the very start of the stream, which is one sample of edge effect and
     * inaudible. Every later block carries real audio here instead.
     */
    private var carry = Array(channels) { FloatArray(1) }

    /** Fractional read position within [carry], never below 1 so `p[i - 1]` exists. */
    private var position = 1.0

    fun process(input: Array<FloatArray>, ratio: Double): Array<FloatArray> {
        if (input[0].isEmpty()) return input

        val padded = Array(channels) { c -> carry[c] + input[c] }
        val out = Array(channels) { FloatArrayBuilder() }

        var at = position
        // Stops two short of the end because the spline reads `p[i + 2]`.
        val limit = padded[0].size - 2.0
        while (at < limit) {
            val i = at.toInt()
            val t = (at - i).toFloat()
            for (c in 0 until channels) {
                val p = padded[c]
                out[c].add(catmullRom(p[i - 1], p[i], p[i + 1], p[i + 2], t))
            }
            at += ratio
        }

        // Keep from one sample behind the next read, which is exactly what the next spline needs,
        // and carry the fraction so the output grid never restarts mid-stream. Recomputing this
        // from anywhere else drops or repeats samples at every block boundary.
        val keepFrom = at.toInt() - 1
        for (c in 0 until channels) carry[c] = padded[c].copyOfRange(keepFrom, padded[c].size)
        position = at - keepFrom

        return Array(channels) { out[it].toArray() }
    }

    private fun catmullRom(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            2f * b +
                (c - a) * t +
                (2f * a - 5f * b + 4f * c - d) * t2 +
                (3f * b - a - 3f * c + d) * t3
            )
    }
}

/** A growable float array, because the output length is not known before the loop runs. */
private class FloatArrayBuilder {
    private var data = FloatArray(1024)
    private var size = 0

    fun add(value: Float) {
        ensure(size + 1)
        data[size++] = value
    }

    fun add(source: FloatArray, offset: Int, count: Int) {
        ensure(size + count)
        System.arraycopy(source, offset, data, size, count)
        size += count
    }

    private fun ensure(capacity: Int) {
        if (capacity <= data.size) return
        var next = data.size
        while (next < capacity) next *= 2
        data = data.copyOf(next)
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
