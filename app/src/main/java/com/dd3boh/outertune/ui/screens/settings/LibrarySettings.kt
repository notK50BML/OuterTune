/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.material.icons.rounded.FilterList
import com.dd3boh.outertune.constants.ShowHomeChipsKey
import androidx.compose.material.icons.rounded.Person
import com.dd3boh.outertune.constants.ArtistCreditEnrichmentKey
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.SettingsGroup
import com.dd3boh.outertune.constants.DownloadThumbnailsKey
import com.dd3boh.outertune.constants.FlatSubfoldersKey
import com.dd3boh.outertune.constants.ShowArtistVideosAsSongsKey
import com.dd3boh.outertune.constants.ShowLikedAndDownloadedPlaylist
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.settings.fragments.LocalizationFrag
import com.dd3boh.outertune.ui.screens.settings.fragments.SongDetailsArrangement
import com.dd3boh.outertune.ui.screens.settings.fragments.PlaylistHeaderButtonsArrangement
import com.dd3boh.outertune.constants.MarkSavedPlaylistsKey
import androidx.compose.material.icons.rounded.Bookmark
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

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
    val (showArtistVideosAsSongs, onShowArtistVideosAsSongsChange) = rememberPreference(
        ShowArtistVideosAsSongsKey,
        defaultValue = true
    )
    val (artistEnrichment, onArtistEnrichmentChange) = rememberPreference(ArtistCreditEnrichmentKey, defaultValue = true)
    val (showHomeChips, onShowHomeChipsChange) = rememberPreference(ShowHomeChipsKey, defaultValue = false)
    val (markSavedPlaylists, onMarkSavedPlaylistsChange) = rememberPreference(MarkSavedPlaylistsKey, defaultValue = false)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // What Home and the song lists show, first: it changes the app most from day to day. The
        // desktop's order too - Display, Content, Local media.
        SettingsGroup(title = stringResource(R.string.grp_display)) {
            SongDetailsArrangement()
            SwitchPreference(
                title = { Text(stringResource(R.string.show_home_chips_title)) },
                description = stringResource(R.string.show_home_chips_description),
                icon = { Icon(Icons.Rounded.FilterList, null) },
                checked = showHomeChips,
                onCheckedChange = onShowHomeChipsChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.show_liked_and_downloaded_playlist)) },
                icon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null) },
                checked = showLikedAndDownloadedPlaylist,
                onCheckedChange = onShowLikedAndDownloadedPlaylistChange
            )
            // Beside the other playlist settings: the buttons on a playlist's page, then the tag
            // that tells someone else's saved playlist from the user's own.
            PlaylistHeaderButtonsArrangement()
            SwitchPreference(
                title = { Text(stringResource(R.string.mark_saved_playlists_title)) },
                description = stringResource(R.string.mark_saved_playlists_description),
                icon = { Icon(Icons.Rounded.Bookmark, null) },
                checked = markSavedPlaylists,
                onCheckedChange = onMarkSavedPlaylistsChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.show_artist_videos_as_songs_title)) },
                description = stringResource(R.string.show_artist_videos_as_songs_description),
                icon = { Icon(Icons.Rounded.SmartDisplay, null) },
                checked = showArtistVideosAsSongs,
                onCheckedChange = onShowArtistVideosAsSongsChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.flat_subfolders_title)) },
                description = stringResource(R.string.flat_subfolders_description),
                icon = { Icon(Icons.Rounded.FolderCopy, null) },
                checked = flatSubfolders,
                onCheckedChange = onFlatSubfoldersChange
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.artist_credit_enrichment_title)) },
                description = stringResource(R.string.artist_credit_enrichment_description),
                icon = { Icon(Icons.Rounded.Person, null) },
                checked = artistEnrichment,
                onCheckedChange = onArtistEnrichmentChange
            )
        }

        // What YouTube Music sends, in which language and for which country - "Content" on the
        // desktop as well.
        SettingsGroup(title = stringResource(R.string.content)) {
            LocalizationFrag()
        }

        // Local media used to be a section of the main settings list; it lives here now, as it does
        // on the desktop. An entry rather than the settings themselves, because the scanner's folder
        // list and dialogs are a screen's worth on their own - and "settings/local" stays where the
        // folder browser's scanner button takes you. The thumbnail settings that followed went to
        // Storage and downloads, being about downloads and the song cache.
        SettingsGroup(title = stringResource(R.string.local_player_settings_title)) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.local_player_settings_entry_title)) },
                description = stringResource(R.string.local_player_settings_entry_description),
                icon = { Icon(Icons.Rounded.SdCard, null) },
                onClick = { navController.navigate("settings/local") }
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
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
