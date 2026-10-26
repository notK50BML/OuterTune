/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class ArtistLinksTest {

    private fun link(name: String, channel: String?) =
        ArtistLink(ArtistLinks.key(name), name, channel, name, "thumb", 1L, manual = false)

    @Test
    fun featuredNamesFromTitles() {
        assertEquals(listOf("Dua Lipa"), ArtistLinks.featuredNames("Cold Heart (feat. Dua Lipa)"))
        assertEquals(listOf("A", "B", "C"), ArtistLinks.featuredNames("Song [ft. A, B & C]"))
        assertEquals(listOf("Khalid"), ArtistLinks.featuredNames("Love Lies ft Khalid"))
        assertEquals(emptyList<String>(), ArtistLinks.featuredNames("Left (Remastered)"))
    }

    @Test
    fun keysIgnoreCaseAndSurroundingSpaceOnly() {
        assertEquals(ArtistLinks.key("Coldplay"), ArtistLinks.key("  coldplay "))
        assert(ArtistLinks.key("Beyoncé") != ArtistLinks.key("Beyonce"))
    }

    @Test
    fun anAmpersandCreditWithNoRealHalvesKeepsALocalFileCredited() {
        val credit = listOf(StoredArtist.unlinked("A & B"))
        val links = listOf(link("A & B", null), link("A", null), link("B", null)).associateBy { it.nameKey }
        assertEquals(credit, ArtistLinks.rewrite(credit, "x", links))
    }

    @Test
    fun anUnrealHalfKeepsTheCombinedCredit() {
        // "Mumford & Sons" missing an exact match while some channel "Mumford" exists is no reason
        // to credit the song to that channel and drop the rest.
        val credit = listOf(StoredArtist.unlinked("A & B"))
        val links = listOf(link("A & B", null), link("A", "UCa"), link("B", null)).associateBy { it.nameKey }
        assertEquals(credit, ArtistLinks.rewrite(credit, "x", links))
    }

    @Test
    fun aNameUnlinkedByHandIsNotSplit() {
        // Unlinking by hand records the name with no channel, like a search that found nothing -
        // but it means "not that channel", not "not an artist".
        val credit = listOf(StoredArtist.unlinked("A & B"))
        val links = listOf(
            link("A & B", null).copy(manual = true), link("A", "UCa"), link("B", "UCb"),
        ).associateBy { it.nameKey }
        assertEquals(credit, ArtistLinks.rewrite(credit, "x", links))
    }

    @Test
    fun localArtistTakesItsChannel() {
        val links = listOf(link("Coldplay", "UCcold")).associateBy { it.nameKey }
        val out = ArtistLinks.rewrite(listOf(StoredArtist.unlinked("Coldplay")), "Yellow", links)
        assertEquals(listOf(StoredArtist("UCcold", "Coldplay")), out)
    }

    @Test
    fun featuredClausesReadLikeThePhone() {
        assertEquals(listOf("X"), ArtistLinks.featuredNames("Song (featuring X)"))
        assertEquals(listOf("X"), ArtistLinks.featuredNames("Song w/ X"))
        assertEquals(listOf("B", "C"), ArtistLinks.featuredNames("Song ft. B (ft. C)"))
        assertEquals(listOf("(G)I-DLE"), ArtistLinks.featuredNames("Song (feat. (G)I-DLE)"))
        assertEquals(listOf("B"), ArtistLinks.featuredNames("Song ft. B - Remix"))
        assertEquals(listOf("A"), ArtistLinks.featuredNames("Song ft. A [Deluxe]"))
        assertEquals(emptyList<String>(), ArtistLinks.featuredNames("Dancing With Myself"))
    }

    @Test
    fun aFeaturedNameAlreadyCreditedIsNotSplitIntoMore() {
        val links = listOf(link("Tyler", "UCtyler"), link("The Creator", "UCcreator")).associateBy { it.nameKey }
        val credits = listOf(StoredArtist("UCtc", "Tyler, The Creator"))
        assertEquals(credits, ArtistLinks.rewrite(credits, "Song (feat. Tyler, The Creator)", links))
    }

    @Test
    fun aFeaturedBandIsTakenWholeBeforeItsPieces() {
        val credits = listOf(StoredArtist("UCa", "A"))
        val title = "Song (feat. Earth, Wind & Fire)"
        val pieces = listOf(link("Earth", "UCearth"), link("Wind", "UCwind"), link("Fire", "UCfire"))
        // The whole is a band: it, not its pieces.
        val band = (pieces + link("Earth, Wind & Fire", "UCewf")).associateBy { it.nameKey }
        assertEquals(credits + StoredArtist("UCewf", "Earth, Wind & Fire"), ArtistLinks.rewrite(credits, title, band))
        // Not yet looked up as a whole: nothing.
        assertEquals(credits, ArtistLinks.rewrite(credits, title, pieces.associateBy { it.nameKey }))
        // Confirmed not one artist: the pieces that are.
        val split = (pieces + link("Earth, Wind & Fire", null)).associateBy { it.nameKey }
        assertEquals(
            credits + listOf(StoredArtist("UCearth", "Earth"), StoredArtist("UCwind", "Wind"), StoredArtist("UCfire", "Fire")),
            ArtistLinks.rewrite(credits, title, split),
        )
    }

    @Test
    fun theSameNameTwiceIsOneCredit() {
        val out = ArtistLinks.dedupeCredits(
            listOf(StoredArtist.unlinked("i-dle"), StoredArtist("UCa", "I-DLE"), StoredArtist("UCa", "I-DLE"), StoredArtist("UCb", "B")),
        )
        assertEquals(listOf(StoredArtist("UCa", "I-DLE"), StoredArtist("UCb", "B")), out)
    }

    @Test
    fun duplicateCreditsAreStoredOnceUnderOneId() {
        val library = LibraryStore(Files.createTempDirectory("ot-dupes").toFile())
        // The same channel under both spellings, and two tag names that lower-case alike: this
        // used to throw on song_artist's key and leave the song half-credited.
        library.database.upsertSong(
            StoredSong(
                "s1", "Song", "A, A, x, X",
                artistList = listOf(
                    StoredArtist("MPLAUCa", "A"), StoredArtist("UCa", "A"),
                    StoredArtist.unlinked("x"), StoredArtist.unlinked("X"),
                ),
            )
        )
        assertEquals(listOf(StoredArtist("UCa", "A"), StoredArtist.unlinked("x")), library.database.songCredits("s1"))
    }

    @Test
    fun aRenameThatMakesTwoCreditsAlikeShowsTheNameOnce() {
        val library = LibraryStore(Files.createTempDirectory("ot-rename").toFile())
        library.database.upsertSong(
            StoredSong("s1", "Song", "(G)I-DLE, i-dle", artistList = listOf(StoredArtist("UCold", "(G)I-DLE"), StoredArtist("UCnew", "i-dle")))
        )
        library.database.renameArtist("UCold", "i-dle")
        assertEquals(listOf("i-dle"), library.database.songCredits("s1").map { it.name })
    }

    @Test
    fun unknownLocalArtistIsKept() {
        val credits = listOf(StoredArtist.unlinked("Garage Band"))
        assertEquals(credits, ArtistLinks.rewrite(credits, "Demo", emptyMap()))
    }

    @Test
    fun featuredArtistIsAddedOnlyWhenConfirmed() {
        // The clause is looked up whole first; only once that is no artist are its names tried.
        val links = listOf(link("Dua Lipa & Nobody", null), link("Dua Lipa", "UCdua"), link("Nobody", null))
            .associateBy { it.nameKey }
        val credits = listOf(StoredArtist("UCelton", "Elton John"))
        val out = ArtistLinks.rewrite(credits, "Cold Heart (feat. Dua Lipa & Nobody)", links)
        assertEquals(listOf(StoredArtist("UCelton", "Elton John"), StoredArtist("UCdua", "Dua Lipa")), out)
    }

    @Test
    fun combinedCreditSplitsOnlyOnceKnownNotToBeOneArtist() {
        val credit = listOf(StoredArtist.unlinked("A & B"))
        val halvesOnly = listOf(link("A", "UCa"), link("B", "UCb")).associateBy { it.nameKey }
        // Not yet known whether "A & B" is an act in its own right: left alone.
        assertEquals(credit, ArtistLinks.rewrite(credit, "x", halvesOnly))
        val confirmed = (halvesOnly.values + link("A & B", null)).associateBy { it.nameKey }
        assertEquals(
            listOf(StoredArtist("UCa", "A"), StoredArtist("UCb", "B")),
            ArtistLinks.rewrite(credit, "x", confirmed),
        )
        // And a duo that is one act keeps its own channel.
        val duo = listOf(link("A & B", "UCab")).associateBy { it.nameKey }
        assertEquals(listOf(StoredArtist("UCab", "A & B")), ArtistLinks.rewrite(credit, "x", duo))
    }

    @Test
    fun linksSurviveARewriteOfTheSong() {
        val library = LibraryStore(Files.createTempDirectory("ot-links").toFile())
        library.database.putArtistLink(link("Coldplay", "UCcold"))
        // A rescan writes the tag's artist again; the stored credit should still be the channel.
        library.database.upsertSong(
            StoredSong("local:1", "Yellow", "Coldplay", artistList = listOf(StoredArtist.unlinked("Coldplay")))
        )
        assertEquals(listOf(StoredArtist("UCcold", "Coldplay")), library.database.songCredits("local:1"))
    }

    /** Live: OT_PROBE set. Does a real local name and a real "feat." resolve? */
    @Test
    fun liveLinking() = runBlocking {
        assumeTrue(System.getenv("OT_PROBE") != null)
        com.zionhuang.innertube.YouTube.locale = com.zionhuang.innertube.models.YouTubeLocale(gl = "US", hl = "en")
        com.zionhuang.innertube.YouTube.visitorData = com.zionhuang.innertube.YouTube.visitorData().getOrThrow()
        val library = LibraryStore(Files.createTempDirectory("ot-links-live").toFile())
        library.database.upsertSong(
            StoredSong("local:a", "Cold Heart (feat. Dua Lipa)", "Elton John", artistList = listOf(StoredArtist.unlinked("Elton John")))
        )
        ArtistLinks.enrich(library, listOf("local:a"))
        val credits = library.database.songCredits("local:a")
        println("credits: $credits")
        assertEquals(listOf("Elton John", "Dua Lipa"), credits.map { it.name })
        assert(credits.all { it.linkable }) { "both should be real channels: $credits" }
    }
}
