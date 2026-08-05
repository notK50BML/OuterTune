/*
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import androidx.compose.ui.util.fastAny
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.constants.MAX_COIL_JOBS
import com.dd3boh.outertune.constants.MAX_DL_JOBS
import com.dd3boh.outertune.constants.MAX_LM_SCANNER_JOBS
import com.dd3boh.outertune.constants.MAX_YTM_CONTENT_JOBS
import com.dd3boh.outertune.constants.MAX_YTM_SYNC_JOBS
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.ui.utils.resize
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.newFixedThreadPoolContext
import java.time.LocalDateTime


/**
 *
 * coilCoroutine: Coil image resolution
 * lmScannerCoroutine: Heave processing tasks such as local media scan/extraction and downloads processing
 *
 */
// This will go down to be the best idea I've had or this will crash and burn like the Hindenburg.

val lmScannerCoroutine = Dispatchers.IO.limitedParallelism(MAX_LM_SCANNER_JOBS)

val dlCoroutine = Dispatchers.IO.limitedParallelism(MAX_DL_JOBS)

val coilCoroutine = Dispatchers.IO.limitedParallelism(MAX_COIL_JOBS)

val syncCoroutine = Dispatchers.IO.limitedParallelism(MAX_YTM_SYNC_JOBS)

val ytmCoroutine = Dispatchers.IO.limitedParallelism(MAX_YTM_CONTENT_JOBS)

@OptIn(DelicateCoroutinesApi::class)
val playerCoroutine = newFixedThreadPoolContext(4, "player_service_offload")

fun reportException(throwable: Throwable) {
    throwable.printStackTrace()
}

/**
 * Converts LocalDateTime (LDT) used my DownloadUtil to androidx.media3.exoplayer.offline.Download.
 *
 * Returns:
 * Download.STATE_COMPLETED if downloaded
 * Download.STATE_DOWNLOADING if downloading
 * Download.STATE_STOPPED otherwise
 */
fun getDownloadState(localDateTime: LocalDateTime?): Int {
    if (localDateTime == null) return Download.STATE_STOPPED
    if (localDateTime > DownloadUtil.STATE_DOWNLOADING) {
        return Download.STATE_COMPLETED
    } else if (localDateTime == DownloadUtil.STATE_DOWNLOADING) {
        return Download.STATE_DOWNLOADING
    } else {
        return Download.STATE_STOPPED // aka not downloaded
    }
}

/**
 * Converts LocalDateTime (LDT) used my DownloadUtil to androidx.media3.exoplayer.offline.Download.
 *
 * Returns:
 * Download.STATE_COMPLETED if ALL elements are downloaded
 * Download.STATE_DOWNLOADING if ANY elements downloading
 * Download.STATE_STOPPED otherwise
 */
fun getDownloadState(localDateTimes: List<LocalDateTime?>): Int {
    if (localDateTimes.fastAny { it == null }) return Download.STATE_STOPPED
    if (localDateTimes.all { it!! > DownloadUtil.STATE_DOWNLOADING }) {
        return Download.STATE_COMPLETED
    } else if (localDateTimes.any { it == DownloadUtil.STATE_DOWNLOADING }) {
        return Download.STATE_DOWNLOADING
    } else {
        return Download.STATE_STOPPED
    }
}

/**
 * Mirrors [com.dd3boh.outertune.constants.HighResArtworkKey] so the non-composable artwork
 * helpers below can consult it without a Context. Kept in sync by a DataStore collector in
 * [com.dd3boh.outertune.App].
 */
@Volatile
var highResArtwork: Boolean = true

/**
 * Ask the CDN for artwork at the size it will actually be drawn at.
 *
 * YouTube returns whatever thumbnail size suited the response it came from — library and browse
 * responses routinely hand back 60-226px art, which the previous code passed to Coil verbatim.
 * Coil then upscaled it to fill the view, which is why album covers looked soft. Rewriting the
 * `=wW-hH` (or `*default.jpg`) portion of the URL costs nothing and gets a sharp image.
 *
 * A non-positive [sizeX] means "size unknown", in which case the URL is left alone.
 */
fun remoteArtwork(thumbnailUrl: String, sizeX: Int = -1, sizeY: Int = -1): String {
    if (!highResArtwork || sizeX <= 0) return thumbnailUrl
    return runCatching { thumbnailUrl.resize(sizeX, if (sizeY > 0) sizeY else sizeX) }
        .getOrDefault(thumbnailUrl)
}

fun getThumbnailModel(thumbnailUrl: String, sizeX: Int = -1, sizeY: Int = -1): Any? {
    return if (thumbnailUrl.startsWith("/storage/")) {
        LocalArtworkPath(thumbnailUrl, sizeX, sizeY)
    } else {
        remoteArtwork(thumbnailUrl, sizeX, sizeY)
    }
}
