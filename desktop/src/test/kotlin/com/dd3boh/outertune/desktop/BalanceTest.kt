/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balance law, measured on real bytes.
 *
 * Worth measuring rather than reading, because the two mistakes available here are both silent. A
 * boost-the-near-side law makes the track louder as it moves off centre, which sounds like an
 * improvement and is not one. And reading samples one way round while writing them the other is not
 * an error the compiler or the ear can catch on a test tone - it is noise, on music, later.
 */
class BalanceTest {

    /** One frame of interleaved 16-bit stereo, big-endian as the decoder in use hands it over. */
    private fun stereo(left: Short, right: Short, frames: Int = 1): ByteArray {
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val at = f * 4
            out[at] = ((left.toInt() shr 8) and 0xFF).toByte()
            out[at + 1] = (left.toInt() and 0xFF).toByte()
            out[at + 2] = ((right.toInt() shr 8) and 0xFF).toByte()
            out[at + 3] = (right.toInt() and 0xFF).toByte()
        }
        return out
    }

    private fun readLeft(bytes: ByteArray) =
        Pcm.readSample(bytes, 0, bytesPerSample = 2, bigEndian = true).toInt()

    private fun readRight(bytes: ByteArray) =
        Pcm.readSample(bytes, 2, bytesPerSample = 2, bigEndian = true).toInt()

    private fun process(balance: Float, bytes: ByteArray): ByteArray {
        Balance().apply { value = balance }
            .process(bytes, bytes.size, bitsPerSample = 16, channels = 2, bigEndian = true)
        return bytes
    }

    @Test
    fun `centre leaves both channels alone`() {
        val out = process(0f, stereo(10000, -8000))
        assertEquals(10000, readLeft(out))
        assertEquals(-8000, readRight(out))
    }

    @Test
    fun `panning attenuates the far side and never boosts the near one`() {
        // The whole law in one assertion. Half right: the right channel is untouched at full scale
        // and the left is halved. A boost law would put the right at 15000 and make every pan a
        // volume increase in disguise.
        val out = process(0.5f, stereo(10000, 10000))
        assertEquals(5000, readLeft(out))
        assertEquals(10000, readRight(out))
    }

    @Test
    fun `hard left silences the right channel`() {
        val out = process(-1f, stereo(10000, 10000))
        assertEquals(10000, readLeft(out))
        assertEquals(0, readRight(out))
    }

    @Test
    fun `hard right silences the left channel`() {
        val out = process(1f, stereo(10000, 10000))
        assertEquals(0, readLeft(out))
        assertEquals(10000, readRight(out))
    }

    @Test
    fun `a value past the ends is clamped rather than inverted`() {
        // Out of range is not a shape this has to handle gracefully so much as one it must not
        // handle *wrongly*: without the clamp, 2.0 gives a left gain of -1, which is the channel
        // played back with its phase inverted - inaudible on its own and a hollowed-out centre
        // image the moment the two are summed, which is what a mono speaker does.
        val out = process(2f, stereo(10000, 10000))
        assertEquals(0, readLeft(out))
        assertEquals(10000, readRight(out))
    }

    @Test
    fun `every frame in a block is processed`() {
        val out = process(-1f, stereo(10000, 10000, frames = 64))
        for (f in 0 until 64) {
            assertEquals(
                "frame $f left",
                10000,
                Pcm.readSample(out, f * 4, 2, bigEndian = true).toInt(),
            )
            assertEquals(
                "frame $f right",
                0,
                Pcm.readSample(out, f * 4 + 2, 2, bigEndian = true).toInt(),
            )
        }
    }

    @Test
    fun `a stream that is not stereo is left alone`() {
        // "Balance" has no defined meaning for mono or for surround, and quietly attenuating a
        // channel because it happens to be indexed odd would be inventing one.
        val mono = byteArrayOf(0x27, 0x10, 0x27, 0x10)
        val before = mono.copyOf()
        Balance().apply { value = 1f }
            .process(mono, mono.size, bitsPerSample = 16, channels = 1, bigEndian = true)
        assertTrue(before.contentEquals(mono))
    }

    @Test
    fun `byte order is respected in both directions`() {
        // Little-endian in, little-endian out. A stage that reads one way and writes the other
        // still produces plausible-looking bytes, and produces them silently.
        val le = byteArrayOf(0x10, 0x27, 0x10, 0x27) // 10000, 10000
        Balance().apply { value = -1f }
            .process(le, le.size, bitsPerSample = 16, channels = 2, bigEndian = false)
        assertEquals(10000, Pcm.readSample(le, 0, 2, bigEndian = false).toInt())
        assertEquals(0, Pcm.readSample(le, 2, 2, bigEndian = false).toInt())
    }
}
