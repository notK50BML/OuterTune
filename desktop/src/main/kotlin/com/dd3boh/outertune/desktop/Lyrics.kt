/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.dd3boh.lrclib.LrcLib
import com.zionhuang.kugou.KuGou
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One line of a song, and when it is sung.
 *
 * [timeMs] is null for lyrics that arrived without timings. Those still display; they just do not
 * follow along, which is the honest presentation of what is known rather than a guess at a timing.
 */
data class LyricLine(val timeMs: Long?, val text: String)

/**
 * A song's words.
 *
 * [synced] is the distinction the whole feature turns on: synced lyrics scroll and highlight, plain
 * ones are a block of text. Conflating them would mean either a static view for lyrics that could
 * have followed the music, or a highlight jumping arbitrarily through lyrics that have no timings.
 */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Parses LRC, the format every lyrics provider hands back.
 *
 * The format is a metadata header of `[tag:value]` lines followed by `[mm:ss.xx]text` lines. Three
 * details make a naive parser wrong on real files, and each is handled below:
 *
 *  - **A line can carry several timestamps.** `[00:12.00][01:04.00]Chorus line` means the same words
 *    are sung twice. Taking only the first would silently drop the repeat.
 *  - **Enhanced LRC puts word timings inline**, as `<00:12.34>`. They are stripped rather than
 *    parsed - karaoke-style per-word highlighting is a separate feature, and leaving the markers in
 *    would show them as text.
 *  - **The `[offset:]` tag shifts everything.** Files that were timed against a different master
 *    carry it, and ignoring it puts the whole song consistently early or late.
 */
object LrcParser {

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val METADATA = Regex("""^\[([a-zA-Z_]+):(.*)]$""")
    private val WORD_TIMING = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String, source: String): Lyrics {
        if (raw.isBlank()) return Lyrics(emptyList(), synced = false, source = source)

        var offsetMs = 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            METADATA.matchEntire(line)?.let { meta ->
                if (meta.groupValues[1].lowercase() == "offset") {
                    // Positive offset means the lyrics run early and should be pushed later, which
                    // is the opposite sign to what the tag literally reads as.
                    offsetMs = -(meta.groupValues[2].trim().toLongOrNull() ?: 0L)
                }
                return@forEach
            }

            val stamps = TIMESTAMP.findAll(line).toList()
            if (stamps.isEmpty()) {
                plain += line
                return@forEach
            }

            val text = WORD_TIMING.replace(line.substring(stamps.last().range.last + 1), "").trim()
            stamps.forEach { stamp ->
                val minutes = stamp.groupValues[1].toLong()
                val seconds = stamp.groupValues[2].toLong()
                val fraction = stamp.groupValues[3]
                // Two digits is hundredths, three is milliseconds. Treating "50" as 50ms rather than
                // 500ms would put every line nearly half a second early.
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                timed += LyricLine((minutes * 60 + seconds) * 1000 + millis + offsetMs, text)
            }
        }

        if (timed.isNotEmpty()) {
            // Sorted because multi-timestamp lines are written where they first occur, so a repeated
            // chorus arrives out of order.
            return Lyrics(timed.sortedBy { it.timeMs }, synced = true, source = source)
        }
        return Lyrics(plain.map { LyricLine(null, it) }, synced = false, source = source)
    }

    /**
     * Which line is being sung at [positionMs], or -1 before the first one.
     *
     * Binary search rather than a scan. It runs on every frame while lyrics are open, and a song
     * with a few hundred lines scanned sixty times a second is work done for no reason.
     */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            val at = lines[mid].timeMs ?: return -1
            if (at <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

/**
 * Finds a song's lyrics.
 *
 * Two providers, tried in order. LrcLib first: it is the one built for this, it returns LRC
 * directly, and its matching is on title, artist and duration rather than on a search. KuGou is the
 * fallback because its catalogue covers a lot that LrcLib does not, particularly non-English
 * releases - it is a different corpus rather than a second opinion on the same one.
 *
 * Both are `kotlin("jvm")` modules already in this repository, built with ktor, so the desktop
 * depends on them directly. Nothing here is a port.
 *
 * Results are cached in the library database, keyed by song. A song is played more than once and the
 * lyrics do not change; re-fetching them on every play would be two network round trips for a result
 * already known, and would leave the pane blank for a second each time.
 */
class LyricsRepository(private val library: LibraryStore) {

    suspend fun lyricsFor(
        songId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Lyrics? = withContext(Dispatchers.IO) {
        library.cachedLyrics(songId)?.let { cached ->
            return@withContext LrcParser.parse(cached.text, cached.source)
        }

        val durationSeconds = (durationMs / 1000).toInt()

        LrcLib.getLyrics(title, artist, durationSeconds).getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { text ->
                library.cacheLyrics(songId, text, LRCLIB)
                return@withContext LrcParser.parse(text, LRCLIB)
            }

        KuGou.getLyrics(title, artist, durationSeconds).getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { text ->
                library.cacheLyrics(songId, text, KUGOU)
                return@withContext LrcParser.parse(text, KUGOU)
            }

        // Recorded as a miss so the next play does not repeat both lookups. An empty string is the
        // marker; it parses to an empty Lyrics, which the pane shows as "no lyrics found".
        library.cacheLyrics(songId, "", NONE)
        null
    }

    /** Forgets what is stored for a song, so the next request goes back to the providers. */
    fun forget(songId: String) = library.clearLyrics(songId)

    companion object {
        const val LRCLIB = "LRCLIB"
        const val KUGOU = "KuGou"
        const val NONE = "none"
    }
}
