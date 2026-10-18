/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zionhuang.innertube.models.SongItem

/**
 * Quick buttons a song row can show beside its ⋮ - chosen in Settings > Appearance > Song rows.
 * Each is also in the ⋮ menu, which holds everything.
 */
enum class RowButton(val code: Char, val label: String) {
    LIKE('L', "Like"),
    DOWNLOAD('D', "Download"),
    PLAY_NEXT('Q', "Play next"),
    ADD_TO_QUEUE('U', "Add to queue"),
    PLAYLIST('P', "Add to playlist"),
    RADIO('R', "Start radio"),
    SHARE('H', "Copy link"),
    ;

    companion object {
        fun decode(stored: String) = stored.mapNotNull { c -> entries.firstOrNull { it.code == c } }.distinct()
        fun encode(buttons: List<RowButton>) = buttons.distinct().joinToString("") { it.code.toString() }
    }
}

/** What song rows show at their end: [buttons], then the ⋮ when [menu]. */
data class RowButtons(val buttons: List<RowButton> = emptyList(), val menu: Boolean = true)

val LocalRowButtons = compositionLocalOf { RowButtons() }

/** The end of a song row: the chosen quick buttons, then the ⋮ that opens [SongMenuPanel]. */
@Composable
fun RowActions(song: SongItem) {
    val actions = LocalSongActions.current ?: return
    val config = LocalRowButtons.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (button in config.buttons) {
            val (icon, label) = rowButtonLook(button, song, actions)
            IconButton(onClick = { runRowButton(button, song, actions) }, modifier = Modifier.size(36.dp)) {
                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        if (config.menu) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
                    Icon(OuterTuneIcons.moreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                if (open) SongMenuPanel(song, actions, anchorEnd = true, onDismiss = { open = false })
            }
        }
    }
}

private fun rowButtonLook(button: RowButton, song: SongItem, actions: SongActions): Pair<ImageVector, String> = when (button) {
    RowButton.LIKE -> if (actions.isLiked(song.id)) OuterTuneIcons.favorite to "Unlike" else OuterTuneIcons.favoriteBorder to "Like"
    RowButton.DOWNLOAD -> OuterTuneIcons.download to if (actions.isDownloaded(song.id)) "Remove download" else "Download"
    RowButton.PLAY_NEXT -> OuterTuneIcons.queueMusic to "Play next"
    RowButton.ADD_TO_QUEUE -> OuterTuneIcons.queueMusic to "Add to queue"
    RowButton.PLAYLIST -> OuterTuneIcons.playlistAdd to "Add to playlist"
    RowButton.RADIO -> OuterTuneIcons.radio to "Start radio"
    RowButton.SHARE -> OuterTuneIcons.share to "Copy link"
}

private fun runRowButton(button: RowButton, song: SongItem, actions: SongActions) = when (button) {
    RowButton.LIKE -> actions.toggleLike(song)
    RowButton.DOWNLOAD -> actions.toggleDownload(song)
    RowButton.PLAY_NEXT -> actions.playNext(song)
    RowButton.ADD_TO_QUEUE -> actions.addToQueue(song)
    RowButton.PLAYLIST -> actions.addToPlaylist(song)
    RowButton.RADIO -> actions.startRadio(song)
    RowButton.SHARE -> actions.copyLink(song)
}

/**
 * The phone's song menu as a desktop panel: the song at the top - cover, title, artist, a heart -
 * and every option underneath as a tile, in a rounded container that grows out of where it was
 * opened from and folds back into it.
 *
 * @param anchorEnd opens leftwards from the ⋮ at a row's end; otherwise downwards from the pointer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongMenuPanel(
    song: SongItem,
    actions: SongActions,
    anchorEnd: Boolean,
    offset: IntOffset = IntOffset.Zero,
    onDismiss: () -> Unit,
) {
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    // Folded away, then gone: dismissing runs the closing animation first.
    LaunchedEffect(shown.currentState, shown.targetState) {
        if (!shown.targetState && !shown.currentState) onDismiss()
    }
    val close = { shown.targetState = false }

    Popup(
        alignment = if (anchorEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = offset,
        onDismissRequest = close,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(
                tween(180, easing = FastOutSlowInEasing),
                initialScale = 0.6f,
                transformOrigin = if (anchorEnd) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f),
            ) + fadeIn(tween(140)),
            exit = scaleOut(
                tween(130),
                targetScale = 0.7f,
                transformOrigin = if (anchorEnd) TransformOrigin(1f, 0f) else TransformOrigin(0f, 0f),
            ) + fadeOut(tween(110)),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.width(340.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // The song, as the phone's menu heads itself.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        Artwork(song.thumbnail, size = 48.dp, cornerRadius = 8.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                song.artists.joinToString { it.name }.ifBlank { "Unknown artist" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val liked = actions.isLiked(song.id)
                        IconButton(onClick = { actions.toggleLike(song) }) {
                            Icon(
                                if (liked) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder,
                                contentDescription = if (liked) "Unlike" else "Like",
                                tint = if (liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxItemsInEachRow = 3,
                    ) {
                        @Composable
                        fun tile(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(102.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = enabled) { onClick(); close() }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(36.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 1f else 0.4f)),
                                ) {
                                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
                                )
                            }
                        }
                        tile("Play next", OuterTuneIcons.queueMusic) { actions.playNext(song) }
                        tile("Add to queue", OuterTuneIcons.queueMusic) { actions.addToQueue(song) }
                        tile("Start radio", OuterTuneIcons.radio) { actions.startRadio(song) }
                        tile("Add to playlist", OuterTuneIcons.playlistAdd) { actions.addToPlaylist(song) }
                        tile(if (actions.isDownloaded(song.id)) "Remove download" else "Download", OuterTuneIcons.download) {
                            actions.toggleDownload(song)
                        }
                        tile("Copy link", OuterTuneIcons.share) { actions.copyLink(song) }
                        val artist = song.artists.firstOrNull()
                        tile("Go to artist", OuterTuneIcons.person, enabled = artist != null) {
                            artist?.let { actions.openArtist(it.id?.let { id -> StoredArtist(id, it.name) } ?: StoredArtist.unlinked(it.name)) }
                        }
                        tile("Go to album", OuterTuneIcons.album, enabled = song.album?.id != null) {
                            song.album?.id?.let(actions.openAlbum)
                        }
                    }
                }
            }
        }
    }
}
