package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Songs shown with the library's credits under the artists' current names - see SongItem.shown. */
class ShownCreditsTest {

    private fun item(id: String, vararg artists: Pair<String?, String>) = SongItem(
        id = id, title = "Song $id", artists = artists.map { (aid, name) -> Artist(name = name, id = aid) },
        album = null, duration = 200, thumbnail = "", explicit = false, endpoint = null,
    )

    @Test
    fun `a song the library has is shown with the library's credits`() {
        val index = CreditIndex(
            songs = mapOf("s" to listOf(StoredArtist("UCa", "A"), StoredArtist("UCb", "B"))),
            names = mapOf("UCa" to "A", "UCb" to "B"),
        )
        // YouTube credited the pair as one unlinked name; the library has them split.
        val shown = item("s", null to "A & B").shown(index)
        assertEquals(listOf("A", "B"), shown.artists.map { it.name })
        assertEquals(listOf("UCa", "UCb"), shown.artists.map { it.id })
    }

    @Test
    fun `a song the library does not have takes its artists' current names`() {
        val index = CreditIndex(songs = emptyMap(), names = mapOf("UCidle" to "i-dle"))
        val shown = item("new", "UCidle" to "(G)I-DLE", "UCother" to "Someone - Topic").shown(index)
        assertEquals(listOf("i-dle", "Someone"), shown.artists.map { it.name })
    }

    @Test
    fun `a name read from the channel is kept when songs spell it the old way`() {
        val library = LibraryStore(Files.createTempDirectory("credits").toFile())
        val old = StoredSong("one", "One", "(G)I-DLE", artistList = listOf(StoredArtist("UCidle", "(G)I-DLE")))
        library.recordPlay(old)
        library.renameArtist("UCidle", "i-dle")
        // Another song from before the rename, crediting the same channel under its old name.
        library.recordPlay(StoredSong("two", "Two", "(G)I-DLE", artistList = listOf(StoredArtist("UCidle", "(G)I-DLE"))))
        val index = library.database.creditIndex()
        assertEquals("i-dle", index.names["UCidle"])
        assertEquals(listOf("i-dle"), index.songs["one"]?.map { it.name })
        assertEquals(listOf("i-dle"), index.songs["two"]?.map { it.name })
        library.close()
    }
}
