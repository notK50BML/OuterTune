/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide whether a song and an artist page agree about who an artist is.
 *
 * Both are one-liners, and both have produced the same user-visible bug: an artist page showing the
 * right name and none of their songs. They are worth pinning because every read and write path in
 * the app depends on applying them identically - the failure mode when one path skips one is not an
 * error anywhere, just a query that quietly matches nothing.
 */
class ArtistIdentityTest {

    @Test
    fun `the two spellings of a channel id resolve to one`() {
        // YouTube returns the MPLAUC form in album and playlist contexts and the bare UC form
        // elsewhere, for the same channel. Storing under one and reading with the other finds
        // nothing, which is exactly what an artist page with no content looks like.
        assertEquals("UCabc123", "MPLAUCabc123".normalizeArtistId())
        assertEquals("UCabc123", "UCabc123".normalizeArtistId())
    }

    @Test
    fun `normalising is idempotent`() {
        // It is applied at several layers, and must not eat a second prefix on the second pass.
        val once = "MPLAUCabc123".normalizeArtistId()
        assertEquals(once, once.normalizeArtistId())
    }

    @Test
    fun `ids that merely start with similar letters are left alone`() {
        // Only the exact MPLAUC prefix means what it means. A locally generated id, or a browse id
        // that happens to begin with MPLA, must survive untouched or it would point at nothing.
        assertEquals("MPLAPLxyz", "MPLAPLxyz".normalizeArtistId())
        assertEquals("LA12345678", "LA12345678".normalizeArtistId())
        assertEquals("FEmusic_library_privately_owned_artist_x", "FEmusic_library_privately_owned_artist_x".normalizeArtistId())
        assertEquals("", "".normalizeArtistId())
    }

    @Test
    fun `the auto-generated channel suffix is removed`() {
        // YouTube credits songs to an auto-generated "X - Topic" channel. Left in place, the same
        // artist exists twice under two names and their songs split between them.
        assertEquals("YOASOBI", "YOASOBI - Topic".stripTopicSuffix())
        assertEquals("Radiohead", "Radiohead- Topic".stripTopicSuffix())
        assertEquals("Radiohead", "Radiohead -Topic".stripTopicSuffix())
        // The suffix match ignores case; the name itself is returned as it came, since the lookup
        // that uses it is COLLATE NOCASE anyway and rewriting an artist's capitalisation would be
        // a worse answer than keeping theirs.
        assertEquals("radiohead", "radiohead - topic".stripTopicSuffix())
        assertEquals("Radiohead", "Radiohead - TOPIC".stripTopicSuffix())
    }

    @Test
    fun `a name that is only the suffix is kept rather than blanked`() {
        // YouTube does produce channels titled exactly "- Topic", for auto-generated channels whose
        // artist name came through empty. Stripping is meant to tidy a name, never to delete one:
        // a blank credit renders as nothing, sorts nowhere, and reads as missing data rather than
        // as the wrong data it actually is.
        assertEquals("- Topic", "- Topic".stripTopicSuffix())
        assertFalse("- Topic".stripTopicSuffix().isBlank())
    }

    @Test
    fun `the suffix surviving a strip is how a nameless channel is recognised`() {
        // Callers use this to decide whether a fetched title is worth writing over a stored name.
        // Without it, "- Topic" arrives looking like an ordinary name and overwrites a good one.
        assertTrue(TOPIC_SUFFIX.containsMatchIn("- Topic".stripTopicSuffix()))
        assertFalse(TOPIC_SUFFIX.containsMatchIn("YOASOBI - Topic".stripTopicSuffix()))
    }

    @Test
    fun `an ordinary name is untouched`() {
        // The suffix is only ever stripped from the end, so an artist whose name contains the word
        // keeps it. "Topic" is a real band name.
        assertEquals("Topic", "Topic".stripTopicSuffix())
        assertEquals("Topical Heat", "Topical Heat".stripTopicSuffix())
        assertEquals("Off Topic Records", "Off Topic Records".stripTopicSuffix())
        assertEquals("YOASOBI", "  YOASOBI  ".stripTopicSuffix())
    }
}
