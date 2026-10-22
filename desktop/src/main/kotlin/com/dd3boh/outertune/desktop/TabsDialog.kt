/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

/**
 * Which sections the rail shows, and in what order - the phone's tab picker. Top of the list is the
 * top of the rail. At least one stays on; Settings is always at the bottom and is not listed.
 */
@Composable
fun TabsDialog(current: List<NavTab>, onSave: (List<NavTab>) -> Unit, onDismiss: () -> Unit) {
    val items = remember {
        mutableStateListOf<Pair<NavTab, Boolean>>().apply {
            addAll(current.map { it to true })
            addAll(NavTab.choosable.filterNot { it in current }.map { it to false })
        }
    }
    val chosen = items.filter { it.second }.map { it.first }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tabs") },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onSave(chosen) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val defaults = NavTab.decode(NavTab.DEFAULT)
                    items.clear()
                    items.addAll(defaults.map { it to true })
                    items.addAll(NavTab.choosable.filterNot { it in defaults }.map { it to false })
                }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(modifier = Modifier.width(380.dp)) {
                Text(
                    "Top of the list is the top of the rail. Settings is always at the bottom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                ReorderableColumn(
                    items = items,
                    key = { it.first },
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                ) { index, (tab, on), handle ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // The last one cannot be turned off: a rail with nothing on it
                                    // leaves nowhere to go but Settings.
                                    if (!(on && chosen.size == 1)) items[index] = tab to !on
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(tab.icon(), contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(tab.label, modifier = Modifier.weight(1f))
                            DragHandle(handle)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                }
            }
        },
    )
}
