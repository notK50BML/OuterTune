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
fun cleanCaptionLyrics(raw: String): String =
    raw.lineSequence().joinToString("\n") { line ->
        val match = LRC_LINE.matchEntire(line) ?: return@joinToString cleanCaptionText(line)
        val (timestamps, text) = match.destructured
        timestamps + cleanCaptionText(text)
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
