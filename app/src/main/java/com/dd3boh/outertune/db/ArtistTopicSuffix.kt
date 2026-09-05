/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.db

/** YouTube's auto-generated channel name for an artist with no official channel of their own. */
val TOPIC_SUFFIX: Regex = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)

/**
 * The bare channel id for an artist, whichever of YouTube's two spellings arrived.
 *
 * YTM hands back the same artist under two ids depending on where the credit came from: the plain
 * channel id (UCWT2ZfW7d8YI-HinHEVhyCA) and a browse-id form with an MPLA prefix on the front of it
 * (MPLAUCWT2ZfW7d8YI-HinHEVhyCA). They are one artist, and nothing here knew that.
 *
 * Two things went wrong as a result. A song credited under both spellings showed the artist twice.
 * And because "is this a real channel" is decided by the id starting with UC, the prefixed form
 * failed that test, got filed as a local placeholder, and rendered greyed out and untappable - so a
 * song credited *only* under the prefixed form had no working artist link at all.
 *
 * Stripping the prefix rather than teaching every check to accept both keeps one id per artist,
 * which is also what stops the duplicate from arising in the first place.
 */
fun String.normalizeArtistId(): String =
    if (startsWith("MPLAUC")) removePrefix("MPLA") else this

/**
 * Shared by every site that needs to compare or store an artist's display name independent of
 * whether the credit came in through YouTube's auto-generated "- Topic" channel: the general
 * song-artist sync path ([DatabaseDao.insert]), [com.dd3boh.outertune.utils.ArtistCreditEnricher]'s
 * search-based resolution, and the one-time migrations that clean up names already stored with
 * the suffix.
 */
fun String.stripTopicSuffix(): String {
    val stripped = replace(TOPIC_SUFFIX, "").trim()
    // A channel titled exactly "- Topic" - which YouTube does produce, for auto-generated channels
    // whose artist name came through empty - matches the pattern in its entirety and strips to
    // nothing. Every caller here stores or displays the result, so that blank became a real artist
    // row with an empty name: a credit that renders as nothing, sorts nowhere, and still links off
    // to the junk channel it came from.
    //
    // Stripping is meant to tidy a name, never to remove it, so a strip that would leave nothing is
    // not applied. "- Topic" is a poor name to show, but it is a name, and it is the truth about
    // what that credit points at - unlike a blank, which reads as though the data were missing
    // rather than wrong.
    return stripped.ifBlank { trim() }
}

/**
 * Whether this credit names an actual artist, rather than an auto-generated channel with no name.
 *
 * YouTube produces channels titled exactly "- Topic" for songs whose artist name came through
 * empty. [stripTopicSuffix] deliberately leaves such a title alone rather than reducing it to a
 * blank - stripping is meant to tidy a name, not delete one - so the suffix surviving the strip is
 * precisely the tell that there was no name in front of it.
 *
 * Such a credit is a real row and a real channel, so it cannot simply be deleted, but it should
 * never be what a "view artist" tap lands on. Its page is the auto-generated one: the right name is
 * not even on it, and the song being looked for is filed under the artist's actual channel. When a
 * song is credited to both - which is common, since the topic channel is how YouTube attributes the
 * upload - the one that names somebody is the one worth linking to.
 */
fun String.namesAnArtist(): Boolean {
    val stripped = stripTopicSuffix()
    return stripped.isNotBlank() && !TOPIC_SUFFIX.containsMatchIn(stripped)
}
