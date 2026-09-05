/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

/** What the host is playing, in the terms a follower needs to play it too. */
data class SharedTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    /**
     * A file on the host's own storage.
     *
     * A follower cannot play it - there is nothing to fetch - so the session shows what is playing
     * and stays quiet rather than failing repeatedly on every tick. Carried in the protocol
     * precisely so the follower can tell the difference between "cannot find this song" and "this
     * song was never available to me".
     */
    val isLocal: Boolean,
)

/**
 * Everything a session needs from the player, and nothing else.
 *
 * The point of the seam is that [HostSession] and [FollowerSession] hold the timing logic - the part
 * that is hard to get right and impossible to eyeball - and neither of them touches Media3, Hilt or
 * a Context. They can therefore be driven by a fake player in a unit test, at any speed, with a
 * scripted network on the other side.
 *
 * Kept as small as it is on purpose. Every method added here is one that has to be faked, and one
 * more way for a session to reach past the seam and become untestable again.
 */
interface PlaybackBridge {

    /** What is playing, or null when the queue is empty. */
    val currentTrack: SharedTrack?

    val isPlaying: Boolean

    /** Current playback rate. Normally 1, but a follower may be mid-correction. */
    val speed: Float

    /**
     * The player's own position reading.
     *
     * Coarse - it moves in steps of a few hundred milliseconds - which is why both ends pass it
     * through [PositionAnchor] rather than using it directly.
     */
    fun positionMs(): Long

    fun seekTo(positionMs: Long)

    fun setPlayWhenReady(playing: Boolean)

    /**
     * Sets the playback rate, for inaudible drift correction.
     *
     * Expected to time-stretch rather than resample, so pitch is unchanged - which is what makes a
     * few percent of correction imperceptible and the whole approach viable.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Switches to a song and starts it at [positionMs].
     *
     * Suspending because a follower will usually have to look the song up and buffer it, which can
     * take seconds. The caller treats that as normal rather than as a stall.
     *
     * @return true if the song is now playing. False means it could not be found, and the session
     *   should say so rather than silently continuing on the wrong track.
     */
    suspend fun playTrack(videoId: String, positionMs: Long): Boolean
}
