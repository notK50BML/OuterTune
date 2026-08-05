/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.MoreHoriz
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
import com.dd3boh.outertune.constants.HighResArtworkKey
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.GestureSettingsFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.TabArrangementFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.TabExtrasFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.ThemeAppFrag
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppearanceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)
    val (highResArtworkPref, onHighResArtworkChange) =
        rememberPreference(HighResArtworkKey, defaultValue = true)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.theme)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ThemeAppFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_layout)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            TabArrangementFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_display)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            TabExtrasFrag()
            SwitchPreference(
                title = { Text(stringResource(R.string.slim_navbar_title)) },
                description = stringResource(R.string.slim_navbar_description),
                icon = { Icon(Icons.Rounded.MoreHoriz, null) },
                checked = slimNav,
                onCheckedChange = onSlimNavChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.high_res_artwork_title)) },
                description = stringResource(R.string.high_res_artwork_description),
                icon = { Icon(Icons.Rounded.HighQuality, null) },
                checked = highResArtworkPref,
                onCheckedChange = onHighResArtworkChange
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_behavior)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            GestureSettingsFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.grp_appearance_controls)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun AppearanceSettingsPreview() {
    CompositionLocalProvider(
        LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
    ) {
        AppearanceSettings(
            navController = rememberNavController(),
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        )
    }
}
