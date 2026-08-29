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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
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
import com.dd3boh.outertune.constants.ArtworkFallbackToLowResKey
import com.dd3boh.outertune.constants.HighResArtworkKey
import com.dd3boh.outertune.constants.ShowTopBarLogoKey
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SettingsGroup
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.GestureSettingsFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.TabArrangementFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.TabExtrasFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.PlayerBackgroundFrag
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
    val (showTopBarLogo, onShowTopBarLogoChange) =
        rememberPreference(ShowTopBarLogoKey, defaultValue = true)
    val (highResArtworkPref, onHighResArtworkChange) =
        rememberPreference(HighResArtworkKey, defaultValue = true)
    val (artworkFallbackToLowResPref, onArtworkFallbackToLowResChange) =
        rememberPreference(ArtworkFallbackToLowResKey, defaultValue = true)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SettingsGroup(title = stringResource(R.string.theme)) {
            ThemeAppFrag()
        }

        // Tabs, the navbar and which tab opens first are all one subject - where the app puts you
        // and how you move around it - so they belong together. Two of them used to sit under
        // "Display" instead, which is what made that group unreadable.
        SettingsGroup(title = stringResource(R.string.grp_layout)) {
            TabArrangementFrag()
            TabExtrasFrag()
            SwitchPreference(
                title = { Text(stringResource(R.string.slim_navbar_title)) },
                description = stringResource(R.string.slim_navbar_description),
                icon = { Icon(Icons.Rounded.MoreHoriz, null) },
                checked = slimNav,
                onCheckedChange = onSlimNavChange
            )
        }

        SettingsGroup(title = stringResource(R.string.grp_now_playing)) {
            PlayerBackgroundFrag()
            PreferenceEntry(
                title = { Text(stringResource(R.string.player_layout)) },
                description = stringResource(R.string.player_layout_summary),
                icon = { Icon(Icons.Rounded.Dashboard, null) },
                onClick = { navController.navigate("settings/appearance/layout") }
            )
        }

        // The fallback only means anything with high-res on, so keeping the two adjacent is the
        // whole point of grouping them - the dependency is visible rather than inferred.
        SettingsGroup(title = stringResource(R.string.grp_thumbnails)) {
            SwitchPreference(
                title = { Text(stringResource(R.string.high_res_artwork_title)) },
                description = stringResource(R.string.high_res_artwork_description),
                icon = { Icon(Icons.Rounded.HighQuality, null) },
                checked = highResArtworkPref,
                onCheckedChange = onHighResArtworkChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.artwork_fallback_low_res_title)) },
                description = stringResource(R.string.artwork_fallback_low_res_description),
                icon = { Icon(Icons.Rounded.HighQuality, null) },
                checked = artworkFallbackToLowResPref,
                onCheckedChange = onArtworkFallbackToLowResChange,
                isEnabled = highResArtworkPref
            )
        }

        SettingsGroup(title = stringResource(R.string.grp_general)) {
            SwitchPreference(
                title = { Text(stringResource(R.string.show_top_bar_logo_title)) },
                description = stringResource(R.string.show_top_bar_logo_description),
                icon = { Icon(Icons.Rounded.Image, null) },
                checked = showTopBarLogo,
                onCheckedChange = onShowTopBarLogoChange
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.app_icon)) },
                icon = { Icon(Icons.Rounded.AppShortcut, null) },
                onClick = { navController.navigate("settings/appearance/icon") }
            )
        }

        SettingsGroup(title = stringResource(R.string.grp_behavior)) {
            GestureSettingsFrag()
        }
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
