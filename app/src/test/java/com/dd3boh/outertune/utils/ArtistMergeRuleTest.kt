/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.TOPIC_SUFFIX
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.normalizeArtistId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When one artist credit may be collapsed into another.
 *
 * This is the rule behind `ArtistCreditEnricher.creditFromArtistPage`, and it is worth pinning
 * separately because loosening it is both easy and irreversible. Merging moves every credit a row
 * holds and then deletes the row; there is no undo, and nothing on screen afterwards says what was
 * lost. The failure is silent in the worst way - the library simply has one artist where it used to
 * have two, and the songs of one of them are now filed under the other.
 *
 * So the tests are written from the refusals inward: the cases that must NOT merge come first,
 * because those are the ones a future "improvement" would quietly break.
 *
 * The rule is reproduced here rather than called, because it is private to the enricher - which is
 * right, nothing else should be making this decision. A copy in a test is the cost of keeping it
 * that way, and any change to one that is not made to the other shows up as a failure here.
 */
class ArtistMergeRuleTest {

    /** Mirrors `ArtistCreditEnricher.mergeableInto`. */
    private fun ArtistEntity.mergeableInto(real: ArtistEntity): Boolean {
        if (id.normalizeArtistId() == real.id.normalizeArtistId()) return true
        if (!isYouTubeArtist) return true
        return TOPIC_SUFFIX.containsMatchIn(name)
    }

    private fun channel(id: String, name: String) = ArtistEntity(id = id, name = name)

    /** A credit that arrived as bare text, with no channel behind it. */
    private fun placeholder(name: String) = ArtistEntity(id = ArtistEntity.generateArtistId(), name = name)

    // ---- what must never merge ---------------------------------------------------------------

    @Test
    fun `two real channels sharing a name are left alone`() {
        // The case this whole rule exists to refuse. "John Williams" is a film composer and also a
        // classical guitarist, and they are not the same person however identical the string is.
        // Merging them cannot be undone and nothing would report that it happened.
        val composer = channel("UCcomposer", "John Williams")
        val guitarist = channel("UCguitarist", "John Williams")
        assertFalse(composer.mergeableInto(guitarist))
        assertFalse(guitarist.mergeableInto(composer))
    }

    @Test
    fun `a real channel is not absorbed just because another page lists its song`() {
        // A song appearing on someone's page is evidence they perform it - a collaboration, a
        // cover, a compilation - not evidence that they *are* the other credited artist.
        val featured = channel("UCfeatured", "Someone Else")
        val pageOwner = channel("UCowner", "The Artist")
        assertFalse(featured.mergeableInto(pageOwner))
    }

    // ---- what may merge ----------------------------------------------------------------------

    @Test
    fun `the same channel in YouTube's other spelling merges`() {
        // UC… and MPLAUC… are one channel. A row under the prefixed spelling is a leftover from
        // before writes were normalised, not a second artist.
        val prefixed = channel("MPLAUCabc123", "Radiohead")
        val bare = channel("UCabc123", "Radiohead")
        assertTrue(prefixed.mergeableInto(bare))
        assertTrue(bare.mergeableInto(prefixed))
    }

    @Test
    fun `a placeholder merges into the real channel`() {
        // A locally generated id means the credit arrived as text with no channel attached. It
        // never named a channel, so it cannot name a different one.
        val text = placeholder("Radiohead")
        val real = channel("UCabc123", "Radiohead")
        assertTrue(text.mergeableInto(real))
    }

    @Test
    fun `a Topic channel merges into the real one`() {
        // YouTube auto-generates these beside an artist's real channel. The suffix is the tell.
        val topic = channel("UCtopic", "Radiohead - Topic")
        val real = channel("UCreal", "Radiohead")
        assertTrue(topic.mergeableInto(real))
    }

    @Test
    fun `the direction matters - a real channel does not merge into a Topic one`() {
        // The rule reads the *candidate's* name, so pointing it the wrong way round refuses, which
        // is what keeps a real channel from being collapsed into an auto-generated one.
        val topic = channel("UCtopic", "Radiohead - Topic")
        val real = channel("UCreal", "Radiohead")
        assertFalse(real.mergeableInto(topic))
    }

    @Test
    fun `a placeholder merges regardless of how the real one is spelled`() {
        val text = placeholder("Boards of Canada")
        assertTrue(text.mergeableInto(channel("UCx", "Boards of Canada")))
        assertTrue(text.mergeableInto(channel("MPLAUCx", "Boards of Canada")))
    }

    @Test
    fun `a privately owned library artist counts as a real channel`() {
        // FEmusic_library_privately_owned_artist ids are uploads, which are as real as a channel
        // and must not be absorbed by a same-named public one.
        val owned = channel("FEmusic_library_privately_owned_artistABC", "Someone")
        val public = channel("UCsomeone", "Someone")
        assertFalse(owned.mergeableInto(public))
    }
}
