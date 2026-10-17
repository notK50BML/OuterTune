/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R

/** The search box at the top of Settings. */
@Composable
fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(stringResource(R.string.settings_search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, null) } }
        } else null,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

/**
 * Settings whose title contains every word typed - or whose screen's name does, so "lyrics" lists
 * the whole lyrics screen. Each opens the screen it is on.
 */
@Composable
fun SettingsSearchResults(query: String, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val matches = remember(query) {
        val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        SETTINGS_INDEX
            .map { Triple(it, context.getString(it.title), context.getString(it.screen)) }
            .filter { (_, title, screen) ->
                val haystack = "$title $screen".lowercase()
                words.all { it in haystack }
            }
            .distinctBy { (entry, title, _) -> entry.route + title }
    }
    if (matches.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_none, query.trim()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    matches.forEach { (entry, title, screen) ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(entry.route) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(screen, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
