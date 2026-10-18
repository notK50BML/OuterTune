/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The queue's title, which opens the queues kept aside - the phone's queue board. Picking one
 * plays it from where it was left; the current one is kept aside in its place.
 */
@Composable
fun QueueSwitcher(
    title: String,
    saved: List<SavedQueue>,
    colour: Color,
    style: TextStyle,
    onSwitch: (SavedQueue) -> Unit,
    onRemove: (SavedQueue) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(enabled = saved.isNotEmpty()) { open = true },
        ) {
            Text(title, style = style, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (saved.isNotEmpty()) {
                Icon(
                    OuterTuneIcons.expandLess,
                    contentDescription = "Other queues",
                    tint = colour.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(180f),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Other queues",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // Newest first, as the phone lists them.
            saved.asReversed().forEach { queue ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(queue.state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (queue.state.current?.title?.let { "$it · " } ?: "") +
                                    "${queue.state.songs.size} " + if (queue.state.songs.size == 1) "song" else "songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = { open = false; onSwitch(queue) },
                    trailingIcon = {
                        IconButton(onClick = { onRemove(queue) }, modifier = Modifier.size(32.dp)) {
                            Icon(OuterTuneIcons.close, contentDescription = "Remove this queue", modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
    }
}
