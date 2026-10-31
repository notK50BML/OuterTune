/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.betterlyrics.QrcConverter
import com.dd3boh.betterlyrics.Singer
import com.dd3boh.betterlyrics.SingerNames
import com.dd3boh.betterlyrics.TTMLParser
import com.dd3boh.betterlyrics.Voice
import com.dd3boh.outertune.ui.utils.withEstimatedWordSync
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Lyrics > Singer names in lyrics on the phone, through the same parse the lyrics take on
 * their way to the screen: who sings each line survives it, whatever the lyrics came as, and so do
 * the colours once word timings - real or estimated - are added for the sweep.
 *
 * Apple's own TTML goes through the phone's strict TTML reader, which needs Android's XML parser and
 * so cannot run here; everything else the phone reads as LRC, and that is what these go through.
 */
class SingerNamesTest {

    private fun lrc(text: String) = parseLrc(text, trimEnabled = false, multiLineEnabled = true) as SemanticLyrics.SyncedLyrics

    private fun SemanticLyrics?.synced() = this as SemanticLyrics.SyncedLyrics

    private val SemanticLyrics.SyncedLyrics.voices get() = text.map { it.speaker.voice }

    /** A duet as KuGou, LRCLIB or QQ's line-timed lyrics write it: names in the text, no marks. */
    private val namedLrc = """
        [00:01.00]Mira: Streetlights hum
        [00:02.00]Paper moon
        [00:03.00]Jonah: Every window
        [00:04.00]Both: Hold on
        [00:05.00]Mira: Paper moon again
    """.trimIndent()

    @Test
    fun `LRC that names its singers in the text is coloured by them`() {
        val shown = lrc(namedLrc).withSingerNames(SingerNames.COLOUR).synced()
        assertEquals(listOf("Streetlights hum", "Paper moon", "Every window", "Hold on", "Paper moon again"), shown.text.map { it.text })
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.GROUP, Voice.ONE), shown.voices)
        assertTrue(shown.text.isMultiVoice())
        // The key: from the names, since LRC has no head to keep them in.
        assertEquals(
            listOf(Singer(Voice.ONE, "Mira"), Singer(Voice.TWO, "Jonah"), Singer(Voice.GROUP, "Both")),
            lrc(namedLrc).singersFromNames(),
        )
    }

    @Test
    fun `the colours survive estimated word timings`() {
        val shown = lrc(namedLrc).withSingerNames(SingerNames.COLOUR).synced().withEstimatedWordSync()
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.GROUP, Voice.ONE), shown.voices)
        assertTrue(shown.text.all { !it.words.isNullOrEmpty() })
    }

    @Test
    fun `hide and show as text leave LRC uncoloured`() {
        assertTrue(lrc(namedLrc).withSingerNames(SingerNames.HIDE).synced().voices.all { it == null })
        val asText = lrc(namedLrc).withSingerNames(SingerNames.TEXT).synced()
        assertEquals("Mira: Streetlights hum", asText.text[0].text)
        assertTrue(asText.voices.all { it == null })
    }

    @Test
    fun `the parsed lyrics are not changed underneath`() {
        val parsed = lrc(namedLrc)
        parsed.withSingerNames(SingerNames.COLOUR)
        assertTrue(parsed.voices.all { it == null })
        assertEquals("Mira: Streetlights hum", parsed.text[0].text)
    }

    @Test
    fun `QQ's word-timed lyrics keep their singers through the TTML-to-LRC fallback and the sweep's words`() {
        // QQ's QRC, as :qqmusic gets it and QrcConverter turns it into TTML; the phone's strict
        // reader turns down TTML with no head, so it reaches the screen through TTMLParser.toLrc.
        val qrc = """<QrcInfos><Lyric_1 LyricContent="[1000,1000]Mira:(1000,300) (1300,0)Night(1300,400) (1700,0)and(1700,300)
[2000,1000]Paper(2000,500) (2500,0)moon(2500,500)
[3000,1000]Jonah:(3000,300) (3300,0)Every(3300,400) (3700,0)day(3700,300)
[4000,1000]Mira:(4000,300) (4300,0)Hold(4300,400) (4700,0)on(4700,300)
"/></QrcInfos>"""
        val ttml = QrcConverter.toTtml(qrc)!!
        assertEquals(listOf("Mira", "Jonah"), TTMLParser.singers(ttml).map { it.name })
        val shown = lrc(TTMLParser.ttmlToLrc(ttml)!!).withSingerNames(SingerNames.COLOUR).synced()
        assertEquals(listOf("Night and", "Paper moon", "Every day", "Hold on"), shown.text.map { it.text })
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.ONE), shown.voices)
        // The word timings index into the text with the name taken off, so the sweep lights words.
        val first = shown.text[0]
        assertEquals(listOf("Night", "and"), first.words!!.map { first.text.substring(it.charRange.first, it.charRange.last + 1) })
        assertEquals(listOf(Voice.ONE, Voice.ONE, Voice.TWO, Voice.ONE), shown.withEstimatedWordSync().voices)
    }

    @Test
    fun `marked voices are kept as they are`() {
        val marked = lrc("[00:01.00]v1:Mira: one\n[00:02.00]v2:Jonah: two\n[00:03.00]v1:Mira: three")
        val shown = marked.withSingerNames(SingerNames.COLOUR).synced()
        assertEquals(listOf(Voice.ONE, Voice.TWO, Voice.ONE), shown.voices)
        assertEquals(listOf("one", "two", "three"), shown.text.map { it.text })
    }
}
