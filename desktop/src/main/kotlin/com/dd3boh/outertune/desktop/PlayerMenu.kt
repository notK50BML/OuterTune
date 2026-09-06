/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the player's overflow menu can do with the current song.
 *
 * Every entry is nullable and a null one simply does not appear. That is the whole design: the
 * desktop build does not yet have album pages, downloads or lyrics, and a menu listing them anyway
 * would be a menu that lies. As each lands, the caller passes a callback and the item shows up with
 * no change here.
 *
 * The order below is the Android player's `PlayerMenu`, so anyone who knows one knows the other.
 */
data class PlayerActions(
    val onStartRadio: (() -> Unit)? = null,
    val onAddToQueue: (() -> Unit)? = null,
    val onAddToPlaylist: (() -> Unit)? = null,
    val onDownload: (() -> Unit)? = null,
    val inLibrary: Boolean = false,
    val onToggleLibrary: (() -> Unit)? = null,
    val onViewArtist: (() -> Unit)? = null,
    val onViewAlbum: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onToggleLyrics: (() -> Unit)? = null,
    val onDetails: (() -> Unit)? = null,
    val onEqualizer: (() -> Unit)? = null,
) {
    /** Whether there is anything to show. A menu button that opens an empty menu is worse than none. */
    val any: Boolean
        get() = listOf(
            onStartRadio, onAddToQueue, onAddToPlaylist, onDownload, onToggleLibrary,
            onViewArtist, onViewAlbum, onShare, onToggleLyrics, onDetails, onEqualizer,
        ).any { it != null }
}

/**
 * The overflow menu, as a text list rather than the icon grid the phone uses.
 *
 * A grid of labelled icons is right on a touch screen, where the target has to be thumb-sized and
 * there is no pointer to aim. On a desktop the same list is a dropdown of plain text, which is what
 * every other application here does and what the right mouse button already trains people to expect.
 * Keeping the phone's grid would have meant inventing eight glyphs to say things a word says better.
 */
@Composable
fun PlayerOverflowMenu(
    actions: PlayerActions,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(buttonSize)) {
            Icon(
                OuterTuneIcons.moreVert,
                contentDescription = "More",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: String, action: (() -> Unit)?) {
                if (action == null) return
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }

            item("Start radio", actions.onStartRadio)
            item("Add to queue", actions.onAddToQueue)
            item("Add to playlist", actions.onAddToPlaylist)
            item("Download", actions.onDownload)
            item(
                if (actions.inLibrary) "Remove from library" else "Add to library",
                actions.onToggleLibrary,
            )
            item("View artist", actions.onViewArtist)
            item("View album", actions.onViewAlbum)
            item("Share", actions.onShare)
            item("Toggle lyrics", actions.onToggleLyrics)
            item("Details", actions.onDetails)
            item("Equaliser", actions.onEqualizer)
        }
    }
}
