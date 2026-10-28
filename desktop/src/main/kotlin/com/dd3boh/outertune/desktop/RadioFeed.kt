/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.pages.NextResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a radio or station gets more songs once its first page runs low - see PlayerQueue.play's
 * `more`. YouTube sends a radio a page at a time; only the first was ever asked for, so a radio
 * stopped after fifty or so songs.
 *
 * The next page when YouTube offers one. When it does not, or asking fails, a fresh radio from one
 * of the last few songs - the phone's way of keeping a radio going.
 */
fun radioFeed(first: NextResult): suspend (List<SongItem>) -> List<SongItem> {
    var endpoint = first.endpoint
    var continuation = first.continuation
    return { songs ->
        withContext(Dispatchers.IO) {
            val page = continuation?.let { YouTube.next(endpoint, it).getOrNull() }
                ?: songs.takeLast(4).randomOrNull()?.let { seed ->
                    YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")).getOrNull()
                }
            page?.let {
                continuation = it.continuation
                endpoint = it.endpoint
            }
            page?.items.orEmpty()
        }
    }
}
