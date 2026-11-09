/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Measures how much later one device's speakers sound than another's, by ear - the host's
 * microphone listening to a click from each.
 *
 * The timing offset exists because nothing in the sync protocol can see audio output delay: two
 * players can agree exactly on position while Bluetooth puts one of them a fifth of a second behind.
 * The only thing that can observe that is a microphone. The host records, plays a click on its own
 * speaker, and has the follower play one [FOLLOWER_GAP_US] later on the shared clock. Both clicks
 * land in the same recording, so the microphone's own input delay - unknown, and different on every
 * phone - is in both arrival times and cancels out of their difference:
 *
 *     measured gap - scheduled gap = follower output delay - host output delay
 *
 * which is exactly how far ahead the follower has to play.
 *
 * Each click is placed using the AudioTrack's own presentation timestamps, the same information the
 * player uses to report its position. So what the measurement sees is the part of each device's
 * delay that its audio stack does *not* report - which is precisely the part the music is out by.
 */
object LatencyCalibration {

    /** Scheduled gap between the host's click and the follower's. */
    const val FOLLOWER_GAP_US = 1_500_000L

    /** Lead time before the host's click, enough for the follower to receive its request and start. */
    const val HOST_CLICK_LEAD_US = 1_200_000L

    /** Anything past this either way is a misdetection, not a real device. */
    private const val MAX_PLAUSIBLE_OFFSET_MS = 900

    private const val PLAY_RATE = 48_000
    private const val RECORD_RATE = 16_000
    private const val CHIRP_MS = 30
    private const val CHIRP_LOW_HZ = 1_500.0
    private const val CHIRP_HIGH_HZ = 4_000.0

    /** The test click: a short rising sweep, Hann-windowed, which a matched filter finds sharply. */
    private fun chirp(rate: Int, amplitude: Double): ShortArray {
        val n = rate * CHIRP_MS / 1000
        val seconds = CHIRP_MS / 1000.0
        val sweep = (CHIRP_HIGH_HZ - CHIRP_LOW_HZ) / seconds
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val phase = 2 * PI * (CHIRP_LOW_HZ * t + 0.5 * sweep * t * t)
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sin(phase) * window * amplitude * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    /** Microseconds on [SystemClock.elapsedRealtimeNanos], the clock the session runs on. */
    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1_000

    /**
     * Plays the click so that it reaches the speaker at [atUs] (elapsed-realtime microseconds), as
     * far as this device's audio stack knows. False if it could not be scheduled in time.
     *
     * Streams silence while waiting for the track's first presentation timestamp, then works out
     * which frame will be presented at [atUs] and writes silence exactly up to it.
     */
    suspend fun playClickAt(atUs: Long): Boolean = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(PLAY_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Media, like the music, so it takes the same route and the same delay.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(PLAY_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, PLAY_RATE / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            val silence = ShortArray(PLAY_RATE / 100) // 10 ms
            val click = chirp(PLAY_RATE, amplitude = 0.7)
            val timestamp = AudioTimestamp()
            var written = 0L
            var clickFrame = -1L
            track.play()
            while (isActive) {
                if (clickFrame < 0 && track.getTimestamp(timestamp)) {
                    // AudioTimestamp is on CLOCK_MONOTONIC; the session clock is BOOTTIME.
                    val bootMinusMonoNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
                    val presentedAtBootNs = timestamp.nanoTime + bootMinusMonoNs
                    clickFrame = timestamp.framePosition + (atUs * 1_000 - presentedAtBootNs) * PLAY_RATE / 1_000_000_000L
                    if (clickFrame < written) return@withContext false
                }
                if (clickFrame < 0 && nowUs() > atUs) return@withContext false
                if (clickFrame >= 0 && written >= clickFrame) break
                val frames = if (clickFrame >= 0) minOf(silence.size.toLong(), clickFrame - written).toInt() else silence.size
                track.write(silence, 0, frames)
                written += frames
            }
            track.write(click, 0, click.size)
            // Enough trailing silence that the click has certainly played before the track goes.
            repeat(40) { track.write(silence, 0, silence.size) }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** A recording in progress - see [startRecording]. */
    class Recording internal constructor(private val record: AudioRecord, private val buffer: ShortArray) {
        /** Reads until the buffer is full, then stops. Blocking; call on an IO thread. */
        internal fun readAll(): ShortArray {
            var filled = 0
            while (filled < buffer.size) {
                val n = record.read(buffer, filled, buffer.size - filled)
                if (n <= 0) break
                filled += n
            }
            runCatching { record.stop() }
            record.release()
            return buffer.copyOf(filled)
        }
    }

    /**
     * Starts recording [seconds] of microphone audio. The caller must hold RECORD_AUDIO. Null when
     * the microphone could not be opened (in use, or no permission after all).
     */
    @SuppressLint("MissingPermission")
    fun startRecording(seconds: Double): Recording? = runCatching {
        val minBuffer = AudioRecord.getMinBufferSize(RECORD_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION: no automatic gain or noise suppression, both of which smear a click.
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RECORD_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, RECORD_RATE / 5 * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        record.startRecording()
        Recording(record, ShortArray((RECORD_RATE * seconds).toInt()))
    }.getOrNull()

    /** Finishes [recording] and works out the follower's offset in ms, or null if the clicks were not both heard. */
    suspend fun analyse(recording: Recording): Int? = withContext(Dispatchers.Default) {
        val samples = withContext(Dispatchers.IO) { recording.readAll() }
        offsetFrom(samples)
    }

    /**
     * Finds the two clicks in [samples] and returns how much later the second sounded than
     * scheduled, in ms. Pure arithmetic, so it can be tested on synthetic recordings.
     */
    internal fun offsetFrom(samples: ShortArray): Int? {
        val template = chirp(RECORD_RATE, amplitude = 1.0)
        if (samples.size <= template.size) return null
        // Matched filter: correlate against the known sweep. Its peak is sharp (well under a
        // millisecond wide) and stands far above music or room noise at any other position.
        val score = FloatArray(samples.size - template.size)
        for (i in score.indices) {
            var acc = 0.0
            for (k in template.indices) acc += samples[i + k].toDouble() * template[k]
            score[i] = abs(acc).toFloat()
        }
        val first = score.indices.maxByOrNull { score[it] } ?: return null
        // The other click is at least half a second away from this one.
        val exclusion = RECORD_RATE / 2
        val second = score.indices
            .filter { abs(it - first) > exclusion }
            .maxByOrNull { score[it] } ?: return null
        // Both must be real clicks: a second peak far below the first is just the loudest noise.
        if (score[second] < score[first] * 0.2f) return null

        val hostAt = minOf(first, second)
        val followerAt = maxOf(first, second)
        val gapUs = (followerAt - hostAt) * 1_000_000L / RECORD_RATE
        val offsetMs = ((gapUs - FOLLOWER_GAP_US) / 1_000).toInt()
        return offsetMs.takeIf { abs(it) <= MAX_PLAUSIBLE_OFFSET_MS }
    }
}
