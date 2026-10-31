/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import com.dd3boh.betterlyrics.BetterLyrics
import com.dd3boh.betterlyrics.Singer
import com.dd3boh.betterlyrics.SingerNames
import com.dd3boh.betterlyrics.Singers
import com.dd3boh.betterlyrics.TTMLParser
import com.dd3boh.betterlyrics.Voice
import com.dd3boh.lrclib.LrcLib
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.utils.cleanCaptionLyrics
import com.zionhuang.innertube.utils.cleanLyrics
import com.zionhuang.kugou.KuGou
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * A second line at the same time - in an LRC file, a translation or romanisation. Drawn under
     * the line and lit with it; as two lines of their own the later one won every lookup, so the
     * original was never highlighted at all.
     *
     * Whatever else belongs under a line goes here too, one per line of this text: background
     * vocals, an untimed continuation in an LRC file, a translation from an Apple TTML head, and a
     * SubRip cue's second line - see LrcParser.
     */
    val translation: String? = null,
    /**
     * Who sings the line, for drawing it in that singer's colour: from a TTML document's agents, or
     * an LRC line's `v1:`/`v2:`/`v3:` mark. Null when nothing says - which is every line from most
     * providers - and cleared by [withSingerNames] unless the lines are to be coloured.
     */
    val voice: Voice? = null,
)

/**
 * A song's words.
 *
 * [synced] is the distinction the whole feature turns on: synced lyrics scroll and highlight, plain
 * ones are a block of text. Conflating them would mean either a static view for lyrics that could
 * have followed the music, or a highlight jumping arbitrarily through lyrics that have no timings.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
    /** The singers the lyrics name, for the legend over coloured lines - see [withSingerNames]. */
    val singers: List<Singer> = emptyList(),
) {
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

    private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d+))?]""")
    private val METADATA = Regex("""^\[([a-zA-Z_#]+):(.*)]$""")
    private val WORD_TIMING = Regex("""<\d{1,3}:\d{2}(?:[.:]\d+)?>""")

    /**
     * Who is singing, written straight after a line's timestamp: `v1:`, `v2:` and `v3:` from Apple's
     * duet extension, `F:`, `M:` and `D:` from Walaoke's.
     *
     * The phone's parser reads these as the singer and takes them out of the text, and so does this:
     * left in, every line of a duet began with "v1:". Apple's three become the line's [Voice] - `v3:`
     * is the group, as on the phone - so a duet can be drawn a colour per singer; Walaoke's gender
     * marks are only taken out, since the phone colours nothing by them either. Recognised in the
     * same place the phone recognises them and nowhere else: directly after the timestamp, with one
     * space before at most.
     */
    private val SINGER = Regex("""^ ?(v[123]|[FMD]):""")

    /**
     * Background vocals in Apple's LRC extension, `[bg: ...]`, on the line after the one they go
     * with. Checked before [METADATA], which would otherwise read it as a header tag and drop it.
     */
    private val BACKGROUND = Regex("""^\[bg:(.*)]$""")

    /** One timestamped line as written, before its copies are spread across the song. */
    private class Row(val lines: List<LyricLine>) {
        /** Untimed lines that follow it in the file, which belong to it - see [parse]. */
        val under = mutableListOf<String>()
    }

    /**
     * Parses LRC, with the same readings of the format's variants that the phone's parser makes.
     *
     * Beyond the three details in the class comment, each of these is something the phone already
     * did and this did not, so the same file read differently on the two:
     *
     *  - **Untimed lines after a timed one belong to it.** The phone's "multi-line" reading, on by
     *    default there: a second line of a lyric, or a translation written beneath without a time of
     *    its own. Shown under the line rather than thrown away.
     *  - **A timestamp after words starts a new line**, so `[00:01]one[00:02]two` is two lines rather
     *    than "two" sung twice.
     *  - **Word marks alone time a line.** `<00:11.22>hello <00:12.85>there` with no `[..]` in front
     *    is enhanced LRC all the same, and was shown with its marks as text.
     *  - **Singer marks and background vocals** - see [SINGER] and [BACKGROUND].
     *  - **A file whose every timestamp is zero is not synced.** It is plain lyrics something has
     *    stamped `[00:00.00]` onto, and read as synced it highlighted its last line throughout.
     *  - **An empty line at the same time as a real one is dropped** - see [mergeSameTime].
     *  - **An `[offset:]` cannot move a line before the start.**
     */
    fun parse(raw: String, source: String): Lyrics {
        if (raw.isBlank()) return Lyrics(emptyList(), synced = false, source = source)

        var offsetMs = 0L
        val rows = mutableListOf<Row>()
        val plain = mutableListOf<String>()
        // Every line's words in file order, for a file whose timestamps turn out to mean nothing.
        val asText = mutableListOf<String>()
        var anyRealTime = false

        raw.lineSequence().flatMap { splitAtInlineStamps(it.trim()) }.forEach { line ->
            if (line.isEmpty()) return@forEach

            BACKGROUND.matchEntire(line)?.let { bg ->
                val text = WORD_TIMING.replace(bg.groupValues[1], "").trim().unwrapParentheses()
                if (text.isNotEmpty()) {
                    rows.lastOrNull()?.under?.add(text) ?: plain.add(text)
                    asText += text
                }
                return@forEach
            }

            METADATA.matchEntire(line)?.let { meta ->
                if (meta.groupValues[1].lowercase() == "offset") {
                    // Positive offset means the lyrics run early and should be pushed later, which
                    // is the opposite sign to what the tag literally reads as.
                    offsetMs = -(meta.groupValues[2].trim().toLongOrNull() ?: 0L)
                }
                return@forEach
            }

            // Every timestamp here leads the line - splitAtInlineStamps saw to that.
            val stamps = TIMESTAMP.findAll(line).toList()
            val afterStamps = if (stamps.isEmpty()) line else line.substring(stamps.last().range.last + 1)
            val firstWordMark = WORD_TIMING.find(afterStamps)
            if (stamps.isEmpty() && firstWordMark == null) {
                // Before any timed line this is a header or plain lyrics; after one, it is more of it.
                rows.lastOrNull()?.under?.add(line) ?: plain.add(line)
                asText += line
                return@forEach
            }

            val singer = SINGER.find(afterStamps)
            val voice = when (singer?.groupValues?.get(1)) {
                "v1" -> Voice.ONE
                "v2" -> Voice.TWO
                "v3" -> Voice.GROUP
                else -> null
            }
            val body = if (singer != null) afterStamps.substring(singer.range.last + 1) else afterStamps
            val text = WORD_TIMING.replace(body, "").trim()
            if (text.isNotEmpty()) asText += text
            // Enhanced LRC marks each word with its own time. Read rather than discarded, so a
            // provider that happens to send enhanced lines gets word-by-word highlighting for free -
            // the format is far more common than the number of players that use it.
            fun stampMs(stamp: MatchResult): Long {
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
                return (minutes * 60 + seconds) * 1000 + millis
            }
            val times = if (stamps.isNotEmpty()) {
                stamps.map(::stampMs)
            } else {
                // No line timestamp, only word marks: the line starts with its first word.
                listOf(timestampToMs(firstWordMark!!.value.trim('<', '>')) ?: return@forEach)
            }
            val firstStampMs = times.first()
            val parsedWords = parseWordTimings(body)
            if (times.any { it > 0 } || parsedWords.any { it.startMs > 0 }) anyRealTime = true
            // Words before the first word mark - "[00:10]Hello <00:10.40>world" - were dropped, so
            // "Hello" vanished while the line was sung. They start with the line.
            val lead = WORD_TIMING.find(body)?.let { body.substring(0, it.range.first) }.orEmpty()
            val words = if (parsedWords.isNotEmpty() && lead.isNotBlank()) {
                listOf(LyricWord(lead.trim(), firstStampMs, parsedWords.first().startMs, lead.endsWith(" "))) + parsedWords
            } else {
                parsedWords
            }
            rows += Row(
                times.map { stamp ->
                    // Never before the start: a large offset on an early line would otherwise give
                    // it a time no position reaches. The phone clamps the same way.
                    val at = (stamp + offsetMs).coerceAtLeast(0L)
                    // A repeated line's word times belong to its first occurrence, so they are moved
                    // to wherever this copy sits. Left alone, every repeat would highlight against
                    // the times of the first one and light up all at once. Measured from the first
                    // stamp, not the first word, which left the first copy's opening word pulled back
                    // to the line's own time.
                    val shift = at - firstStampMs
                    LyricLine(
                        timeMs = at,
                        text = text,
                        words = words.map {
                            it.copy(
                                startMs = (it.startMs + shift).coerceAtLeast(0L),
                                endMs = (it.endMs + shift).coerceAtLeast(0L),
                            )
                        },
                        voice = voice,
                    )
                }
            )
        }

        // Timestamps, but none of them past zero: plain lyrics wearing a timestamp, read as the
        // plain lyrics they are.
        if (rows.isNotEmpty() && !anyRealTime) {
            return Lyrics(asText.filter { it.isNotBlank() }.map { LyricLine(null, it) }, synced = false, source = source)
        }

        if (rows.isNotEmpty()) {
            val timed = rows.flatMap { row ->
                if (row.under.isEmpty()) row.lines
                else row.lines.map { it.copy(translation = row.under.joinToString("\n")) }
            }
            return Lyrics(closeFinalWords(mergeSameTime(timed)), synced = true, source = source)
        }
        return Lyrics(plain.map { LyricLine(null, it) }, synced = false, source = source)
    }

    /**
     * [timed] in time order, with the lines sharing a time folded into one.
     *
     * Sorted because multi-timestamp lines are written where they first occur, so a repeated chorus
     * arrives out of order. Lines sharing a time are one line and its translation: folded together,
     * the first keeping its words, the rest drawn beneath it.
     *
     * An empty line at a real line's time is an empty translation, and goes - kept, it was the line
     * found at that time, and the one with the words in it was never lit. Empty lines before the
     * first words go too, as on the phone: they hold nothing, and only put off the first highlight.
     */
    private fun mergeSameTime(timed: List<LyricLine>): List<LyricLine> {
        val merged = mutableListOf<LyricLine>()
        for (line in timed.sortedBy { it.timeMs }) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.timeMs == line.timeMs && previous.text.isNotBlank()) {
                val beneath = listOfNotNull(previous.translation, line.text.takeIf { it.isNotBlank() }, line.translation)
                merged[merged.lastIndex] = previous.copy(translation = beneath.joinToString("\n").ifEmpty { null })
            } else {
                merged += line
            }
        }
        return merged.dropWhile { it.text.isBlank() && it.translation == null }
    }

    /**
     * [line] cut wherever a timestamp follows words, so each piece has its timestamps at the front.
     *
     * `[00:01]one[00:02]two` is two lines, which is how the phone reads it. Treating every timestamp
     * on the line as the line's own put "two" at both times and lost "one". Words before the first
     * timestamp become a piece of their own, without a time.
     */
    private fun splitAtInlineStamps(line: String): List<String> {
        val stamps = TIMESTAMP.findAll(line).toList()
        if (stamps.isEmpty()) return listOf(line)
        val cuts = mutableListOf<Int>()
        var from = 0
        for (stamp in stamps) {
            if (line.substring(from, stamp.range.first).isNotBlank()) cuts += stamp.range.first
            from = stamp.range.last + 1
        }
        if (cuts.isEmpty()) return listOf(line)
        return (listOf(0) + cuts).zip(cuts + line.length) { a, b -> line.substring(a, b).trim() }
    }

    /**
     * Background vocals without the parentheses Apple writes them in. They are already set apart by
     * being drawn beneath the line, and the phone drops the brackets for the same reason.
     */
    private fun String.unwrapParentheses(): String =
        if (length >= 2 && startsWith('(') && endsWith(')')) substring(1, length - 1).trim() else this

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
     * Background vocals are drawn beneath the line they belong to, without Apple's parentheses, and
     * so are the translations an Apple document carries in its head. The phone shows both - its own
     * TTML reader makes each a smaller line of its own - and this dropped them. Beneath rather than
     * as lines of their own, still: a second stream of words overlapping the main one, interleaved
     * into one column, reads as neither voice.
     *
     * Each line keeps who sings it, and the lyrics the singers' names, for the colours and legend
     * of [withSingerNames]. They come from re-reading the stored TTML, so lyrics cached before any of
     * this existed are coloured too.
     */
    fun parseTtml(ttml: String, source: String): Lyrics {
        val document = runCatching { TTMLParser.parse(ttml) }.getOrNull()
        val parsed = document?.lines.orEmpty()
        val translations = TTMLParser.parseTranslations(ttml)
        val lines = parsed
            .filterNot { it.isBackground }
            .map { line ->
                val beneath = line.backgroundLines.map { it.text.trim().unwrapParentheses() }.filter { it.isNotEmpty() } +
                    line.key?.let { translations[it] }.orEmpty()
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
                    translation = beneath.joinToString("\n").ifEmpty { null },
                    voice = line.voice,
                )
            }
            .filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return Lyrics(emptyList(), synced = false, source = source)
        return Lyrics(lines.sortedBy { it.timeMs }, synced = true, source = source, singers = document?.singers.orEmpty())
    }

    private val SRT_TIMING = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->""")
    private val SRT_MARKUP = Regex("""<[^>]+>|\{\\[^}]*}""")

    /**
     * SubRip, which the phone reads beside a song as well as LRC and TTML: numbered cues, each a
     * `00:01:02,500 --> 00:01:04,000` line and its text.
     *
     * A cue's first line is the lyric and any further lines are drawn beneath it, the same as a
     * translation - which in a bilingual file is exactly what they are. Cues at the same moment fold
     * together the way LRC lines do. Styling tags are taken out; left in, they would show as text.
     */
    fun parseSrt(raw: String, source: String): Lyrics {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split(Regex("""\n\s*\n""")).mapNotNull { block ->
            val rows = block.trim().lines()
            val timingAt = rows.indexOfFirst { SRT_TIMING.containsMatchIn(it) }
            if (timingAt < 0) return@mapNotNull null
            val (h, m, sec, ms) = SRT_TIMING.find(rows[timingAt])!!.destructured
            val text = rows.drop(timingAt + 1).map { SRT_MARKUP.replace(it, "").trim() }.filter { it.isNotEmpty() }
            LyricLine(
                timeMs = ((h.toLong() * 60 + m.toLong()) * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').toLong(),
                text = text.firstOrNull().orEmpty(),
                translation = text.drop(1).joinToString("\n").ifEmpty { null },
            )
        }
        if (lines.isEmpty()) return Lyrics(emptyList(), synced = false, source = source)
        return Lyrics(mergeSameTime(lines), synced = true, source = source)
    }

    /**
     * Whether [raw] is SubRip: the phone's test, that it begins with the first cue's number. Any
     * stricter and a file saved with a byte-order mark would be missed, so that is allowed for.
     */
    fun looksLikeSrt(raw: String): Boolean {
        val text = raw.removePrefix("﻿")
        return (text.startsWith("1\n") || text.startsWith("1\r")) && SRT_TIMING.containsMatchIn(text.take(200))
    }

    /**
     * Parses whatever a provider sent, whichever format it is in.
     *
     * Sniffed rather than taken from the source name, because the cache stores raw text and a stored
     * source string is a claim about where it came from rather than about what it is.
     *
     * Everything but TTML is tidied first with the cleanup every lyrics source gets (`cleanLyrics`,
     * shared with the phone): caption speaker marks, `[Music]` and the like. The fetch path already
     * tidies what it stores, but a row cached before it did and a file beside a song never went
     * through it, and both are what is on screen - the phone cleans on the way to the screen for the
     * same reason. Idempotent, so text that is already clean comes out the same. Not TTML, which is
     * markup: the cleanup works on lines of text, and would eat a tag that starts a line with `>`.
     */
    fun parseAny(raw: String, source: String): Lyrics = when {
        TTMLParser.looksLikeTtml(raw) -> parseTtml(raw, source)
        looksLikeSrt(raw) -> parseSrt(cleanLyrics(raw), source)
        else -> parse(cleanLyrics(raw), source)
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
 * These lyrics as Settings > Lyrics > Singer names in lyrics asks for them to be shown.
 *
 * - [SingerNames.COLOUR]: each line keeps its [LyricLine.voice] so it can be drawn in its singer's
 *   colour, and [Lyrics.singers] stays for the legend - but only when more than one voice sings,
 *   since one colour for the whole song says nothing. Names written into the text go.
 * - [SingerNames.HIDE]: names written into the text go, and so do the voices: plain lyrics.
 * - [SingerNames.TEXT]: the text exactly as it came, "Name: " and all, with no colours.
 *
 * A name written into the text is a short leading name and its colon on lines of a song that is
 * labelled that way throughout - see [Singers.namePrefixes] for how cautious that is. A line that
 * was nothing but a name ("Latto:", heading her verse) goes with it.
 *
 * Applied on the way to the screen, like the timing offset, so the cache keeps what the provider
 * sent and changing the setting needs no fetch.
 */
fun Lyrics.withSingerNames(mode: SingerNames): Lyrics {
    if (mode == SingerNames.TEXT) {
        if (singers.isEmpty() && lines.none { it.voice != null }) return this
        return copy(lines = lines.map { it.copy(voice = null) }, singers = emptyList())
    }
    val coloured = mode == SingerNames.COLOUR && Singers.isMultiVoice(lines.map { it.voice })
    val names = Singers.namePrefixes(lines.map { it.text })
    val shown = lines.mapNotNull { line ->
        val cut = Singers.namePrefixLength(line.text, names)
        val stripped = if (cut == 0) line else line.copy(
            text = line.text.substring(cut),
            words = line.words.withoutLeading(line.text.substring(0, cut)),
        )
        if (cut > 0 && stripped.text.isBlank() && stripped.translation == null) return@mapNotNull null
        if (coloured) stripped else stripped.copy(voice = null)
    }
    return copy(lines = shown, singers = if (coloured) singers else emptyList())
}

/**
 * These words without the ones that spell [prefix] - a singer's name and colon taken off the front
 * of their line. Matched by the characters that are not spaces, since the words carry their spacing
 * apart from their text; a word only partly inside the prefix ("Latto:Uh" sung as one) keeps its
 * remainder.
 */
private fun List<LyricWord>.withoutLeading(prefix: String): List<LyricWord> {
    var left = prefix.count { !it.isWhitespace() }
    val out = mutableListOf<LyricWord>()
    for (word in this) {
        if (left <= 0) {
            out += word
            continue
        }
        val ink = word.text.count { !it.isWhitespace() }
        if (ink <= left) {
            left -= ink
            continue
        }
        var at = 0
        while (at < word.text.length && left > 0) {
            if (!word.text[at].isWhitespace()) left--
            at++
        }
        out += word.copy(text = word.text.substring(at).trimStart())
    }
    return out
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
 * depends on them directly. Nothing here is a port. BetterLyrics joined them since, and then YouTube
 * Music's own lyrics and the video's captions, both through `:innertube` as on the phone - see
 * [LyricsSource] for the whole list and its default order.
 *
 * Results are cached in the library database, keyed by song. A song is played more than once and the
 * lyrics do not change; re-fetching them on every play would be two network round trips for a result
 * already known, and would leave the pane blank for a second each time.
 */
class LyricsRepository(private val library: LibraryStore) {

    /**
     * The providers switched on - the phone's per-provider switches. One set rather than a flag per
     * provider, so a provider added later is wired by adding it to [LyricsSource] alone.
     */
    @Volatile
    var enabled: Set<LyricsSource> = LyricsSource.entries.toSet()

    suspend fun lyricsFor(
        songId: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Lyrics? = withContext(Dispatchers.IO) {
        // A local file's own lyrics win: an .lrc beside it, or lyrics in its tags. Without either it
        // is looked up online like any song - the providers match on title, artist and length.
        if (LocalMusic.isLocal(songId)) {
            LocalMusic.bundledLyrics(songId)?.let { return@withContext LrcParser.parseAny(it, "File") }
        }
        library.cachedLyrics(songId)?.let { cached ->
            // Removed by hand from the lyrics menu: none, and not looked for again until fetched
            // again by hand - unlike a miss, which is.
            if (cached.source == REMOVED) return@withContext null
            // A recorded miss is asked again after a week: providers add lyrics all the time, and a
            // miss used to be forever.
            // And once a session regardless: "no lyrics found" is the moment to look again, rather
            // than showing a week-old miss for a song whose lyrics have since been added.
            val staleMiss = cached.source == NONE &&
                (System.currentTimeMillis() - cached.fetchedAt > MISS_TTL_MS || missesRechecked.add(songId))
            if (!staleMiss) {
                val parsed = LrcParser.parseAny(cached.text, cached.source)
                // Stored lyrics nothing here can read are looked for again - once a session, as the
                // phone does, since a provider that keeps sending something unreadable would
                // otherwise be asked on every play. Until then, and if that finds nothing better,
                // what is stored is shown as it is.
                if (cached.source == NONE || !parsed.isEmpty || !unreadableRefetched.add(songId)) {
                    return@withContext parsed
                }
            }
        }
        // Whether any provider failed rather than answered "none" - see the end.
        var failed = false

        val durationSeconds = (durationMs / 1000).toInt()

        // In the order set in Settings > Lyrics > Lyrics providers - by default BetterLyrics first,
        // because word timings are strictly more than line timings, then LRCLIB, then KuGou, then
        // YouTube's two - and only the ones switched on.
        val enabled = askable(order, this@LyricsRepository.enabled, songId)
        // Which answer wins is the phone's rule in both modes: the best kind of timing, and the
        // order only between answers of the same kind - see LyricsRace. The modes differ only in
        // whether the providers are asked together or one after another.
        val race = LyricsRace(enabled)
        val startedAt = System.currentTimeMillis()
        if (inOrder) {
            // A budget per provider rather than one wall for the lot, as on the phone: a fixed wall
            // would quietly mean the last entries in the order were never asked at all.
            val deadline = startedAt + enabled.size * IN_ORDER_BUDGET_MS
            for (source in enabled) {
                val remaining = deadline - System.currentTimeMillis()
                // Out of time with providers still unasked is not their answer that there is nothing.
                if (remaining <= 0) {
                    failed = true
                    break
                }
                val result = fetchWithin(minOf(PROVIDER_TIMEOUT_MS, remaining), source, songId, title, artist, durationSeconds)
                if (result.isFailure) failed = true
                if (race.offer(source, result.getOrNull())) break
            }
        } else if (enabled.isNotEmpty()) {
            // Automatic: every provider at once, judged as the answers arrive, until nothing still
            // out could beat what is held or the whole lookup has had its time.
            val deadline = startedAt + RACE_TIMEOUT_MS
            val answers = kotlinx.coroutines.channels.Channel<Pair<LyricsSource, Result<Pair<String, Lyrics>?>>>(enabled.size)
            val jobs = enabled.map { source ->
                launch { answers.send(source to fetchWithin(PROVIDER_TIMEOUT_MS, source, songId, title, artist, durationSeconds)) }
            }
            try {
                for (asked in enabled.indices) {
                    val remaining = deadline - System.currentTimeMillis()
                    val answer = if (remaining > 0) withTimeoutOrNull(remaining) { answers.receive() } else null
                    // Some never answered in time - which is not an answer that there is nothing.
                    if (answer == null) {
                        failed = true
                        break
                    }
                    val (source, result) = answer
                    if (result.isFailure) failed = true
                    if (race.offer(source, result.getOrNull())) break
                }
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
        race.best?.let { found ->
            ensureActive()
            library.cacheLyrics(songId, found.text, found.source.id)
            return@withContext found.lyrics
        }

        // Recorded as a miss so the next play does not repeat the lookups - but only a real one.
        // The providers catch everything, cancellation included, so a song skipped mid-lookup or a
        // request that failed offline came back as "nothing" and was recorded as "this song has no
        // lyrics", for good. Now: not if this lookup was cancelled, and not if any provider failed
        // rather than answered. An empty string is the marker; the pane shows "no lyrics found".
        ensureActive()
        if (!failed) library.cacheLyrics(songId, "", NONE)
        null
    }

    /** The stored text for [songId] as it came - LRC or TTML - or null; for the lyrics editor. */
    fun stored(songId: String): String? =
        library.cachedLyrics(songId)?.takeIf { it.source != NONE && it.source != REMOVED }?.text

    /** Keeps [text] as [songId]'s lyrics - edited by hand, or picked from a search. */
    fun save(songId: String, text: String, source: String) = library.cacheLyrics(songId, text, source)

    /** No lyrics for [songId], kept that way until fetched again by hand ([forget]). */
    fun remove(songId: String) = library.cacheLyrics(songId, "", REMOVED)

    /**
     * Every candidate the providers have for [title] by [artist], each passed to [onFound] as it
     * arrives - LRCLIB's matches, then KuGou's, then BetterLyrics' one. Providers that are switched
     * off are asked anyway: this is a search the user asked for by hand.
     */
    suspend fun candidates(title: String, artist: String, durationMs: Long, onFound: (LyricsCandidate) -> Unit) =
        withContext(Dispatchers.IO) {
            val seconds = if (durationMs > 0) (durationMs / 1000).toInt() else -1
            runCatching { LrcLib.getAllLyrics(title, artist, seconds) { onFound(LyricsCandidate(LRCLIB, it)) } }
            ensureActive()
            runCatching { KuGou.getAllPossibleLyricsOptions(title, artist, seconds) { onFound(LyricsCandidate(KUGOU, it)) } }
            ensureActive()
            BetterLyrics.getLyrics(title, artist, seconds.coerceAtLeast(0)).getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { onFound(LyricsCandidate(BETTERLYRICS, it)) }
        }

    /** The order providers are asked in - Settings > Lyrics > Lyrics providers. */
    @Volatile
    var order: List<LyricsSource> = LyricsSource.DEFAULT

    /** One at a time in [order] when true; otherwise all at once - see LyricsFetchMode. */
    @Volatile
    var inOrder: Boolean = false

    /**
     * One provider's lyrics for a song, as the text to keep and the parsed lyrics - null when it has
     * none, a failure when it could not be asked. A file that parses to nothing is a failure too
     * (see [readable]): caching it would poison the song until cleared, and it is no evidence the
     * song has no lyrics either.
     */
    private suspend fun fetchFrom(
        source: LyricsSource,
        songId: String,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<Pair<String, Lyrics>?> =
        // A local file has no YouTube id to look up - "none", rather than a request that can only fail.
        if (source.byVideoId && LocalMusic.isLocal(songId)) Result.success(null) else when (source) {
            // TTML is not tidied: it is markup, and the cleanup works on lines of text.
            LyricsSource.BetterLyrics -> BetterLyrics.getLyrics(title, artist, durationSeconds).mapCatching { ttml ->
                ttml?.takeIf { it.isNotBlank() }?.let { readable(it, BETTERLYRICS) }
            }
            // Every other provider's text gets the cleanup every source gets on the phone, before it
            // is judged as well as before it is kept: a "lyric sheet" that is nothing but [Music]
            // over and over is not evidence of lyrics.
            LyricsSource.LrcLib -> LrcLib.getLyrics(title, artist, durationSeconds).mapCatching { text ->
                text?.takeIf { it.isNotBlank() }?.let { readable(cleanLyrics(it), LRCLIB) }
            }
            LyricsSource.KuGou -> KuGou.getLyrics(title, artist, durationSeconds).mapCatching { text ->
                text?.takeIf { it.isNotBlank() }?.let { readable(cleanLyrics(it), KUGOU) }
            }
            LyricsSource.YouTubeMusic -> youTubeLyrics(songId).mapCatching { text ->
                // Never timed: YouTube Music only carries the words, so this parses as plain.
                text?.takeIf { it.isNotBlank() }?.let { readable(cleanLyrics(it), YOUTUBE) }
            }
            LyricsSource.YouTubeSubtitle -> youTubeCaptions(songId).mapCatching { transcript ->
                transcript?.let { captionLyrics(it) }
            }
        }

    /**
     * [fetchFrom], given [timeoutMs] to answer. Running out is a failure rather than "none", so a
     * provider that was merely slow can never be the reason a song is remembered as having no lyrics.
     */
    private suspend fun fetchWithin(
        timeoutMs: Long,
        source: LyricsSource,
        songId: String,
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<Pair<String, Lyrics>?> =
        withTimeoutOrNull(timeoutMs) { fetchFrom(source, songId, title, artist, durationSeconds) }
            ?: Result.failure(java.util.concurrent.TimeoutException("${source.label} took longer than ${timeoutMs}ms"))

    /**
     * YouTube Music's own lyrics for [videoId], as the phone's YouTubeLyricsProvider gets them: the
     * watch page names a lyrics endpoint, and that endpoint carries the text. No endpoint is
     * YouTube's answer that the song has none.
     */
    private suspend fun youTubeLyrics(videoId: String): Result<String?> {
        val next = YouTube.next(WatchEndpoint(videoId = videoId)).getOrElse { return Result.failure(it) }
        val endpoint = next.lyricsEndpoint ?: return Result.success(null)
        return YouTube.lyrics(endpoint)
    }

    /**
     * The video's captions as LRC, as `transcript()` builds it. That signals a video with no caption
     * track through check(), so an IllegalStateException is "none" rather than a failure. Tidied by
     * [captionLyrics].
     */
    private suspend fun youTubeCaptions(videoId: String): Result<String?> =
        YouTube.transcript(videoId).fold(
            onSuccess = { Result.success(it) },
            onFailure = { if (it is IllegalStateException) Result.success(null) else Result.failure(it) },
        )

    /**
     * [songId]'s lyrics from [source] alone - the lyrics menu's "Get lyrics from", for when the
     * provider that answered first has the wrong version or worse timings. Kept as the song's
     * lyrics when found; null when that provider has none.
     */
    suspend fun lyricsFrom(source: LyricsSource, songId: String, title: String, artist: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            fetchFrom(source, songId, title, artist, (durationMs / 1000).toInt()).getOrNull()?.let { (text, parsed) ->
                library.cacheLyrics(songId, text, source.id)
                parsed
            }
        }

    /** Songs whose recorded miss has already been checked again this session - see [lyricsFor]. */
    private val missesRechecked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Songs whose unreadable stored lyrics have already been looked for again this session. */
    private val unreadableRefetched: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Forgets what is stored for a song, so the next request goes back to the providers. */
    fun forget(songId: String) = library.clearLyrics(songId)

    /**
     * [songId]'s own timing offset in milliseconds, 0 if never set - positive shows each line later.
     * Applied when the lyrics are handed to the player ([Lyrics.shiftedBy]), never to what is cached,
     * and kept through [forget]: fetching again is not a reason to lose an offset tuned by ear.
     */
    fun offset(songId: String): Int = library.lyricsOffset(songId)

    fun setOffset(songId: String, offsetMs: Int) = library.setLyricsOffset(songId, offsetMs)

    companion object {
        const val BETTERLYRICS = "BetterLyrics"
        const val LRCLIB = "LRCLIB"
        const val KUGOU = "KuGou"
        const val YOUTUBE = "YouTube Music"
        const val YOUTUBE_SUBTITLE = "YouTube Subtitle"
        const val NONE = "none"

        /** Lyrics removed by hand - see [remove]. */
        const val REMOVED = "removed"

        /** How long a recorded "no lyrics" stands before the providers are asked again. */
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** How long one provider has to answer - the phone's figure. */
        const val PROVIDER_TIMEOUT_MS = 8_000L

        /** The most a lookup asking every provider at once may take - the phone's figure too. */
        const val RACE_TIMEOUT_MS = 12_000L

        /**
         * Each provider's share of an in-order lookup: its own timeout and a little over, so the
         * whole is bounded without the last in the order ever being squeezed out.
         */
        const val IN_ORDER_BUDGET_MS = PROVIDER_TIMEOUT_MS + 1_000L

        /**
         * [text] parsed, as the text to keep and the lyrics; a failure when nothing in it can be
         * read. The phone counts such a result as neither lyrics nor an absence of them, so it is
         * neither shown nor remembered as a miss - and a failure is what does both here.
         */
        internal fun readable(text: String, source: String): Pair<String, Lyrics> {
            val parsed = LrcParser.parseAny(text, source)
            check(!parsed.isEmpty) { "$source sent lyrics nothing here can read" }
            return text to parsed
        }

        /**
         * A caption track, as `transcript()` returns it, made into lyrics the way the phone does:
         * its YouTubeSubtitleLyricsProvider re-breaks and tidies the track ([cleanCaptionLyrics]),
         * and then the general cleanup every source gets runs over it ([cleanLyrics]) before it is
         * judged and stored. The second changes nothing on text the first has already tidied, but
         * it is what the phone stores, so it is what is stored here.
         *
         * Null - "none" - for a track of nothing but annotations, which cleans away to no words at
         * all. Kept as lyrics it would be a page of empty lines and notes beating a real answer.
         */
        internal fun captionLyrics(transcript: String): Pair<String, Lyrics>? {
            if (transcript.isBlank()) return null
            val text = cleanLyrics(cleanCaptionLyrics(transcript))
            val parsed = LrcParser.parseAny(text, YOUTUBE_SUBTITLE)
            return (text to parsed).takeIf { parsed.lines.any { it.text.isNotBlank() && it.text != "♪" } }
        }

        /**
         * The providers to ask about [songId]: [order] kept, less the ones not [enabled], and less
         * the ones that look a song up by YouTube id when [songId] is a file on this computer.
         * Without the last, a local song with no lyrics would see those two fail every time, and a
         * failure keeps the miss from ever being remembered.
         */
        fun askable(order: List<LyricsSource>, enabled: Set<LyricsSource>, songId: String): List<LyricsSource> =
            order.filter { it in enabled && !(it.byVideoId && LocalMusic.isLocal(songId)) }
    }
}
