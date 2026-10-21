/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    /** Whether the song is saved: the download entry then reads "Remove download", with the tick. */
    val downloaded: Boolean = false,
    val inLibrary: Boolean = false,
    val onToggleLibrary: (() -> Unit)? = null,
    val onViewArtist: (() -> Unit)? = null,
    /**
     * Every artist the song credits, with [onOpenArtist] to open one. With more than one, "View
     * artist" and the artist button ask which, instead of [onViewArtist] opening the first.
     */
    val artists: List<StoredArtist> = emptyList(),
    val onOpenArtist: ((StoredArtist) -> Unit)? = null,
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
 * The player's overflow menu: the same animated panel of tiles as a song row's ⋮ - the phone's
 * menu, opening out of the button below it, or above when the button is low in the window.
 */
@Composable
fun PlayerOverflowMenu(
    actions: PlayerActions,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
) {
    var open by remember { mutableStateOf(false) }
    var pickingArtist by remember { mutableStateOf(false) }
    val onOpenArtist = actions.onOpenArtist
    val chooseArtist = actions.artists.size > 1 && onOpenArtist != null

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(buttonSize)) {
            Icon(
                OuterTuneIcons.moreVert,
                contentDescription = "More",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        if (open) {
            val tiles = buildList {
                fun tile(label: String, icon: ImageVector, action: (() -> Unit)?) {
                    if (action != null) add(MenuTile(label, icon, onClick = action))
                }
                tile("Start radio", OuterTuneIcons.radio, actions.onStartRadio)
                tile("Add to queue", OuterTuneIcons.queueMusic, actions.onAddToQueue)
                tile("Add to playlist", OuterTuneIcons.playlistAdd, actions.onAddToPlaylist)
                tile(
                    if (actions.downloaded) "Remove download" else "Download",
                    if (actions.downloaded) OuterTuneIcons.offlinePin else OuterTuneIcons.download,
                    actions.onDownload,
                )
                tile(
                    if (actions.inLibrary) "Remove from library" else "Add to library",
                    if (actions.inLibrary) OuterTuneIcons.libraryAddCheck else OuterTuneIcons.libraryAdd,
                    actions.onToggleLibrary,
                )
                if (chooseArtist) {
                    add(MenuTile("View artist", OuterTuneIcons.person, keepOpen = true) { pickingArtist = true })
                } else {
                    tile("View artist", OuterTuneIcons.person, actions.onViewArtist)
                }
                tile("View album", OuterTuneIcons.album, actions.onViewAlbum)
                tile("Share", OuterTuneIcons.share, actions.onShare)
                tile("Lyrics", OuterTuneIcons.lyrics, actions.onToggleLyrics)
                tile("Details", OuterTuneIcons.info, actions.onDetails)
                tile("Equaliser", OuterTuneIcons.equalizer, actions.onEqualizer)
            }
            MenuPanel(
                tiles = tiles,
                onDismiss = { open = false; pickingArtist = false },
                page = if (pickingArtist && onOpenArtist != null) {
                    { close ->
                        ArtistPickerList(actions.artists, onBack = { pickingArtist = false }) {
                            onOpenArtist(it)
                            close()
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}
