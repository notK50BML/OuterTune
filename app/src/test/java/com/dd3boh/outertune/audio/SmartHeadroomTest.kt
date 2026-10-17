/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.dd3boh.outertune.models.EqualizerSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * SMART headroom against AUTO, on music that does not use the room it has.
 *
 * A tone 12dB below full scale with a +6dB band on it never needs turning down - it peaks at -6dB
 * after the boost. AUTO turns it down by the whole 6dB regardless, so the boosted tone comes out
 * where it went in; SMART should leave nearly all of the boost audible.
 */
class SmartHeadroomTest {

    private val rate = 44_100

    private fun peakDbAfter(smart: Boolean): Double {
        val processor = EqualizerAudioProcessor()
        val base = EqualizerSettings.DEFAULT
        val boosted = base.copy(
            enabled = true,
            bands = base.bands.map { if (it.freqHz in 900f..1100f) it.copy(gainDb = 6f) else it },
        )
        processor.setSettings(boosted)
        processor.setSmartHeadroom(smart)
        processor.setHeadroom(1f)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        val amplitude = 0.25 // -12 dBFS
        var lastPeak = 0.0
        // Five seconds in tenth-of-a-second buffers, so SMART has time to learn the room.
        repeat(50) { block ->
            val frames = rate / 10
            val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) {
                val n = block * frames + i
                val v = (sin(2 * PI * 1000 * n / rate) * amplitude * 32767).toInt().toShort()
                input.putShort(v)
                input.putShort(v)
            }
            input.flip()
            processor.queueInput(input)
            val out = processor.output
            var peak = 0
            while (out.remaining() >= 2) peak = maxOf(peak, abs(out.getShort().toInt()))
            lastPeak = peak / 32768.0
        }
        return 20 * log10(lastPeak)
    }

    @Test
    fun `smart keeps a boost that fits, where auto takes it all away`() {
        val auto = peakDbAfter(smart = false)
        val smart = peakDbAfter(smart = true)
        // Auto: back near the input level of -12dB. Smart: most of the +6dB still there.
        assertTrue("auto peaked at %.1f dB".format(auto), auto < -10.5)
        assertTrue("smart peaked at %.1f dB".format(smart), smart > -8.0)
        assertTrue("smart never clips: %.1f dB".format(smart), smart < 0.0)
    }
}
