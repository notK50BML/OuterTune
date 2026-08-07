/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * Discord rich presence adapted from reocat/OuterTune (GPL-3.0), rebuilt with OuterTune's own
 * settings components. See git history for contributors.
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DiscordNameKey
import com.dd3boh.outertune.constants.DiscordTokenKey
import com.dd3boh.outertune.constants.DiscordUsernameKey
import com.dd3boh.outertune.constants.EnableDiscordRPCKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.my.kizzy.rpc.KizzyRPC

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var discordToken by rememberPreference(DiscordTokenKey, "")
    var discordUsername by rememberPreference(DiscordUsernameKey, "")
    var discordName by rememberPreference(DiscordNameKey, "")
    val (discordRPC, onDiscordRPCChange) = rememberPreference(EnableDiscordRPCKey, defaultValue = true)

    val isLoggedIn = remember(discordToken) { discordToken.isNotEmpty() }

    // Resolve the account name once a token appears, so the screen can show who is signed in
    // rather than an opaque "logged in".
    LaunchedEffect(discordToken) {
        if (discordToken.isEmpty()) {
            discordUsername = ""
            discordName = ""
            return@LaunchedEffect
        }
        if (discordUsername.isNotEmpty()) return@LaunchedEffect
        KizzyRPC.getUserInfo(discordToken).onSuccess { info ->
            discordUsername = info.username
            discordName = info.name
        }
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.account))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            if (isLoggedIn) {
                PreferenceEntry(
                    title = { Text(discordName.ifEmpty { discordUsername }) },
                    description = discordUsername.takeIf { it.isNotEmpty() }?.let { "@$it" },
                    icon = { Icon(Icons.Rounded.AccountCircle, null) },
                    onClick = {},
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.discord_logout)) },
                    icon = { Icon(Icons.Rounded.Logout, null) },
                    onClick = {
                        discordToken = ""
                        discordUsername = ""
                        discordName = ""
                    },
                )
            } else {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.login)) },
                    description = stringResource(R.string.discord_login_summary),
                    icon = { Icon(painterResource(R.drawable.discord), null) },
                    onClick = { navController.navigate("settings/discord/login") },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.options))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = { Text(stringResource(R.string.enable_discord_rpc)) },
                icon = { Icon(painterResource(R.drawable.discord), null) },
                checked = discordRPC,
                onCheckedChange = onDiscordRPCChange,
                isEnabled = isLoggedIn,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Discord's own terms discourage driving a user account over the gateway. Say so plainly
        // rather than burying it, since the account at risk is the user's.
        Text(
            text = stringResource(R.string.discord_information),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.discord_integration)) },
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
