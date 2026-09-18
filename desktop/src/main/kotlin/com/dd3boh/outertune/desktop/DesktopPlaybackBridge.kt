/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real player behind a [PlaybackBridge], for the desktop client.
 *
 * The counterpart to the Android app's `MediaPlaybackBridge` - same seam, same responsibility of
 * translating it to a real player and nothing else. Everything difficult about syncing lives in
 * [HostSession] and [FollowerSession], which know nothing about [DesktopPlayer]; if logic starts
 * accumulating here it belongs on the other side of the seam, where it can be tested.
 *
 * Two differences from the phone worth being explicit about:
 *
 * **[SharedTrack.isLocal] is always false.** The desktop has no local-file playback yet (see
 * `PARITY.md`) - everything it plays comes from YouTube, so there is nothing to flag.
 *
 * **No main-thread requirement of its own.** Media3 enforces one on Android, which is why
 * [ListenTogetherManager] there runs on a main-dispatched scope. [DesktopPlayer] does not have that
 * constraint - its mutable state is plain `StateFlow`s - but the manager still runs on
 * `Dispatchers.Swing` here, because [playerQueue] is read from Compose state and Compose expects its
 * own state to change on the UI thread.
 */
class DesktopPlaybackBridge(
    private val player: DesktopPlayer,
    private val playerQueue: PlayerQueue,
    private val library: LibraryStore,
) : PlaybackBridge {

    override val currentTrack: SharedTrack?
        get() = playerQueue.state.value.current?.let {
            SharedTrack(
                videoId = it.id,
                title = it.title,
                artist = it.artists.joinToString { artist -> artist.name },
                durationMs = (it.duration ?: 0) * 1000L,
                isLocal = false,
            )
        }

    /**
     * Whether audio is actually coming out, not merely whether it was asked for.
     *
     * [DesktopPlayer.state] already makes this distinction the way the phone's
     * `Player.isPlaying` does: it is [PlaybackState.Playing] only once decoding has actually started
     * writing to the line, and [PlaybackState.Loading] the rest of the time a track is buffering - so
     * a follower reading this is paused for the same stall the host is in, rather than running ahead
     * of a host that has gone silent to rebuffer.
     */
    override val isPlaying: Boolean
        get() = player.state.value is PlaybackState.Playing

    override val speed: Float
        get() = player.timeStretch.tempo

    override fun positionMs(): Long = player.positionMs.value

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun setPlayWhenReady(playing: Boolean) {
        // Absolute rather than a toggle: the caller wants "make it so", and DesktopPlayer only
        // exposes a toggle plus a one-directional pause. Nothing happens if already in the state
        // asked for, or if the player is not in a state either applies to (Idle/Loading/Failed) -
        // there is nothing to play or pause yet.
        when {
            playing && player.state.value is PlaybackState.Paused -> player.togglePause()
            !playing && player.state.value is PlaybackState.Playing -> player.pause()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        // Pitch is untouched - TimeStretch is WSOLA-based and changes tempo independently of pitch,
        // same property Media3's Sonic-backed stretch gives the phone. That is the entire reason a
        // few percent of drift correction can be inaudible.
        player.timeStretch.tempo = speed
    }

    override suspend fun playTrack(videoId: String, positionMs: () -> Long): Boolean {
        val item = withContext(Dispatchers.IO) {
            // The local library first. A song already known here - played before, or downloaded -
            // starts immediately and needs no network at all, where looking it up on YouTube first
            // would add seconds of silence to something meant to feel simultaneous.
            library.database.song(videoId)?.toItem()
                ?: YouTube.queue(videoIds = listOf(videoId)).getOrNull()?.firstOrNull()
        } ?: return false

        // Replaces the queue with this one song, the same as the phone: following destroys whatever
        // this device's own queue was, which is the correct trade for "play what the host plays".
        // Started at the host's position via startAtMs rather than from zero and seeked afterward -
        // a seek right after a fresh load is an extra rebuffer, and it would be heard.
        //
        // The position is read here rather than taken as an argument, after the lookup above: the
        // host kept playing throughout it, and a value decided beforehand would land this device
        // exactly that far behind. See PlaybackBridge.playTrack.
        playerQueue.play(listOf(item), 0, title = "Listening along", startAtMs = positionMs())
        return true
    }
}
