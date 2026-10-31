package com.zionhuang.innertube

import com.zionhuang.innertube.models.AlbumItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What counts as another version of an album on its page, and what as a similar album - see YouTube.albumShelves. */
class OtherVersionsTitleTest {
    @Test
    fun `editions of one album share a base title`() {
        val base = YouTube.baseAlbumTitle("1989")
        assertEquals(base, YouTube.baseAlbumTitle("1989 (Taylor's Version)"))
        assertEquals(base, YouTube.baseAlbumTitle("1989 [Deluxe Edition]"))
        assertEquals(base, YouTube.baseAlbumTitle("1989 - Live"))
        assertEquals(YouTube.baseAlbumTitle("Midnights"), YouTube.baseAlbumTitle("Midnights (3am Edition)"))
    }

    @Test
    fun `a different album does not`() {
        assertNotEquals(YouTube.baseAlbumTitle("1989"), YouTube.baseAlbumTitle("Red"))
        assertNotEquals(YouTube.baseAlbumTitle("Folklore"), YouTube.baseAlbumTitle("Evermore"))
    }

    private fun album(id: String, title: String) =
        AlbumItem(browseId = id, playlistId = "PL$id", title = title, artists = null, thumbnail = "")

    @Test
    fun `versions come from any shelf, recommendations from the rest`() {
        val (versions, similar) = YouTube.albumShelves(
            title = "1989",
            browseId = "this",
            shelves = listOf(
                // The versions shelf, with a renamed edition on it that is no recommendation.
                listOf(album("tv", "1989 (Taylor's Version)"), album("dlx", "1989 [Deluxe]"), album("jp", "Ichi-kyu-hachi-kyu")),
                // A recommendations shelf, which can carry a version too - and this album itself.
                listOf(album("red", "Red"), album("this", "1989"), album("live", "1989 - Live"), album("folk", "Folklore")),
            ),
        )
        assertEquals(listOf("tv", "dlx", "live"), versions.map { it.browseId })
        assertEquals(listOf("red", "folk"), similar.map { it.browseId })
    }

    @Test
    fun `an album without versions has only recommendations`() {
        val (versions, similar) = YouTube.albumShelves(
            title = "Discovery",
            browseId = "this",
            shelves = listOf(listOf(album("ram", "Random Access Memories"), album("hb", "Homework"), album("ram", "Random Access Memories"))),
        )
        assertEquals(emptyList<String>(), versions.map { it.browseId })
        assertEquals(listOf("ram", "hb"), similar.map { it.browseId })
    }
}
