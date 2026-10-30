/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Choosing and ordering the player's title-row buttons - the phone's "Player buttons" dialog.
 *
 * The result is a row and this is a column, so the two are tied together on screen: a preview of
 * the row sits above the list, and each switched-on item carries its position counted from the
 * left. Top of the list is leftmost. Reordered by each row's handle, as on the phone.
 */
@Composable
fun PlayerButtonsDialog(
    current: List<PlayerButtonKind>,
    onSave: (List<PlayerButtonKind>) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Player buttons",
    /** The buttons on offer - every one, or for files on this computer only those that apply. */
    available: List<PlayerButtonKind> = PlayerButtonKind.entries,
    /** What Reset goes back to. */
    defaults: List<PlayerButtonKind> = PlayerButtonKind.decode(PlayerButtonKind.DEFAULT),
) {
    val items = remember {
        mutableStateListOf<Pair<PlayerButtonKind, Boolean>>().apply {
            addAll(current.filter { it in available }.map { it to true })
            addAll(available.filterNot { it in current }.map { it to false })
        }
    }
    var refused by remember { mutableStateOf(false) }
    val chosen = items.filter { it.second }.map { it.first }

    fun reset() {
        items.clear()
        items.addAll(defaults.map { it to true })
        items.addAll(available.filterNot { it in defaults }.map { it to false })
        refused = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = ::reset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(modifier = Modifier.width(460.dp)) {
                Text(
                    "Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "♪  Song title",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    chosen.forEach { PreviewDisc(it) }
                }
                Hint(
                    "Top of the list is the leftmost button. Click to turn on or off, drag the handle " +
                        "to reorder. Up to ${PlayerButtonKind.MAX}."
                )
                if (refused) Hint("Up to ${PlayerButtonKind.MAX} buttons. Turn one off first.", error = true)
                if (PlayerButtonKind.MENU !in chosen) {
                    Hint("Without More options, anything not shown as a button cannot be reached from the player.", error = true)
                }
                Spacer(modifier = Modifier.height(8.dp))
                ReorderableColumn(
                    items = items,
                    key = { it.first },
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState()),
                ) { index, (button, on), handle ->
                        val position = chosen.indexOf(button)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!on && chosen.size >= PlayerButtonKind.MAX) {
                                        refused = true
                                    } else {
                                        refused = false
                                        items[index] = button to !on
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = if (on) 0f else 0.6f),
                                        CircleShape,
                                    ),
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
                            PreviewDisc(button, dimmed = !on)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                button.label,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            DragHandle(handle)
                        }
                }
            }
        },
    )
}

@Composable
private fun PreviewDisc(button: PlayerButtonKind, dimmed: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dimmed) 0.35f else 1f)),
    ) {
        Icon(button.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
    }
}

/** A line of guidance under a button editor's preview - shared with MiniPlayerButtonsDialog. */
@Composable
internal fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
