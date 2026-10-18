/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.utils.SettingsProfiles
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shareable settings profiles - see [SettingsProfiles]. Opens and writes the same files as the
 * desktop app.
 */
@Composable
fun SettingsProfilesFrag() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosingExport by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Pair<String, Set<SettingsProfiles.Category>>?>(null) }
    var importing by remember { mutableStateOf<SettingsProfiles.Profile?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val (name, chosen) = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = SettingsProfiles.export(context.dataStore, name, chosen)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }.isSuccess
            toast(context.getString(if (ok) R.string.profile_saved else R.string.profile_save_failed))
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()?.let(SettingsProfiles::parse)
            }
            if (profile == null) toast(context.getString(R.string.profile_not_valid)) else importing = profile
        }
    }

    PreferenceGroupTitle(title = stringResource(R.string.profiles_title))
    Text(
        stringResource(R.string.profiles_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.profile_export)) },
        icon = { Icon(Icons.Rounded.Upload, null) },
        onClick = { choosingExport = true },
    )
    PreferenceEntry(
        title = { Text(stringResource(R.string.profile_import)) },
        icon = { Icon(Icons.Rounded.Download, null) },
        onClick = { openLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
    )

    if (choosingExport) {
        CategoryDialog(
            title = stringResource(R.string.profile_export),
            available = SettingsProfiles.Category.entries,
            askName = true,
            confirm = stringResource(R.string.profile_export_confirm),
            onDismiss = { choosingExport = false },
            onConfirm = { name, chosen ->
                choosingExport = false
                val safe = name.ifBlank { "OuterTune profile" }
                pendingExport = safe to chosen
                saveLauncher.launch("$safe.${SettingsProfiles.EXTENSION}")
            },
        )
    }

    importing?.let { profile ->
        CategoryDialog(
            title = stringResource(
                if (profile.createdOn == "desktop") R.string.profile_import_from_desktop else R.string.profile_import_named,
                profile.name,
            ),
            available = profile.categories.keys.toList(),
            askName = false,
            confirm = stringResource(R.string.profile_apply),
            onDismiss = { importing = null },
            onConfirm = { _, chosen ->
                importing = null
                scope.launch {
                    val count = SettingsProfiles.apply(context.dataStore, profile, chosen)
                    toast(context.getString(R.string.profile_applied, count, profile.name))
                }
            },
        )
    }
}

@Composable
private fun CategoryDialog(
    title: String,
    available: List<SettingsProfiles.Category>,
    askName: Boolean,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<SettingsProfiles.Category>) -> Unit,
) {
    val defaultName = stringResource(R.string.profile_default_name)
    var name by remember { mutableStateOf(defaultName) }
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
                        label = { Text(stringResource(R.string.profile_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                available.forEach { category ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = if (category in chosen) chosen - category else chosen + category },
                    ) {
                        Checkbox(checked = category in chosen, onCheckedChange = null)
                        Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                            Text(stringResource(category.title))
                            Text(
                                stringResource(category.description),
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
