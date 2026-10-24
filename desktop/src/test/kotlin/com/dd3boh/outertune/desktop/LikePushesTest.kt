package com.dd3boh.outertune.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID

/**
 * Likes reaching YouTube Music a minute late: nothing sent for a change taken back, only the final
 * state for one changed several times, everything waiting sent as the window closes, and a failed
 * send retried rather than lost. Runs the real [LikePushes] with its wait shortened and its sender
 * recorded instead of sent.
 */
class LikePushesTest {

    private val sent = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
    private var failuresLeft = 0

    @Before
    fun setUp() {
        LikePushes.delayMs = DELAY_MS
        LikePushes.retryDelaysMs = listOf(20L, 20L)
        LikePushes.send = { id, liked ->
            if (failuresLeft > 0) {
                failuresLeft--
                error("network down")
            }
            sent += id to liked
        }
    }

    @After
    fun tearDown() {
        LikePushes.delayMs = LikePushes.DELAY_MS
    }

    private fun id() = UUID.randomUUID().toString()

    private fun settle() = Thread.sleep(DELAY_MS * 4)

    @Test
    fun `an unlike is sent after the wait, once`() {
        val song = id()
        LikePushes.schedule(song, liked = false)
        assertTrue(sent.none { it.first == song })
        assertTrue(LikePushes.isPending(song))
        settle()
        assertEquals(listOf(song to false), sent.filter { it.first == song })
        assertFalse(LikePushes.isPending(song))
    }

    @Test
    fun `an unlike undone inside the wait sends nothing`() {
        val song = id()
        LikePushes.schedule(song, liked = false)
        LikePushes.schedule(song, liked = true)
        assertFalse(LikePushes.isPending(song))
        settle()
        assertTrue(sent.none { it.first == song })
    }

    @Test
    fun `only where you ended up is sent`() {
        val song = id()
        LikePushes.schedule(song, liked = true)
        LikePushes.schedule(song, liked = false)
        LikePushes.schedule(song, liked = true)
        settle()
        assertEquals(listOf(song to true), sent.filter { it.first == song })
    }

    @Test
    fun `closing the window sends what is waiting`() {
        val song = id()
        LikePushes.delayMs = 60_000
        LikePushes.schedule(song, liked = false)
        LikePushes.flushBlocking()
        assertEquals(listOf(song to false), sent.filter { it.first == song })
        assertFalse(LikePushes.isPending(song))
    }

    @Test
    fun `a failed send is retried rather than lost`() {
        val song = id()
        failuresLeft = 2
        LikePushes.schedule(song, liked = false)
        settle()
        Thread.sleep(200)
        assertEquals(listOf(song to false), sent.filter { it.first == song })
    }

    private companion object {
        const val DELAY_MS = 100L
    }
}
