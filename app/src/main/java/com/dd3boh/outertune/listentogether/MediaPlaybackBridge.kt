/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The real player behind a [PlaybackBridge].
 *
 * Thin on purpose. Everything difficult about syncing lives in the sessions, which know nothing
 * about Media3; this only translates. If logic starts accumulating here it belongs on the other side
 * of the seam, where it can be tested.
 *
 * **Every method must be called on the main thread.** Media3 enforces that itself, and the sessions
 * satisfy it by being launched on a main-dispatched scope - see [ListenTogetherManager]. The one
 * exception is [playTrack], which is suspending and moves to IO for the lookup before coming back.
 */
class MediaPlaybackBridge(
    private val player: Player,
    private val database: MusicDatabase,
    private val playQueue: (Queue) -> Unit,
) : PlaybackBridge {

    override val currentTrack: SharedTrack?
        get() = player.currentMetadata?.let {
            SharedTrack(
                videoId = it.id,
                title = it.title,
                artist = it.artists.joinToString { artist -> artist.name },
                durationMs = it.duration * 1000L,
                isLocal = it.isLocal,
            )
        }

    override val isPlaying: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    override val speed: Float
        get() = player.playbackParameters.speed

    override fun positionMs(): Long = player.currentPosition

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    override fun setPlayWhenReady(playing: Boolean) {
        player.playWhenReady = playing
    }

    override fun setPlaybackSpeed(speed: Float) {
        // Pitch deliberately left at 1 while speed moves. Media3 then time-stretches through Sonic
        // rather than resampling, so a few percent of correction changes tempo without touching
        // pitch - which is the entire reason drift can be corrected without anyone hearing it.
        player.playbackParameters = PlaybackParameters(speed, 1f)
    }

    override suspend fun playTrack(videoId: String, positionMs: Long): Boolean {
        val metadata = withContext(Dispatchers.IO) {
            // The local database first. A song already in the library - downloaded, or simply seen
            // before - starts immediately and works with no connection at all, where a network
            // lookup would add seconds of silence to something that is meant to feel simultaneous.
            database.song(videoId).first()?.toMediaMetadata()
                ?: YouTube.queue(listOf(videoId)).getOrNull()?.firstOrNull()?.toMediaMetadata()
        } ?: return false

        // Started at the host's position rather than from the beginning and seeked afterwards: a
        // seek right after a load is an extra rebuffer, and it would be heard.
        playQueue(
            ListQueue(
                title = metadata.title,
                items = listOf(metadata),
                position = positionMs.coerceAtLeast(0),
            )
        )
        return true
    }
}
