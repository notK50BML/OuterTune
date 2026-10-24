/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.util.Collections
import java.util.UUID

/**
 * The unlike safety net: Undo puts a song back where it was, YouTube Music hears about a change only
 * once it has stuck, and nothing that happens in between - a sync, a dropped request, the app going
 * to the background - loses or reverses what the user did.
 *
 * Runs the real [LikeSync] with its wait shortened and its sender recorded instead of sent.
 */
class LikeSyncTest {

    private val sent = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
    private var failuresLeft = 0

    @Before
    fun setUp() {
        LikeSync.pushDelayMs = DELAY_MS
        LikeSync.retryDelaysMs = listOf(20L, 20L)
        LikeSync.send = { id, liked ->
            if (failuresLeft > 0) {
                failuresLeft--
                error("network down")
            }
            sent += id to liked
        }
    }

    @After
    fun tearDown() {
        LikeSync.pushDelayMs = LikeSync.PUSH_DELAY_MS
    }

    /** Each test its own songs, since LikeSync is one object for the whole app. */
    private fun song(liked: Boolean, likedDate: LocalDateTime? = null, isLocal: Boolean = false) = SongEntity(
        id = UUID.randomUUID().toString(),
        title = "song",
        localPath = null,
        isLocal = isLocal,
        liked = liked,
        likedDate = likedDate,
    )

    private fun settle() = Thread.sleep(DELAY_MS * 4)

    // ---- order -------------------------------------------------------------------------------

    @Test
    fun `liking again restores the original liked date`() {
        val original = LocalDateTime.of(2024, 3, 1, 12, 0)
        val unliked = song(liked = true, likedDate = original).toggleLike()
        assertFalse(unliked.liked)
        assertNull(unliked.likedDate)

        val relike = unliked.toggleLike()
        assertTrue(relike.liked)
        assertEquals("back where it was in Liked songs, not on top", original, relike.likedDate)
    }

    @Test
    fun `a song liked for the first time is dated now`() {
        val before = LocalDateTime.now().minusSeconds(1)
        val liked = song(liked = false).toggleLike()
        assertTrue(liked.likedDate!!.isAfter(before))
    }

    @Test
    fun `the original date is used once - a later like is a new like`() {
        val original = LocalDateTime.of(2024, 3, 1, 12, 0)
        val relike = song(liked = true, likedDate = original).toggleLike().toggleLike()
        val second = relike.toggleLike().toggleLike()
        // Unliked again, its date is the one it had just been given back, which is still the
        // original - so it round-trips however many times it is undone.
        assertEquals(original, second.likedDate)
    }

    // ---- undo --------------------------------------------------------------------------------

    @Test
    fun `an unlike is offered for undo, and liking it again withdraws the offer`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        val unliked = s.toggleLike()
        assertTrue(s.id in LikeSync.unlikedWithin(10_000))
        unliked.toggleLike()
        assertFalse(s.id in LikeSync.unlikedWithin(10_000))
    }

    @Test
    fun `a song unliked by the sync is not offered for undo`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        s.localToggleLike()
        assertFalse(s.id in LikeSync.unlikedWithin(10_000))
    }

    @Test
    fun `once its bar has gone a song is not offered again by the next one`() {
        val first = song(liked = true, likedDate = LocalDateTime.now())
        first.toggleLike()
        LikeSync.forgetUndo(listOf(first.id))

        val second = song(liked = true, likedDate = LocalDateTime.now())
        second.toggleLike()
        val offered = LikeSync.unlikedWithin(10_000)
        assertTrue(second.id in offered)
        assertFalse("the first song's bar timed out; Undo must not bring it back", first.id in offered)
    }

    // ---- YouTube Music -----------------------------------------------------------------------

    @Test
    fun `an unlike reaches YouTube Music after the wait, once`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        s.toggleLike()
        assertTrue("nothing is sent at once", sent.none { it.first == s.id })
        assertTrue(LikeSync.isPending(s.id))
        settle()
        assertEquals(listOf(s.id to false), sent.filter { it.first == s.id })
        assertFalse(LikeSync.isPending(s.id))
    }

    @Test
    fun `an unlike undone inside the wait sends nothing at all`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        s.toggleLike().toggleLike()
        settle()
        assertTrue("YouTube's list must never move", sent.none { it.first == s.id })
        assertFalse(LikeSync.isPending(s.id))
    }

    @Test
    fun `changing your mind three times sends only where you ended up`() {
        val s = song(liked = false)
        s.toggleLike().toggleLike().toggleLike()
        settle()
        assertEquals(listOf(s.id to true), sent.filter { it.first == s.id })
    }

    @Test
    fun `leaving the app sends what is waiting straight away`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        LikeSync.pushDelayMs = 60_000
        s.toggleLike()
        LikeSync.flushNow()
        Thread.sleep(200)
        assertEquals(listOf(s.id to false), sent.filter { it.first == s.id })
        assertFalse(LikeSync.isPending(s.id))
    }

    @Test
    fun `a failed send is retried rather than lost`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        failuresLeft = 2
        s.toggleLike()
        settle()
        Thread.sleep(200)
        assertEquals(listOf(s.id to false), sent.filter { it.first == s.id })
    }

    @Test
    fun `a file on this phone is never sent to YouTube`() {
        val s = song(liked = true, likedDate = LocalDateTime.now(), isLocal = true)
        s.toggleLike()
        settle()
        assertTrue(sent.none { it.first == s.id })
        assertFalse(LikeSync.isPending(s.id))
    }

    @Test
    fun `the sync is told to leave a song alone while its change is waiting`() {
        val s = song(liked = true, likedDate = LocalDateTime.now())
        s.toggleLike()
        // SyncUtils skips pending songs in both directions - see its liked-songs sync.
        assertTrue(LikeSync.isPending(s.id))
        settle()
        assertFalse(LikeSync.isPending(s.id))
    }

    private companion object {
        const val DELAY_MS = 100L
    }
}
