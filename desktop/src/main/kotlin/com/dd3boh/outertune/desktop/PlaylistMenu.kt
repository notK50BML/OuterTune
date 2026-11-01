/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a playlist's ⋮ can do - the phone's playlist menu, in its order.
 *
 * Every entry is nullable and a null one is left out, as [PlayerActions] does: a playlist on this
 * computer has no link to copy and nothing to sync, and a YouTube playlist cannot be renamed here.
 */
data class PlaylistActions(
    val playNext: (() -> Unit)? = null,
    val addToQueue: (() -> Unit)? = null,
    val addToPlaylist: (() -> Unit)? = null,
    val startRadio: (() -> Unit)? = null,
    /** Fetch it again from YouTube Music - the phone's Sync. */
    val sync: (() -> Unit)? = null,
    /** True while [sync] is running: its tile spins. */
    val syncing: Boolean = false,
    val rename: (() -> Unit)? = null,
    val exportM3u: (() -> Unit)? = null,
    val copyLink: (() -> Unit)? = null,
    /** Asked about first - a whole playlist is a lot to lose to a misclick. */
    val delete: (() -> Unit)? = null,
    /** Shown in the delete confirmation. */
    val name: String = "",
)

/** The ⋮ beside a playlist's title, opening its menu in the same panel a song's ⋮ does. */
@Composable
fun PlaylistMenuButton(actions: PlaylistActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(OuterTuneIcons.moreVert, contentDescription = "Playlist options", modifier = Modifier.size(22.dp))
        }
        if (open) {
            val tiles = buildList {
                fun tile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: (() -> Unit)?, busy: Boolean = false) {
                    if (action != null) add(MenuTile(label, icon, enabled = !busy, busy = busy, onClick = action))
                }
                tile("Play next", OuterTuneIcons.playlistPlay, actions.playNext)
                tile("Add to queue", OuterTuneIcons.queueMusicRounded, actions.addToQueue)
                tile("Add to playlist", OuterTuneIcons.playlistAdd, actions.addToPlaylist)
                tile("Start radio", OuterTuneIcons.radio, actions.startRadio)
                tile(if (actions.syncing) "Syncing…" else "Sync", OuterTuneIcons.refresh, actions.sync, busy = actions.syncing)
                tile("Edit", OuterTuneIcons.edit, actions.rename)
                tile("Export M3U", OuterTuneIcons.download, actions.exportM3u)
                tile("Copy link", OuterTuneIcons.share, actions.copyLink)
                actions.delete?.let { add(MenuTile("Delete", OuterTuneIcons.delete) { confirmDelete = true }) }
            }
            MenuPanel(tiles = tiles, onDismiss = { open = false })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${actions.name}\" will be deleted. The songs stay in your library.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.delete?.invoke() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** One song as an M3U entry wants it: its length, credits and title, and where to find it. */
data class M3uEntry(val durationSeconds: Int, val artists: String, val title: String, val location: String)

/**
 * The phone's M3U export, line for line - `#EXTINF:<seconds>,<artists> - <title>` then the song's
 * YouTube link, or its file for a local song - so the phone's import reads a file made here.
 * A length that is not known is written as -1, which is what M3U means by it.
 */
fun m3u(entries: List<M3uEntry>): String = buildString {
    append("#EXTM3U\n")
    for (entry in entries) {
        append("#EXTINF:").append(entry.durationSeconds).append(',')
            .append(entry.artists).append(" - ").append(entry.title).append('\n')
        append(entry.location).append('\n')
    }
}

/** A song's M3U location: its file when it is one on this computer, its YouTube link otherwise. */
fun m3uLocation(songId: String, localFile: java.io.File?): String =
    localFile?.absolutePath ?: "https://youtube.com/watch?v=$songId"
