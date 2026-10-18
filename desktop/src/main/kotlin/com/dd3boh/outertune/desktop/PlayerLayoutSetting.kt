/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
 * Settings > Player > Player layout: open the layout editor, import what it saves, go back to the
 * built-in player. The editor ships inside the app and opens in the browser.
 */
@Composable
fun PlayerLayoutSetting(settings: Settings) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val custom = settings.playerLayout.isNotBlank()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Icon(
            OuterTuneIcons.tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp).size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text("Player layout", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (custom) "Your own, from the layout editor" else "Built-in",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            message = runCatching { openEditor() }.fold(
                { "The editor is open in your browser. Choose Windows, then Download, then Import here." to false },
                { "Could not open the editor: ${it.message}" to true },
            )
        }) { Text("Open editor") }
        TextButton(onClick = {
            scope.launch {
                val file = FilePicker.pickFile("Choose a player layout", "Player layout", "*.json") ?: return@launch
                val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
                val parsed = text?.let { PlayerLayout.parse(it) }
                message = when {
                    parsed == null -> "Could not read that file." to true
                    parsed.isFailure -> (parsed.exceptionOrNull()?.message ?: "Not a layout.") to true
                    else -> {
                        settings.playerLayout = text
                        "Imported. Open the player to see it." to false
                    }
                }
            }
        }) { Text("Import…") }
        if (custom) TextButton(onClick = { settings.playerLayout = ""; message = "Back to the built-in player." to false }) { Text("Reset") }
    }
    message?.let { (text, error) ->
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp, bottom = 8.dp),
        )
    }
}

/** Writes the bundled editor out to a temporary file and opens it in the default browser. */
private fun openEditor() {
    val html = PlayerLayout::class.java.getResourceAsStream("/editor/layout-editor.html")
        ?.use { it.readBytes() } ?: error("the editor is missing from this build")
    val file = File(System.getProperty("java.io.tmpdir"), "outertune-layout-editor.html")
    // Opened from Windows, so it starts on the Windows player.
    file.writeText(html.decodeToString().replaceFirst("</head>", "<script>window.OT_PLATFORM = \"windows\";</script></head>"))
    java.awt.Desktop.getDesktop().browse(file.toURI())
}
