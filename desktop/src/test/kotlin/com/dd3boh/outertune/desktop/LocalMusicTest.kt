/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

class LocalMusicTest {

    @Test
    fun `artists split on tagger separators but not inside a name`() {
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), LocalMusic.artistsFrom(emptyList(), listOf("Daft Punk feat. Pharrell Williams"), ""))
        assertEquals(listOf("A", "B", "C"), LocalMusic.artistsFrom(emptyList(), listOf("A; B / C"), ""))
        assertEquals(listOf("Simon & Garfunkel"), LocalMusic.artistsFrom(emptyList(), listOf("Simon & Garfunkel"), ""))
        assertEquals(listOf("Tyler, The Creator"), LocalMusic.artistsFrom(emptyList(), listOf("Tyler, The Creator"), ""))
        // A slash with no spaces is part of the name.
        assertEquals(listOf("AC/DC"), LocalMusic.artistsFrom(emptyList(), listOf("AC/DC"), ""))
        assertEquals(listOf("AC/DC", "Ozzy"), LocalMusic.artistsFrom(emptyList(), listOf("AC/DC / Ozzy"), ""))
        // The multi-value field wins when present; album artist is the last resort.
        assertEquals(listOf("X", "Y"), LocalMusic.artistsFrom(listOf("X", "Y"), listOf("X & Y"), ""))
        assertEquals(listOf("Band"), LocalMusic.artistsFrom(emptyList(), emptyList(), "Band"))
    }

    @Test
    fun `a wav file plays through the file source from start to end, and seeks`() {
        val rate = 44_100f
        val seconds = 2
        val pcm = ByteArray((rate * seconds).toInt() * 4)
        for (i in 0 until (rate * seconds).toInt()) {
            val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt()
            for (ch in 0..1) {
                pcm[i * 4 + ch * 2] = (v and 0xFF).toByte()
                pcm[i * 4 + ch * 2 + 1] = (v shr 8).toByte()
            }
        }
        val format = AudioFormat(rate, 16, 2, true, false)
        val file = Files.createTempFile("local", ".wav").toFile()
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(pcm), format, pcm.size / 4L), AudioFileFormat.Type.WAVE, file)

        FileSource(file).use { source ->
            assertEquals(44_100, source.sampleRate)
            assertEquals(2, source.channels)
            assertEquals(2_000L, source.durationMs)
            var total = 0
            while (true) total += (source.next() ?: break).size
            assertEquals(pcm.size, total)
            assertEquals(1_000L, source.seek(1_000))
            var after = 0
            while (true) after += (source.next() ?: break).size
            assertEquals(pcm.size / 2, after)
        }
        file.delete()
    }

    @Test
    fun `the compressed formats have decoders registered`() {
        // Java Sound finds decoders through service files; this is what the fat jar's merge keeps.
        val readers = java.util.ServiceLoader.load(javax.sound.sampled.spi.AudioFileReader::class.java).map { it::class.java.name }
        assertTrue("no MP3 reader: $readers", readers.any { "mpeg" in it.lowercase() || "mp3" in it.lowercase() })
        assertTrue("no FLAC reader: $readers", readers.any { "flac" in it.lowercase() })
        assertTrue("no Vorbis reader: $readers", readers.any { "vorbis" in it.lowercase() })
    }

    /** Read-only, with OT_LOCAL_FILE naming a real file: decodes a little of it and reads its tags. */
    @Test
    fun realFile() {
        val path = System.getenv("OT_LOCAL_FILE")
        assumeTrue(path != null)
        val file = File(path!!)
        val track = LocalMusic.readTrack(file)
        assertNotNull(track)
        (if (M4aReader.isM4a(file)) M4aReader.open(file) else FileSource(file, track!!.durationMs)).use { source ->
            var bytes = 0
            repeat(20) { bytes += source.next()?.size ?: 0 }
            println("PROBE ${file.extension}: ${source.sampleRate}Hz x${source.channels}, ${source.durationMs}ms, decoded $bytes bytes, artists=${track!!.song.artistList.size}")
            assertTrue(bytes > 0)
        }
    }
}
