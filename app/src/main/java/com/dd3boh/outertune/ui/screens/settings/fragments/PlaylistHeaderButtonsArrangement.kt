/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.SmartButton
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
import com.dd3boh.outertune.constants.PlaylistHeaderButtonsKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.playlist.PlaylistHeaderButton
import com.dd3boh.outertune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * "Playlist buttons": which buttons sit under a playlist's title on its page, and in what order.
 *
 * The same dialog as Player buttons and Song details - drag to order, tap to switch on or off, a
 * number on each switched-on item giving its place - with the row of icons as the preview. Top of
 * the list is leftmost. There is no upper limit: the buttons wrap onto a second line rather than
 * run off the edge, so choosing all six costs space, not buttons.
 */
@Composable
fun PlaylistHeaderButtonsArrangement() {
    var stored by rememberPreference(PlaylistHeaderButtonsKey, defaultValue = "")
    var open by rememberSaveable { mutableStateOf(false) }
    val current = PlaylistHeaderButton.decode(stored)

    PreferenceEntry(
        title = { Text(stringResource(R.string.playlist_header_buttons)) },
        description = current.map { stringResource(it.title) }.joinToString()
            .ifEmpty { stringResource(R.string.playlist_header_buttons_none) },
        icon = { Icon(Icons.Rounded.SmartButton, null) },
        onClick = { open = true },
    )
    if (!open) return

    // Every button, the chosen ones first in their order, then the rest switched off.
    val items = remember { mutableStateListOf<Pair<PlaylistHeaderButton, Boolean>>() }
    fun load(chosen: List<PlaylistHeaderButton>) {
        items.clear()
        items += chosen.map { it to true }
        items += PlaylistHeaderButton.entries.filterNot { it in chosen }.map { it to false }
    }
    LaunchedEffect(Unit) { load(current) }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items.add(to.index, items.removeAt(from.index))
    }
    val chosen = items.filter { it.second }.map { it.first }

    ActionPromptDialog(
        title = stringResource(R.string.playlist_header_buttons),
        onDismiss = { open = false },
        onConfirm = {
            stored = PlaylistHeaderButton.encode(chosen)
            open = false
        },
        onReset = { load(PlaylistHeaderButton.decode(PlaylistHeaderButton.DEFAULT)) },
        onCancel = { open = false },
    ) {
        // The row as it will look, left to right.
        Text(
            stringResource(R.string.player_buttons_preview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (chosen.isEmpty()) {
                Text(
                    stringResource(R.string.playlist_header_buttons_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            chosen.forEach { Icon(it.icon, contentDescription = stringResource(it.title)) }
        }

        InfoLabel(stringResource(R.string.playlist_header_buttons_order_hint))
        InfoLabel(stringResource(R.string.playlist_header_buttons_saved_hint))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(vertical = 12.dp)
                .heightIn(max = 360.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        ) {
            itemsIndexed(items, key = { _, item -> item.first.name }) { _, item ->
                ReorderableItem(state = reorderState, key = item.first.name) {
                    val (button, on) = item
                    val position = chosen.indexOf(button)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { items[items.indexOf(item)] = button to !on }
                            .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    ) {
                        // Its place in the row, counted from the left - or nothing while it is off.
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
                        Icon(
                            button.icon,
                            contentDescription = null,
                            tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(button.title),
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
