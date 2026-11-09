/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.AccountAvatar

/**
 * Stats, Settings and Account, for the end of Home's chips row when the Search tab is on - the
 * search bar they normally share a row with is gone then, and they still need somewhere to live.
 */
@Composable
fun HomeShortcutIcons(navController: NavController, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = { navController.navigate("stats") }) {
            Icon(Icons.AutoMirrored.Rounded.TrendingUp, contentDescription = stringResource(R.string.stats))
        }
        IconButton(onClick = { navController.navigate("settings") }) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
        }
        IconButton(onClick = { navController.navigate("settings/account_sync") }) {
            AccountAvatar(contentDescription = stringResource(R.string.account)) {
                Icon(Icons.Rounded.Person, contentDescription = stringResource(R.string.account))
            }
        }
    }
}
