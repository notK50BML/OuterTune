/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.SongItem
import kotlin.math.roundToInt

/**
 * What can be done to any song, wherever it is shown.
 *
 * Provided once, at the top of the window, through [LocalSongActions] - so a row on Home, in a
 * playlist, on an artist page or in Stats offers the same menu without each screen being handed a
 * dozen callbacks it only passes along.
 */
class SongActions(
    val playNext: (SongItem) -> Unit,
    val addToQueue: (SongItem) -> Unit,
    val startRadio: (SongItem) -> Unit,
    val addToPlaylist: (SongItem) -> Unit,
    val toggleLike: (SongItem) -> Unit,
    val isLiked: (String) -> Boolean,
    val toggleDownload: (SongItem) -> Unit,
    val isDownloaded: (String) -> Boolean,
    val openArtist: (StoredArtist) -> Unit,
    val openAlbum: (String) -> Unit,
    val copyLink: (SongItem) -> Unit,
    /** For a file on this computer: out of the library, the file left where it is. */
    val removeFromLibrary: (SongItem) -> Unit = {},
)

val LocalSongActions = compositionLocalOf<SongActions?> { null }

/**
 * [content] with a right-click menu for [song]: the phone's long-press song menu, as a desktop
 * expects it - opening where the pointer is.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SongMenuArea(song: SongItem, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val actions = LocalSongActions.current
    var at by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier.onPointerEvent(PointerEventType.Press) { event ->
            if (actions != null && event.buttons.isSecondaryPressed) {
                at = event.changes.first().position
                event.changes.forEach { it.consume() }
            }
        },
    ) {
        content()
        val anchor = at
        if (actions != null && anchor != null) {
            // The same panel as the row's ⋮, opening where the click was.
            SongMenuPanel(
                song = song,
                actions = actions,
                anchorEnd = false,
                offset = IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()),
                onDismiss = { at = null },
            )
        }
    }
}

@Composable
private fun SongMenuItems(song: SongItem, actions: SongActions, close: () -> Unit) {
    @Composable
    fun entry(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
            enabled = enabled,
            onClick = {
                onClick()
                close()
            },
        )
    }

    entry("Play next", OuterTuneIcons.playlistPlay) { actions.playNext(song) }
    entry("Add to queue", OuterTuneIcons.queueMusicRounded) { actions.addToQueue(song) }
    entry("Start radio", OuterTuneIcons.radio) { actions.startRadio(song) }
    HorizontalDivider()
    entry(if (actions.isLiked(song.id)) "Unlike" else "Like", if (actions.isLiked(song.id)) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder) {
        actions.toggleLike(song)
    }
    entry("Add to playlist", OuterTuneIcons.playlistAdd) { actions.addToPlaylist(song) }
    entry(
        if (actions.isDownloaded(song.id)) "Remove download" else "Download",
        if (actions.isDownloaded(song.id)) OuterTuneIcons.offlinePin else OuterTuneIcons.download,
    ) {
        actions.toggleDownload(song)
    }
    HorizontalDivider()
    val artist = song.artists.firstOrNull()
    entry("Go to artist", OuterTuneIcons.artist, enabled = artist != null) {
        artist?.let { actions.openArtist(it.id?.let { id -> StoredArtist(id, it.name) } ?: StoredArtist.unlinked(it.name)) }
    }
    entry("Go to album", OuterTuneIcons.albumRounded, enabled = song.album?.id != null) {
        song.album?.id?.let(actions.openAlbum)
    }
    entry("Copy link", OuterTuneIcons.share) { actions.copyLink(song) }
}
