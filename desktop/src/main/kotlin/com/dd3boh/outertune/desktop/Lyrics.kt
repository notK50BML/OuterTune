/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.dd3boh.betterlyrics.BetterLyrics
import com.dd3boh.betterlyrics.TTMLParser
import com.dd3boh.lrclib.LrcLib
import com.zionhuang.kugou.KuGou
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One word, and the window it is sung across.
 *
 * [endMs] is a real end rather than the next word's start. A sung word has a length - it is held,
 * or clipped, or followed by a rest - and a highlight that runs from each word straight into the
 * next one drifts ahead of the voice through anything slow.
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val trailingSpace: Boolean = true,
)

/**
 * One line of a song, and when it is sung.
 *
 * [timeMs] is null for lyrics that arrived without timings. Those still display; they just do not
 * follow along, which is the honest presentation of what is known rather than a guess at a timing.
 *
 * [words] is populated only where the source actually carried per-word timings - TTML from
 * BetterLyrics, or enhanced LRC. Empty means the line is timed as a whole, which is not the same as
 * a line of one word and must not be rendered as though the first word lasts the whole line.
 */
data class LyricLine(
    val timeMs: Long?,
    val text: String,
    val words: List<LyricWord> = emptyList(),
) {}

/**
 * A song's words.
 *
 * [synced] is the distinction the whole feature turns on: synced lyrics scroll and highlight, plain
 * ones are a block of text. Conflating them would mean either a static view for lyrics that could
 * have followed the music, or a highlight jumping arbitrarily through lyrics that have no timings.
 */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Whether any line carries word timings.
     *
     * Checked rather than assumed from the source: a TTML file can be line-timed only, and enhanced
     * LRC is enhanced per line rather than per file, so "came from BetterLyrics" is not the same
     * claim as "has words".
     */
    val wordSynced: Boolean get() = synced && lines.any { it.words.isNotEmpty() }

    /**
     * The same lyrics, every line moved by [ms].
     *
     * Applied when the lyrics are handed to the player rather than when they are cached, so that
     * changing the offset costs nothing and the cached copy stays as the provider sent it. Plain
     * lyrics are returned untouched - there is nothing to shift.
     */
    fun shiftedBy(ms: Long): Lyrics {
        if (ms == 0L || !synced) return this
        return copy(
            lines = lines.map { line ->
                line.copy(
                    timeMs = line.timeMs?.plus(ms),
                    // The words move with their line. Shifting the line and leaving the words would
                    // put the highlight and the line it is highlighting in different places.
                    words = line.words.map { it.copy(startMs = it.startMs + ms, endMs = it.endMs + ms) },
                )
            }
        )
    }
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

            val body = line.substring(stamps.last().range.last + 1)
            val text = WORD_TIMING.replace(body, "").trim()
            // Enhanced LRC marks each word with its own time. Read rather than discarded, so a
            // provider that happens to send enhanced lines gets word-by-word highlighting for free -
            // the format is far more common than the number of players that use it.
            val words = parseWordTimings(body)
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
                val at = (minutes * 60 + seconds) * 1000 + millis + offsetMs
                // A repeated line's word times belong to its first occurrence, so they are moved to
                // wherever this copy sits. Left alone, every repeat would highlight against the
                // times of the first one and light up all at once.
                val shift = at - ((words.firstOrNull()?.startMs ?: at) + offsetMs)
                timed += LyricLine(
                    timeMs = at,
                    text = text,
                    words = words.map {
                        it.copy(
                            startMs = it.startMs + offsetMs + shift,
                            endMs = it.endMs + offsetMs + shift,
                        )
                    },
                )
            }
        }

        if (timed.isNotEmpty()) {
            // Sorted because multi-timestamp lines are written where they first occur, so a repeated
            // chorus arrives out of order.
            val sorted = timed.sortedBy { it.timeMs }
            return Lyrics(closeFinalWords(sorted), synced = true, source = source)
        }
        return Lyrics(plain.map { LyricLine(null, it) }, synced = false, source = source)
    }

    /**
     * The word timings inside one enhanced-LRC line body.
     *
     * Each word runs until the next mark, and a mark with no text after it is an end mark rather
     * than a word - which is how enhanced LRC closes a line, and how the converter in
     * `:betterlyrics` writes one. Dropping those outright would leave every line's last word with no
     * end but the following line's start, throwing away a time the file actually stated.
     *
     * A last word still left open is closed later against the next line - see [closeFinalWords].
     */
    private fun parseWordTimings(body: String): List<LyricWord> {
        val marks = WORD_TIMING.findAll(body).toList()
        if (marks.isEmpty()) return emptyList()

        // Every mark with the text that follows it, empty text included.
        val stamped = marks.mapIndexedNotNull { index, mark ->
            val startMs = timestampToMs(mark.value.trim('<', '>')) ?: return@mapIndexedNotNull null
            val from = mark.range.last + 1
            val to = marks.getOrNull(index + 1)?.range?.first ?: body.length
            startMs to body.substring(from, to)
        }

        return stamped.mapIndexedNotNull { index, (startMs, raw) ->
            val word = raw.trim()
            if (word.isEmpty()) return@mapIndexedNotNull null
            LyricWord(
                text = word,
                startMs = startMs,
                // Provisional when nothing follows; closeFinalWords resolves it.
                endMs = stamped.getOrNull(index + 1)?.first ?: startMs,
                trailingSpace = raw.endsWith(" "),
            )
        }
    }

    /** `mm:ss.xx` to milliseconds, sharing the fraction rules the line timestamps use. */
    private fun timestampToMs(value: String): Long? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val rest = parts[1].split('.', ':')
        val seconds = rest[0].toLongOrNull() ?: return null
        val fraction = rest.getOrNull(1).orEmpty()
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: 0L) * 100
            2 -> (fraction.toLongOrNull() ?: 0L) * 10
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return (minutes * 60 + seconds) * 1000 + millis
    }

    /**
     * Gives each line's last word an end time.
     *
     * It runs until the next line begins, capped at a couple of seconds. Without the cap, the final
     * word before an instrumental break would stay lit for the length of the break, which reads as
     * the lyrics having frozen.
     */
    private fun closeFinalWords(lines: List<LyricLine>): List<LyricLine> =
        lines.mapIndexed { index, line ->
            val last = line.words.lastOrNull() ?: return@mapIndexed line
            if (last.endMs > last.startMs) return@mapIndexed line
            val nextLine = lines.getOrNull(index + 1)?.timeMs ?: (last.startMs + MAX_TRAILING_WORD_MS)
            val end = minOf(nextLine, last.startMs + MAX_TRAILING_WORD_MS)
            line.copy(words = line.words.dropLast(1) + last.copy(endMs = maxOf(end, last.startMs + 1)))
        }

    /** How long a line's final word may stay lit when nothing follows it soon. */
    private const val MAX_TRAILING_WORD_MS = 2_000L

    /**
     * TTML with syllable timing, as BetterLyrics returns it.
     *
     * The parsing is `:betterlyrics`'s own - a real XML parse rather than a regex, which TTML needs:
     * words are nested spans, timings appear on several namespaces, and background vocals are spans
     * inside spans. This only converts its output into the shape the rest of the app uses.
     *
     * Background vocal lines are dropped for now. They are a second stream of words overlapping the
     * main one, and showing them as ordinary lines would interleave two voices into one column of
     * text that reads as neither.
     */
    fun parseTtml(ttml: String, source: String): Lyrics {
        val parsed = runCatching { TTMLParser.parseTTML(ttml) }.getOrNull().orEmpty()
        val lines = parsed
            .filterNot { it.isBackground }
            .map { line ->
                LyricLine(
                    timeMs = (line.startTime * 1000).toLong(),
                    text = line.text,
                    words = line.words.map { word ->
                        LyricWord(
                            text = word.text,
                            startMs = (word.startTime * 1000).toLong(),
                            endMs = (word.endTime * 1000).toLong(),
                            trailingSpace = word.hasTrailingSpace,
                        )
                    },
                )
            }
            .filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return Lyrics(emptyList(), synced = false, source = source)
        return Lyrics(lines.sortedBy { it.timeMs }, synced = true, source = source)
    }

    /**
     * Parses whatever a provider sent, whichever format it is in.
     *
     * Sniffed rather than taken from the source name, because the cache stores raw text and a stored
     * source string is a claim about where it came from rather than about what it is.
     */
    fun parseAny(raw: String, source: String): Lyrics =
        if (TTMLParser.looksLikeTtml(raw)) parseTtml(raw, source) else parse(raw, source)

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

    /**
     * Whether to ask BetterLyrics at all.
     *
     * A setting because it is the one provider that regularly refuses: its API answers 401 for songs
     * it has not already cached, so on an unlucky run it costs a request per song and returns
     * nothing. Worth having first when it works, and worth being able to switch off when it does
     * not.
     */
    var useWordByWord: Boolean = true

    suspend fun lyricsFor(
        songId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Lyrics? = withContext(Dispatchers.IO) {
        library.cachedLyrics(songId)?.let { cached ->
            return@withContext LrcParser.parseAny(cached.text, cached.source)
        }

        val durationSeconds = (durationMs / 1000).toInt()

        // BetterLyrics first, because word timings are strictly more than line timings - a
        // word-timed file can always be shown as lines, and the reverse is not true. It is also the
        // most likely to come back empty, which is why the other two follow rather than being
        // skipped once it is asked.
        if (useWordByWord) {
            BetterLyrics.getLyrics(title, artist, durationSeconds).getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { ttml ->
                    val parsed = LrcParser.parseTtml(ttml, BETTERLYRICS)
                    // Only accepted if it actually parsed. TTML that yields no lines is a failure
                    // dressed as a success, and caching it would poison the song until cleared.
                    if (!parsed.isEmpty) {
                        library.cacheLyrics(songId, ttml, BETTERLYRICS)
                        return@withContext parsed
                    }
                }
        }

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
        const val BETTERLYRICS = "BetterLyrics"
        const val LRCLIB = "LRCLIB"
        const val KUGOU = "KuGou"
        const val NONE = "none"
    }
}
