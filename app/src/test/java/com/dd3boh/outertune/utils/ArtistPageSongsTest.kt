/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.ArtistSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shelves of an artist page count as that artist's songs.
 *
 * The question matters because the answer is written to the database. A song taken from the wrong
 * shelf becomes a credit saying this artist performs a track they have nothing to do with, and
 * nothing afterwards knows it was invented - it looks exactly like a credit that came from the
 * sync.
 *
 * So the shape of the page is reproduced here rather than the strings on it: a `musicShelfRenderer`
 * song listing, whose rows carry an album, against the carousels either side of it, whose songs
 * `ArtistPage.fromMusicTwoRowItemRenderer` always builds with `album = null`.
 */
class ArtistPageSongsTest {

    private fun song(id: String, album: String?) = SongItem(
        id = id,
        title = "Song $id",
        artists = listOf(Artist(name = "Someone", id = "UCsomeone")),
        album = album?.let { Album(name = it, id = "MPRE$it") },
        thumbnail = "https://example.invalid/$id.jpg",
    )

    /** The Songs listing: rows from a musicShelfRenderer, which carry an album column. */
    private fun songShelf(vararg ids: String) =
        ArtistSection(title = "Songs", items = ids.map { song(it, album = "An Album") }, moreEndpoint = null)

    /** A carousel of songs - recommendations, other people's music. Never carries an album. */
    private fun carousel(title: String, vararg ids: String) =
        ArtistSection(title = title, items = ids.map { song(it, album = null) }, moreEndpoint = null)

    private fun page(vararg sections: ArtistSection) = ArtistPage(
        artist = ArtistItem(
            id = "UCartist",
            title = "The Artist",
            thumbnail = "https://example.invalid/artist.jpg",
            channelId = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        ),
        sections = sections.toList(),
        description = null,
    )

    @Test
    fun `songs from the Songs shelf are taken`() {
        assertEquals(listOf("a", "b"), page(songShelf("a", "b")).songShelfIds())
    }

    @Test
    fun `songs from a carousel are not taken`() {
        // The refusal this exists for. A carousel on an artist's page is other people's music -
        // "fans might also like", a playlist the artist appears on, a recommendation - and a song
        // there is on the page for a reason that has nothing to do with who performs it.
        assertTrue(page(carousel("Fans might also like", "x", "y")).songShelfIds().isEmpty())
    }

    @Test
    fun `a page mixing both takes only the shelf`() {
        // The realistic shape, and the one where a naive flatMap over every section goes wrong
        // without ever looking obviously wrong.
        val ids = page(
            carousel("Videos", "v1"),
            songShelf("a", "b"),
            carousel("Fans might also like", "x"),
        ).songShelfIds()
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `a shelf is recognised even when one of its rows has no album`() {
        // A single that belongs to no album still parses with album = null, so testing only the
        // first row would drop the entire shelf if that happened to be the one. The section is
        // judged as a whole, and everything in it comes along.
        val shelf = ArtistSection(
            title = "Songs",
            items = listOf(song("a", album = null), song("b", album = "An Album")),
            moreEndpoint = null,
        )
        assertEquals(listOf("a", "b"), page(shelf).songShelfIds())
    }

    @Test
    fun `a page with no song shelf yields nothing`() {
        assertTrue(page().songShelfIds().isEmpty())
    }

    @Test
    fun `a song listed twice is returned once`() {
        // song_artist_map is keyed on (songId, artistId), and the caller uses the count of ids to
        // decide whether there is anything to do at all.
        val ids = page(songShelf("a", "b"), songShelf("b", "c")).songShelfIds()
        assertEquals(listOf("a", "b", "c"), ids)
    }
}
