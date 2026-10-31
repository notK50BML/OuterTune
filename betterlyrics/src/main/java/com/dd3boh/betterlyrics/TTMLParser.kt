/*
 * TTML parsing for the BetterLyrics API.
 *
 * Ported from the Metrolist project (GPL-3.0), which in turn derives from OuterTune.
 * See git history for contributors.
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.betterlyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A lenient TTML reader for the Apple-Music-style documents the BetterLyrics API returns.
 *
 * OuterTune's primary lyric parser (`org.akanework.gramophone.logic.utils.parseTtml`) is a strict
 * TTML2 reader: it requires a `<head>` element straight after `<tt>`, and rejects the document
 * outright when the structure differs. Several BetterLyrics responses omit `<head>` or put timing
 * in the `ttp:` namespace, so that parser throws and the app shows "Unable to parse lyrics".
 *
 * This parser is deliberately forgiving — it walks the DOM looking for `<p>` elements wherever they
 * are, accepts unprefixed or `ttp:`-namespaced timing attributes, and falls back to the earliest
 * child `<span>` when a line carries no `begin` of its own. [toLrc] then emits standard LRC so the
 * rest of the app can render it with no special casing.
 *
 * Word-level timings are parsed both to reconstruct a line's text when it is split across syllable
 * spans and, in [toLrc]'s enhanced mode, to be written back out as Enhanced LRC — the widely
 * supported "A2 extension" that carries a `<mm:ss.xx>` sync point before each word:
 *
 * ```
 * [00:21.10]<00:21.10>Never <00:21.62>gonna <00:22.05>give <00:22.48>you <00:22.79>up<00:23.20>
 * ```
 *
 * A reader that only understands line-level LRC ignores the `<...>` marks and still sees an ordinary
 * line, so this degrades on its own; OuterTune's own parser reads them back into word timings and
 * renders the line word by word.
 */
object TTMLParser {

    /** TTML timing attributes may appear unprefixed or as `ttp:*` (parameter namespace). */
    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"
    private const val TTML_METADATA_NS = "http://www.w3.org/ns/ttml#metadata"

    /** Where `xml:id` lives - the id each `<ttm:agent>` is declared under. */
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"

    /** Apple numbers its group agents from 1000 (`v1000`), its singers from 1. */
    private val APPLE_GROUP_AGENT = Regex("""v\d{4,}""")

    /** Apple's internal namespace, which names each line (`itunes:key`) for its translations to point at. */
    private const val ITUNES_INTERNAL_NS = "http://music.apple.com/lyric-ttml-internal"

    /** Characters the LRC syntax gives meaning to, and so cannot appear inside an emitted word. */
    private const val LRC_RESERVED = "<>[]\n\r"

    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord> = emptyList(),
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList(),
        /** The line's `itunes:key`, which [parseTranslations] keys its translations by. */
        val key: String? = null,
        /**
         * Which singer's colour the line is drawn in, from [agent] - see [Singers.voices]. Background
         * lines have the voice of the line they belong to. Null when nothing says who sings it.
         */
        val voice: Voice? = null,
    )

    /**
     * A whole document: its [lines], and the [singers] it names - for the legend that says which
     * colour is whom. Empty when the document names nobody.
     */
    data class ParsedLyrics(
        val lines: List<ParsedLine>,
        val singers: List<Singer>,
    )

    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean = true,
    )

    private data class SpanInfo(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val hasTrailingSpace: Boolean,
    )

    /**
     * Cheap check for whether [text] is worth handing to [parseTTML]. Avoids spinning up a DOM
     * parser for LRC or plain text.
     */
    fun looksLikeTtml(text: String): Boolean {
        val head = text.take(2048)
        return head.contains("<tt") && head.contains("http://www.w3.org/ns/ttml")
    }

    fun parseTTML(ttml: String): List<ParsedLine> = parse(ttml).lines

    /** [ttml]'s singers alone, for the legend - see [ParsedLyrics.singers]. */
    fun singers(ttml: String): List<Singer> = parse(ttml).singers

    /**
     * [ttml]'s lines, each with the voice of whoever sings it, and the singers it names.
     *
     * Who sings what comes from the document's agents: `ttm:agent` on a line or its `<div>`, and the
     * `<ttm:agent>` declarations in the head that say whether each is a person or a group and may
     * name them. An agent a line points at but the head never declares still gets a voice, after the
     * declared ones - Apple's group agents are `v1000` and up, so those are taken as the group.
     *
     * A document with no agents at all may still name its singers in the text, "Latto: Uh, yeah" -
     * QQ Music's lyrics, through BetterLyrics' QQ relay, write duets that way. Those names become
     * the agents instead (see [Singers.agentsFromNames]). The text itself is left exactly as it is:
     * whether the names are shown is the display's choice, and is made there.
     */
    fun parse(ttml: String): ParsedLyrics {
        val lines = mutableListOf<ParsedLine>()
        var declared = emptyList<Singers.Agent>()
        try {
            val doc = parseDocument(ttml)
            val root = doc.documentElement ?: return ParsedLyrics(emptyList(), emptyList())
            declared = readAgents(root)

            // Apple exports can carry a global offset on head/metadata/audio@lyricOffset.
            val globalOffset = findChild(root, "head")
                ?.let { findChild(it, "metadata") }
                ?.let { findChild(it, "audio") }
                ?.getAttribute("lyricOffset")
                ?.toDoubleOrNull()
                ?: 0.0

            // Walk from <body> when present, otherwise from the root: some documents skip <body>.
            walk(findChild(root, "body") ?: root, lines, globalOffset, null)
        } catch (e: Exception) {
            return ParsedLyrics(emptyList(), emptyList())
        }
        return withVoices(lines, declared)
    }

    /** Every `<ttm:agent>` declared in the document, in order. */
    private fun readAgents(root: Element): List<Singers.Agent> {
        val out = mutableListOf<Singers.Agent>()
        val elements = root.getElementsByTagNameNS(TTML_METADATA_NS, "agent")
        for (i in 0 until elements.length) {
            val agent = elements.item(i) as? Element ?: continue
            val id = agent.getAttributeNS(XML_NS, "id").ifEmpty { agent.getAttribute("xml:id") }
                .ifEmpty { agent.getAttribute("id") }
            if (id.isEmpty()) continue
            val name = findChild(agent, "name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            out += Singers.Agent(id, agent.getAttribute("type").ifEmpty { "person" }, name)
        }
        return out
    }

    /** [lines] with their voices filled in from [declared] - see [parse]. */
    private fun withVoices(lines: List<ParsedLine>, declared: List<Singers.Agent>): ParsedLyrics {
        val agents: List<Singers.Agent>
        var tagged = lines
        val used = lines.mapNotNull { it.agent }.distinct()
        if (used.isEmpty()) {
            // Named only in the text, if at all.
            val (perLine, fromNames) = Singers.agentsFromNames(lines.map { it.text })
                ?: return ParsedLyrics(lines, emptyList())
            agents = fromNames
            tagged = lines.mapIndexed { i, line ->
                val id = perLine[i] ?: return@mapIndexed line
                line.copy(agent = id, backgroundLines = line.backgroundLines.map { it.copy(agent = id) })
            }
        } else {
            val known = declared.map { it.id }.toSet()
            agents = declared + used.filter { it !in known }.map { id ->
                Singers.Agent(id, if (APPLE_GROUP_AGENT.matches(id)) "group" else "person", null)
            }
        }
        val voices = Singers.voices(agents)
        fun ParsedLine.voiced(): ParsedLine = copy(
            voice = agent?.let { voices[it] },
            backgroundLines = backgroundLines.map { it.voiced() },
        )
        val singers = agents.mapNotNull { agent ->
            val voice = voices[agent.id] ?: return@mapNotNull null
            agent.name?.let { Singer(voice, it) }
        }
        return ParsedLyrics(tagged.map { it.voiced() }, singers)
    }

    /**
     * The translations an Apple document carries in its head, by the `itunes:key` of the line each
     * belongs to - `<iTunesMetadata><translations><translation><text for="L1">`.
     *
     * Read apart from [parseTTML] because they are not lines of their own: they say what an existing
     * line means, and belong under it. The phone's strict TTML reader shows them that way, so the
     * desktop, which reads TTML through this parser instead, needs them handed over separately.
     *
     * Only `type="subtitle"` translations, which is the one kind that strict reader accepts. Several
     * languages give several entries per line, in document order. Empty when there are none or the
     * document cannot be read - never an exception, since a missing translation is not a reason to
     * lose the lyrics.
     */
    fun parseTranslations(ttml: String): Map<String, List<String>> = try {
        val root = parseDocument(ttml).documentElement
        val out = LinkedHashMap<String, MutableList<String>>()
        if (root != null) {
            val translations = root.getElementsByTagNameNS("*", "translation")
            for (i in 0 until translations.length) {
                val translation = translations.item(i) as? Element ?: continue
                if (translation.getAttribute("type") != "subtitle") continue
                var child = translation.firstChild
                while (child != null) {
                    if (child is Element && localNameOf(child) == "text") {
                        val key = child.getAttribute("for")
                        val text = child.textContent?.trim().orEmpty()
                        if (key.isNotEmpty() && text.isNotEmpty()) out.getOrPut(key) { mutableListOf() } += text
                    }
                    child = child.nextSibling
                }
            }
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    private fun parseDocument(ttml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true

        // Harden against XXE. Android's parser does not support every feature below, and
        // setFeature throws for the ones it lacks, so each is attempted independently.
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        runCatching { factory.setXIncludeAware(false) }
        runCatching { factory.isExpandEntityReferences = false }

        return factory.newDocumentBuilder().parse(ttml.byteInputStream())
    }

    /**
     * Render [lines] as LRC: one `[mm:ss.cc]text` entry per line, in time order.
     *
     * With [enhanced] set, a line that carries word timings is written as Enhanced LRC instead —
     * the same text, with a `<mm:ss.cc>` sync point before every word and one more after the last
     * word to pin its end time. Lines without word timings are unaffected, so a mixed document
     * (real TTML routinely has both) comes out mixed and each line degrades on its own. With
     * [enhanced] unset the output is plain, line-level LRC.
     *
     * Background vocals become their own lines rather than being dropped, so a duet or an
     * ad-libbed line still shows up.
     *
     * Who sings each line is kept when more than one voice does, as the singer marks Apple's LRC
     * extension puts straight after the timestamp - `v1:`, `v2:`, and `v3:` for the group, which is
     * how the phone's LRC reader takes them. That is what lets a duet the phone can only read through
     * here still be coloured by singer. A document with one voice, or none, gets no marks, and comes
     * out exactly as it always did.
     */
    fun toLrc(lines: List<ParsedLine>, enhanced: Boolean = false): String {
        val marked = Singers.isMultiVoice(lines.flatMap { line -> listOf(line.voice) + line.backgroundLines.map { it.voice } })
        fun mark(line: ParsedLine) = if (!marked) "" else when (line.voice) {
            Voice.ONE -> "v1:"
            Voice.TWO -> "v2:"
            Voice.GROUP -> "v3:"
            null -> ""
        }
        val flat = mutableListOf<Pair<Double, String>>()
        for (line in lines) {
            if (line.text.isNotBlank()) flat += line.startTime to mark(line) + renderLine(line, enhanced)
            for (bg in line.backgroundLines) {
                if (bg.text.isNotBlank()) flat += bg.startTime to mark(bg) + renderLine(bg, enhanced)
            }
        }
        if (flat.isEmpty()) return ""

        // A stable sort keeps a background line directly after the line it belongs to when the
        // two share a timestamp.
        return flat.sortedBy { it.first }
            .joinToString("\n") { (time, text) -> formatLrcTime(time) + text }
    }

    /** Convenience: TTML straight to LRC, or null when nothing usable came out. */
    fun ttmlToLrc(ttml: String, enhanced: Boolean = true): String? =
        toLrc(parse(ttml).lines, enhanced).takeIf { it.isNotBlank() }

    /**
     * The body of one LRC entry — everything after the `[mm:ss.cc]`.
     *
     * Plain mode, and any line the enhanced encoding cannot safely represent, yields the trimmed
     * line text exactly as before. Otherwise the text is rebuilt from the words with a sync point
     * in front of each, which keeps the emitted text and the emitted timings consistent by
     * construction even for the aggregated background lines, whose [ParsedLine.text] and
     * [ParsedLine.words] are assembled separately.
     *
     * A line is left alone when:
     * - it has no word timings at all;
     * - a word contains `<`, `>`, `[` or `]`, which the LRC syntax would read back as a tag and so
     *   would corrupt the whole line rather than just losing its timings;
     * - the word timings run backwards, which would make the per-word ranges nonsense.
     */
    private fun renderLine(line: ParsedLine, enhanced: Boolean): String {
        val plain = line.text.trim()
        if (!enhanced) return plain

        val words = line.words
        if (words.isEmpty()) return plain
        if (words.any { word -> word.text.any { it in LRC_RESERVED } }) return plain
        for (i in 1 until words.size) {
            if (words[i].startTime < words[i - 1].startTime) return plain
        }

        return buildString {
            words.forEachIndexed { i, word ->
                append(formatLrcTime(word.startTime, '<', '>'))
                append(word.text)
                // Same spacing rule buildLineText uses, so the enhanced text reads identically to
                // the plain one. A trailing hyphen means the next word continues this one.
                if (word.hasTrailingSpace && !word.text.endsWith('-') && i < words.lastIndex) append(' ')
            }
            // One final sync point so the reader knows where the last word ends. Without it the
            // reader has to estimate that from a characters-per-second average. Skipped when it
            // would round to the last word's own start, which carries no information.
            val last = words.last()
            val start = formatLrcTime(last.startTime, '<', '>')
            val end = formatLrcTime(last.endTime, '<', '>')
            if (end != start && last.endTime > last.startTime) append(end)
        }
    }

    // region DOM walking

    private fun getAttr(el: Element, localName: String): String {
        val prefixed = el.getAttribute("ttm:$localName")
        if (prefixed.isNotEmpty()) return prefixed
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS(TTML_METADATA_NS, localName)
    }

    /** `itunes:key`, under whichever prefix the document bound Apple's internal namespace to. */
    private fun lineKey(p: Element): String? =
        p.getAttributeNS(ITUNES_INTERNAL_NS, "key").ifEmpty { p.getAttribute("itunes:key") }.ifEmpty { null }

    private fun timingAttr(el: Element, localName: String): String {
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS(TTML_PARAMETER_NS, localName)
    }

    private fun localNameOf(node: Node): String =
        node.localName ?: node.nodeName.substringAfterLast(':')

    private fun findChild(parent: Element, localName: String): Element? {
        var child = parent.firstChild
        while (child != null) {
            if (child is Element && localNameOf(child) == localName) return child
            child = child.nextSibling
        }
        return null
    }

    /** When `<p>` has no `begin`, use the earliest `begin` on a direct child `<span>`. */
    private fun findFirstSpanBegin(p: Element): String? {
        var child = p.firstChild
        var best: String? = null
        var bestSeconds = Double.POSITIVE_INFINITY
        while (child != null) {
            if (child is Element && localNameOf(child) == "span") {
                val begin = timingAttr(child, "begin")
                if (begin.isNotEmpty()) {
                    val seconds = parseTime(begin)
                    if (seconds < bestSeconds) {
                        bestSeconds = seconds
                        best = begin
                    }
                }
            }
            child = child.nextSibling
        }
        return best
    }

    private fun walk(element: Element, lines: MutableList<ParsedLine>, offset: Double, parentAgent: String?) {
        var currentAgent = parentAgent
        when (localNameOf(element)) {
            "div" -> getAttr(element, "agent").takeIf { it.isNotEmpty() }?.let { currentAgent = it }
            "p" -> {
                parseP(element, lines, offset, currentAgent)
                return // parseP consumes the children
            }
        }

        var child = element.firstChild
        while (child != null) {
            if (child is Element) walk(child, lines, offset, currentAgent)
            child = child.nextSibling
        }
    }

    private fun parseP(p: Element, lines: MutableList<ParsedLine>, offset: Double, divAgent: String?) {
        val begin = timingAttr(p, "begin").ifEmpty { findFirstSpanBegin(p) ?: return }
        val startTime = parseTime(begin) + offset

        val spanInfos = mutableListOf<SpanInfo>()
        val backgroundLines = mutableListOf<ParsedLine>()
        val agent = getAttr(p, "agent").ifEmpty { divAgent }
        val isPBackground = getAttr(p, "role") == "x-bg"

        var child = p.firstChild
        while (child != null) {
            if (child is Element && localNameOf(child) == "span") {
                when (getAttr(child, "role")) {
                    "x-bg" ->
                        if (isPBackground) {
                            parseWordSpan(child, offset, spanInfos)
                        } else {
                            parseBackgroundSpan(child, startTime, offset)?.let { backgroundLines += it }
                        }
                    // Translations and romanisations are separate renderings of the same line.
                    "x-translation", "x-roman" -> Unit
                    else -> parseWordSpan(child, offset, spanInfos)
                }
            }
            child = child.nextSibling
        }

        val words = mergeSpansIntoWords(spanInfos)
        val lineText = if (words.isEmpty()) getDirectText(p).trim() else buildLineText(words)

        if (lineText.isNotEmpty()) {
            val bgLines = if (backgroundLines.isNotEmpty()) {
                listOf(
                    ParsedLine(
                        text = backgroundLines.joinToString(" ") { it.text },
                        startTime = backgroundLines.minOf { it.startTime },
                        words = backgroundLines.flatMap { it.words },
                        agent = agent,
                        isBackground = true,
                    )
                )
            } else {
                emptyList()
            }
            lines += ParsedLine(lineText, startTime, words, agent, isPBackground, bgLines, lineKey(p))
        } else if (backgroundLines.isNotEmpty()) {
            lines += ParsedLine(
                text = backgroundLines.joinToString(" ") { it.text },
                startTime = backgroundLines.minOf { it.startTime },
                words = backgroundLines.flatMap { it.words },
                agent = agent,
                isBackground = true,
            )
        }
    }

    private fun parseWordSpan(span: Element, offset: Double, spanInfos: MutableList<SpanInfo>) {
        val begin = timingAttr(span, "begin")
        val end = timingAttr(span, "end")
        if (begin.isEmpty() || end.isEmpty()) return

        val text = span.textContent ?: ""
        val next = span.nextSibling
        val hasSpace = (text.isNotEmpty() && text.last().isWhitespace()) ||
            (next?.nodeType == Node.TEXT_NODE && next.textContent?.firstOrNull()?.isWhitespace() == true)
        spanInfos += SpanInfo(text, parseTime(begin) + offset, parseTime(end) + offset, hasSpace)
    }

    private fun parseBackgroundSpan(span: Element, parentStart: Double, offset: Double): ParsedLine? {
        val begin = timingAttr(span, "begin")
        val start = if (begin.isNotEmpty()) parseTime(begin) + offset else parentStart

        val spanInfos = mutableListOf<SpanInfo>()
        var hasSpans = false
        var child = span.firstChild
        while (child != null) {
            if (child is Element && localNameOf(child) == "span") {
                hasSpans = true
                val role = getAttr(child, "role")
                if (role != "x-translation" && role != "x-roman") {
                    parseWordSpan(child, offset, spanInfos)
                }
            }
            child = child.nextSibling
        }

        if (!hasSpans) {
            val text = span.textContent?.trim().orEmpty()
            return ParsedLine(text, start, isBackground = true)
        }

        val words = mergeSpansIntoWords(spanInfos)
        val text = if (words.isEmpty()) getDirectText(span).trim() else buildLineText(words)
        return ParsedLine(text, start, words, isBackground = true)
    }

    private fun getDirectText(el: Element): String = buildString {
        var child = el.firstChild
        while (child != null) {
            if (child.nodeType == Node.TEXT_NODE) {
                append(child.textContent)
            } else if (child is Element && localNameOf(child) == "span") {
                val role = getAttr(child, "role")
                if (role != "x-bg" && role != "x-translation" && role != "x-roman") {
                    append(child.textContent)
                }
            }
            child = child.nextSibling
        }
    }

    private fun buildLineText(words: List<ParsedWord>) = buildString {
        words.forEachIndexed { i, w ->
            append(w.text)
            if (w.hasTrailingSpace && !w.text.endsWith('-') && i < words.lastIndex) append(" ")
        }
    }.trim()

    /**
     * Apple TTML times each syllable separately. Join syllables back into words, breaking only
     * where the previous span ended with whitespace (a hyphen means the word continues).
     */
    private fun mergeSpansIntoWords(spanInfos: List<SpanInfo>): List<ParsedWord> {
        if (spanInfos.isEmpty()) return emptyList()

        val words = mutableListOf<ParsedWord>()
        var text = StringBuilder(spanInfos[0].text)
        var start = spanInfos[0].startTime
        var end = spanInfos[0].endTime

        for (i in 1 until spanInfos.size) {
            val prev = spanInfos[i - 1]
            val curr = spanInfos[i]
            if (prev.hasTrailingSpace && !prev.text.endsWith('-')) {
                words += ParsedWord(text.toString(), start, end, true)
                text = StringBuilder(curr.text)
                start = curr.startTime
                end = curr.endTime
            } else {
                text.append(curr.text)
                end = curr.endTime
            }
        }
        words += ParsedWord(text.toString(), start, end, spanInfos.last().hasTrailingSpace)

        return words.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
    }

    // endregion

    private fun formatLrcTime(time: Double, open: Char = '[', close: Char = ']'): String {
        val ms = (time.coerceAtLeast(0.0) * 1000).toLong()
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val centis = (ms % 1000) / 10
        return buildString(12) {
            append(open)
            if (minutes < 10) append('0')
            append(minutes).append(':')
            if (seconds < 10) append('0')
            append(seconds).append('.')
            if (centis < 10) append('0')
            append(centis).append(close)
        }
    }

    /** Accepts `h:mm:ss.ff`, `mm:ss.ff`, and the `12.5s` / `500ms` / `2m` clock-value forms. */
    private fun parseTime(time: String): Double {
        val t = time.trim()
        val firstColon = t.indexOf(':')
        if (firstColon != -1) {
            val lastColon = t.lastIndexOf(':')
            return if (firstColon == lastColon) {
                (t.substring(0, firstColon).toIntOrNull() ?: 0) * 60.0 +
                    (t.substring(firstColon + 1).toDoubleOrNull() ?: 0.0)
            } else {
                (t.substring(0, firstColon).toIntOrNull() ?: 0) * 3600.0 +
                    (t.substring(firstColon + 1, lastColon).toIntOrNull() ?: 0) * 60.0 +
                    (t.substring(lastColon + 1).toDoubleOrNull() ?: 0.0)
            }
        }
        if (t.endsWith("ms")) return (t.dropLast(2).toDoubleOrNull() ?: 0.0) / 1000.0
        val bare = if (t.endsWith("s") || t.endsWith("m") || t.endsWith("h")) t.dropLast(1) else t
        val value = bare.toDoubleOrNull() ?: 0.0
        return when {
            t.endsWith("m") -> value * 60.0
            t.endsWith("h") -> value * 3600.0
            else -> value
        }
    }
}
