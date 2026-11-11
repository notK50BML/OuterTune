/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.R
import com.dd3boh.outertune.playback.removeDownloadKeepingCache
import com.dd3boh.outertune.utils.getDownloadState

/**
 * The one question asked before a whole list's downloads go: a playlist's or an album's, from its
 * menu or from the buttons at the top of its page.
 *
 * Removing a list's downloads used to happen on the first tap. The button that does it is the same
 * icon, in the same place, as the one that shows the list is downloaded, so a tap meant to check
 * the state could wipe gigabytes that then had to be fetched again over the network. One song's
 * download is still removed straight away, since that is cheap to undo and asked for song by song.
 *
 * The count is of the songs that actually have a download (finished or in progress), not of the
 * list: "Remove 3 downloaded songs" on a 200-song playlist with three downloaded says what will
 * happen. It falls back to the list's size when nothing is known yet, rather than claiming zero.
 *
 * Confirming removes every one of [songIds] (removing a song that has no download is a no-op), then
 * runs [onConfirm] for whatever else the caller does at that point.
 */
@Composable
fun RemoveDownloadsDialog(
    songIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit = {},
) {
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val count = remember(songIds, downloads) {
        songIds.distinct()
            .count { getDownloadState(downloads[it]) != Download.STATE_STOPPED }
            .takeIf { it > 0 } ?: songIds.distinct().size
    }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(Icons.Rounded.OfflinePin, contentDescription = null) },
        title = { Text(stringResource(R.string.remove_downloads_title)) },
        content = {
            Text(
                text = pluralStringResource(R.plurals.remove_n_downloads_confirm, count, count),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }

            TextButton(
                onClick = {
                    onDismiss()
                    songIds.forEach { removeDownloadKeepingCache(it) }
                    onConfirm()
                }
            ) {
                Text(text = stringResource(R.string.action_remove))
            }
        }
    )
}
