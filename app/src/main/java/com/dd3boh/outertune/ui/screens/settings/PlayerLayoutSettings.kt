/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayerLayoutKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.models.PlayerLayout
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerLayoutSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    var layoutJson by rememberPreference(PlayerLayoutKey, "")
    // Only ever holds a failure. Success is visible in the description below, which
    // reads the stored layout rather than remembering what was done to it.
    var error by remember { mutableStateOf<String?>(null) }

    val current = remember(layoutJson) {
        if (layoutJson.isBlank()) null else PlayerLayout.parse(layoutJson).getOrNull()
    }

    // OpenDocument rather than GetContent: this needs a real file the user picked from storage,
    // and OpenDocument is the one that gives a durable, readable uri for it.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        if (text.isNullOrBlank()) {
            error = context.getString(R.string.player_layout_unreadable)
            return@rememberLauncherForActivityResult
        }
        // Validated before it is stored, so a bad file cannot leave the player in a state the
        // user has to reinstall to escape.
        PlayerLayout.parse(text)
            .onSuccess {
                layoutJson = text
                error = null
            }
            .onFailure { error = it.message ?: context.getString(R.string.player_layout_unreadable) }
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.player_layout))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_layout_import)) },
                // What is actually in use, always - not a message about the last thing that
                // happened. A sticky "Imported" or "Reset" line survives the state it was
                // describing, which makes a reset that did work look like one that did not.
                description = current?.let {
                    val hidden = it.blocks.count { block -> !block.visible }
                    val mode = if (it.mode == PlayerLayout.Mode.FREE) "free placement" else "stacked"
                    "In use: $mode" + if (hidden > 0) ", $hidden block(s) hidden" else ""
                } ?: error ?: stringResource(R.string.player_layout_import_description),
                icon = { Icon(Icons.Rounded.FileOpen, null) },
                onClick = { pickFile.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_layout_reset)) },
                icon = { Icon(Icons.Rounded.RestartAlt, null) },
                isEnabled = layoutJson.isNotBlank(),
                onClick = {
                    layoutJson = ""
                    error = null
                },
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.player_layout_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.player_layout)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
