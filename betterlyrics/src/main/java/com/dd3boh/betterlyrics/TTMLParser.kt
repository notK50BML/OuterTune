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
 * child `<span>` when a line carries no `begin` of its own. [toLrc] then emits plain, standard LRC
 * so the rest of the app can render it line by line with no special casing.
 *
 * Word-level timings are still parsed, because they are needed to reconstruct a line's text when it
 * is split across syllable spans. They are not emitted: the strict parser above handles karaoke
 * rendering when it can read the document, and this path exists to guarantee line-by-line lyrics
 * when it cannot.
 */
object TTMLParser {

    /** TTML timing attributes may appear unprefixed or as `ttp:*` (parameter namespace). */
    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"
    private const val TTML_METADATA_NS = "http://www.w3.org/ns/ttml#metadata"

    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord> = emptyList(),
        val agent: String? = null,
        val isBackground: Boolean = false,
        val backgroundLines: List<ParsedLine> = emptyList(),
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

    fun parseTTML(ttml: String): List<ParsedLine> {
        val lines = mutableListOf<ParsedLine>()
        try {
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

            val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
            val root = doc.documentElement ?: return emptyList()

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
            return emptyList()
        }
        return lines
    }

    /**
     * Render [lines] as plain LRC: one `[mm:ss.cc]text` entry per line, in time order.
     *
     * Background vocals become their own lines rather than being dropped, so a duet or an
     * ad-libbed line still shows up. Speaker/agent information is discarded because standard LRC
     * has no way to express it and OuterTune's renderer does not read it.
     */
    fun toLrc(lines: List<ParsedLine>): String {
        val flat = mutableListOf<Pair<Double, String>>()
        for (line in lines) {
            if (line.text.isNotBlank()) flat += line.startTime to line.text.trim()
            for (bg in line.backgroundLines) {
                if (bg.text.isNotBlank()) flat += bg.startTime to bg.text.trim()
            }
        }
        if (flat.isEmpty()) return ""

        // A stable sort keeps a background line directly after the line it belongs to when the
        // two share a timestamp.
        return flat.sortedBy { it.first }
            .joinToString("\n") { (time, text) -> formatLrcTime(time) + text }
    }

    /** Convenience: TTML straight to LRC, or null when nothing usable came out. */
    fun ttmlToLrc(ttml: String): String? =
        toLrc(parseTTML(ttml)).takeIf { it.isNotBlank() }

    // region DOM walking

    private fun getAttr(el: Element, localName: String): String {
        val prefixed = el.getAttribute("ttm:$localName")
        if (prefixed.isNotEmpty()) return prefixed
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        return el.getAttributeNS(TTML_METADATA_NS, localName)
    }

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
                        isBackground = true,
                    )
                )
            } else {
                emptyList()
            }
            lines += ParsedLine(lineText, startTime, words, agent, isPBackground, bgLines)
        } else if (backgroundLines.isNotEmpty()) {
            lines += ParsedLine(
                text = backgroundLines.joinToString(" ") { it.text },
                startTime = backgroundLines.minOf { it.startTime },
                words = backgroundLines.flatMap { it.words },
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

    private fun formatLrcTime(time: Double): String {
        val ms = (time.coerceAtLeast(0.0) * 1000).toLong()
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val centis = (ms % 1000) / 10
        return buildString(12) {
            append('[')
            if (minutes < 10) append('0')
            append(minutes).append(':')
            if (seconds < 10) append('0')
            append(seconds).append('.')
            if (centis < 10) append('0')
            append(centis).append(']')
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
