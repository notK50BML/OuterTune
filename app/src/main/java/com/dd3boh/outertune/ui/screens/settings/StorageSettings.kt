/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
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
import com.dd3boh.outertune.utils.rememberPreference
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.constants.RemovedDownloadsToCacheKey
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.DownloadsFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.ImageCacheFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.SongCacheFrag
import com.dd3boh.outertune.ui.utils.backToMain
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection


@SuppressLint("PrivateResource")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (removedToCache, onRemovedToCacheChange) = rememberPreference(RemovedDownloadsToCacheKey, defaultValue = true)

    val downloadUtil = LocalDownloadUtil.current
    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()
    val isDownloadingAllThumbnails by downloadUtil.isDownloadingAllThumbnails.collectAsState()
    val downloadAllThumbnailsStartedMessage = stringResource(R.string.download_all_thumbnails_started)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(
            title = stringResource(R.string.downloaded_songs)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth().animateContentSize()
        ) {
            DownloadsFrag()
            // Downloads always save the full-resolution cover now - the setting for it is gone.
            // This fetches covers for songs downloaded before then; it used to sit under Library
            // and content, away from the downloads it is about.
            PreferenceEntry(
                title = { Text(stringResource(R.string.download_all_thumbnails_title)) },
                description = stringResource(R.string.download_all_thumbnails_description),
                icon = {
                    if (isDownloadingAllThumbnails) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        Icon(Icons.Rounded.Image, null)
                    }
                },
                isEnabled = !isDownloadingAllThumbnails,
                onClick = {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(downloadAllThumbnailsStartedMessage)
                        downloadUtil.downloadAllThumbnails()
                    }
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))


        PreferenceGroupTitle(
            title = stringResource(R.string.song_cache)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SongCacheFrag()
            SwitchPreference(
                title = { Text(stringResource(R.string.removed_downloads_to_cache_title)) },
                description = stringResource(R.string.removed_downloads_to_cache_desc),
                icon = { Icon(Icons.Rounded.Image, null) },
                checked = removedToCache,
                onCheckedChange = onRemovedToCacheChange
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.image_cache)
        )

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ImageCacheFrag()
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.storage_and_downloads)) },
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
private fun StorageSettingsPreview() {
    CompositionLocalProvider(
        LocalPlayerAwareWindowInsets provides WindowInsets(0, 0, 0, 0),
        LocalPlayerConnection provides null,
    ) {
        StorageSettings(
            navController = rememberNavController(),
            scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
        )
    }
}
