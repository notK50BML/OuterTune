/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.dd3boh.betterlyrics.Singer
import com.dd3boh.betterlyrics.SingerNames
import com.dd3boh.betterlyrics.Voice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings > Lyrics > Singer names in lyrics, on the lyrics the desktop draws: who sings each line
 * survives the parse, and each of the three choices shows it the way it says.
 */
class SingerNamesTest {

    /** QQ's way of writing a duet, through BetterLyrics: names in the text, word-timed. */
    private val namedInText = """
        <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
          <p begin="1.0" end="3.0"><span begin="1.0" end="1.4">Jung </span><span begin="1.4" end="1.8">Kook: </span><span begin="1.8" end="2.4">Night </span><span begin="2.4" end="3.0">and</span></p>
          <p begin="4.0" end="5.0">Seven days a week</p>
          <p begin="6.0" end="7.0">Latto: Uh, yeah</p>
          <p begin="8.0" end="9.0">Latto:</p>
          <p begin="10.0" end="11.0">Tell me</p>
        </div></body></tt>
    """.trimIndent()

    private val parsed = LrcParser.parseAny(namedInText, "BetterLyrics")

    @Test
    fun `colour code takes the names out and keeps each line's singer`() {
        val shown = parsed.withSingerNames(SingerNames.COLOUR)
        assertEquals(listOf("Night and", "Seven days a week", "Uh, yeah", "Tell me"), shown.lines.map { it.text })
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.TWO), shown.lines.map { it.voice })
        // The words go with the name, and the rest keep their timings.
        assertEquals(listOf("Night", "and"), shown.lines[0].words.map { it.text })
        assertEquals(1_800L, shown.lines[0].words[0].startMs)
        assertEquals(listOf(Singer(Voice.ONE, "Jung Kook"), Singer(Voice.TWO, "Latto")), shown.singers)
    }

    @Test
    fun `hide takes the names out and the colours with them`() {
        val shown = parsed.withSingerNames(SingerNames.HIDE)
        assertEquals(listOf("Night and", "Seven days a week", "Uh, yeah", "Tell me"), shown.lines.map { it.text })
        assertEquals(listOf<Voice?>(null, null, null, null), shown.lines.map { it.voice })
        assertEquals(emptyList<Singer>(), shown.singers)
    }

    @Test
    fun `show as text leaves the lines as they came`() {
        val shown = parsed.withSingerNames(SingerNames.TEXT)
        assertEquals("Jung Kook: Night and", shown.lines[0].text)
        assertEquals("Latto:", shown.lines[3].text)
        assertEquals(emptyList<Singer>(), shown.singers)
        assertEquals(true, shown.lines.all { it.voice == null })
    }

    @Test
    fun `LRC singer marks become voices, and one voice alone is not coloured`() {
        val duet = LrcParser.parse("[00:01.00]v1:Night\n[00:02.00]v2:Day\n[00:03.00]v3:Both", "LRCLIB")
        assertEquals(listOf("Night", "Day", "Both"), duet.lines.map { it.text })
        assertEquals(listOf(Voice.ONE, Voice.TWO, Voice.GROUP), duet.withSingerNames(SingerNames.COLOUR).lines.map { it.voice })

        val solo = LrcParser.parse("[00:01.00]v1:Night\n[00:02.00]v1:Day", "LRCLIB")
        assertEquals(listOf<Voice?>(null, null), solo.withSingerNames(SingerNames.COLOUR).lines.map { it.voice })
    }

    @Test
    fun `a colon in an ordinary lyric is left alone`() {
        val lyrics = LrcParser.parse("[00:01.00]Remember: you're mine\n[00:02.00]Remember: you're mine\n[00:03.00]Time: it flies", "LRCLIB")
        assertEquals(lyrics.lines.map { it.text }, lyrics.withSingerNames(SingerNames.HIDE).lines.map { it.text })
    }

    @Test
    fun `LRC that names its singers in the text is coloured by them, with a key`() {
        // KuGou, LRCLIB and QQ's line-timed lyrics write a duet this way and have no v1:/v2: marks.
        // The names used to be taken out and nothing put in their place: one colour, no key.
        val lrc = LrcParser.parse(
            """
                [00:01.00]Mira: Streetlights hum
                [00:02.00]Paper moon
                [00:03.00]Jonah: Every window
                [00:04.00]Both: Hold on
                [00:05.00]Mira: Paper moon again
            """.trimIndent(),
            "KuGou",
        )
        val shown = lrc.withSingerNames(SingerNames.COLOUR)
        assertEquals(listOf("Streetlights hum", "Paper moon", "Every window", "Hold on", "Paper moon again"), shown.lines.map { it.text })
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.GROUP, Voice.ONE), shown.lines.map { it.voice })
        assertEquals(listOf(Singer(Voice.ONE, "Mira"), Singer(Voice.TWO, "Jonah"), Singer(Voice.GROUP, "Both")), shown.singers)
        // And the colours survive the estimated word timings that make it sing word by word.
        val estimated = shown.withEstimatedWords()
        assertEquals(shown.lines.map { it.voice }, estimated.lines.map { it.voice })
        assertEquals(true, estimated.lines.all { it.words.isNotEmpty() })
        // Hide still means plain.
        assertEquals(true, lrc.withSingerNames(SingerNames.HIDE).lines.all { it.voice == null })
    }

    @Test
    fun `word-timed lyrics keep their voices through the sweep's words and the estimate`() {
        val shown = parsed.withSingerNames(SingerNames.COLOUR).withEstimatedWords()
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.TWO), shown.lines.map { it.voice })
        assertEquals(true, shown.lines.all { it.words.isNotEmpty() })
    }
}
