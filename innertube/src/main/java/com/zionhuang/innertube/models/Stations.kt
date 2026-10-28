/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.zionhuang.innertube.models

/**
 * Whether [playlistId] is a station: a mix YouTube makes up as it goes - a supermix (RDTMAK), an
 * artist's radio (RDEM), an album's or playlist's (RDAMPL), a personal mix (RDMM) - rather than a
 * list somebody made, or a song somebody picked. Those all start "RD", and three that do are not
 * stations:
 * - RDCLAK, the curated playlists ("Kollywood Hitlist"), which are playlists like any other;
 * - RDAO, an artist's shuffle, which is that artist's own songs;
 * - RDAMVM, one song's radio, which "Start radio" on a song makes too. It starts from a song the
 *   listener chose, and counting it one way from a song's menu and the other from a home card
 *   was the same radio counted two ways.
 *
 * Songs heard from a station are not counted in Stats - both apps ask this.
 */
fun isStationPlaylistId(playlistId: String?): Boolean =
    playlistId != null && playlistId.startsWith("RD") &&
        listOf("RDCLAK", "RDAO", "RDAMVM").none { playlistId.startsWith(it) }
