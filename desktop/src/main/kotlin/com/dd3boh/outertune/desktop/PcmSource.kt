/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.aac.Decoder
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Decoded audio, a block at a time, for DesktopPlayer's processing loop.
 *
 * The loop - equaliser, compressor, tempo, visualiser, balance, the output line - is the same
 * whatever the music came from. What differs is only where the PCM comes from: YouTube's AAC in a
 * fragmented MP4, or a file on disk in whatever format it is. This is that seam.
 */
internal interface PcmSource : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val bigEndian: Boolean

    /** The track's length, or 0 when the format does not say. */
    val durationMs: Long

    /** The next block of PCM, possibly empty; null once the track has ended. */
    fun next(): ByteArray?

    /** Moves to [ms] and returns where it actually landed, which is where the clock restarts. */
    fun seek(ms: Long): Long

    override fun close() {}
}

/**
 * YouTube's audio: AAC frames pulled out of a fragmented MP4 that is already wholly in memory.
 *
 * Every AAC-LC frame is 1024 samples, so a time is simply a frame index and seeking costs nothing.
 * The first frame is decoded on construction, because the output format comes from the decoder
 * itself - never assumed (see DesktopPlayer's class doc for the byte-order bug that taught that).
 */
internal class AacSource(
    private val samples: List<ByteArray>,
    config: ByteArray,
) : PcmSource {
    private val decoder = Decoder.create(config)
    private val buffer = SampleBuffer()
    private var index = 0
    private var pending: ByteArray?

    init {
        var first = ByteArray(0)
        while (first.isEmpty() && index < samples.size) {
            decoder.decodeFrame(samples[index++], buffer)
            first = buffer.data
        }
        pending = first
    }

    override val sampleRate = buffer.sampleRate
    override val channels = buffer.channels
    override val bitsPerSample = buffer.bitsPerSample
    override val bigEndian = buffer.isBigEndian
    override val durationMs = samples.size.toLong() * FRAME_SAMPLES * 1000 / sampleRate.coerceAtLeast(1)

    override fun next(): ByteArray? {
        pending?.let {
            pending = null
            return it
        }
        if (index >= samples.size) return null
        decoder.decodeFrame(samples[index++], buffer)
        return buffer.data
    }

    override fun seek(ms: Long): Long {
        pending = null
        index = ((ms * sampleRate / 1000) / FRAME_SAMPLES).toInt().coerceIn(0, (samples.size - 1).coerceAtLeast(0))
        return index.toLong() * FRAME_SAMPLES * 1000 / sampleRate
    }

    private companion object {
        const val FRAME_SAMPLES = 1024L
    }
}

/**
 * A music file on disk - MP3, FLAC, Ogg Vorbis, M4A/AAC, WAV or AIFF - through Java Sound's
 * decoder plugins, converted to 16-bit PCM so the processing loop sees one format whatever the file
 * was - including 24-bit FLAC, which its decoder will not convert and [next] narrows itself.
 *
 * Streamed, not loaded: an album of FLAC is gigabytes. Seeking reopens the file and skips forward,
 * which for compressed formats means decoding up to the point - a fraction of a second for most
 * songs, and simple enough to be right for every format at once.
 */
internal class FileSource(
    private val file: File,
    /** From the tags when known; some decoders cannot tell how long a stream is. */
    knownDurationMs: Long = 0,
) : PcmSource {
    private var stream: AudioInputStream = open()
    private val format: AudioFormat = stream.format

    /**
     * Bytes per sample as decoded: 2 normally, 3 or 4 when the decoder could only give the file's
     * own depth (see [open]) and [next] narrows each sample to 16 bits itself.
     */
    private val sampleBytes = (format.sampleSizeInBits + 7) / 8
    private val frameBytes = format.frameSize.coerceAtLeast(1)
    private val block = ByteArray(BLOCK_FRAMES * frameBytes)

    override val sampleRate = format.sampleRate.toInt()
    override val channels = format.channels
    override val bitsPerSample = 16
    override val bigEndian = false
    override val durationMs: Long = knownDurationMs.takeIf { it > 0 }
        ?: stream.frameLength.takeIf { it > 0 }?.let { it * 1000 / sampleRate.coerceAtLeast(1) }
        ?: 0L

    private fun open(): AudioInputStream {
        val source = AudioSystem.getAudioInputStream(file)
        val base = source.format
        // Signed 16-bit at the file's own rate and channel count: what the loop and the line expect,
        // and what every decoder plugin can produce.
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            base.sampleRate,
            16,
            base.channels,
            base.channels * 2,
            base.sampleRate,
            false,
        )
        if (base.encoding == AudioFormat.Encoding.PCM_SIGNED && base.sampleSizeInBits == 16 && !base.isBigEndian) {
            return source
        }
        if (AudioSystem.isConversionSupported(target, base)) return AudioSystem.getAudioInputStream(target, source)
        // Not every decoder can change depth as it decodes: the FLAC plugin gives a 24-bit file only
        // as 24-bit PCM, and asked for 16 it refused outright, so a hi-res FLAC would not play at all.
        // Decoded at the file's own depth instead, and narrowed in next().
        val bits = base.sampleSizeInBits.takeIf { it > 16 } ?: 16
        val native = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            base.sampleRate,
            bits,
            base.channels,
            base.channels * ((bits + 7) / 8),
            base.sampleRate,
            false,
        )
        return AudioSystem.getAudioInputStream(native, source)
    }

    override fun next(): ByteArray? {
        var filled = 0
        while (filled < block.size) {
            val read = stream.read(block, filled, block.size - filled)
            if (read < 0) break
            filled += read
        }
        if (filled == 0) return null
        // Whole frames only, so a stereo sample is never split across two blocks.
        val whole = filled - filled % frameBytes
        if (sampleBytes == 2) return block.copyOf(whole)
        // Deeper than 16 bits: each little-endian sample keeps its top two bytes, which is the
        // 16-bit value the same sound would have had - truncation, inaudible below the noise floor.
        val samples = whole / sampleBytes
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val top = i * sampleBytes + sampleBytes - 2
            out[i * 2] = block[top]
            out[i * 2 + 1] = block[top + 1]
        }
        return out
    }

    override fun seek(ms: Long): Long {
        runCatching { stream.close() }
        stream = open()
        val frames = ms * sampleRate / 1000
        var toSkip = frames * frameBytes
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        return ms
    }

    override fun close() {
        runCatching { stream.close() }
    }

    private companion object {
        /** About a tenth of a second: small enough to seek and pause promptly. */
        const val BLOCK_FRAMES = 4096
    }
}
