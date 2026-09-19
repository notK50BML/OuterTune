/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.ArtistEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an artist page is allowed to write.
 *
 * [ArtistMergeRuleTest] pins the rule for one credit against one artist. This pins the two
 * decisions built on top of it - whether the page may be written from at all, and whether a song's
 * same-named credits may be collapsed as a set - because both were wrong in ways the single-credit
 * rule cannot see.
 *
 * As with that test, the refusals come first: they are the cases a later loosening would quietly
 * break, and the damage is silent. Nothing reports a credit invented from a recommendation shelf,
 * or an artist merged into an auto-generated channel.
 */
class ArtistPageCreditPolicyTest {

    private fun channel(id: String, name: String) = ArtistEntity(id = id, name = name)
    private fun placeholder(name: String) = ArtistEntity(id = ArtistEntity.generateArtistId(), name = name)

    // ---- which pages may be written from -----------------------------------------------------

    @Test
    fun `an auto-generated Topic channel is not written from`() {
        // These are what every other repair in the enricher points away from, so taking one at its
        // word would undo that work a page visit at a time - and a merge into one cannot be undone.
        assertFalse(ArtistCreditEnricher.mayCreditFrom(channel("UCtopic", "Radiohead - Topic")))
    }

    @Test
    fun `the Topic suffix is read from the name as YouTube gave it`() {
        // The bug this exists to prevent, and it was real: the caller stripped the suffix before
        // handing the artist over, so the guard could only ever see a channel titled exactly
        // "- Topic" and waved every ordinary "X - Topic" straight through. Stripped, the same
        // channel is indistinguishable from a real one - which is precisely why the contract says
        // unstripped, and why this test asserts the *pair*.
        assertFalse(ArtistCreditEnricher.mayCreditFrom(channel("UCtopic", "Radiohead - Topic")))
        assertTrue(ArtistCreditEnricher.mayCreditFrom(channel("UCtopic", "Radiohead")))
    }

    @Test
    fun `a channel named only by the suffix is not written from`() {
        // YouTube does produce these, for auto-generated channels whose artist name came through
        // empty. It names nobody, so there is nothing to match a credit against.
        assertFalse(ArtistCreditEnricher.mayCreditFrom(channel("UCnameless", "- Topic")))
        assertFalse(ArtistCreditEnricher.mayCreditFrom(channel("UCnameless", "   ")))
    }

    @Test
    fun `a locally generated placeholder is not written from`() {
        // The screen may move which library row it *reads* from, and that row is not required to be
        // a channel. Crediting songs to it would write the greyed-out, untappable kind of credit
        // the enricher exists to remove - and a real "- Topic" channel is mergeable into it, so a
        // credit that at least opened something would be replaced by one that opens nothing.
        assertFalse(ArtistCreditEnricher.mayCreditFrom(placeholder("Radiohead")))
    }

    @Test
    fun `an ordinary channel is written from`() {
        assertTrue(ArtistCreditEnricher.mayCreditFrom(channel("UCreal", "Radiohead")))
        // Both of YouTube's spellings of one channel id, since the credit may arrive as either.
        assertTrue(ArtistCreditEnricher.mayCreditFrom(channel("MPLAUCreal", "Radiohead")))
    }

    // ---- which duplicate sets may be collapsed -----------------------------------------------

    @Test
    fun `one unmergeable credit refuses the whole set`() {
        // The case a per-credit rule cannot see, and the dangerous one. A song credited to both
        // John Williams the guitarist's real channel and a bare-text "John Williams" left by some
        // import: the placeholder qualifies on its own, but what it stands for is far likelier to
        // be the guitarist sitting beside it than the composer whose page happens to be open.
        // Merging it would move a credit between two real artists on the strength of a name.
        val page = channel("UCcomposer", "John Williams")
        val set = listOf(channel("UCguitarist", "John Williams"), placeholder("John Williams"))
        assertFalse(ArtistCreditEnricher.mayMergeAll(set, page))
    }

    @Test
    fun `an empty set is not a merge`() {
        // Nothing to collapse is not permission to collapse nothing - the caller branches on this,
        // and a vacuous true would send it down the merge path with no duplicate to merge.
        assertFalse(ArtistCreditEnricher.mayMergeAll(emptyList(), channel("UCreal", "Radiohead")))
    }

    @Test
    fun `a song carrying both a Topic channel and a placeholder collapses entirely`() {
        // Both are the same artist by the single-credit rule, and both must go. Merging only the
        // first left the other in place, so the name still appeared twice on the song - with the
        // merge reported in the log as done.
        val page = channel("UCreal", "Radiohead")
        val set = listOf(channel("UCtopic", "Radiohead - Topic"), placeholder("Radiohead"))
        assertTrue(ArtistCreditEnricher.mayMergeAll(set, page))
    }

    @Test
    fun `the other spelling of the page's own id collapses`() {
        val page = channel("UCabc123", "Radiohead")
        assertTrue(ArtistCreditEnricher.mayMergeAll(listOf(channel("MPLAUCabc123", "Radiohead")), page))
    }
}
