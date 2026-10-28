package com.dd3boh.outertune.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the player's lyrics follow the song that is playing.
 *
 * Worth pinning because the failure is quiet: moving to a song whose lyrics had not been fetched yet
 * kept the previous song's lyrics on screen, since nothing replaced them until the new song's first
 * value arrived. Lyrics are stood in for by plain strings here - the switching does not care what
 * they are, and the real ones need Android's XML parser just to exist.
 */
class SongLyricsTest {

    /** A song as the player hands it over; [rev] makes rebuilds of the same song unequal, as on device. */
    private data class Song(val id: String, val rev: Double = Math.random())

    /** Per-song lyric sources the test controls: a gate that releases one value when completed. */
    private val gates = HashMap<String, CompletableDeferred<String?>>()
    private fun gate(id: String) = gates.getOrPut(id) { CompletableDeferred() }
    private fun resolve(song: Song): Flow<String?> = flow { emit(gate(song.id).await()) }

    private fun CoroutineScope.collect(source: Flow<Song?>): MutableList<SongLyrics<String>?> {
        val seen = mutableListOf<SongLyrics<String>?>()
        launch { source.lyricsPerSong(idOf = { it.id }, resolve = ::resolve).collect { seen += it } }
        return seen
    }

    private suspend fun settle() = repeat(20) { yield() }

    @Test
    fun `a song change shows the new song as loading before its lyrics are known`() = runBlocking {
        val song = MutableStateFlow<Song?>(Song("a"))
        val seen = collect(song)
        settle()
        gate("a").complete("lyrics of a")
        settle()
        assertEquals(SongLyrics("a", "lyrics of a"), seen.last())

        // b's lyrics are not known yet, and may not be for a while.
        song.value = Song("b")
        settle()
        assertEquals(SongLyrics<String>("b", null), seen.last())

        gate("b").complete("lyrics of b")
        settle()
        assertEquals(SongLyrics("b", "lyrics of b"), seen.last())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a song that turns out to have no lyrics does not keep the previous song's`() = runBlocking {
        val song = MutableStateFlow<Song?>(Song("a"))
        val seen = collect(song)
        gate("a").complete("lyrics of a")
        settle()

        song.value = Song("b")
        gate("b").complete(null)
        settle()
        assertEquals(SongLyrics<String>("b", null), seen.last())
        assertEquals(listOf("a"), seen.filter { it?.lyrics != null }.map { it!!.mediaId })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a slow lookup for the previous song cannot land under the next one`() = runBlocking {
        val song = MutableStateFlow<Song?>(Song("a"))
        val seen = collect(song)
        settle()

        // a is still being looked up when the player moves on.
        song.value = Song("b")
        settle()
        gate("a").complete("lyrics of a")
        settle()

        assertEquals(SongLyrics<String>("b", null), seen.last())
        assertEquals(emptyList<SongLyrics<String>?>(), seen.filter { it?.lyrics != null })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `rebuilding the playing song's metadata does not flash the loading state`() = runBlocking {
        val song = MutableStateFlow<Song?>(Song("a"))
        val seen = collect(song)
        gate("a").complete("lyrics of a")
        settle()
        val before = seen.size

        song.value = Song("a") // same song, unequal object
        settle()
        assertEquals(before, seen.size)
        assertEquals(SongLyrics("a", "lyrics of a"), seen.last())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `nothing playing means no lyrics`() = runBlocking {
        val song = MutableStateFlow<Song?>(Song("a"))
        val seen = collect(song)
        gate("a").complete("lyrics of a")
        settle()

        song.value = null
        settle()
        assertNull(seen.last())
        coroutineContext.cancelChildren()
    }
}
