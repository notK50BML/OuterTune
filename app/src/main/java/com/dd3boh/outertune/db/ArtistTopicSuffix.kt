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
fun String.stripTopicSuffix(): String = replace(TOPIC_SUFFIX, "").trim()
