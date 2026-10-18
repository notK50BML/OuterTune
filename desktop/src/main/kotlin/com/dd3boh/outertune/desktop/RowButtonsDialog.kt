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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp

/**
 * What every song row shows at its end: any of the ⋮ menu's actions as quick buttons, in the
 * order ticked, and the ⋮ itself, which can be turned off - right-click still opens the menu.
 */
@Composable
fun RowButtonsDialog(
    current: List<RowButton>,
    menu: Boolean,
    onSave: (List<RowButton>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = remember { mutableStateListOf<RowButton>().apply { addAll(current) } }
    var showMenu by remember { mutableStateOf(menu) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song rows") },
        confirmButton = { TextButton(onClick = { onSave(chosen.toList(), showMenu) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { chosen.clear(); showMenu = true }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(modifier = Modifier.width(360.dp)) {
                Text(
                    "Buttons beside the ⋮ on every song row, left to right in the order ticked. All of them are in the ⋮ menu anyway.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
                    RowButton.entries.forEach { button ->
                        val on = button in chosen
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (on) chosen.remove(button) else chosen.add(button) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(button.label, modifier = Modifier.weight(1f))
                            if (on) Text("${chosen.indexOf(button) + 1}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show the ⋮ menu")
                        Text(
                            "Off, the menu is still a right-click away.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showMenu, onCheckedChange = { showMenu = it })
                }
            }
        },
    )
}
