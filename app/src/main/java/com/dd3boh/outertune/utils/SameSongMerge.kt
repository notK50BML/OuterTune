/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.entities.SongPlayStats

/**
 * The same song under different ids, counted as one in Stats: the same title by the same artists -
 * a single and its album copy, a re-upload, a local file of a song also played from YouTube.
 *
 * Compared without case or surrounding spaces, and otherwise exactly, so a different version or the
 * same title by someone else stays apart. Plays and listening time are added together, and the id
 * played most stands for the group, so its cover and the page it opens are the version actually
 * listened to. The desktop's Stats does the same - see its mergeSameSongs.
 */
fun mergeSameSongs(rows: List<SongPlayStats>): List<SongPlayStats> =
    rows.groupBy { stats ->
        stats.song.song.title.trim().lowercase() to
            stats.song.artists.joinToString(", ") { it.name.trim().lowercase() }
    }.values.map { same ->
        if (same.size == 1) return@map same.single()
        same.maxBy { it.totalPlays }.copy(
            totalPlays = same.sumOf { it.totalPlays },
            totalPlayTime = same.sumOf { it.totalPlayTime },
        )
    }
