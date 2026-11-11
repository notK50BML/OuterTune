/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.playlist

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlaylistHeaderButtonsKey
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.utils.rememberPreference

/**
 * The buttons under a playlist's title on its page, as Settings > Library and content > Playlist
 * buttons has them. Stored as one character each, in order, like Song details.
 *
 * A chosen button still shows only where it means something: Edit on a playlist that can be
 * renamed, Sync on one that lives on YouTube Music (or Liked songs, which is synced from it),
 * Download where there is something left to download. So the one setting serves every playlist
 * page, and choosing Sync does not put a dead button on a local playlist.
 *
 * Remove from library is not here. It is what a saved playlist (someone else's, kept in the
 * library) has instead of Edit, and is always shown there - a saved playlist with no way off the
 * library from its own page would be a trap.
 */
enum class PlaylistHeaderButton(val code: Char, @StringRes val title: Int, val icon: ImageVector) {
    EDIT('E', R.string.edit, Icons.Rounded.Edit),
    SYNC('S', R.string.action_sync, Icons.Rounded.Sync),
    DOWNLOAD('D', R.string.action_download, Icons.Rounded.Download),
    QUEUE('Q', R.string.add_to_queue, Icons.AutoMirrored.Rounded.QueueMusic),
    PLAY_NEXT('N', R.string.play_next, Icons.AutoMirrored.Rounded.PlaylistPlay),
    EXPORT('X', R.string.m3u_export, Icons.Rounded.Output),
    ;

    companion object {
        /** Edit, Sync, Download, Add to queue - what the playlist page showed before it had a choice. */
        const val DEFAULT = "ESDQ"

        /** Stored for "none at all" - an empty string is "never chosen", which means [DEFAULT]. */
        const val NONE = "-"

        /** The stored string as buttons, in order. Unknown or repeated characters are skipped. */
        fun decode(stored: String): List<PlaylistHeaderButton> =
            stored.ifEmpty { DEFAULT }.mapNotNull { c -> entries.firstOrNull { it.code == c } }.distinct()

        fun encode(buttons: List<PlaylistHeaderButton>): String =
            buttons.distinct().joinToString("") { it.code.toString() }.ifEmpty { NONE }
    }
}

/** The chosen playlist buttons, in order. */
@Composable
fun rememberPlaylistHeaderButtons(): List<PlaylistHeaderButton> {
    val stored by rememberPreference(PlaylistHeaderButtonsKey, defaultValue = "")
    return remember(stored) { PlaylistHeaderButton.decode(stored) }
}

/**
 * A list's download button: Download, a spinner while it downloads, the downloaded pin once every
 * song is on the device. The pin and the spinner both lead to [onRemoveDownload] - which every
 * caller routes through RemoveDownloadsDialog, since it takes the whole list's downloads with it.
 */
@Composable
fun PlaylistDownloadButton(
    @Download.State state: Int,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    when (state) {
        Download.STATE_COMPLETED -> {
            IconButton(onClick = onRemoveDownload) {
                Icon(
                    imageVector = Icons.Rounded.OfflinePin,
                    contentDescription = stringResource(R.string.remove_download)
                )
            }
        }

        Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> {
            IconButton(onClick = onRemoveDownload) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        else -> {
            IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.action_download)
                )
            }
        }
    }
}

/** One of the plain chosen buttons: its icon, with its name for TalkBack. */
@Composable
fun PlaylistHeaderIconButton(
    button: PlaylistHeaderButton,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = button.icon,
            contentDescription = stringResource(button.title)
        )
    }
}
