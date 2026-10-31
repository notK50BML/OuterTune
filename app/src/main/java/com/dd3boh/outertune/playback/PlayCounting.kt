/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MultiQueueObject

/**
 * Whether [playedMs] of listening to a track [durationSeconds] long counts as a play - for Stats,
 * the play counts and YouTube's history.
 *
 * A track of known length counts once [minRatio] of it has been heard. One with no length - a live
 * stream, which is what a 24/7 radio station is, -1, null or 0 here - never counts: it is a station,
 * not a song, and the listener asked for stations to stay out of Stats; the desktop does the same.
 * The ratio alone got 0 wrong the other way: divided by it, a millisecond counted as a full play.
 */
fun countsAsPlay(playedMs: Long, durationSeconds: Int?, minRatio: Float): Boolean {
    if (playedMs <= 0 || durationSeconds == null || durationSeconds <= 0) return false
    return playedMs.toFloat() / (durationSeconds * 1000f) >= minRatio
}

/**
 * Whether the songs of [queue] are the listener's own picks, and so can count in Stats, the play
 * counts and the account's history. Not a station's, which chooses its songs itself (see
 * isStationPlaylistId); and not a Listen Together host's, played while following their session -
 * the host counts those on their own device, where they were chosen. Hosting changes nothing: the
 * host's queue is the host's own.
 */
fun isOwnPick(queue: MultiQueueObject?): Boolean = queue?.isStation != true && queue?.isFollowing != true

/**
 * Whether a play counted here is also put in the account's YouTube Music history.
 *
 * Only the listener's [ownPick] - not a station's, nor a Listen Together host's heard while
 * following them: Stats leaves those out, and reads the account's history back in as plays made
 * elsewhere - one sent there came straight back, so every station pick reappeared. The desktop
 * never sent them. Not a file on this phone, which YouTube has never heard of; and only signed in,
 * since without the account's cookie the ping lands in nobody's history.
 */
fun sharesPlayWithAccount(ownPick: Boolean, isLocal: Boolean, signedIn: Boolean, remotePaused: Boolean): Boolean =
    ownPick && !isLocal && signedIn && !remotePaused
