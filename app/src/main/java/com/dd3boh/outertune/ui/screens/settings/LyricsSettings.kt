/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings


import androidx.compose.foundation.layout.fillMaxHeight
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
import com.dd3boh.outertune.ui.screens.settings.fragments.LyricWordByWordFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LyricDisplayExtrasFrag
import com.dd3boh.outertune.ui.component.SettingsGroup
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.LyricAdvancedFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LyricFormatFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LyricParserFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.LyricSourceFrag
import com.dd3boh.outertune.ui.utils.backToMain
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // The desktop's groups: where lyrics come from, how they look, word by word, then the parser
        // and the sweep's update speed under Advanced. Word by word used to be under Advanced too,
        // as a card inside a card.
        SettingsGroup(title = stringResource(R.string.grp_lyrics_providers)) {
            LyricSourceFrag()
        }

        SettingsGroup(title = stringResource(R.string.grp_display)) {
            LyricFormatFrag()
            LyricDisplayExtrasFrag()
        }

        SettingsGroup(title = stringResource(R.string.grp_word_by_word)) {
            LyricWordByWordFrag()
        }

        SettingsGroup(title = stringResource(R.string.advanced)) {
            LyricParserFrag()
            LyricAdvancedFrag()
        }
    }


    TopAppBar(
        title = { Text(stringResource(R.string.lyrics_settings_title)) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun LyricsSettingsPreview() {
    CompositionLocalProvider(
        LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
    ) {
        LyricsSettings(
            navController = rememberNavController(),
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        )
    }
}
