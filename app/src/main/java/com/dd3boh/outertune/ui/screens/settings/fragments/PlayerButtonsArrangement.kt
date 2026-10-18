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
import androidx.compose.foundation.layout.Column
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
import com.dd3boh.outertune.constants.PlayerButtonsKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.player.PlayerButton
import com.dd3boh.outertune.ui.player.PlayerButtonDisc
import com.dd3boh.outertune.ui.player.rememberPlayerButtons
import com.dd3boh.outertune.utils.rememberPreference
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * "Player buttons": which of the player's actions sit beside the song title, and in what order.
 *
 * The same dialog as Tab arrangement - drag to order, tap to switch on or off - with two additions,
 * because the result is a row and the list is a column: a preview of the row above the list, and a
 * number on each switched-on item giving its position from the left. Top of the list is leftmost.
 */
@Composable
fun PlayerButtonsArrangement() {
    var stored by rememberPreference(PlayerButtonsKey, defaultValue = "")
    val current = rememberPlayerButtons()
    var open by rememberSaveable { mutableStateOf(false) }

    PreferenceEntry(
        title = { Text(stringResource(R.string.player_buttons)) },
        description = stringResource(R.string.player_buttons_desc),
        icon = { Icon(Icons.Rounded.SmartButton, null) },
        onClick = { open = true },
    )
    if (!open) return

    // Every button, the chosen ones first in their order, then the rest switched off.
    val items = remember { mutableStateListOf<Pair<PlayerButton, Boolean>>() }
    fun load(chosen: List<PlayerButton>) {
        items.clear()
        items += chosen.map { it to true }
        items += PlayerButton.CHOOSABLE.filterNot { it in chosen }.map { it to false }
    }
    LaunchedEffect(Unit) { load(current) }
    var refused by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        items.add(to.index, items.removeAt(from.index))
    }
    val chosen = items.filter { it.second }.map { it.first }

    ActionPromptDialog(
        title = stringResource(R.string.player_buttons),
        onDismiss = { open = false },
        onConfirm = {
            stored = PlayerButton.encode(chosen)
            open = false
        },
        onReset = { load(PlayerButton.decode(PlayerButton.DEFAULT)) },
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
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "♪",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            chosen.forEach { PlayerButtonDisc(button = it, state = null, onClick = null, size = 32.dp) }
        }

        InfoLabel(stringResource(R.string.player_buttons_order_hint, PlayerButton.MAX))
        if (refused) InfoLabel(stringResource(R.string.player_buttons_full, PlayerButton.MAX))
        if (PlayerButton.MENU !in chosen) InfoLabel(stringResource(R.string.player_buttons_no_menu))

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
                            .clickable {
                                val index = items.indexOf(item)
                                if (!on && chosen.size >= PlayerButton.MAX) {
                                    refused = true
                                } else {
                                    refused = false
                                    items[index] = button to !on
                                }
                            }
                            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
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
                        PlayerButtonDisc(button = button, state = null, onClick = null, size = 28.dp)
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
