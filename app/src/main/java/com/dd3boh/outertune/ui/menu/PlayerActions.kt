/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.menu

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastSumBy
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.db.namesAnArtist
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.ExoDownloadService
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.ArtistDialog
import com.dd3boh.outertune.ui.dialog.DetailsDialog
import com.dd3boh.outertune.ui.player.LocalEqualizerPanelState
import com.dd3boh.outertune.ui.player.PlayerButton
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDateTime

/**
 * What the player's actions are doing right now, and how to run one.
 *
 * One implementation of every action, shared by the ⋮ menu and the buttons beside the title. They
 * used to be written inside the menu alone, which is why only Like, the sleep timer and the
 * equaliser could ever be buttons: anything else would have meant a second copy of its logic and
 * its dialog.
 */
class PlayerActionState(
    val run: (PlayerButton) -> Unit,
    val isAvailable: (PlayerButton) -> Boolean,
    val liked: Boolean,
    val inLibrary: Boolean,
    @Download.State val downloadState: Int,
    val sleepTimerActive: Boolean,
    val sleepTimerLeftMs: Long,
    val lyricsShown: Boolean,
)

/**
 * Remembers the actions for [mediaMetadata] and draws whichever of their dialogs is open.
 *
 * [onDone] is called once an action has finished with the screen it was started from - the menu
 * passes its own dismissal, the title-row buttons pass nothing. [onOpenMenu] opens the ⋮ menu, for
 * the button that stands for it.
 */
@Composable
fun rememberPlayerActions(
    mediaMetadata: MediaMetadata,
    navController: NavController,
    playerBottomSheetState: BottomSheetState,
    onDone: () -> Unit,
    onOpenMenu: () -> Unit,
): PlayerActionState? {
    val context = LocalContext.current
    val equalizerPanelState = LocalEqualizerPanelState.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val clipboardManager = LocalClipboard.current
    val playerConnection = LocalPlayerConnection.current ?: return null

    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)
    val queueBoard by playerConnection.queueBoard.collectAsState()
    val currentFormat by database.format(mediaMetadata.id).collectAsState(initial = null)
    val librarySong by database.song(mediaMetadata.id).collectAsState(initial = null)
    val download by downloadUtil.getDownload(mediaMetadata.id).collectAsState(initial = null)
    val latestOnDone by rememberUpdatedState(onDone)

    var showChooseQueueDialog by rememberSaveable { mutableStateOf(false) }
    var showChoosePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectArtistDialog by rememberSaveable { mutableStateOf(false) }
    var showPitchTempoDialog by rememberSaveable { mutableStateOf(false) }
    var showDetailsDialog by rememberSaveable { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    /**
     * The credits worth offering as "view artist". YouTube credits uploads to an auto-generated
     * "- Topic" channel beside the real artist; that channel is never what a tap should land on.
     * Falls back to every credit when none has a real name, since an empty picker helps nobody.
     */
    val linkableArtists = mediaMetadata.artists.filter { it.name.namesAnArtist() }
        .ifEmpty { mediaMetadata.artists }

    val sleepTimerActive = remember(
        playerConnection.service.sleepTimer.triggerTime,
        playerConnection.service.sleepTimer.pauseWhenSongEnd,
    ) { playerConnection.service.sleepTimer.isActive }
    var sleepTimerLeft by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepTimerActive) {
        while (sleepTimerActive && isActive) {
            sleepTimerLeft = if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                playerConnection.player.duration - playerConnection.player.currentPosition
            } else {
                playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
            }
            delay(1000L)
        }
    }

    val downloadState = getDownloadState(download)
    val inLibrary = librarySong?.song?.inLibrary != null

    fun isAvailable(button: PlayerButton): Boolean = when (button) {
        PlayerButton.RADIO, PlayerButton.DOWNLOAD, PlayerButton.SHARE, PlayerButton.LIBRARY -> !mediaMetadata.isLocal
        PlayerButton.ARTIST -> mediaMetadata.artists.any { it.hasArtistPage }
        PlayerButton.ALBUM -> mediaMetadata.album != null && !mediaMetadata.isLocal
        else -> true
    }

    fun run(button: PlayerButton) {
        if (!isAvailable(button)) return
        when (button) {
            PlayerButton.LIKE -> playerConnection.toggleLike()
            PlayerButton.MENU -> onOpenMenu()
            PlayerButton.SLEEP -> showSleepTimerDialog = true
            PlayerButton.EQUALIZER -> equalizerPanelState.visible = true
            PlayerButton.DOWNLOAD -> when (downloadState) {
                Download.STATE_COMPLETED, Download.STATE_QUEUED, Download.STATE_DOWNLOADING ->
                    DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, mediaMetadata.id, false)
                else -> {
                    database.transaction { insert(mediaMetadata) }
                    downloadUtil.download(mediaMetadata)
                }
            }
            PlayerButton.LYRICS -> {
                showLyrics = !showLyrics
                latestOnDone()
            }
            PlayerButton.RADIO -> {
                playerConnection.playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
                latestOnDone()
            }
            PlayerButton.QUEUE -> showChooseQueueDialog = true
            PlayerButton.PLAYLIST -> showChoosePlaylistDialog = true
            PlayerButton.LIBRARY -> if (inLibrary) {
                database.query { toggleInLibrary(mediaMetadata.id, null) }
            } else {
                database.transaction {
                    insert(mediaMetadata)
                    toggleInLibrary(mediaMetadata.id, LocalDateTime.now())
                }
            }
            PlayerButton.ARTIST -> {
                val single = linkableArtists.singleOrNull()?.takeIf { it.hasArtistPage }?.id
                if (single != null) {
                    navController.navigate("artist/$single?songId=${mediaMetadata.id}")
                    playerBottomSheetState.collapseSoft()
                    latestOnDone()
                } else {
                    showSelectArtistDialog = true
                }
            }
            PlayerButton.ALBUM -> {
                mediaMetadata.album?.let { navController.navigate("album/${it.id}") }
                playerBottomSheetState.collapseSoft()
                latestOnDone()
            }
            PlayerButton.SHARE -> {
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${mediaMetadata.id}")
                }
                context.startActivity(Intent.createChooser(intent, null))
                latestOnDone()
            }
            PlayerButton.DETAILS -> showDetailsDialog = true
            PlayerButton.ADVANCED -> showPitchTempoDialog = true
        }
    }

    // ---- dialogs ---------------------------------------------------------------------------

    if (showSleepTimerDialog) {
        SleepTimerDialog(onDismiss = { showSleepTimerDialog = false })
    }
    if (showPitchTempoDialog) {
        PitchTempoDialog(onDismiss = { showPitchTempoDialog = false })
    }
    if (showDetailsDialog) {
        DetailsDialog(
            mediaMetadata = mediaMetadata,
            currentFormat = currentFormat,
            currentPlayCount = librarySong?.playCount?.fastSumBy { it.count } ?: 0,
            clipboardManager = clipboardManager,
            setVisibility = { showDetailsDialog = it },
        )
    }
    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                queueBoard.addQueue(queueName, listOf(mediaMetadata), forceInsert = true, delta = false)
                    ?.let { queueBoard.setCurrQueue(it) }
            },
            onDismiss = {
                showChooseQueueDialog = false
                latestOnDone() // the queue is switched to, so the screen it came from is done
            },
        )
    }
    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = listOf(mediaMetadata.id),
            onPreAdd = { _ ->
                database.transaction { insert(mediaMetadata) }
                listOf(mediaMetadata.id)
            },
            onDismiss = { showChoosePlaylistDialog = false },
        )
    }
    if (showSelectArtistDialog) {
        ArtistDialog(
            navController = navController,
            artists = linkableArtists,
            fromSongId = mediaMetadata.id,
            onDismiss = {
                playerBottomSheetState.collapseSoft()
                showSelectArtistDialog = false
            },
        )
    }

    return PlayerActionState(
        run = ::run,
        isAvailable = ::isAvailable,
        liked = librarySong?.song?.liked == true,
        inLibrary = inLibrary,
        downloadState = downloadState,
        sleepTimerActive = sleepTimerActive,
        sleepTimerLeftMs = sleepTimerLeft,
        lyricsShown = showLyrics,
    )
}
