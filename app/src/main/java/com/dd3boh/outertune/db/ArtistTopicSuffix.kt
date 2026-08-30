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
