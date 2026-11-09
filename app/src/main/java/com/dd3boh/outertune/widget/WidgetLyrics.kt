/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

/**
 * The widget's lyrics line: just enough LRC reading to say which line is being sung.
 *
 * Deliberately not the app's full lyrics parser. The widget shows one line of plain text, refreshed
 * every couple of seconds, so word timings, translations and singer markup have nothing to drive;
 * what it needs is the line timestamps, and the text with any inline word marks taken out.
 */
object WidgetLyrics {
    private val LINE = Regex("""^\s*\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)$""")
    private val WORD_MARK = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")

    /** Timed lines, in order. Empty for unsynced lyrics - there is no "current line" to show then. */
    fun parse(raw: String?): List<Pair<Long, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val match = LINE.find(line) ?: return@mapNotNull null
            val (min, sec, frac, text) = match.destructured
            val fracMs = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.take(3).toLong()
            }
            val cleaned = text.replace(WORD_MARK, "").trim()
            (min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs) to cleaned
        }.filter { it.second.isNotEmpty() }.sortedBy { it.first }.toList()
    }

    /** The line being sung at [positionMs], or null before the first one. */
    fun lineAt(lines: List<Pair<Long, String>>, positionMs: Long): String? =
        lines.lastOrNull { it.first <= positionMs }?.second
}
