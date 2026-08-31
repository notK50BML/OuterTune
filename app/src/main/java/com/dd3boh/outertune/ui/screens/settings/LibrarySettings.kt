/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DownloadThumbnailsKey
import com.dd3boh.outertune.constants.FlatSubfoldersKey
import com.dd3boh.outertune.constants.ShowArtistVideosAsSongsKey
import com.dd3boh.outertune.constants.ShowLikedAndDownloadedPlaylist
import com.dd3boh.outertune.constants.DEFAULT_PLAYLIST_SEARCH_THRESHOLD
import com.dd3boh.outertune.constants.PlaylistSearchThresholdKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalizationFrag
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (showLikedAndDownloadedPlaylist, onShowLikedAndDownloadedPlaylistChange) = rememberPreference(
        key = ShowLikedAndDownloadedPlaylist,
        defaultValue = true
    )
    val (flatSubfolders, onFlatSubfoldersChange) = rememberPreference(FlatSubfoldersKey, defaultValue = true)
    val (downloadThumbnails, onDownloadThumbnailsChange) = rememberPreference(DownloadThumbnailsKey, defaultValue = false)
    val (showArtistVideosAsSongs, onShowArtistVideosAsSongsChange) = rememberPreference(
        ShowArtistVideosAsSongsKey,
        defaultValue = true
    )
    val (playlistSearchThreshold, onPlaylistSearchThresholdChange) = rememberPreference(
        PlaylistSearchThresholdKey,
        defaultValue = DEFAULT_PLAYLIST_SEARCH_THRESHOLD
    )

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
            title = stringResource(R.string.grp_localization)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            LocalizationFrag()
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_display)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SwitchPreference(
                title = { Text(stringResource(R.string.show_liked_and_downloaded_playlist)) },
                icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                checked = showLikedAndDownloadedPlaylist,
                onCheckedChange = onShowLikedAndDownloadedPlaylistChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.flat_subfolders_title)) },
                description = stringResource(R.string.flat_subfolders_description),
                icon = { Icon(Icons.Rounded.FolderCopy, null) },
                checked = flatSubfolders,
                onCheckedChange = onFlatSubfoldersChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.show_artist_videos_as_songs_title)) },
                description = stringResource(R.string.show_artist_videos_as_songs_description),
                icon = { Icon(Icons.Rounded.SmartDisplay, null) },
                checked = showArtistVideosAsSongs,
                onCheckedChange = onShowArtistVideosAsSongsChange
            )
            ListPreference(
                title = { Text(stringResource(R.string.playlist_search_button_title)) },
                icon = { Icon(Icons.Rounded.Search, null) },
                selectedValue = playlistSearchThreshold,
                values = listOf(0, 20, 50, 100, 200),
                valueText = {
                    if (it == 0) {
                        stringResource(R.string.playlist_search_button_never)
                    } else {
                        stringResource(R.string.playlist_search_button_from, it)
                    }
                },
                onValueSelected = onPlaylistSearchThresholdChange,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(
            title = stringResource(R.string.grp_thumbnails)
        )
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SwitchPreference(
                title = { Text(stringResource(R.string.download_thumbnails_title)) },
                description = stringResource(R.string.download_thumbnails_description),
                icon = { Icon(Icons.Rounded.Image, null) },
                checked = downloadThumbnails,
                onCheckedChange = onDownloadThumbnailsChange
            )
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
        Spacer(Modifier.height(96.dp))
    }


    TopAppBar(
        title = { Text(stringResource(R.string.grp_library_and_content)) },
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
