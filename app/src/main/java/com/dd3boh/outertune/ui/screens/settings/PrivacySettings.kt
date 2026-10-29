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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ElevatedCard
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
import com.dd3boh.outertune.constants.ProxyEnabledKey
import com.dd3boh.outertune.constants.ProxyTypeKey
import com.dd3boh.outertune.constants.ProxyUrlKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.EditTextPreference
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
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
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ListenHistoryFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SearchHistoryFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_proxy)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
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
        title = { Text(stringResource(R.string.privacy)) },
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
