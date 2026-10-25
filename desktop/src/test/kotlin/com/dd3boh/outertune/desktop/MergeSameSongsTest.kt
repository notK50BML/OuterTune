package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/** Stats counting one song once, whatever ids it was played under. */
class MergeSameSongsTest {

    private fun played(id: String, title: String, artists: String, plays: Int, at: Long = 0) =
        PlayedSong(StoredSong(id, title, artists), plays, at)

    @Test
    fun `the same title by the same artists is one song`() {
        val merged = mergeSameSongs(
            listOf(
                played("single", "Gimme Dat Love", "i-dle", 30),
                played("album", "Gimme Dat Love", "i-dle", 12),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(42, merged.single().plays)
        assertEquals("the version played most stands for it", "single", merged.single().song.id)
    }

    @Test
    fun `case and surrounding spaces do not split a song`() {
        val merged = mergeSameSongs(
            listOf(
                played("a", "Hello", "Adele", 3),
                played("b", " hello ", "ADELE", 2),
            )
        )
        assertEquals(listOf(5), merged.map { it.plays })
    }

    @Test
    fun `the same title by someone else stays separate`() {
        val merged = mergeSameSongs(
            listOf(
                played("a", "DAHLIA", "i-dle", 5),
                played("b", "DAHLIA", "X JAPAN", 4),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `a different version is a different song`() {
        // Exact titles, so a remix or a live take keeps its own place.
        val merged = mergeSameSongs(
            listOf(
                played("a", "CRAZY", "LE SSERAFIM", 5),
                played("b", "CRAZY (English ver.)", "LE SSERAFIM", 4),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `merged plays can lift a song above one played more under a single id`() {
        val merged = mergeSameSongs(
            listOf(
                played("x", "Other", "Someone", 10),
                played("a", "Song", "Artist", 6),
                played("b", "Song", "Artist", 6),
            )
        )
        assertEquals(listOf("Song", "Other"), merged.map { it.song.title })
    }

    @Test
    fun `ties go to the most recently played`() {
        val merged = mergeSameSongs(
            listOf(
                played("old", "Old", "A", 3, at = 100),
                played("new", "New", "B", 3, at = 200),
            )
        )
        assertEquals(listOf("New", "Old"), merged.map { it.song.title })
    }
}
