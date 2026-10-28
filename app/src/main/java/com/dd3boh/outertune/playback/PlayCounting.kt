/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

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
 * Whether a play counted here is also put in the account's YouTube Music history.
 *
 * Not a station's pick: Stats leaves those out, and reads the account's history back in as plays
 * made elsewhere - one sent there came straight back, so every station pick reappeared. The
 * desktop never sent them. Not a file on this phone, which YouTube has never heard of; and only
 * signed in, since without the account's cookie the ping lands in nobody's history.
 */
fun sharesPlayWithAccount(fromStation: Boolean, isLocal: Boolean, signedIn: Boolean, remotePaused: Boolean): Boolean =
    !fromStation && !isLocal && signedIn && !remotePaused
