/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube.utils

/*
 * Here rather than in either app because both use it: the phone's YouTube Subtitle provider and the
 * desktop's. Plain Kotlin with nothing platform-specific in it, beside the transcript() it cleans up.
 */

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
 * A single `>` at the start of a line, which [SPEAKER_MARKER] does not catch.
 *
 * YouTube emits both: `>>` for a change of speaker and a lone `>` where the same one carries on.
 * The two-or-more rule was written against the first and left every line of the second with an
 * angle bracket in front of it.
 *
 * Anchored to the start of the line deliberately. A `>` in the middle of one is far likelier to be
 * something the lyricist wrote than a caption artefact, and once it is out of the position the
 * convention puts it in there is no way to tell the two apart.
 */
private val LEADING_ANGLE = Regex("""^\s*>+\s*""")

/**
 * Note characters wrapping a line that has words in it - `♪ Never gonna give ♪`.
 *
 * YouTube brackets sung lines this way, and across a whole song it reads as decoration applied to
 * every line rather than as a mark that means anything. A line that is *only* notes is left alone:
 * that one is standing in for an instrumental passage, which is what the mark is actually for.
 */
private val WRAPPING_NOTES = Regex("""^[\s♪♫]+|[\s♪♫]+$""")

/**
 * Transcription artefacts: the recogniser reporting that it could not hear something.
 *
 * Removed outright rather than replaced. Unlike a music annotation these describe a failure of the
 * transcript rather than anything in the recording, so there is nothing for a placeholder to stand
 * in for - an empty line where the words are unknown is the honest result.
 */
private val INAUDIBLE_ANNOTATION =
    Regex("""\[[^\]]*\b(?:inaudible|unintelligible|indistinct|no audio|silence)\b[^\]]*]""", RegexOption.IGNORE_CASE)

/**
 * A bracketed sound annotation about music or singing - `[Music]`, `[Singing]`,
 * `[Music and singing]`, `[MUSIC PLAYING]`, `[Instrumental]` and so on.
 *
 * Only music and singing are matched, deliberately. Auto-captions also emit things like
 * `[Applause]` and `[Laughter]`, and those are genuinely part of a live recording rather than an
 * artefact of transcribing one, so they are left alone.
 */
private val MUSIC_ANNOTATION =
    Regex("""\[[^\]]*\b(?:music|singing|instrumental|guitar solo|drum solo)\b[^\]]*]""", RegexOption.IGNORE_CASE)

/**
 * Which of those annotations describe something being played, as opposed to something being sung.
 *
 * A line that is nothing but one of these earns the placeholder, because an instrumental break is
 * a real event in the recording worth marking. `[Singing]` is not: it labels the obvious, and a
 * note in its place would claim there was a break where there was a vocal.
 */
private val PLAYED_NOT_SUNG =
    Regex("""\b(?:music|instrumental|guitar solo|drum solo)\b""", RegexOption.IGNORE_CASE)

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
/**
 * The cleanup that is safe for *any* lyrics source, whatever it is.
 *
 * Caption artefacts are dropped - `>>` and `>` speaker markers, music and singing annotations,
 * notes wrapping a line that has words in it, and the recogniser's own "could not hear this"
 * markers. A line left with nothing but a music annotation becomes the standard placeholder. All
 * of it is noise in a lyric sheet whichever provider produced it, and none of it can damage a line
 * that does not contain it.
 *
 * Idempotent, which matters because it runs twice: once on what gets stored, and again on what
 * gets displayed - see `LyricsHelper.parseResilient` for why the second is not redundant.
 *
 * Deliberately does **not** re-break lines. That is the one part of the caption treatment that must
 * not be applied generally: a real lyrics file is already broken where the lyricist intended, and
 * splitting its lines at sentence ends would cut "Hello. Is it me you're looking for?" in half.
 * Captions have no such intent behind their breaks, which is why [cleanCaptionLyrics] is allowed to
 * re-break them and this is not.
 */
fun cleanLyrics(raw: String): String =
    raw.lines().joinToString("\n") { line ->
        val match = LRC_LINE.matchEntire(line)
        if (match == null) {
            cleanCaptionText(line)
        } else {
            val (timestamps, text) = match.destructured
            timestamps + cleanCaptionText(text)
        }
    }

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
    // The lone leading bracket after the pair, not before: a line reading ">> >Never" carries both
    // conventions, and removing the pair leaves the single one at what is now the start.
    val despeakered = raw.replace(SPEAKER_MARKER, " ").replace(LEADING_ANGLE, "")
    val stripped = despeakered
        .replace(MUSIC_ANNOTATION, " ")
        .replace(INAUDIBLE_ANNOTATION, " ")
        .replace(COLLAPSIBLE_SPACE, " ")
        .trim()

    if (stripped.isNotEmpty()) {
        // Notes wrapping real words are decoration; notes on their own are the mark itself, so the
        // unwrapping only applies when something would be left after it.
        val unwrapped = stripped.replace(WRAPPING_NOTES, "")
        return if (unwrapped.isNotEmpty()) unwrapped else stripped
    }

    // Nothing survived, so the line was only annotation. One describing something played earns the
    // placeholder; a bare "[Singing]" was labelling the obvious, and leaves an empty line behind.
    val describedPlaying = MUSIC_ANNOTATION.findAll(despeakered).any { PLAYED_NOT_SUNG.containsMatchIn(it.value) }
    return if (describedPlaying) MUSIC_PLACEHOLDER else ""
}
