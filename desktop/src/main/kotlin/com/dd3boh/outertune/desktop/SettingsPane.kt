/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything that can be changed, in one scrolling column.
 *
 * One page rather than the phone's tree of sub-screens. That tree exists because a phone can show
 * about six rows at a time, so anything longer has to be split; a desktop window shows forty, and
 * splitting them would mean navigating to find a switch that could simply have been visible.
 *
 * Signing in lives here rather than in its own top-level place. It is setup, done once, and its old
 * spot in the main navigation gave it standing next to the library and the playlists that it does
 * not have - nothing about signing in is something anyone visits twice.
 */
@Composable
fun SettingsPane(
    settings: Settings,
    account: Account,
    libraryPath: String,
    onBack: () -> Unit,
    onClearLyricsCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Library") }
            Spacer(modifier = Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Section("Account") {
            // The whole sign-in pane, unchanged, just relocated. Four routes and their explanations
            // are a lot of surface, and it reads better as one card here than as a destination.
            AccountPane(account = account)
        }

        Section("Playback") {
            SettingSwitch(
                title = "Seek buttons",
                subtitle = "Five-second skip either side of play. Off by default, as on the phone.",
                checked = settings.showSeekButtons,
                onCheckedChange = { settings.showSeekButtons = it },
            )
            SettingSwitch(
                title = "Visualiser",
                subtitle = "Spectrum bars above the seek bar.",
                checked = settings.showVisualizer,
                onCheckedChange = { settings.showVisualizer = it },
            )
        }

        Section("Lyrics") {
            SettingSwitch(
                title = "Click the cover for lyrics",
                subtitle = "Swaps the album art for the words, and back.",
                checked = settings.lyricsOnCoverClick,
                onCheckedChange = { settings.lyricsOnCoverClick = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Timing offset", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Shifts every line. Positive is later. Some files are timed against a " +
                            "different master of the same song.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RotaryDial(
                    value = settings.lyricsOffsetMs.toFloat(),
                    onValueChange = { settings.lyricsOffsetMs = it.toInt() },
                    valueRange = -3000f..3000f,
                    color = if (settings.colourByValue) {
                        ValueGradient.forValue(settings.lyricsOffsetMs.toFloat(), -3000f..3000f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textColor = MaterialTheme.colorScheme.onSurface,
                    dialSize = 68.dp,
                    valueLabel = "${settings.lyricsOffsetMs} ms",
                    step = 25f,
                    coarseStep = 250f,
                    centeredAt = 0f,
                )
            }
            TextButton(onClick = onClearLyricsCache) { Text("Clear cached lyrics") }
        }

        Section("Appearance") {
            SettingSwitch(
                title = "Colour controls by value",
                subtitle = "Yellow low, green middle, blue high, on dials and equaliser bands.",
                checked = settings.colourByValue,
                onCheckedChange = { settings.colourByValue = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Player background", style = MaterialTheme.typography.bodyLarge)
            BackgroundStyle.entries.forEach { style ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settings.background = style }
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(
                        selected = settings.background == style,
                        onClick = { settings.background = style },
                    )
                    Column {
                        Text(style.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            style.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Section("Library") {
            SettingSwitch(
                title = "Keep listening history",
                subtitle = "Records what has been played, which is what the recent list is made of.",
                checked = settings.keepHistory,
                onCheckedChange = { settings.keepHistory = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Stored at", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = libraryPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "One SQLite file. Copying it somewhere safe is a complete backup - songs, " +
                    "playlists, history, cached lyrics and these settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScopeMarker.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
    Card(modifier = Modifier.widthIn(max = 720.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ColumnScopeMarker.content()
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * A marker receiver so section bodies read as a block rather than as a lambda argument.
 *
 * There is nothing on it. Compose's own ColumnScope cannot be passed through here without the
 * content having to declare it, and the sections do not need column-scoped modifiers anyway.
 */
object ColumnScopeMarker

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, not just the switch. A switch is a small target and the label
            // beside it is the thing being read - clicking what you just read should work.
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
