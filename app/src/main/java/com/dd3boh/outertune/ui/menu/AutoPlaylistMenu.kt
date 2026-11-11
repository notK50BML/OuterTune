/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSyncUtils
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SongSortDescendingKey
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.constants.SongSortTypeKey
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.items.AutoPlaylistListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.RemoveDownloadsDialog
import com.dd3boh.outertune.ui.screens.playlist.PlaylistType
import com.dd3boh.outertune.ui.utils.m3uFileName
import com.dd3boh.outertune.ui.utils.rememberM3uExportLauncher
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The menu for Liked songs and Downloaded songs: the library's automatic playlists. They had none,
 * so a long press on either did nothing, where every other playlist has Play, Download, Export and
 * the rest.
 *
 * It is a normal playlist's menu less what cannot apply. Neither is a playlist in the database, so
 * there is nothing to rename or delete, and no link to share (Liked songs is YouTube Music's "LM",
 * which only its owner can open). Downloaded songs is made of downloads, so it has nothing to
 * download and - deliberately - no "Remove download": one tap there would empty it, and its songs
 * can still be removed one by one. Resync is Liked songs' only, since it is the one synced from
 * YouTube Music.
 *
 * The songs are in the order the playlist page shows them (Library > Songs' sort), so Play here
 * plays what Play on the page plays.
 */
@Composable
fun AutoPlaylistMenu(
    navController: NavController,
    playlistType: PlaylistType,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val syncUtils = LocalSyncUtils.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val queueBoard by playerConnection.queueBoard.collectAsState()
    val isNetworkConnected = LocalNetworkConnected.current

    val sortType by rememberEnumPreference(SongSortTypeKey, SongSortType.CREATE_DATE)
    val sortDescending by rememberPreference(SongSortDescendingKey, true)

    val isLiked = playlistType == PlaylistType.LIKE
    val playlist = PlaylistEntity(
        id = if (isLiked) "liked" else "downloaded",
        name = stringResource(if (isLiked) R.string.liked_songs else R.string.downloaded_songs),
        browseId = if (isLiked) "LM" else null,
    )

    val songs by remember(isLiked, sortType, sortDescending) {
        if (isLiked) database.likedSongs(sortType, sortDescending)
        else database.downloadSongs(sortType, sortDescending)
    }.collectAsState(initial = emptyList<Song>())

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }
    // Some, but not all, of the songs downloaded. Liked songs is rarely downloaded whole - songs are
    // liked faster than anyone downloads them - so with only the all-or-nothing Download item the
    // menu would almost never offer to remove the downloads it does have.
    var someDownloaded by remember {
        mutableStateOf(false)
    }
    LaunchedEffect(songs) {
        if (!isLiked) return@LaunchedEffect
        val remote = songs.filterNot { it.song.isLocal }
        if (remote.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(remote.map { downloads[it.id] })
            someDownloaded = remote.any { getDownloadState(downloads[it.id]) == Download.STATE_COMPLETED }
        }
    }

    val m3uLauncher = rememberM3uExportLauncher { songs }

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    AutoPlaylistListItem(
        playlist = playlist,
        thumbnail = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.CloudDownload,
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = Icons.Rounded.PlayArrow,
            title = R.string.play,
            enabled = songs.isNotEmpty()
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = playlist.name,
                    items = songs.map { it.toMediaMetadata() },
                    playlistId = playlist.browseId
                )
            )
        }

        GridMenuItem(
            icon = Icons.Rounded.Shuffle,
            title = R.string.shuffle,
            enabled = songs.isNotEmpty()
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = playlist.name,
                    items = songs.map { it.toMediaMetadata() },
                    startShuffled = true,
                    playlistId = playlist.browseId
                )
            )
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next,
            enabled = songs.isNotEmpty()
        ) {
            onDismiss()
            playerConnection.enqueueNext(songs.map { it.toMediaItem() })
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = R.string.add_to_queue,
            enabled = songs.isNotEmpty()
        ) {
            showChooseQueueDialog = true
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist,
            enabled = songs.isNotEmpty()
        ) {
            showChoosePlaylistDialog = true
        }

        if (isLiked && songs.any { !it.song.isLocal }) {
            DownloadGridMenu(
                state = downloadState,
                onDownload = {
                    downloadUtil.download(songs.filterNot { it.song.isLocal }.map { it.toMediaMetadata() })
                },
                onRemoveDownload = {
                    showRemoveDownloadDialog = true
                }
            )
            // DownloadGridMenu already offers removal once everything is downloaded or while it
            // downloads; this is the partly downloaded case it leaves out.
            if (downloadState == Download.STATE_STOPPED && someDownloaded) {
                GridMenuItem(
                    icon = Icons.Rounded.OfflinePin,
                    title = R.string.remove_download
                ) {
                    showRemoveDownloadDialog = true
                }
            }
        }

        if (isLiked) {
            GridMenuItem(
                icon = Icons.Rounded.Sync,
                title = R.string.action_sync,
                enabled = isNetworkConnected
            ) {
                onDismiss()
                // The screen's scope, not the menu's: the sync outlives the menu closing.
                coroutineScope.launch {
                    syncUtils.syncRemoteLikedSongs(true)
                }
            }
        }

        GridMenuItem(
            icon = Icons.Rounded.Output,
            title = R.string.m3u_export,
            enabled = songs.isNotEmpty()
        ) {
            m3uLauncher.launch(m3uFileName(playlist.name))
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = queueBoard.addQueue(
                    queueName, songs.map { it.toMediaMetadata() },
                    forceInsert = true, delta = false
                )
                q?.let {
                    queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }

    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = songs.map { it.id },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showRemoveDownloadDialog) {
        RemoveDownloadsDialog(
            songIds = songs.filterNot { it.song.isLocal }.map { it.id },
            onDismiss = { showRemoveDownloadDialog = false },
            onConfirm = onDismiss
        )
    }
}
