/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hand-rolled binary format is only worth its size if round-tripping is actually checked, which is
 * what these do. The interesting cases are the ones where a field could silently land in the wrong
 * place: strings with multi-byte characters, empty strings, and values at the edges of their types.
 */
class ProtocolTest {

    private fun roundTrip(frame: Protocol.Frame): Protocol.Frame? =
        Protocol.decode(Protocol.encode(frame))

    @Test
    fun `every frame type survives a round trip`() {
        val frames = listOf(
            Protocol.Frame.Hello(Protocol.VERSION, "Pixel 8"),
            Protocol.Frame.Welcome(Protocol.VERSION, "Living room"),
            Protocol.Frame.Ping(1_234_567_890L),
            Protocol.Frame.Pong(1L, 2L, 3L),
            Protocol.Frame.Track("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213_000L, false),
            Protocol.Frame.Tick(42_000L, 9_876_543_210L, true, 1.0f),
            Protocol.Frame.Bye(Protocol.ByeReason.NORMAL),
        )
        frames.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun `titles with multi-byte characters survive`() {
        // Length-prefixing is in bytes, not characters. Getting that wrong truncates exactly the
        // titles most likely to appear in a music library.
        val frame = Protocol.Frame.Track(
            videoId = "abc",
            title = "夜に駆ける — ヨアソビ",
            artist = "YOASOBI ✨",
            durationMs = 261_000L,
            isLocal = false,
        )
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `empty strings survive`() {
        val frame = Protocol.Frame.Track("", "", "", 0L, true)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `booleans and floats are not lost`() {
        val paused = Protocol.Frame.Tick(0L, 0L, false, 0.98f)
        assertEquals(paused, roundTrip(paused))
        val local = Protocol.Frame.Track("x", "y", "z", 1L, isLocal = true)
        assertEquals(local, roundTrip(local))
    }

    @Test
    fun `large timestamps survive`() {
        // elapsedRealtimeNanos in microseconds is comfortably inside a Long, but a careless Int
        // anywhere in the codec would wrap here rather than at a value anyone would notice.
        val frame = Protocol.Frame.Tick(7_200_000L, 8_000_000_000_000L, true, 1f)
        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(Protocol.decode(byteArrayOf()))
        assertNull(Protocol.decode(byteArrayOf(99)))
        assertNull(Protocol.decode(byteArrayOf(Protocol.Type.TICK)))
        assertNull(Protocol.decode(ByteArray(4) { 0x7F }))
    }

    @Test
    fun `a truncated frame decodes to null rather than a wrong value`() {
        // The dangerous failure is not a crash, it is a frame that decodes to something plausible.
        val encoded = Protocol.encode(Protocol.Frame.Tick(50_000L, 1_000L, true, 1f))
        for (length in 1 until encoded.size) {
            assertNull("truncating to $length bytes should not decode", Protocol.decode(encoded.copyOf(length)))
        }
    }

    @Test
    fun `a tick stays small enough to send every second`() {
        val size = Protocol.encode(Protocol.Frame.Tick(1L, 2L, true, 1f)).size
        assertTrue("tick was $size bytes", size < 40)
    }

    @Test
    fun `an over-long string is truncated rather than rejected`() {
        // A pathological title should cost a truncated title, not a dropped frame - the position in
        // that frame is still worth having.
        val frame = Protocol.Frame.Track("id", "x".repeat(5000), "artist", 1L, false)
        val decoded = roundTrip(frame)
        assertNotNull(decoded)
        assertTrue((decoded as Protocol.Frame.Track).title.length <= 512)
    }
}
