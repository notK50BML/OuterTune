/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.SongDetailsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.SongDetail
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * "Song details": what shows under a song's title on its row, and in what order.
 *
 * The same dialog as Player buttons - drag to order, tap to switch on or off, a number on each
 * switched-on item giving its place - with a song row as the preview, since a row is what it sets.
 * Top of the list comes first on the line.
 */
@Composable
fun SongDetailsArrangement() {
    var stored by rememberPreference(SongDetailsKey, defaultValue = "")
    var open by rememberSaveable { mutableStateOf(false) }
    val current = SongDetail.decode(stored)

    PreferenceEntry(
        title = { Text(stringResource(R.string.song_details)) },
        description = current.map { stringResource(it.title) }.joinToString()
            .ifEmpty { stringResource(R.string.song_details_title_only) },
        icon = { Icon(Icons.AutoMirrored.Rounded.Notes, null) },
        onClick = { open = true },
    )
    if (!open) return

    // Every detail, the chosen ones first in their order, then the rest switched off.
    val items = remember { mutableStateListOf<Pair<SongDetail, Boolean>>() }
    fun load(chosen: List<SongDetail>) {
        items.clear()
        items += chosen.map { it to true }
        items += SongDetail.entries.filterNot { it in chosen }.map { it to false }
    }
    LaunchedEffect(Unit) { load(current) }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items.add(to.index, items.removeAt(from.index))
    }
    val chosen = items.filter { it.second }.map { it.first }

    ActionPromptDialog(
        title = stringResource(R.string.song_details),
        onDismiss = { open = false },
        onConfirm = {
            stored = SongDetail.encode(chosen)
            open = false
        },
        onReset = { load(SongDetail.decode(SongDetail.DEFAULT)) },
        onCancel = { open = false },
    ) {
        // A row as it will look, with a sample song that has all three details to show.
        Text(
            stringResource(R.string.player_buttons_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            ListItem(
                title = SAMPLE_TITLE,
                subtitle = SongDetail.line(chosen, SAMPLE_ARTISTS, SAMPLE_ALBUM, SAMPLE_LENGTH),
                thumbnailContent = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(ListThumbnailSize)
                            .clip(RoundedCornerShape(ThumbnailCornerRadius))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                },
            )
        }

        InfoLabel(stringResource(R.string.song_details_order_hint))
        InfoLabel(stringResource(R.string.song_details_album_hint))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(vertical = 12.dp)
                .heightIn(max = 360.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        ) {
            itemsIndexed(items, key = { _, item -> item.first.name }) { _, item ->
                ReorderableItem(state = reorderState, key = item.first.name) {
                    val (detail, on) = item
                    val position = chosen.indexOf(detail)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { items[items.indexOf(item)] = detail to !on }
                            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        // Its place on the line - or nothing while it is off.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (on) 0f else 0.6f), CircleShape),
                        ) {
                            if (on) {
                                Text(
                                    "${position + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(detail.title),
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = null,
                            modifier = Modifier.draggableHandle(),
                        )
                    }
                }
            }
        }
    }
}

// The preview's song. A real one, so the line reads as a real row would - not translated, as a
// song's own title and credits never are.
private const val SAMPLE_TITLE = "Instant Crush"
private const val SAMPLE_ARTISTS = "Daft Punk, Julian Casablancas"
private const val SAMPLE_ALBUM = "Random Access Memories"
private const val SAMPLE_LENGTH = "5:37"
