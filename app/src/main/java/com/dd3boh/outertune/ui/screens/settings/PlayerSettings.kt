/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
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
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.screens.settings.fragments.BackgroundPlaybackFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.QueueFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.PlayerControlsFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.PlaybackFrag
import com.dd3boh.outertune.ui.component.SettingsGroup
import com.dd3boh.outertune.constants.AudioDecoderKey
import com.dd3boh.outertune.constants.DEFAULT_AUDIO_DECODER
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.screens.settings.fragments.AudioQualityFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.SleepTimerFrag
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (audioDecoder, onAudioDecoderChange) = rememberPreference(
        key = AudioDecoderKey,
        defaultValue = DEFAULT_AUDIO_DECODER
    )

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // The desktop's order: what plays and how it sounds, then the controls, the queue and the
        // sleep timer, with the niche ones last. How the player looks - background, layout, seek
        // bar - is under Appearance; tapping the cover for lyrics is under Lyrics; counting plays
        // from other devices is under Privacy and history.
        SettingsGroup(title = stringResource(R.string.grp_playback)) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.eq_settings_title)) },
                icon = { Icon(Icons.Rounded.Equalizer, null) },
                onClick = { navController.navigate("settings/player/equalizer") }
            )
            AudioQualityFrag()
            PlaybackFrag()
        }

        SettingsGroup(title = stringResource(R.string.grp_controls)) {
            PlayerControlsFrag()
        }

        SettingsGroup(title = stringResource(R.string.queue)) {
            QueueFrag()
        }

        SettingsGroup(title = stringResource(R.string.sleep_timer)) {
            SleepTimerFrag()
        }

        SettingsGroup(title = stringResource(R.string.advanced)) {
            if (ENABLE_FFMETADATAEX) {
                ListPreference(
                    title = { Text(stringResource(R.string.audio_decoder_preference)) },
                    icon = { Icon(Icons.Rounded.AudioFile, null) },
                    selectedValue = audioDecoder,
                    onValueSelected = onAudioDecoderChange,
                    values = listOf(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
                    ),
                    valueText = {
                        when (it) {
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> stringResource(R.string.audio_decoder_system_only)
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> stringResource(R.string.audio_decoder_system_with_ffmpeg)
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> stringResource(R.string.audio_decoder_ffmpeg_only)
                            else -> {stringResource(R.string.error_unknown)}
                        }
                    }
                )
                InfoLabel(stringResource(R.string.restart_to_apply_changes))
            }
            BackgroundPlaybackFrag()
        }
        Spacer(Modifier.height(96.dp))
    }


    TopAppBar(
        title = { Text(stringResource(R.string.player_and_audio)) },
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
private fun PlayerSettingsPreview() {
    CompositionLocalProvider(
        LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
    ) {
        PlayerSettings(
            navController = rememberNavController(),
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        )
    }
}
