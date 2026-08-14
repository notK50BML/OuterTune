/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.LyricClickable
import com.dd3boh.outertune.constants.LyricFontSizeKey
import com.dd3boh.outertune.constants.LyricKaraokeEnable
import com.dd3boh.outertune.constants.LyricUpdateSpeed
import com.dd3boh.outertune.constants.LyricsPosition
import com.dd3boh.outertune.constants.LyricsTextPositionKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.QueuePeekHeight
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.Speed
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.extensions.isPowerSaver
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.shimmer.ShimmerHost
import com.dd3boh.outertune.ui.component.shimmer.TextPlaceholder
import com.dd3boh.outertune.ui.menu.LyricsMenu
import com.dd3boh.outertune.ui.player.rememberPlayerOnBackgroundColor
import com.dd3boh.outertune.ui.utils.fadingEdge
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import java.io.File
import kotlin.time.Duration.Companion.seconds

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun Lyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val density = LocalDensity.current
    var (showLyrics, onShowLyricsChange) = rememberPreference(ShowLyricsKey, false)
    val landscapeOffset = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.CENTER)
    val lyricsFontSize by rememberPreference(LyricFontSizeKey, 20)

    val lyricsClickable by rememberPreference(LyricClickable, true)
    val lyricsFancy by rememberPreference(LyricKaraokeEnable, false)
    val lyricsUpdateSpeed by rememberEnumPreference(LyricUpdateSpeed, Speed.MEDIUM)

    // Asking the power manager costs a binder call, so it is asked once per song rather than once
    // per lyric line per recomposition. Keyed rather than cached outright: caching it for the life
    // of the composable meant that turning battery saver off never brought the sweep back.
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val powerSaver = remember(mediaMetadata) { context.isPowerSaver() }
    val karaokeEnabled = lyricsFancy && !powerSaver

    // How often the *current line* is recomputed, which is what drives highlighting and scrolling.
    // The word sweep does not go through here: it redraws itself every frame.
    val lyricRefreshRate = remember(lyricsUpdateSpeed, karaokeEnabled) {
        if (karaokeEnabled) lyricsUpdateSpeed.toLrcRefreshMillis() else Speed.SLOW.toLrcRefreshMillis()
    }


    // NOTE: lyricsModel is the current display lyrics that is updated by playerLyrics AND/OR manually
    val playerLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    var lyricsModel by remember { mutableStateOf(playerLyrics) }

    val lines: SnapshotStateList<LyricLine> = remember { mutableStateListOf<LyricLine>() }

    val isSynced = remember(lyricsModel) {
        lyricsModel is SemanticLyrics.SyncedLyrics
    }

    LaunchedEffect(playerLyrics) {
        lyricsModel = playerLyrics
    }

    LaunchedEffect(lyricsModel) {
        lines.clear()
        lyricsModel?.let { model ->
            if (isSynced) {
                lines.addAll((model as SemanticLyrics.SyncedLyrics).text)
            } else {
                lines.add(
                    LyricLine(
                        model.unsyncedText.joinToString { "${it.first}\n" }, 0L.toULong(), 0L.toULong(),
                        null, null, false
                    )
                )
            }
        }
    }

    // Lyrics are drawn over the player background, so they need the colour that was measured from
    // it - the theme's secondary is chosen against a surface that is not there, and over a pale
    // album cover it is close to invisible. With the background set to follow the theme this
    // resolves to secondary anyway, so nothing changes for anyone not using artwork behind the
    // player.
    val playerBackgroundStyle by rememberEnumPreference(key = PlayerBackgroundStyleKey, defaultValue = DEFAULT_PLAYER_BACKGROUND)
    val textColor = rememberPlayerOnBackgroundColor(mediaMetadata, playerBackgroundStyle)
    // Lines already sung keep their own shade of the same colour rather than the theme's primary,
    // which has the same problem: readable against the theme, arbitrary against an album cover.
    val prevTextColor = textColor.copy(alpha = CONSUMED_ALPHA)

    var currentLineIndex by remember {
        mutableIntStateOf(-1)
    }
    // Because LaunchedEffect has delay, which leads to inconsistent with current line color and scroll animation,
    // we use deferredCurrentLineIndex when user is scrolling
    var deferredCurrentLineIndex by rememberSaveable {
        mutableIntStateOf(0)
    }

    var lastPreviewTime by rememberSaveable {
        mutableLongStateOf(0L)
    }
    var isSeeking by remember {
        mutableStateOf(false)
    }

    /**
     * Playback position for the word sweep. Held apart from [currentLineIndex] because it is only
     * ever read from a draw lambda, so writing it every frame costs a redraw of the two or three
     * lines around the playhead and no recomposition at all.
     */
    val karaokePosition = remember { mutableLongStateOf(0L) }

    LaunchedEffect(lyricsModel) {
        if (lyricsModel == null || !isSynced || (lyricsModel as SemanticLyrics.SyncedLyrics).text.isEmpty()) {
            currentLineIndex = -1
            return@LaunchedEffect
        }
        while (isActive) {
            // TODO: likely can improve power usage by disabling lyric refresh
            delay(lyricRefreshRate)
            val sliderPosition = sliderPositionProvider()
            isSeeking = sliderPosition != null
            // Scrubbing has to move the highlight even while paused, which is why this no longer
            // skips the whole tick when the player is stopped.
            if (!playerConnection.isPlaying.value && !isSeeking) continue
            currentLineIndex = findCurrentLineIndex(lines, sliderPosition ?: playerConnection.player.currentPosition)
        }
    }

    /**
     * Advance [karaokePosition] once per frame while the sweep is visible.
     *
     * ExoPlayer only moves `currentPosition` every few hundred milliseconds, so between its updates
     * the wall clock carries the sweep: the last position the player actually reported is latched
     * together with the time it was reported, and the difference is added on. A seek changes
     * `currentPosition`, which re-latches on the very next frame, so the estimate never drifts
     * further than one player tick and lands exactly right after a seek.
     */
    LaunchedEffect(lyricsModel, karaokeEnabled, isSynced) {
        if (!karaokeEnabled || !isSynced || lyricsModel == null) return@LaunchedEffect
        // Seed it before waiting for anything. Left at its initial zero the first frame draws an
        // entirely unswept line, which is indistinguishable from the sweep not working at all.
        karaokePosition.longValue = sliderPositionProvider() ?: playerConnection.player.currentPosition
        var latchedPosition = Long.MIN_VALUE
        var latchedAt = 0L
        while (isActive) {
            val scrubbed = sliderPositionProvider()
            if (scrubbed != null) {
                karaokePosition.longValue = scrubbed
                latchedPosition = Long.MIN_VALUE // re-latch against the player once the scrub ends
            } else {
                val now = System.currentTimeMillis()
                val reported = playerConnection.player.currentPosition
                if (reported != latchedPosition) {
                    latchedPosition = reported
                    latchedAt = now
                }
                karaokePosition.longValue =
                    latchedPosition + if (playerConnection.player.isPlaying) now - latchedAt else 0L
            }
            // Frame-synced while something is moving, idle polling otherwise, so a paused player
            // with the lyrics open does not hold the choreographer awake.
            if (scrubbed != null || playerConnection.player.isPlaying) {
                withFrameMillis { }
            } else {
                delay(IDLE_KARAOKE_REFRESH_MS)
            }
        }
    }

    LaunchedEffect(isSeeking, lastPreviewTime) {
        if (isSeeking) {
            lastPreviewTime = 0L
        } else if (lastPreviewTime != 0L) {
            delay(LyricsPreviewTime)
            lastPreviewTime = 0L
        }
    }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(currentLineIndex, lastPreviewTime) {
        /**
         * Count number of new lines in a lyric
         */
        fun countNewLine(str: String) = str.count { it == '\n' }

        /**
         * Calculate the lyric offset Based on how many lines (\n chars)
         */
        fun calculateOffset() = with(density) {
            if (landscapeOffset) {
                16.dp.toPx().toInt() * countNewLine(lines[currentLineIndex].text) // landscape sits higher by default
            } else {
                20.dp.toPx().toInt() * countNewLine(lines[currentLineIndex].text)
            }
        }

        if (!isSynced) return@LaunchedEffect
        if (currentLineIndex != -1) {
            deferredCurrentLineIndex = currentLineIndex
            if (lastPreviewTime == 0L) {
                if (isSeeking) {
                    lazyListState.scrollToItem(
                        currentLineIndex,
                        with(density) { 36.dp.toPx().toInt() } + calculateOffset())
                } else {
                    lazyListState.animateScrollToItem(
                        currentLineIndex,
                        with(density) { 36.dp.toPx().toInt() } + calculateOffset())
                }
            }
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = WindowInsets.systemBars
                .only(WindowInsetsSides.Top)
                .add(WindowInsets(top = maxHeight / 2, bottom = maxHeight / 2))
                .asPaddingValues(),
            modifier = Modifier
                .fadingEdge(vertical = 64.dp)
                .nestedScroll(remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            lastPreviewTime = System.currentTimeMillis()
                            return super.onPostScroll(consumed, available, source)
                        }

                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                            lastPreviewTime = System.currentTimeMillis()
                            return super.onPostFling(consumed, available)
                        }
                    }
                })
        ) {
            val displayedCurrentLineIndex = if (isSeeking) deferredCurrentLineIndex else currentLineIndex

            if (lyricsModel == null) {
                item {
                    ShimmerHost {
                        repeat(10) {
                            Box(
                                contentAlignment = when (lyricsTextPosition) {
                                    LyricsPosition.LEFT -> Alignment.CenterStart
                                    LyricsPosition.CENTER -> Alignment.Center
                                    LyricsPosition.RIGHT -> Alignment.CenterEnd
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 4.dp)
                            ) {
                                TextPlaceholder()
                            }
                        }
                    }
                }
            } else if (lyricsModel != uninitializedLyric) {
                itemsIndexed(
                    items = lines
                ) { index, item ->
                    var lyricFontSizeAdjusted = lyricsFontSize
                    if (item.speaker?.isBackground == true) {
                        lyricFontSizeAdjusted = (lyricFontSizeAdjusted * 0.75).toInt()
                    }
                    if (item.isTranslated) {
                        lyricFontSizeAdjusted = (lyricFontSizeAdjusted * 0.75).toInt()
                    }

                    Column(
                        horizontalAlignment = when (lyricsTextPosition) {
                            LyricsPosition.LEFT -> Alignment.Start
                            LyricsPosition.CENTER -> Alignment.CenterHorizontally
                            LyricsPosition.RIGHT -> Alignment.End
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 24.dp,
                                top = if (item.isTranslated) 0.dp else 8.dp,
                                end = 24.dp,
                                bottom = if (item.isTranslated) 16.dp else 8.dp,
                            )
                            // we allow clicking on blank lyrics, ignore item.isClickable
                            .clickable(enabled = isSynced && lyricsClickable) {
                                playerConnection.player.seekTo(item.start.toLong())
                                currentLineIndex = index
                                karaokePosition.longValue = item.start.toLong()
                                lastPreviewTime = 0L
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                    ) {
                        val textAlign = when (lyricsTextPosition) {
                            LyricsPosition.LEFT -> TextAlign.Left
                            LyricsPosition.CENTER -> TextAlign.Center
                            LyricsPosition.RIGHT -> TextAlign.Right
                        }
                        val isHighlighted =
                            index == displayedCurrentLineIndex ||
                                (index == displayedCurrentLineIndex + 1 && item.isTranslated)
                        // Which lines are behind the playhead is decided by index rather than by
                        // comparing the playback position, so the position does not have to be read
                        // during composition and the whole list does not recompose as it advances.
                        val isConsumed = displayedCurrentLineIndex >= 0 && index < displayedCurrentLineIndex

                        val words = item.words
                        // The line being sung and the one after it, and nothing else. Taking the
                        // next line too means a line is already being drawn this way before it
                        // starts, so there is no swap at the moment it does - and an unswept line
                        // is drawn exactly as the plain path draws an upcoming one, so the two are
                        // indistinguishable. Every other line is cheaper as a plain Text.
                        val karaoke = karaokeEnabled && isSynced && !words.isNullOrEmpty() &&
                            if (displayedCurrentLineIndex < 0) index == 0
                            else index == displayedCurrentLineIndex || index == displayedCurrentLineIndex + 1

                        if (karaoke) {
                            KaraokeLyricLine(
                                text = item.text,
                                words = words.orEmpty(),
                                style = LocalTextStyle.current.copy(
                                    fontSize = lyricFontSizeAdjusted.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = textAlign,
                                ),
                                sungColor = textColor,
                                unsungColor = textColor.copy(alpha = UNSUNG_ALPHA),
                                positionProvider = { karaokePosition.longValue },
                            )
                        } else {
                            Text(
                                text = item.text,
                                fontSize = lyricFontSizeAdjusted.sp,
                                color = if (isConsumed && !isHighlighted) prevTextColor else textColor,
                                textAlign = textAlign,
                                fontWeight = FontWeight.Bold,
                                // prevTextColor already carries its own alpha, so a consumed line
                                // must not be faded a second time on top of it.
                                modifier = Modifier.alpha(
                                    if (!isSynced || isHighlighted || isConsumed) 1f else UNSUNG_ALPHA
                                )
                            )
                        }
                    }
                }
            }
        }

        if (lyricsModel == uninitializedLyric) {
            Text(
                text = stringResource(R.string.lyrics_not_found),
                fontSize = lyricsFontSize.sp,
                color = textColor,
                textAlign = when (lyricsTextPosition) {
                    LyricsPosition.LEFT -> TextAlign.Left
                    LyricsPosition.CENTER -> TextAlign.Center
                    LyricsPosition.RIGHT -> TextAlign.Right
                },
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        mediaMetadata?.let { mediaMetadata ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 12.dp,
                        // In landscape the queue's collapsed peek strip (its handle) is drawn as an
                        // overlay across the bottom of this same pane, so flush-bottom here means
                        // sitting right underneath it. Lift clear of that strip's height instead of
                        // just guessing at a fixed offset.
                        bottom = if (landscapeOffset) QueuePeekHeight * 1.2f + 8.dp else 0.dp,
                    )
            ) {
                IconButton(
                    onClick = { onShowLyricsChange(false) }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = textColor
                    )
                }
                IconButton(
                    onClick = {
                        menuState.show {
                            LyricsMenu(
                                lyricsProvider = {
                                    var dbLyric = runBlocking(Dispatchers.IO) {
                                        playerConnection.service.database.lyrics(mediaMetadata.id).first()
                                    }

                                    // eye bleach to try to load local file for editor
                                    if (dbLyric == null && mediaMetadata.localPath != null) {
                                        LrcUtils.loadLyricsFile(File(mediaMetadata.localPath))?.let {
                                            dbLyric = LyricsEntity(mediaMetadata.id, it)
                                        }
                                    }

                                    dbLyric
                                },
                                mediaMetadataProvider = { mediaMetadata },
                                onRefreshRequest = { lyricsModel = it },
                                onDismiss = menuState::dismiss,
                            )
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = null,
                        tint = textColor
                    )
                }
            }
        }
    }
}

/**
 * Get current position in lyric line list
 */
fun findCurrentLineIndex(lines: List<LyricLine>, position: Long): Int {
    // ExoPlayer legitimately reports a transient negative position around seeks and track
    // transitions. position.toUInt() on a negative Long wraps to a huge unsigned value, so every
    // real line.start compares as smaller than it - the loop below never finds a "current" line
    // and falls through to the *last* one, which is what made the highlighted line snap to the
    // end of the song and stay there until the next real position update. Treat "before the song
    // started" the same way position 0 is treated instead of letting it wrap.
    val positionULong = position.coerceAtLeast(0L).toULong()
    for (index in lines.indices) {
        if (lines[index].start > positionULong) {
            return if (index > 0 && lines[index - 1].isTranslated) index - 2 else index - 1
        }
    }
    return if (lines[lines.lastIndex].isTranslated) lines.lastIndex - 1 else lines.lastIndex
}

const val animateScrollDuration = 300L
val LyricsPreviewTime = 7.seconds

/** How dim the not-yet-sung part of a karaoke line is, relative to the sung part. */
private const val UNSUNG_ALPHA = 0.5f

/** How dim a line that has already been sung is. Kept above [UNSUNG_ALPHA] so the two read apart. */
private const val CONSUMED_ALPHA = 0.65f

/**
 * How often the karaoke position is refreshed when nothing is moving. Only exists so that a seek
 * made while paused still lands on screen; the sweep is frame-synced whenever it is actually moving.
 */
private const val IDLE_KARAOKE_REFRESH_MS = 250L
