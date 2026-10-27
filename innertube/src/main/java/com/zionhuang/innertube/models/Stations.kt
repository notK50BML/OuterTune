/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube.models

/**
 * Whether [playlistId] is a station: a radio or mix YouTube makes up as it goes - a song's radio
 * (RDAMVM), a supermix (RDTMAK), an artist's or an album's radio - rather than a list somebody
 * made. All of those start "RD". RDCLAK is the exception: the curated playlists ("Kollywood
 * Hitlist") carry it, and those are playlists like any other.
 *
 * Songs heard from a station are not counted in Stats - both apps ask this.
 */
fun isStationPlaylistId(playlistId: String?): Boolean =
    playlistId != null && playlistId.startsWith("RD") && !playlistId.startsWith("RDCLAK")
