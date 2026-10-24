/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import kotlinx.coroutines.CoroutineStart
import androidx.annotation.VisibleForTesting
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
 * - While a change is waiting ([isPending]) the liked-songs sync leaves that song alone: YouTube does
 *   not have the change yet, so its list would otherwise undo it - a song just unliked coming back,
 *   or one just liked disappearing.
 */
object LikeSync {

    const val PUSH_DELAY_MS = 60_000L

    /**
     * Retries for a send that fails, after these waits. A dropped request used to be dropped for
     * good, leaving the account disagreeing with the phone until the next sync quietly reverted the
     * change.
     */
    private val RETRY_DELAYS_MS = listOf(5_000L, 30_000L)

    // Settable for tests, which cannot wait a minute or reach YouTube.
    @VisibleForTesting
    internal var pushDelayMs = PUSH_DELAY_MS

    @VisibleForTesting
    internal var retryDelaysMs = RETRY_DELAYS_MS

    // likeVideo hands its failure back as a Result rather than throwing - unwrapped here, or a
    // failed send would count as a sent one.
    @VisibleForTesting
    internal var send: suspend (String, Boolean) -> Unit = { id, liked -> YouTube.likeVideo(id, liked).getOrThrow() }

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

    /**
     * Called as a song is unliked: keeps its liked date, and - for an unlike the user made, which is
     * what [offerUndo] means - offers it for Undo. The liked-songs sync unliking what YouTube no
     * longer has is not offered: that is not a mistake to take back, and Undo would like it again on
     * the account.
     */
    fun rememberUnliked(id: String, likedDate: LocalDateTime?, offerUndo: Boolean = true) {
        if (likedDate != null) likedDates[id] = likedDate
        if (!offerUndo) return
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

    /**
     * No longer offered for Undo - its bar has gone. Without this, a song whose bar had timed out was
     * counted again by the next unlike's bar, and that bar's Undo liked it again too.
     */
    fun forgetUndo(ids: Collection<String>) {
        ids.forEach { unlikedAt.remove(it) }
    }

    /** Whether a like or unlike of [id] is still waiting to reach YouTube Music. */
    fun isPending(id: String): Boolean = pending.containsKey(id)

    /** Sends [liked] for [id] to YouTube Music after [PUSH_DELAY_MS], unless it is changed back first. */
    fun push(id: String, liked: Boolean) {
        val previous = pending.remove(id)
        previous?.job?.cancel()
        val remote = previous?.remoteLiked ?: !liked
        // Back to what YouTube already has: nothing to send.
        if (remote == liked) return
        // Started only once it is in [pending], so it cannot finish and remove itself first.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(pushDelayMs)
            pending.remove(id)
            sendWithRetry(id, liked)
        }
        pending[id] = Pending(job, remote)
        job.start()
    }

    private suspend fun sendWithRetry(id: String, liked: Boolean) {
        for (attempt in 0..retryDelaysMs.size) {
            if (attempt > 0) delay(retryDelaysMs[attempt - 1])
            // A newer change for the same song has taken over; it sends its own.
            if (pending.containsKey(id)) return
            val result = runCatching { send(id, liked) }
            if (result.isSuccess) return
            if (attempt == retryDelaysMs.size) result.exceptionOrNull()?.let { reportException(it) }
        }
    }

    /** Sends everything still waiting, now - for the app going to the background. */
    fun flushNow() {
        for ((id, waiting) in pending.entries.toList()) {
            if (!pending.remove(id, waiting)) continue
            waiting.job.cancel()
            scope.launch { sendWithRetry(id, !waiting.remoteLiked) }
        }
    }
}
