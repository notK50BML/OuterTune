/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.lyrics

/**
 * The universal "instrumental / non-vocal passage" marker. Every lyrics source that bothers to
 * indicate one uses it, so a caption track saying the same thing in words should end up saying it
 * the same way.
 */
private const val MUSIC_PLACEHOLDER = "♪"

/**
 * YouTube's caption convention for a change of speaker. Meaningful in a transcript of dialogue,
 * noise in a song.
 */
private val SPEAKER_MARKER = Regex(""">>+\s*""")

/**
 * A bracketed sound annotation about music or singing - `[Music]`, `[Singing]`,
 * `[Music and singing]`, `[MUSIC PLAYING]` and so on.
 *
 * Only music and singing are matched, deliberately. Auto-captions also emit things like
 * `[Applause]` and `[Laughter]`, and those are genuinely part of a live recording rather than an
 * artefact of transcribing one, so they are left alone.
 */
private val MUSIC_ANNOTATION = Regex("""\[[^\]]*\b(?:music|singing)\b[^\]]*]""", RegexOption.IGNORE_CASE)

/** Timestamps first, then whatever the line actually says. */
private val LRC_LINE = Regex("""^((?:\[\d+:\d{2}(?:[.:]\d+)?])+)(.*)$""")

private val COLLAPSIBLE_SPACE = Regex("""\s{2,}""")

/**
 * Where one sentence ends and the next begins.
 *
 * Requiring a space after the punctuation keeps decimals like "3.5" whole. That alone is not
 * enough, though: a title is followed by a space too, so it splits "Mr. Jones" into "Mr." and
 * "Jones paid" - which testing caught and which matters, since titles turn up in lyrics regularly.
 * The common ones are excluded explicitly.
 */
private val SENTENCE_END = Regex(
    """(?<!\bMr\.)(?<!\bMrs\.)(?<!\bMs\.)(?<!\bDr\.)(?<!\bSt\.)(?<!\bJr\.)(?<!\bSr\.)""" +
        """(?<!\bvs\.)(?<!\bft\.)(?<!\bfeat\.)(?<=[.?!])\s+"""
)

/** The first timestamp on a line, which is the point a split is measured from. */
private val FIRST_STAMP = Regex("""\[(\d+):(\d{2})([.:]\d+)?]""")

/**
 * Tidies a caption track that is standing in for lyrics.
 *
 * Auto-generated captions are a transcript, not a lyric sheet, and they carry three habits that
 * read badly as lyrics: `>>` speaker-change markers, `[Music]` dropped in mid-sentence wherever the
 * recogniser heard backing over the vocal, and `[Singing]` tagged onto lines that are - being song
 * lyrics - obviously sung.
 *
 * The one case worth keeping is a line that is *nothing but* a music annotation, since that is
 * describing a real instrumental passage. Those become [MUSIC_PLACEHOLDER], which is what every
 * other lyrics source uses for the same thing.
 *
 * Lines that empty out are kept as empty lines rather than dropped: their timestamps are still
 * real, and a lyric sheet with a gap where the singing stops is correct.
 */
fun cleanCaptionLyrics(raw: String): String {
    val lines = raw.lines().map { line ->
        val match = LRC_LINE.matchEntire(line)
        if (match == null) {
            CaptionLine(stamp = null, text = cleanCaptionText(line), original = line)
        } else {
            val (timestamps, text) = match.destructured
            CaptionLine(stamp = timestamps, text = cleanCaptionText(text), original = line)
        }
    }
    return lines.indices.joinToString("\n") { index -> renderLine(lines, index) }
}

/** One caption line after cleaning: its timestamps, its text, and what it looked like before. */
private data class CaptionLine(val stamp: String?, val text: String, val original: String)

/**
 * A line, split into sentences where it runs several together.
 *
 * Captions are punctuated as prose and broken wherever the recogniser happened to pause, so one
 * timestamped line regularly carries two or three complete sentences while the next carries half of
 * one. Read as lyrics that is worse than useless. Splitting on sentence endings gives lines that
 * break where the singing does far more often than the original breaks did.
 *
 * The split only happens when there is a following timestamp to bound it, because the second
 * sentence needs a time of its own and there is nowhere to get one otherwise. Within that span the
 * time is divided by how long each sentence is - the same approximation the estimated word sync
 * uses, and the same caveat applies: a line is not delivered at a constant rate, so the split point
 * drifts, while both ends stay on real timestamps.
 */
private fun renderLine(lines: List<CaptionLine>, index: Int): String {
    val line = lines[index]
    val stamp = line.stamp ?: return line.text
    if (line.text.isBlank()) return stamp + line.text

    val sentences = splitSentences(line.text)
    if (sentences.size < 2) return stamp + line.text

    val start = parseStamp(stamp)
    val end = lines.drop(index + 1).firstOrNull { it.stamp != null }?.stamp?.let { parseStamp(it) }
    if (start == null || end == null || end <= start) return stamp + line.text

    val total = sentences.sumOf { it.length }
    if (total <= 0) return stamp + line.text

    var consumed = 0
    return sentences.joinToString("\n") { sentence ->
        val offset = (end - start) * consumed / total
        consumed += sentence.length
        // The first sentence keeps the line's own timestamp exactly, so nothing is nudged off a
        // real time by rounding; only the invented ones are derived.
        val at = if (offset == 0L) stamp else formatStamp(start + offset)
        at + sentence
    }
}

/**
 * Sentence-sized pieces, keeping the punctuation that ended each one.
 *
 * A full stop only ends a sentence when a space follows it, which keeps "3.5" and "Mr. Jones"
 * intact - a naive split on the character alone would cut both in half.
 */
private fun splitSentences(text: String): List<String> =
    SENTENCE_END.split(text).map { it.trim() }.filter { it.isNotEmpty() }

private fun parseStamp(stamp: String): Long? {
    val match = FIRST_STAMP.find(stamp) ?: return null
    val (minutes, seconds, fraction) = match.destructured
    val fractionMs = fraction.removePrefix(".").removePrefix(":")
        .padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
}

private fun formatStamp(ms: Long): String {
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1_000
    val hundredths = (ms % 1_000) / 10
    return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
}

private fun cleanCaptionText(raw: String): String {
    val despeakered = raw.replace(SPEAKER_MARKER, " ")
    val stripped = despeakered
        .replace(MUSIC_ANNOTATION, " ")
        .replace(COLLAPSIBLE_SPACE, " ")
        .trim()
    if (stripped.isNotEmpty()) return stripped

    // Nothing survived, so the line was only annotation. A music one was describing an instrumental
    // passage and earns the placeholder; a bare "[Singing]" was only labelling the obvious, and
    // leaves an empty line behind.
    val describedMusic = MUSIC_ANNOTATION.findAll(despeakered).any { it.value.contains("music", ignoreCase = true) }
    return if (describedMusic) MUSIC_PLACEHOLDER else ""
}
