/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * The wire format for a listen-together session.
 *
 * Deliberately hand-rolled and fixed-width rather than JSON. Every frame is small and most are sent
 * once a second for the length of a session, so the cost of a format is paid continuously; a TICK
 * is 26 bytes here against roughly 120 as JSON. It is also the reason the codec is exhaustively
 * tested - a hand-rolled format is only safe if round-tripping is actually checked.
 *
 * Two decisions are worth stating because they are not obvious and are expensive to change later.
 *
 * **Every frame carries the whole state, not a change to it.** A follower that misses a frame is
 * corrected by the next one rather than being permanently wrong, which removes an entire class of
 * failure - no resynchronisation protocol, no sequence gaps to detect, no divergence that persists.
 * It also means the same protocol can run over an unreliable transport later without redesign.
 *
 * **Times are [android.os.SystemClock.elapsedRealtimeNanos] in microseconds, never wall clock.**
 * `System.currentTimeMillis()` steps when NTP corrects it or the user edits the clock, and a step
 * mid-session would look exactly like a huge sync error and provoke a seek. The monotonic clock also
 * survives deep sleep, which uptime-based clocks do not.
 */
object Protocol {

    /**
     * Bumped when a frame's meaning or layout changes. A peer announcing a different version is
     * refused with a message rather than allowed to misread frames - a protocol mismatch that
     * "mostly works" is worse than one that fails immediately.
     */
    const val VERSION = 1

    /** Frame type tags. Byte-valued so a frame's first byte identifies it. */
    object Type {
        const val HELLO: Byte = 1
        const val WELCOME: Byte = 2
        const val PING: Byte = 3
        const val PONG: Byte = 4
        const val TRACK: Byte = 5
        const val TICK: Byte = 6
        const val BYE: Byte = 7
    }

    /** Why a session ended, carried by [Frame.Bye] so the other end can say something useful. */
    object ByeReason {
        const val NORMAL: Byte = 0
        const val VERSION_MISMATCH: Byte = 1
        const val REJECTED: Byte = 2
        const val HOST_STOPPED: Byte = 3
    }

    sealed interface Frame {
        /** A follower introducing itself. Sent first; nothing else is valid before it. */
        data class Hello(val protocolVersion: Int, val deviceName: String) : Frame

        /** The host accepting, and naming itself so the follower can show who it is following. */
        data class Welcome(val protocolVersion: Int, val hostName: String) : Frame

        /** Clock sync probe. [t1] is the follower's send time. */
        data class Ping(val t1: Long) : Frame

        /**
         * Clock sync reply. [t2] is when the host read the ping and [t3] when it wrote this - both
         * stamped on the socket thread, because any delay between them is indistinguishable from
         * network delay and would bias the offset estimate.
         */
        data class Pong(val t1: Long, val t2: Long, val t3: Long) : Frame

        /**
         * The song changed. Separate from [Tick] because it is rare and carries text, while a tick
         * is frequent and fixed-width - putting them together would pay for the title every second.
         */
        data class Track(
            val videoId: String,
            val title: String,
            val artist: String,
            val durationMs: Long,
            val isLocal: Boolean,
        ) : Frame

        /**
         * Where the host is now.
         *
         * [positionMs] is paired with [hostClockUs]: the position the host reported *at that
         * instant*, not "the position right now". A follower can then work out where the host has
         * reached by the time the frame arrives, which is what makes the delay in transit
         * correctable rather than baked into the answer.
         */
        data class Tick(
            val positionMs: Long,
            val hostClockUs: Long,
            val playing: Boolean,
            val speed: Float,
        ) : Frame

        data class Bye(val reason: Byte) : Frame
    }

    /**
     * Longest string a frame will encode or accept, **in bytes**.
     *
     * Bytes rather than characters, and the distinction is not pedantry. The length prefix is a byte
     * count, so a limit measured in characters does not constrain what actually goes on the wire: 512
     * characters of Japanese is 1536 bytes, which both overflows the encode buffer and fails this
     * check on the way back in. The failure is invisible - the frame is dropped and the follower
     * simply never learns what is playing.
     */
    private const val MAX_STRING = 512

    /**
     * Encode buffer size, derived rather than guessed.
     *
     * The largest frame is a TRACK: a type byte, three length-prefixed strings, a long and a flag.
     * Deriving it means adding a field cannot silently overflow the buffer, which would throw out of
     * [encode] and take the connection down with it.
     */
    private const val MAX_ENCODED = 1 + 3 * (2 + MAX_STRING) + 16

    fun encode(frame: Frame): ByteArray = when (frame) {
        is Frame.Hello -> buffer(Type.HELLO) { putInt(frame.protocolVersion); putString(frame.deviceName) }
        is Frame.Welcome -> buffer(Type.WELCOME) { putInt(frame.protocolVersion); putString(frame.hostName) }
        is Frame.Ping -> buffer(Type.PING) { putLong(frame.t1) }
        is Frame.Pong -> buffer(Type.PONG) { putLong(frame.t1); putLong(frame.t2); putLong(frame.t3) }
        is Frame.Track -> buffer(Type.TRACK) {
            putString(frame.videoId)
            putString(frame.title)
            putString(frame.artist)
            putLong(frame.durationMs)
            put(if (frame.isLocal) 1 else 0)
        }
        is Frame.Tick -> buffer(Type.TICK) {
            putLong(frame.positionMs)
            putLong(frame.hostClockUs)
            put(if (frame.playing) 1 else 0)
            putFloat(frame.speed)
        }
        is Frame.Bye -> buffer(Type.BYE) { put(frame.reason) }
    }

    /**
     * Reads one frame, or null if the bytes are not a frame this version understands.
     *
     * Null rather than an exception for a malformed frame: the caller's only sensible response is to
     * drop it and wait for the next one, and every frame carries the whole state so dropping one
     * costs nothing. Throwing would push that decision up to a socket loop that cannot do anything
     * better with it.
     */
    fun decode(bytes: ByteArray): Frame? = runCatching {
        val b = ByteBuffer.wrap(bytes)
        when (b.get()) {
            Type.HELLO -> Frame.Hello(b.int, b.getString())
            Type.WELCOME -> Frame.Welcome(b.int, b.getString())
            Type.PING -> Frame.Ping(b.long)
            Type.PONG -> Frame.Pong(b.long, b.long, b.long)
            Type.TRACK -> Frame.Track(
                videoId = b.getString(),
                title = b.getString(),
                artist = b.getString(),
                durationMs = b.long,
                isLocal = b.get() != 0.toByte(),
            )
            Type.TICK -> Frame.Tick(
                positionMs = b.long,
                hostClockUs = b.long,
                playing = b.get() != 0.toByte(),
                speed = b.float,
            )
            Type.BYE -> Frame.Bye(b.get())
            else -> null
        }
    }.getOrNull()

    private inline fun buffer(type: Byte, body: ByteBuffer.() -> Unit): ByteArray {
        // One size for every frame, trimmed afterwards, rather than computed per frame - a
        // hand-computed size that is wrong is a buffer overflow, and this one is derived from the
        // limits it has to respect.
        val b = ByteBuffer.allocate(MAX_ENCODED)
        b.put(type)
        b.body()
        return ByteArray(b.position()).also { b.rewind(); b.get(it) }
    }

    private fun ByteBuffer.putString(value: String) {
        val encoded = truncateToBytes(value, MAX_STRING)
        // Length-prefixed rather than delimited: a title can contain any byte a delimiter might use.
        putShort(encoded.size.toShort())
        put(encoded)
    }

    /**
     * Encodes [value], cut to at most [maxBytes] without splitting a character in half.
     *
     * Cutting the byte array at an arbitrary index would leave a dangling UTF-8 sequence, and the
     * decoder would render it as a replacement character - a title ending in a stray diamond. So the
     * cut backs up over continuation bytes, which are the ones matching 10xxxxxx, until it lands on
     * the start of a character.
     */
    private fun truncateToBytes(value: String, maxBytes: Int): ByteArray {
        val full = value.toByteArray(StandardCharsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = maxBytes
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOf(end)
    }

    private fun ByteBuffer.getString(): String {
        val length = short.toInt()
        require(length in 0..MAX_STRING) { "string length $length out of range" }
        val bytes = ByteArray(length)
        get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
