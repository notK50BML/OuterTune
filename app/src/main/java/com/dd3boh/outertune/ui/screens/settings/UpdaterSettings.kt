/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutoCheckUpdatesKey
import com.dd3boh.outertune.constants.AutoDownloadUpdatesKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.UpdateAvailableKey
import com.dd3boh.outertune.constants.UpdateFlavorKey
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.AppUpdater
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Update checking and installing.
 *
 * The download is this app's; the install is not. Android requires the user to have allowed
 * "install unknown apps" for OuterTune and then shows its own confirmation every time, so the
 * furthest "automatic" can go is having the apk already downloaded when the user says yes. That is
 * what [AutoDownloadUpdatesKey] does, and why there is no "install automatically" switch to pair
 * with it - it would be a switch that could not do what it said.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdaterSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var autoCheck by rememberPreference(AutoCheckUpdatesKey, defaultValue = true)
    var autoDownload by rememberPreference(AutoDownloadUpdatesKey, defaultValue = false)
    var updateFlavor by rememberPreference(UpdateFlavorKey, defaultValue = BuildConfig.FLAVOR)

    // Already read by the search bar and the settings list, but until now nothing ever wrote it,
    // so those indicators could not light up. This is the only place that knows the answer.
    var updateAvailable by rememberPreference(UpdateAvailableKey, defaultValue = false)

    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<AppUpdater.Update?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun check(thenDownload: Boolean) {
        checking = true
        status = context.getString(R.string.update_checking)
        val found = AppUpdater.checkForUpdate(updateFlavor)
        available = found
        updateAvailable = found != null
        status = if (found == null) {
            context.getString(R.string.update_up_to_date)
        } else {
            context.getString(R.string.update_available, found.versionName)
        }
        checking = false
        if (found != null && thenDownload) {
            AppUpdater.downloadAndInstall(context, found) { fraction ->
                status = context.getString(
                    R.string.update_downloading, found.versionName, (fraction * 100).toInt()
                )
            }.onSuccess {
                status = context.getString(R.string.update_handed_to_installer)
            }.onFailure {
                status = context.getString(R.string.update_failed, it.message.orEmpty())
            }
        }
    }

    // Checked on open rather than only on tap: a screen called "Updater" that says nothing until
    // prodded is just a button with extra steps.
    LaunchedEffect(Unit) {
        if (autoCheck) check(thenDownload = autoDownload)
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceEntry(
            title = {
                Text(
                    available?.let { stringResource(R.string.update_install_now) }
                        ?: stringResource(R.string.check_for_updates)
                )
            },
            description = status ?: stringResource(
                R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
            ),
            icon = {
                Icon(if (available != null) Icons.Rounded.Download else Icons.Rounded.Refresh, null)
            },
            isEnabled = !checking,
            onClick = {
                coroutineScope.launch {
                    val update = available
                    if (update == null) {
                        check(thenDownload = autoDownload)
                    } else {
                        AppUpdater.downloadAndInstall(context, update) { fraction ->
                            status = context.getString(
                                R.string.update_downloading, update.versionName, (fraction * 100).toInt()
                            )
                        }.onSuccess {
                            status = context.getString(R.string.update_handed_to_installer)
                        }.onFailure {
                            status = context.getString(R.string.update_failed, it.message.orEmpty())
                        }
                    }
                }
            }
        )

        PreferenceGroupTitle(title = stringResource(R.string.update_options))

        SwitchPreference(
            title = { Text(stringResource(R.string.update_auto_check)) },
            description = stringResource(R.string.update_auto_check_desc),
            checked = autoCheck,
            onCheckedChange = { autoCheck = it }
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.update_auto_download)) },
            description = stringResource(R.string.update_auto_download_desc),
            isEnabled = autoCheck,
            checked = autoDownload,
            onCheckedChange = { autoDownload = it }
        )
        SwitchPreference(
            title = { Text(stringResource(R.string.update_prefer_full)) },
            description = stringResource(R.string.update_prefer_full_desc),
            checked = updateFlavor == "full",
            onCheckedChange = { updateFlavor = if (it) "full" else "core" }
        )

        Spacer(modifier = Modifier.height(16.dp))
        InfoLabel(stringResource(R.string.update_install_tooltip))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.updater)) },
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
