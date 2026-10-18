/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Export and import of shareable settings profiles - see [Profiles]. Settings > Privacy & backup.
 *
 * @param onApplied after an import, for anything that reads settings once rather than live (the
 *   player's equaliser).
 */
@Composable
fun ProfilesSection(settings: Settings, onApplied: () -> Unit) {
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<Profiles.Profile?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = OuterTuneIcons.palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp).size(24.dp),
        )
        Text("Settings profiles", style = MaterialTheme.typography.bodyLarge)
    }
    Text(
        text = "Share a theme, an equaliser or a whole setup. Pick what goes in; stats, history, " +
            "likes, playlists and accounts never do. Profiles open on the Android app too.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { exporting = true }) { Text("Export profile…") }
        TextButton(onClick = {
            scope.launch {
                val file = FilePicker.pickFile("Choose a profile", "OuterTune profile", "*.${Profiles.EXTENSION};*.json")
                    ?: return@launch
                val profile = withContext(Dispatchers.IO) { runCatching { Profiles.parse(file.readText()) }.getOrNull() }
                if (profile == null) message = "That is not an OuterTune profile, or it is from a newer version." to true
                else importing = profile
            }
        }) { Text("Import profile…") }
        message?.let { (text, error) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (exporting) {
        CategoryDialog(
            title = "Export a profile",
            available = Profiles.Category.entries,
            askName = true,
            confirm = "Export…",
            onDismiss = { exporting = false },
            onConfirm = { name, chosen ->
                exporting = false
                scope.launch {
                    val folder = FilePicker.pickFolders("Where to save the profile").firstOrNull() ?: return@launch
                    val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "OuterTune profile" }
                    val file = File(folder, "$safe.${Profiles.EXTENSION}")
                    message = runCatching {
                        withContext(Dispatchers.IO) { file.writeText(Profiles.export(settings, name.ifBlank { safe }, chosen)) }
                    }.fold({ "Saved ${file.absolutePath}" to false }, { "Could not save: ${it.message}" to true })
                }
            },
        )
    }

    importing?.let { profile ->
        CategoryDialog(
            title = "Import \"${profile.name}\"" +
                (if (profile.createdOn == "android") " (from Android)" else ""),
            available = profile.categories.keys.toList(),
            askName = false,
            confirm = "Apply",
            onDismiss = { importing = null },
            onConfirm = { _, chosen ->
                importing = null
                val count = Profiles.apply(settings, profile, chosen)
                onApplied()
                message = "Applied $count settings from \"${profile.name}\"." to false
            },
        )
    }
}

@Composable
private fun CategoryDialog(
    title: String,
    available: List<Profiles.Category>,
    askName: Boolean,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, chosen: Set<Profiles.Category>) -> Unit,
) {
    var name by remember { mutableStateOf("My OuterTune setup") }
    var chosen by remember { mutableStateOf(available.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (askName) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.typingField().fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (available.isEmpty()) Text("This profile has nothing this app can use.")
                available.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = if (category in chosen) chosen - category else chosen + category },
                    ) {
                        Checkbox(checked = category in chosen, onCheckedChange = null)
                        Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                            Text(category.label)
                            Text(
                                category.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(name.trim(), chosen) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
