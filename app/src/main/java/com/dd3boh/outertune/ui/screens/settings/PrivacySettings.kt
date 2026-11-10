/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.History
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SettingsGroup
import com.dd3boh.outertune.constants.ProxyEnabledKey
import com.dd3boh.outertune.constants.ProxyTypeKey
import com.dd3boh.outertune.constants.ProxyUrlKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.EditTextPreference
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.ListenHistoryFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.SearchHistoryFrag
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import java.net.Proxy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (proxyEnabled, onProxyEnabledChange) = rememberPreference(key = ProxyEnabledKey, defaultValue = false)
    val (proxyType, onProxyTypeChange) = rememberEnumPreference(key = ProxyTypeKey, defaultValue = Proxy.Type.HTTP)
    val (proxyUrl, onProxyUrlChange) = rememberPreference(key = ProxyUrlKey, defaultValue = "host:port")

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Each kind of history with its own switches and its clear button, as on the desktop. The
        // history itself opens from here now rather than from the main settings list, where it was
        // the one entry that was not settings.
        SettingsGroup(title = stringResource(R.string.listen_history)) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.history)) },
                icon = { Icon(Icons.Rounded.History, null) },
                onClick = { navController.navigate("history") }
            )
            ListenHistoryFrag()
            // Where "Count plays from other devices" was: syncing plays with the user's other devices
            // is what actually counts them. The YouTube Music history switch lives on that screen.
            PreferenceEntry(
                title = { Text(stringResource(R.string.sync_title)) },
                description = stringResource(R.string.sync_entry_summary),
                icon = { Icon(Icons.Rounded.CloudSync, null) },
                onClick = { navController.navigate("settings/sync") },
            )
        }

        SettingsGroup(title = stringResource(R.string.search_history)) {
            SearchHistoryFrag()
        }

        SettingsGroup(title = stringResource(R.string.grp_proxy)) {
            SwitchPreference(
                title = { Text(stringResource(R.string.enable_proxy)) },
                checked = proxyEnabled,
                onCheckedChange = onProxyEnabledChange
            )

            AnimatedVisibility(proxyEnabled) {
                Column {
                    ListPreference(
                        title = { Text(stringResource(R.string.proxy_type)) },
                        selectedValue = proxyType,
                        values = listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS),
                        valueText = { it.name },
                        onValueSelected = onProxyTypeChange
                    )
                    EditTextPreference(
                        title = { Text(stringResource(R.string.proxy_url)) },
                        value = proxyUrl,
                        onValueChange = onProxyUrlChange
                    )
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.privacy_and_history)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
