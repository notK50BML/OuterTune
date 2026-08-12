/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.media3.common.C
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_READY
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.PlayerLayoutKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.PLAYER_DEBUG
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.QueuePeekHeight
import com.dd3boh.outertune.constants.DEFAULT_SHOW_LYRICS_ON_CLICK
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.DEFAULT_SWIPE_TO_SKIP
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.ShowLyricsOnClickKey
import com.dd3boh.outertune.constants.SleepTimerShowOnPlayerKey
import com.dd3boh.outertune.constants.SwipeToSkipKey
import com.dd3boh.outertune.models.PlayerLayout
import com.dd3boh.outertune.extensions.isPowerSaver
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.supportsWideScreen
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.extensions.toggleRepeatMode
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.playback.QueueBoard
import com.dd3boh.outertune.ui.component.BottomSheet
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.PlayerSliderTrack
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.button.ResizableIconButton
import com.dd3boh.outertune.ui.component.collapsedAnchor
import com.dd3boh.outertune.ui.component.dismissedAnchor
import com.dd3boh.outertune.ui.component.rememberBottomSheetState
import com.dd3boh.outertune.ui.menu.PlayerMenu
import com.dd3boh.outertune.ui.menu.SleepTimerDialog
import com.dd3boh.outertune.ui.theme.extractGradientColors
import com.dd3boh.outertune.ui.utils.SnapLayoutInfoProvider
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BottomSheetPlayer(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val TAG = "BottomSheetPlayer"
    if (PLAYER_DEBUG) Log.v(TAG, "PLR-1")

    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val queueBoard by playerConnection.service.queueBoard.collectAsState()

    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = DEFAULT_PLAYER_BACKGROUND
    )

    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

    val showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    val qbInit by playerConnection.service.qbInit.collectAsState()

    LaunchedEffect(qbInit, queueBoard.masterQueues.toList()) {
        if (PLAYER_DEBUG) Log.d(TAG, "Queues changed. qbInit = $qbInit")
        if (qbInit && !queueBoard.masterQueues.isEmpty() && state.isDismissed) {
            if (PLAYER_DEBUG) Log.d(TAG, "Triggering sheet collapseSoft")
            state.collapseSoft()
        }
    }


    BottomSheet(
        state = state,
        modifier = modifier,
        background = {
            PlayerBackground(
                playerConnection = playerConnection,
                playerBackground = playerBackground,
                showLyrics = showLyrics,
                useDarkTheme = useDarkTheme,
            )
        },
        collapsedBackgroundColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        onDismiss = {
            playerConnection.softKillPlayer()
        },
        collapsedContent = {
            MiniPlayer()
        }
    ) {
        if (PLAYER_DEBUG) Log.v(TAG, "PLR-3.0")

        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && context.supportsWideScreen()) {
            LandscapePlayer(state, navController, queueBoard)
        } else {
            PortraitPlayer(state, navController, queueBoard)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortraitPlayer(
    playerSheetState: BottomSheetState,
    navController: NavController,
    queueBoard: QueueBoard,
    enableQueueSheet: Boolean = true,
) {
    val TAG = "BottomSheetPlayer"
    if (PLAYER_DEBUG) Log.v(TAG, "PLR-3.1b")

    val playerConnection = LocalPlayerConnection.current ?: return

    val layoutJson by rememberPreference(PlayerLayoutKey, "")
    val freePlacement = remember(layoutJson) {
        layoutJson.isNotBlank() &&
                PlayerLayout.parse(layoutJson).getOrNull()?.mode == PlayerLayout.Mode.FREE
    }

    val dismissedBound = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val queueSheetState = rememberBottomSheetState(
        dismissedBound = dismissedBound,
        expandedBound = playerSheetState.expandedBound,
        collapsedBound = dismissedBound + (QueuePeekHeight * 1.2f),
        initialAnchor = collapsedAnchor,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
            .padding(bottom = queueSheetState.collapsedBound)
    ) {
        if (freePlacement) {
            // One coordinate space for the whole player. The cover is handed to ControlsContent as
            // a block rather than drawn here, because in free placement it is positioned by the
            // same rules as everything else and has to share their box.
            //
            // Swipe to skip is not offered here: it is a full-width pager, and a cover that can be
            // any width, anywhere, at any angle is not one.
            val meta by playerConnection.mediaMetadata.collectAsState()
            val showLyricsOnClick by rememberPreference(ShowLyricsOnClickKey, defaultValue = DEFAULT_SHOW_LYRICS_ON_CLICK)
            var freeSliderPosition by remember { mutableStateOf<Long?>(null) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(playerSheetState.preUpPostDownNestedScrollConnection)
            ) {
                ControlsContent(
                    playerSheetState, queueSheetState, navController, queueBoard,
                    artwork = {
                        Thumbnail(
                            modifier = Modifier.animateContentSize(),
                            sliderPositionProvider = { freeSliderPosition },
                            showLyricsOnClick = showLyricsOnClick,
                            customMediaMetadata = meta,
                        )
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .nestedScroll(playerSheetState.preUpPostDownNestedScrollConnection)
        ) {
            if (PLAYER_DEBUG) Log.v(TAG, "PLR-3.2b")
            val mediaMetadata by playerConnection.mediaMetadata.collectAsState()


            val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
            val canSkipNext by playerConnection.canSkipNext.collectAsState()

            val swipeToSkip by rememberPreference(SwipeToSkipKey, defaultValue = DEFAULT_SWIPE_TO_SKIP)
            val showLyricsOnClick by rememberPreference(ShowLyricsOnClickKey, defaultValue = DEFAULT_SHOW_LYRICS_ON_CLICK)
            val previousMediaMetadata = if (swipeToSkip && playerConnection.player.hasPreviousMediaItem()) {
                val previousIndex = playerConnection.player.previousMediaItemIndex
                playerConnection.player.getMediaItemAt(previousIndex).metadata
            } else null


            val nextMediaMetadata = if (swipeToSkip && playerConnection.player.hasNextMediaItem()) {
                val nextIndex = playerConnection.player.nextMediaItemIndex
                playerConnection.player.getMediaItemAt(nextIndex).metadata
            } else null

            val mediaItems = listOfNotNull(previousMediaMetadata, mediaMetadata, nextMediaMetadata)
            val currentMediaIndex = mediaItems.indexOf(mediaMetadata)


            var sliderPosition by remember {
                mutableStateOf<Long?>(null)
            }


            if (!swipeToSkip) {
                Thumbnail(
                    modifier = Modifier
//                                .width(horizontalLazyGridItemWidth)
                        .animateContentSize(),
                    sliderPositionProvider = { sliderPosition },
                    showLyricsOnClick = showLyricsOnClick,
                    customMediaMetadata = mediaMetadata
                )
            } else {
                val thumbnailLazyGridState = rememberLazyGridState()
                val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
                val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

                LaunchedEffect(itemScrollOffset) {
                    if (!thumbnailLazyGridState.isScrollInProgress || itemScrollOffset != 0) return@LaunchedEffect

                    if (currentItem > currentMediaIndex)
                        playerConnection.player.seekToNext()
                    else if (currentItem < currentMediaIndex)
                        playerConnection.player.seekToPreviousMediaItem()
                }

                LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
                    // When the media item changes, scroll to it
                    val index = maxOf(0, currentMediaIndex)

                    // Only animate scroll when player expanded, otherwise animated scroll won't work
                    if (playerSheetState.isExpanded)
                        thumbnailLazyGridState.animateScrollToItem(index)
                    else
                        thumbnailLazyGridState.scrollToItem(index)
                }

                val horizontalLazyGridItemWidthFactor = 1f
                val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
                    SnapLayoutInfoProvider(
                        lazyGridState = thumbnailLazyGridState,
                        positionInLayout = { layoutSize, itemSize ->
                            (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                        }
                    )
                }
                val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor

                LazyHorizontalGrid(
                    state = thumbnailLazyGridState,
                    rows = GridCells.Fixed(1),
                    flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                    userScrollEnabled = playerSheetState.isExpanded,
                    modifier = Modifier.padding(vertical = QueuePeekHeight / 2)
                ) {
                    items(
                        items = mediaItems,
                        key = { it.id }
                    ) {
                        Thumbnail(
                            modifier = Modifier
                                .width(horizontalLazyGridItemWidth)
                                .animateContentSize(),
                            sliderPositionProvider = { sliderPosition },
                            showLyricsOnClick = showLyricsOnClick,
                            customMediaMetadata = it
                        )
                    }
                }
            }
        }

        ControlsContent(playerSheetState, queueSheetState, navController, queueBoard)


        Spacer(Modifier.height(24.dp))


    }

    if (enableQueueSheet) {
        QueueSheet(
            state = queueSheetState,
            playerBottomSheetState = playerSheetState,
            onTerminate = {
                playerSheetState.dismiss()
                queueBoard.detachedHead = false
            },
            navController = navController
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LandscapePlayer(
    playerSheetState: BottomSheetState,
    navController: NavController,
    queueBoard: QueueBoard,
    enableQueueSheet: Boolean = true,
) {
    val TAG = "BottomSheetPlayer"

    val playerConnection = LocalPlayerConnection.current ?: return

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()


    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val swipeToSkip by rememberPreference(SwipeToSkipKey, defaultValue = DEFAULT_SWIPE_TO_SKIP)
    val showLyricsOnClick by rememberPreference(ShowLyricsOnClickKey, defaultValue = DEFAULT_SHOW_LYRICS_ON_CLICK)
    val previousMediaMetadata = if (swipeToSkip && playerConnection.player.hasPreviousMediaItem()) {
        val previousIndex = playerConnection.player.previousMediaItemIndex
        playerConnection.player.getMediaItemAt(previousIndex).metadata
    } else null

    val nextMediaMetadata = if (swipeToSkip && playerConnection.player.hasNextMediaItem()) {
        val nextIndex = playerConnection.player.nextMediaItemIndex
        playerConnection.player.getMediaItemAt(nextIndex).metadata
    } else null

    val mediaItems = listOfNotNull(previousMediaMetadata, mediaMetadata, nextMediaMetadata)
    val currentMediaIndex = mediaItems.indexOf(mediaMetadata)


    val showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }

    val dismissedBound = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    val queueSheetState = rememberBottomSheetState(
        dismissedBound = dismissedBound,
        expandedBound = playerSheetState.expandedBound,
        // Matches PortraitPlayer: a zero collapsed bound meant there was no peek strip at all to
        // drag from, and the handle only existed as the narrower, controls-column-width hint
        // below. This gives the sheet's own full-width collapsed content (icon + queue title) a
        // real height to render into, spanning the whole player like portrait's does.
        collapsedBound = dismissedBound + (QueuePeekHeight * 1.2f),
        initialAnchor = collapsedAnchor,
    )

    val vPadding = max(
        WindowInsets.safeDrawing.getTop(LocalDensity.current),
        WindowInsets.safeDrawing.getBottom(LocalDensity.current)
    )
    val vPaddingDp = with(LocalDensity.current) { vPadding.toDp() }
    val verticalInsets = WindowInsets(left = 0.dp, top = vPaddingDp, right = 0.dp, bottom = vPaddingDp)
    Row(
        modifier = Modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).add(verticalInsets)
            )
            .fillMaxSize()
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                // A Row's children default to top-aligned and only take their own content height,
                // so without this the thumbnail (often shorter than the full row) sits at the top
                // instead of centered - contentAlignment alone only centers within whatever bounds
                // this Box actually ends up with. Filling the row's height gives it the full bounds
                // to center in.
                .fillMaxHeight()
                .nestedScroll(playerSheetState.preUpPostDownNestedScrollConnection)
        ) {
            if (PLAYER_DEBUG) Log.v(TAG, "PLR-3.1a")
            if (!swipeToSkip) {
                Thumbnail(
                    sliderPositionProvider = { sliderPosition },
                    modifier = Modifier
//                                .width(horizontalLazyGridItemWidth)
                        .animateContentSize(),
                    showLyricsOnClick = showLyricsOnClick,
                    customMediaMetadata = mediaMetadata
                )
            } else {
                val thumbnailLazyGridState = rememberLazyGridState()
                val currentItem by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemIndex } }
                val itemScrollOffset by remember { derivedStateOf { thumbnailLazyGridState.firstVisibleItemScrollOffset } }

                LaunchedEffect(itemScrollOffset) {
                    if (!thumbnailLazyGridState.isScrollInProgress || itemScrollOffset != 0) return@LaunchedEffect

                    if (currentItem > currentMediaIndex)
                        playerConnection.player.seekToNext()
                    else if (currentItem < currentMediaIndex)
                        playerConnection.player.seekToPreviousMediaItem()
                }

                LaunchedEffect(mediaMetadata, canSkipPrevious, canSkipNext) {
                    // When the media item changes, scroll to it
                    val index = maxOf(0, currentMediaIndex)

                    // Only animate scroll when player expanded, otherwise animated scroll won't work
                    if (playerSheetState.isExpanded)
                        thumbnailLazyGridState.animateScrollToItem(index)
                    else
                        thumbnailLazyGridState.scrollToItem(index)
                }

                val horizontalLazyGridItemWidthFactor = 1f
                val thumbnailSnapLayoutInfoProvider = remember(thumbnailLazyGridState) {
                    SnapLayoutInfoProvider(
                        lazyGridState = thumbnailLazyGridState,
                        positionInLayout = { layoutSize, itemSize ->
                            (layoutSize * horizontalLazyGridItemWidthFactor / 2f - itemSize / 2f)
                        }
                    )
                }
                val horizontalLazyGridItemWidth = maxWidth * horizontalLazyGridItemWidthFactor


                LazyHorizontalGrid(
                    state = thumbnailLazyGridState,
                    rows = GridCells.Fixed(1),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    flingBehavior = rememberSnapFlingBehavior(thumbnailSnapLayoutInfoProvider),
                    userScrollEnabled = playerSheetState.isExpanded
                ) {
                    items(
                        items = mediaItems,
                        key = { it.id }
                    ) {
                        Thumbnail(
                            sliderPositionProvider = { sliderPosition },
                            modifier = Modifier
                                .width(horizontalLazyGridItemWidth)
                                .animateContentSize(),
                            showLyricsOnClick = showLyricsOnClick,
                            customMediaMetadata = it
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Arrangement.Center rather than a weighted-spacer sandwich: equivalent for this one
            // child, but doesn't silently stop centering the day a second sibling is added here.
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                // "percentage to half width", not "percentage of width"
                .weight(if (showLyrics) 0.65f else 1f, false)
                .fillMaxHeight()
                .animateContentSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
        ) {
            ControlsContent(playerSheetState, queueSheetState, navController, queueBoard)
        }
    }

    if (enableQueueSheet) {
        QueueSheet(
            state = queueSheetState,
            playerBottomSheetState = playerSheetState,
            onTerminate = {
                playerSheetState.dismiss()
                queueBoard.detachedHead = false
            },
            navController = navController
        )
    }
}


@Composable
fun ActionButtons(
    playerSheetState: BottomSheetState,
    navController: NavController,
) {
    val TAG = "ActionButtons()"
    if (PLAYER_DEBUG) Log.v(TAG, "PLR-AB-1")

    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current


    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val showSleepTimerButton by rememberPreference(SleepTimerShowOnPlayerKey, defaultValue = true)

    val sleepTimerActive = remember(
        playerConnection.service.sleepTimer.triggerTime,
        playerConnection.service.sleepTimer.pauseWhenSongEnd
    ) {
        playerConnection.service.sleepTimer.isActive
    }

    var sleepTimerTimeLeft by remember {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(sleepTimerActive) {
        if (sleepTimerActive) {
            while (isActive) {
                sleepTimerTimeLeft = if (playerConnection.service.sleepTimer.pauseWhenSongEnd) {
                    playerConnection.player.duration - playerConnection.player.currentPosition
                } else {
                    playerConnection.service.sleepTimer.triggerTime - System.currentTimeMillis()
                }
                delay(1000L)
            }
        }
    }

    var showSleepTimerDialog by remember {
        mutableStateOf(false)
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(onDismiss = { showSleepTimerDialog = false })
    }

    Spacer(modifier = Modifier.width(10.dp))

    if (showSleepTimerButton) {
        if (sleepTimerActive) {
            Box(
                modifier = Modifier
                    .offset(y = 5.dp)
                    .height(36.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { showSleepTimerDialog = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = makeTimeString(sleepTimerTimeLeft),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .offset(y = 5.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                ResizableIconButton(
                    icon = Icons.Rounded.Bedtime,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    onClick = { showSleepTimerDialog = true }
                )
            }
        }

        Spacer(modifier = Modifier.width(7.dp))
    }

    // The dedicated lyrics button used to live here. Lyrics are now toggled the way older
    // OuterTune did it: tap the album cover, or use the player's overflow menu.

    Box(
        modifier = Modifier
            .offset(y = 5.dp)
            .size(36.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary)
    ) {
        ResizableIconButton(
            icon = if (currentSong?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.Center)
                .size(24.dp),
            onClick = playerConnection::toggleLike
        )
    }

    Spacer(modifier = Modifier.width(7.dp))

    Box(
        modifier = Modifier
            .offset(y = 5.dp)
            .size(36.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary)
    ) {
        ResizableIconButton(
            icon = Icons.Rounded.MoreVert,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(24.dp)
                .align(Alignment.Center),
            onClick = {
                menuState.show {
                    PlayerMenu(
                        mediaMetadata = mediaMetadata,
                        navController = navController,
                        playerBottomSheetState = playerSheetState,
                        onDismiss = menuState::dismiss
                    )
                }
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsContent(
    playerSheetState: BottomSheetState,
    queueSheetState: BottomSheetState,
    navController: NavController,
    queueBoard: QueueBoard,
    /**
     * The cover, supplied only in free placement. There it is one placeable block among the rest
     * and has to share their coordinate space, so the caller hands it over instead of drawing it
     * itself. Null everywhere else, and the caller keeps drawing it as it always did.
     */
    artwork: (@Composable () -> Unit)? = null,
) {
    val TAG = "ControlsContent()"
    if (PLAYER_DEBUG) Log.v(TAG, "PLR-CC-1")

    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()


    val isPlaying by playerConnection.isPlaying.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()

    val playPauseRoundness by animateDpAsState(
        targetValue = if (isPlaying) 24.dp else 36.dp,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "playPauseRoundness"
    )


    val seekIncrement by rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )


    val sliderStyle by rememberEnumPreference(
        key = SliderStyleKey,
        defaultValue = DEFAULT_SLIDER_STYLE
    )

    // Parsed once per change rather than per frame. A file that no longer parses - edited by
    // hand, or written by a newer editor after a downgrade - falls back to the built-in layout
    // instead of leaving the player empty.
    val layoutJson by rememberPreference(PlayerLayoutKey, "")
    val playerLayout = remember(layoutJson) {
        if (layoutJson.isBlank()) PlayerLayout.DEFAULT
        else PlayerLayout.parse(layoutJson).getOrDefault(PlayerLayout.DEFAULT)
    }

    // The same decision the queue handle makes, so the two cannot disagree about what is readable
    // over this background.
    val onBackgroundColor = rememberPlayerOnBackgroundColor(mediaMetadata)


    val playbackState by playerConnection.playbackState.collectAsState()
    var duration by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.duration)
    }

    var position by remember(playbackState) {
        mutableLongStateOf(playerConnection.player.currentPosition)
    }

    LaunchedEffect(playbackState) {
        if (playbackState == STATE_READY) {
            while (isActive) {
                delay(500)
                position = playerConnection.player.currentPosition
                duration = playerConnection.player.duration
            }
        }
    }


    var sliderPosition by remember {
        mutableStateOf<Long?>(null)
    }

    BoxWithConstraints() {
        val maxW = maxWidth
        val compactWidth = false
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hasImportedLayout = layoutJson.isNotBlank()
            val actionsVisible = playerLayout.isVisible(PlayerLayout.BlockId.ACTIONS)
            // action buttons for landscape (above title)
            if (compactWidth && actionsVisible && artwork == null) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = PlayerHorizontalPadding, end = PlayerHorizontalPadding, bottom = 16.dp)
                ) {
                    ActionButtons(playerSheetState, navController)
                }
            }

            // Each group is captured rather than emitted in place, so the imported layout can
            // decide the order and which of them appear at all. Nothing about how they are built
            // changed - only when they are called.
            val infoBlock: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding)
                ) {
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mediaMetadata?.title ?: "",
                                style = MaterialTheme.typography.titleLarge,
                                color = onBackgroundColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .basicMarquee(
                                        iterations = 1,
                                        initialDelayMillis = 3000
                                    )
                                    .clickable(enabled = mediaMetadata?.album != null) {
                                        navController.navigate("album/${mediaMetadata?.album!!.id}")
                                        playerSheetState.collapseSoft()
                                    }
                            )

                            Row {
                                mediaMetadata?.artists?.fastForEachIndexed { index, artist ->
                                    Text(
                                        text = artist.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = onBackgroundColor,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .basicMarquee(
                                                iterations = 1,
                                                initialDelayMillis = 5000
                                            )
                                            .clickable(enabled = artist.id != null) {
                                                navController.navigate("artist/${artist.id}")
                                                playerSheetState.collapseSoft()
                                            }
                                    )

                                    if (index != mediaMetadata?.artists?.lastIndex) {
                                        Text(
                                            text = ", ",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = onBackgroundColor
                                        )
                                    }
                                } ?: Text(
                                    text = "",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = onBackgroundColor,
                                    maxLines = 1,
                                )
                            }
                        }

                        // action buttons for portrait (inline with title). Not in free placement:
                        // there they are their own block with their own coordinates, and drawing
                        // them here as well would put two copies on screen.
                        if (!compactWidth && actionsVisible && artwork == null) {
                            ActionButtons(playerSheetState, navController)
                        }
                    }
                }

            }
            val progressBlock: @Composable () -> Unit = {
                Slider(
                    value = (sliderPosition ?: position).toFloat(),
                    valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
                    onValueChange = {
                        sliderPosition = it.toLong()
                        // slider too granular for this haptic to feel right
    //                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    },
                    onValueChangeFinished = {
                        sliderPosition?.let {
                            playerConnection.player.seekTo(it)
                            position = it
                        }
                        sliderPosition = null
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    },
                    thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                    track = { sliderState ->
                        PlayerSliderTrack(
                            sliderState = sliderState,
                            colors = SliderDefaults.colors(),
                            style = sliderStyle,
                            animate = isPlaying && sliderPosition == null
                        )
                    },
                    modifier = Modifier.padding(horizontal = PlayerHorizontalPadding)
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding + 4.dp)
                ) {
                    Text(
                        text = makeTimeString(sliderPosition ?: position),
                        style = MaterialTheme.typography.labelMedium,
                        color = onBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = if (duration != C.TIME_UNSET) makeTimeString(duration) else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = onBackgroundColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(12.dp))
            }
            val controlsBlock: @Composable () -> Unit = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PlayerHorizontalPadding)
                ) {
                    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()

                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = if (shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off,
                            modifier = Modifier
                                .size(32.dp)
                                .padding(4.dp)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            enabled = playerConnection.player.currentMediaItem != null,
                            onClick = {
                                playerConnection.triggerShuffle()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = Icons.Rounded.SkipPrevious,
                            enabled = canSkipPrevious,
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            onClick = {
                                if (playerConnection.player.currentMediaItem == null) {
                                    queueBoard.setCurrQueue()
                                }
                                playerConnection.player.seekToPrevious()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }

                    if (seekIncrement != SeekIncrement.OFF) {
                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = Icons.Rounded.FastRewind,
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.Center),
                                color = onBackgroundColor,
                                enabled = playerConnection.player.currentMediaItem != null,
                                onClick = {
                                    playerConnection.player.seekTo(playerConnection.player.currentPosition - seekIncrement.millisec)
                                }
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            // Same size whether or not lyrics are showing: the controls should not
                            // change shape just because the artwork was swapped for lyrics.
                            .size(if (maxW >= 320.dp) 72.dp else 42.dp)
                            .animateContentSize()
                            .clip(RoundedCornerShape(playPauseRoundness))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable {
                                if (playerConnection.player.currentMediaItem == null) {
                                    queueBoard.setCurrQueue()
                                    playerConnection.player.togglePlayPause()
                                } else if (playbackState == STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                                // play/pause is slightly harder haptic
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                    ) {
                        Image(
                            imageVector = if (playbackState == STATE_ENDED) Icons.Rounded.Replay else if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    if (seekIncrement != SeekIncrement.OFF) {
                        Box(modifier = Modifier.weight(1f)) {
                            ResizableIconButton(
                                icon = Icons.Rounded.FastForward,
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.Center),
                                color = onBackgroundColor,
                                enabled = playerConnection.player.currentMediaItem != null,
                                onClick = {
                                    //ExoPlayer seek increment can only be set in builder
                                    //playerConnection.player.seekForward()
                                    playerConnection.player.seekTo(playerConnection.player.currentPosition + seekIncrement.millisec)
                                }
                            )
                        }
                    }



                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = Icons.Rounded.SkipNext,
                            enabled = canSkipNext,
                            modifier = Modifier
                                .size(32.dp)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            onClick = {
                                playerConnection.player.seekToNext()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        ResizableIconButton(
                            icon = when (repeatMode) {
                                REPEAT_MODE_OFF -> R.drawable.repeat_off
                                REPEAT_MODE_ALL -> R.drawable.repeat_on
                                REPEAT_MODE_ONE -> R.drawable.repeat_one
                                else -> throw IllegalStateException()
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .padding(4.dp)
                                .align(Alignment.Center),
                            color = onBackgroundColor,
                            enabled = playerConnection.player.currentMediaItem != null,
                            onClick = {
                                playerConnection.player.toggleRepeatMode()
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                        )
                    }
                }

            }

            val actionsBlock: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ActionButtons(playerSheetState, navController)
                }
            }

            // What each block draws. The queue is not here: it is a bottom sheet anchored to the
            // bottom of the screen, so it has nothing to place.
            val contentFor: @Composable (PlayerLayout.BlockId) -> Unit = { id ->
                when (id) {
                    PlayerLayout.BlockId.ARTWORK -> artwork?.invoke()
                    PlayerLayout.BlockId.INFO -> infoBlock()
                    PlayerLayout.BlockId.PROGRESS -> progressBlock()
                    PlayerLayout.BlockId.CONTROLS -> controlsBlock()
                    PlayerLayout.BlockId.ACTIONS -> actionsBlock()
                    PlayerLayout.BlockId.QUEUE -> Unit
                }
            }

            // CONTROLS is emitted whatever the file says. Hiding the play button leaves a player
            // that cannot be paused from its own screen, and a layout file is not a good place to
            // discover that.
            fun shows(b: PlayerLayout.Block) = b.visible || b.id == PlayerLayout.BlockId.CONTROLS

            if (artwork != null) {
                // Free placement. Every block is drawn into one box the size of the player, each
                // one positioned by its own coordinates, in the order the file lists them - so a
                // block later in the list draws over an earlier one where they overlap.
                Box(modifier = Modifier.fillMaxSize()) {
                    playerLayout.blocks.forEach { b ->
                        if (shows(b) && b.id != PlayerLayout.BlockId.QUEUE) {
                            FreeBlock(b) { contentFor(b.id) }
                        }
                    }
                }
            } else {
                // ARTWORK is drawn by the caller in its own box above these, the QUEUE is a
                // bottom sheet, and ACTIONS is drawn inline with the title - emitting it here as
                // well would put two action rows on screen.
                val stacked = playerLayout.blocks.filter {
                    shows(it) &&
                            it.id != PlayerLayout.BlockId.QUEUE &&
                            it.id != PlayerLayout.BlockId.ARTWORK &&
                            it.id != PlayerLayout.BlockId.ACTIONS
                }
                stacked.forEachIndexed { i, b ->
                    if (hasImportedLayout) {
                        // Spacing and the size transform are things a file asked for. With no file
                        // there is nothing to apply, and applying the defaults anyway would add gaps
                        // and a wrapper the built-in layout never had - which is what "reset to
                        // default" is supposed to take away, not introduce.
                        if (i > 0) Spacer(Modifier.height(playerLayout.spacingDp.dp))
                        StackBlock(b) { contentFor(b.id) }
                    } else {
                        contentFor(b.id)
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerBackground(
    playerConnection: PlayerConnection,
    playerBackground: PlayerBackgroundStyle,
    showLyrics: Boolean,
    useDarkTheme: Boolean,
) {
    val TAG = "PlayerBackground"
    if (PLAYER_DEBUG) Log.v(TAG, "PLR_BG-1")

    val context = LocalContext.current

    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
            .fillMaxSize()
    ) {

        val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
        var gradientColors by remember {
            mutableStateOf<List<Color>>(emptyList())
        }


        // gradient colours
        LaunchedEffect(mediaMetadata, playerBackground) {
            val needsPalette = playerBackground == PlayerBackgroundStyle.GRADIENT ||
                    playerBackground == PlayerBackgroundStyle.LIQUID ||
                    playerBackground == PlayerBackgroundStyle.FOLLOW_THEME
            // Extraction itself is one cheap Palette pass over a 100x100 bitmap, not the thing
            // power saver needs to guard against - only the continuous Liquid animation actually
            // costs anything ongoing, and that already stops itself via `isActive` below. Skipping
            // extraction here left gradientColors permanently empty for as long as power saver was
            // on, which is what actually produced a solid-black background, not a slow one.
            if (!needsPalette) return@LaunchedEffect

            withContext(coilCoroutine) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(mediaMetadata?.getThumbnailModel(100, 100))
                        .allowHardware(false)
                        .build()
                )

                val bitmap = result.image?.toBitmap()?.extractGradientColors()
                bitmap?.let {
                    gradientColors = it
                }
            }
        }


        AnimatedContent(
            targetState = mediaMetadata,
            transitionSpec = {
                fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
            }
        ) { metadata ->
            if (playerBackground == PlayerBackgroundStyle.BLUR) {
                if (PLAYER_DEBUG) Log.v(TAG, "PLR-2.2a")
                AsyncImage(
                    model = metadata?.getThumbnailModel(100, 100),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(100.dp)
                        .alpha(0.5f)
                )
            }
        }

        AnimatedContent(
            targetState = gradientColors,
            transitionSpec = {
                fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
            }
        ) { colors ->
            if (playerBackground == PlayerBackgroundStyle.GRADIENT && colors.size >= 2) {
                if (PLAYER_DEBUG) Log.v(TAG, "PLR-2.2b")
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colors), alpha = 0.4f)
                )
            }

            // FOLLOW_THEME had no rendering branch at all previously, so it fell through to the
            // plain elevated-surface Box background - solid near-black in dark theme. Two soft,
            // overlapping blotches (one per dominant cover colour) rather than a flat blended wash,
            // since a flat blend of two very different colours is what looked wrong on some covers.
            if (playerBackground == PlayerBackgroundStyle.FOLLOW_THEME && colors.isNotEmpty()) {
                if (PLAYER_DEBUG) Log.v(TAG, "PLR-2.2f")
                FollowThemeBackground(colors = colors)
            }
        }

        if (playerBackground == PlayerBackgroundStyle.FROSTED) {
            if (PLAYER_DEBUG) Log.v(TAG, "PLR-2.2e")
            // Falls back to the theme's own sense of light/dark until the artwork has been
            // measured, so a track change never flashes text in the wrong colour.
            val coverIsLight = rememberCoverIsLight(mediaMetadata, enabled = true)
            FrostedBackground(
                mediaMetadata = mediaMetadata,
                isLight = coverIsLight ?: !useDarkTheme,
            )
        }

        if (playerBackground == PlayerBackgroundStyle.LIQUID) {
            if (PLAYER_DEBUG) Log.v(TAG, "PLR-2.2d")
            val isPlaying by playerConnection.isPlaying.collectAsState()
            LiquidBackground(
                colors = gradientColors,
                // Stop the animation clock whenever it cannot be appreciated: paused playback or
                // battery saver. The player sheet being collapsed already removes this from the
                // composition entirely.
                isActive = isPlaying && !context.isPowerSaver(),
            )
        }

        if (playerBackground != PlayerBackgroundStyle.FOLLOW_THEME && showLyrics) {
            if (PLAYER_DEBUG) Log.v(TAG, "PLR-2.2c")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (useDarkTheme) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.5f))
            )
        }
    }
}
