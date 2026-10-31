/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.dd3boh.betterlyrics.QrcConverter
import com.dd3boh.betterlyrics.SingerNames
import com.zionhuang.innertube.utils.cleanLyrics
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A duet's lyrics coloured by singer, in the full player's lyrics and the mini player's, from each
 * kind of source that can say who sings what - `OT_SNAPSHOTS=<folder>` only.
 *
 * Every case goes the way real lyrics do: the provider's text through [LrcParser.parseAny], then the
 * same steps Main takes on the way to the screen ([withSingerNames], then [withEstimatedWords] when
 * that is on). The current line is caught partway through being sung.
 */
class SingerColoursPreview {

    /** Made-up words; who sings each line, and when. */
    private class Row(val singer: String?, val voice: String, val start: Double, val text: String)

    private val rows = listOf(
        Row("Mira", "v1", 10.0, "Streetlights hum a quiet tune"),
        Row(null, "v1", 13.5, "Underneath a paper moon"),
        Row("Jonah", "v2", 17.0, "Every window holds a light"),
        Row(null, "v2", 20.5, "Carry me into the night"),
        Row("Both", "v1000", 24.0, "Hold on, the morning's near"),
        Row("Mira", "v1", 27.5, "Paper moon, paper moon"),
    )

    /** Partway through "Every window holds a light", Jonah's first line. */
    private val positionMs = 18_400L

    private val wordSeconds = 0.45

    private fun secs(s: Double) = "%.3f".format(java.util.Locale.ROOT, s)

    /** Apple's way: who sings is in `ttm:agent`, and the head names them. With or without word spans. */
    private fun appleTtml(words: Boolean): String {
        val body = rows.joinToString("\n") { row ->
            val parts = row.text.split(' ')
            val end = row.start + parts.size * wordSeconds
            val content = if (!words) row.text else parts.mapIndexed { i, w ->
                val s = row.start + i * wordSeconds
                """<span begin="${secs(s)}" end="${secs(s + wordSeconds)}">$w</span>"""
            }.joinToString(" ")
            """<p begin="${secs(row.start)}" end="${secs(end)}" ttm:agent="${row.voice}">$content</p>"""
        }
        return """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="en">
            <head><metadata>
              <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Mira</ttm:name></ttm:agent>
              <ttm:agent type="person" xml:id="v2"><ttm:name type="full">Jonah</ttm:name></ttm:agent>
              <ttm:agent type="group" xml:id="v1000"><ttm:name type="full">Mira &amp; Jonah</ttm:name></ttm:agent>
            </metadata></head>
            <body><div>
            $body
            </div></body></tt>
        """.trimIndent()
    }

    /** A line as QQ writes it - the singer's name, when it changes, in front of the words. */
    private fun named(row: Row) = if (row.singer != null) "${row.singer}: ${row.text}" else row.text

    /** QQ Music's word-timed QRC, with the names in the text - as its lyric_download sends it, decrypted. */
    private fun qqQrc(): String {
        val content = rows.joinToString("\n") { row ->
            val parts = named(row).split(' ')
            val start = (row.start * 1000).toLong()
            val each = (wordSeconds * 1000).toLong()
            val words = parts.mapIndexed { i, w ->
                val s = start + i * each
                // Spaces come as words of their own, as QQ writes them.
                "$w($s,$each)" + if (i < parts.lastIndex) " (${s + each},0)" else ""
            }.joinToString("")
            "[$start,${parts.size * each}]$words"
        }
        return """<?xml version="1.0" encoding="utf-8"?><QrcInfos><LyricInfo LyricCount="1"><Lyric_1 LyricType="1" LyricContent="$content&#10;"/></LyricInfo></QrcInfos>"""
    }

    /** QQ's line-timed LRC - the same names, no word timings - or any LRC that labels its singers. */
    private fun qqLrc(): String = rows.joinToString("\n") { row ->
        val ms = (row.start * 1000).toLong()
        "[%02d:%02d.%02d]%s".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 10, named(row))
    }

    /** The provider's text as the desktop draws it: parsed, names applied, words estimated if asked. */
    private fun shown(raw: String, estimate: Boolean): Lyrics {
        val parsed = LrcParser.parseAny(if (com.dd3boh.betterlyrics.TTMLParser.looksLikeTtml(raw)) raw else cleanLyrics(raw), "preview")
        val named = parsed.withSingerNames(SingerNames.COLOUR)
        return if (estimate) named.withEstimatedWords() else named
    }

    private class Case(val label: String, val lyrics: Lyrics, val highlight: LyricsHighlight = LyricsHighlight.Fade)

    @Test
    fun render() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        val qqTtml = QrcConverter.toTtml(qqQrc(), "Paper Moon")!!
        val first = listOf(
            Case("(a) Apple TTML, agents named in the head - line-timed", shown(appleTtml(words = false), estimate = false)),
            Case("(c1) Apple TTML with word spans - fade", shown(appleTtml(words = true), estimate = false)),
            Case("(c1) Apple TTML with word spans - sweep", shown(appleTtml(words = true), estimate = false), LyricsHighlight.Sweep),
            Case("(c2) Apple TTML line-timed, estimated words - sweep", shown(appleTtml(words = false), estimate = true), LyricsHighlight.Sweep),
        )
        val second = listOf(
            Case("(b) QQ QRC, \"Name: \" in the text, word-timed - fade", shown(qqTtml, estimate = false)),
            Case("(b) QQ QRC, \"Name: \" in the text, word-timed - sweep", shown(qqTtml, estimate = false), LyricsHighlight.Sweep),
            Case("(b) QQ / KuGou LRC, \"Name: \" in the text - line-timed", shown(qqLrc(), estimate = false)),
            Case("(c2) QQ / KuGou LRC, \"Name: \", estimated words - sweep", shown(qqLrc(), estimate = true), LyricsHighlight.Sweep),
        )
        capture(out!!, "singer_colours_ttml", first)
        capture(out, "singer_colours_named", second)
    }

    private fun capture(out: File, name: String, cases: List<Case>) {
        out.mkdirs()
        val scene = ImageComposeScene(1700, 800, Density(1.25f), Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        cases.forEach { case -> Panel(case) }
                    }
                }
            }
        }
        try {
            var t = 0L
            repeat(40) {
                scene.render(t)
                t += 50_000_000L
                Thread.sleep(10)
            }
            File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun Panel(case: Case) {
        Column(modifier = Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(case.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "voices: " + case.lyrics.lines.joinToString(" ") { it.voice?.name?.take(1) ?: "-" } +
                    "   key: " + case.lyrics.singers.joinToString { "${it.name}=${it.voice}" }.ifEmpty { "none" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.width(320.dp).height(440.dp).background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))) {
                LyricsPane(
                    lyrics = case.lyrics,
                    loading = false,
                    positionMs = { positionMs },
                    onSeek = {},
                    onColour = MaterialTheme.colorScheme.onSurface,
                    highlight = case.highlight,
                )
            }
            Text("Mini player", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.width(320.dp).height(64.dp).background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))) {
                MiniLyrics(case.lyrics, { positionMs }, Modifier.fillMaxSize())
            }
        }
    }
}
