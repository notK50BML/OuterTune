/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlaySyncCodeKey
import com.dd3boh.outertune.constants.PlaySyncWifiKey
import com.dd3boh.outertune.constants.StatsIncludeRemoteKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.sync.PlaySync
import com.dd3boh.outertune.sync.SyncCode
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

/**
 * Cross-device sync: linking this device to the user's others with a sync code, and syncing plays
 * with them - over WiFi for now. Where "Count plays from other devices" used to be, since this is
 * what actually counts them; the YouTube Music history switch is kept here as the rough fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySyncSettings(navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var code by rememberPreference(PlaySyncCodeKey, "")
    var wifi by rememberPreference(PlaySyncWifiKey, false)
    val (statsIncludeRemote, onStatsIncludeRemoteChange) = rememberPreference(StatsIncludeRemoteKey, false)
    val status by (PlaySync.manager?.status ?: PlaySync.idle).collectAsStateWithLifecycle()
    var entering by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        InfoLabel(stringResource(R.string.sync_intro))
        Spacer(Modifier.height(12.dp))

        if (code.isBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.sync_set_up)) },
                    description = stringResource(R.string.sync_set_up_desc),
                    icon = { Icon(Icons.Rounded.Add, null) },
                    onClick = {
                        code = SyncCode.generate()
                        wifi = true
                    },
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.sync_enter_code)) },
                    description = stringResource(R.string.sync_enter_code_desc),
                    icon = { Icon(Icons.Rounded.Key, null) },
                    onClick = { entering = true },
                )
            }
        } else {
            ElevatedCard(Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = { Text(code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) },
                    description = stringResource(R.string.sync_code_desc),
                    descriptionIsExplanation = false,
                    icon = { Icon(Icons.Rounded.Key, null) },
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, R.string.sync_code_copied, Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.sync_wifi_title)) },
                    description = stringResource(R.string.sync_wifi_desc),
                    icon = { Icon(Icons.Rounded.Wifi, null) },
                    checked = wifi,
                    onCheckedChange = { wifi = it },
                )
                if (wifi) {
                    val title = when {
                        !status.running -> stringResource(R.string.sync_status_starting)
                        status.syncing -> stringResource(R.string.sync_status_syncing)
                        status.peers.isEmpty() -> stringResource(R.string.sync_status_looking)
                        else -> stringResource(R.string.sync_status_found, status.peers.joinToString())
                    }
                    val last = status.last
                    val lastAt = status.lastAt
                    val description = when {
                        status.error != null -> stringResource(R.string.sync_status_failed, status.error.orEmpty())
                        last != null && lastAt != null -> stringResource(
                            R.string.sync_status_last,
                            DateUtils.getRelativeTimeSpanString(lastAt).toString(),
                            last.peer,
                            last.received,
                        )
                        else -> stringResource(R.string.sync_status_never)
                    }
                    PreferenceEntry(
                        title = { Text(title) },
                        description = description,
                        descriptionIsExplanation = false,
                        icon = { Icon(Icons.Rounded.Sync, null) },
                        onClick = { PlaySync.manager?.syncNow() },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.sync_unlink)) },
                    description = stringResource(R.string.sync_unlink_desc),
                    icon = { Icon(Icons.Rounded.LinkOff, null) },
                    onClick = { confirmUnlink = true },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = { Text(stringResource(R.string.stats_include_remote_title)) },
                description = stringResource(R.string.stats_include_remote_description),
                icon = { Icon(Icons.Rounded.CloudSync, null) },
                checked = statsIncludeRemote,
                onCheckedChange = onStatsIncludeRemoteChange,
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (entering) {
        var typed by remember { mutableStateOf("") }
        val valid = SyncCode.normalize(typed)
        AlertDialog(
            onDismissRequest = { entering = false },
            title = { Text(stringResource(R.string.sync_enter_code)) },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.sync_code_hint)) },
                    isError = typed.length >= 12 && valid == null,
                    supportingText = if (typed.length >= 12 && valid == null) {
                        { Text(stringResource(R.string.sync_code_invalid)) }
                    } else null,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid != null,
                    onClick = {
                        code = valid.orEmpty()
                        wifi = true
                        entering = false
                    },
                ) { Text(stringResource(R.string.sync_link)) }
            },
            dismissButton = {
                TextButton(onClick = { entering = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text(stringResource(R.string.sync_unlink)) },
            text = { Text(stringResource(R.string.sync_unlink_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    wifi = false
                    code = ""
                    confirmUnlink = false
                }) { Text(stringResource(R.string.sync_unlink)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnlink = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.sync_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}
