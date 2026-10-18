/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * Every keyboard shortcut, each with as many keys as wanted. Click "+ Add key" and press one; a key
 * already used by another action moves here. Esc cancels.
 */
@Composable
fun KeyboardSettings(settings: Settings) {
    val bindings = remember(settings.keyBindings) { KeyBindings.decode(settings.keyBindings) }
    var capturing by remember { mutableStateOf<KeyAction?>(null) }
    fun save(next: KeyBindings) { settings.keyBindings = next.encode() }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Shortcuts work anywhere in the window except while typing. Media keys always work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { save(KeyBindings.DEFAULT) }) { Text("Reset all") }
        }
        KeyAction.entries.forEachIndexed { index, action ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Text(action.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(200.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    bindings.chordsFor(action).forEach { chord ->
                        KeyChip(chord.label, onRemove = { save(bindings.with(action, bindings.chordsFor(action) - chord)) })
                    }
                    if (capturing == action) {
                        Capture(
                            onChord = { chord ->
                                capturing = null
                                save(bindings.bind(action, chord))
                            },
                            onCancel = { capturing = null },
                        )
                    } else {
                        Text(
                            "+ Add key",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                                .clickable { capturing = action },
                        )
                    }
                }
                if (!bindings.isDefault(action)) {
                    Text(
                        "Reset",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp).clickable { save(bindings.reset(action)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Icon(
                OuterTuneIcons.close,
                contentDescription = "Remove $label",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).clickable(onClick = onRemove),
            )
        }
    }
}

/** Takes focus and turns the next key press into a chord. Its keys never reach the shortcuts. */
@Composable
private fun Capture(onChord: (Chord) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val chord = Chord.of(event) ?: return@onPreviewKeyEvent true
                if (chord == Chord.key(java.awt.event.KeyEvent.VK_ESCAPE)) onCancel() else onChord(chord)
                true
            },
    ) {
        Text(
            "Press a key…",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
