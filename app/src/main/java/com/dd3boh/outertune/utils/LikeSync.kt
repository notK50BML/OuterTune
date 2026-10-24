/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes unliking forgiving.
 *
 * An accidental unlike used to be costly twice over: liking the song again stamped it with the
 * current time, so it moved from wherever it had been in Liked songs to the top; and the unlike had
 * already gone to YouTube Music, whose own list reordered to match. Now:
 * - A song unliked this session remembers when it was first liked, and liking it again restores
 *   that date - it goes back exactly where it was.
 * - The change is sent to YouTube Music a minute later rather than at once. Changing your mind
 *   inside that minute sends nothing at all, so YouTube's list never moves. Anything still waiting
 *   is sent when the app goes to the background ([flushNow]), so nothing is lost to a closed app.
 * - [unliked] tells the UI, which offers Undo for a few seconds.
 */
object LikeSync {

    const val PUSH_DELAY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** When each song unliked this session was liked - restored if it is liked again. */
    private val likedDates = ConcurrentHashMap<String, LocalDateTime>()

    /** When each song was unliked, for Undo. */
    private val unlikedAt = ConcurrentHashMap<String, Long>()

    /** A change waiting to be sent, and what YouTube Music had before it. */
    private class Pending(val job: Job, val remoteLiked: Boolean)

    private val pending = ConcurrentHashMap<String, Pending>()

    private val _unliked = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** A song id each time a song is unliked. */
    val unliked: SharedFlow<String> = _unliked

    /** Called as a song is unliked: keeps its liked date and announces it. */
    fun rememberUnliked(id: String, likedDate: LocalDateTime?) {
        if (likedDate != null) likedDates[id] = likedDate
        unlikedAt[id] = System.currentTimeMillis()
        _unliked.tryEmit(id)
    }

    /** The date to like [id] with: the original one when it was unliked this session, else now. */
    fun likedDateFor(id: String): LocalDateTime {
        unlikedAt.remove(id)
        return likedDates.remove(id) ?: LocalDateTime.now()
    }

    /** Songs unliked within the last [windowMs] - what Undo puts back. */
    fun unlikedWithin(windowMs: Long): List<String> {
        val since = System.currentTimeMillis() - windowMs
        return unlikedAt.filterValues { it >= since }.keys.toList()
    }

    /** Sends [liked] for [id] to YouTube Music after [PUSH_DELAY_MS], unless it is changed back first. */
    fun push(id: String, liked: Boolean) {
        val previous = pending.remove(id)
        previous?.job?.cancel()
        val remote = previous?.remoteLiked ?: !liked
        // Back to what YouTube already has: nothing to send.
        if (remote == liked) return
        val job = scope.launch {
            delay(PUSH_DELAY_MS)
            pending.remove(id)
            runCatching { YouTube.likeVideo(id, liked) }.onFailure { reportException(it) }
        }
        pending[id] = Pending(job, remote)
    }

    /** Sends everything still waiting, now - for the app going to the background. */
    fun flushNow() {
        for ((id, waiting) in pending.entries.toList()) {
            if (!pending.remove(id, waiting)) continue
            waiting.job.cancel()
            scope.launch { runCatching { YouTube.likeVideo(id, !waiting.remoteLiked) }.onFailure { reportException(it) } }
        }
    }
}
