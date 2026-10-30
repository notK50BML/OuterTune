/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.Lyrics
import com.dd3boh.outertune.utils.rememberPreference

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun Thumbnail(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
    showLyricsOnClick: Boolean = false,
    customMediaMetadata: MediaMetadata? = null,
    /** From an imported layout (see PlayerLayout): the side inset, corner radius and size of the cover. */
    horizontalPadding: Dp = PlayerHorizontalPadding,
    cornerRadius: Dp = ThumbnailCornerRadius * 2,
    sizeScale: Float = 1f,
) {
    val context = LocalContext.current
    val currentView = LocalView.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return

    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)

    val playerMediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()
    val mediaMetadata = customMediaMetadata ?: playerMediaMetadata

    DisposableEffect(showLyrics) {
        currentView.keepScreenOn = showLyrics
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = !showLyrics && error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {

            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f, false)
                ) {
                    // The player cover is the largest artwork in the app; ask the CDN for it at
                    // the real drawn size instead of upscaling a small library thumbnail.
                    val density = LocalDensity.current
                    val coverPx = remember(maxWidth, maxHeight, density) {
                        with(density) { maxOf(maxWidth, maxHeight).roundToPx() }
                    }

                    AsyncImage(
                        model = mediaMetadata?.getThumbnailModel(coverPx, coverPx),
                        contentDescription = null,
                        // Cropped, not fitted: a song that is only a music video has a 16:9
                        // thumbnail, which fitted into this square left a band of nothing above
                        // and below it - see ItemThumbnail.
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            // Scaled as a transform about its centre - the layout editor's "size" -
                            // so a bigger or smaller cover moves nothing else, and the radius is
                            // divided back out so the corners come out at the size asked for.
                            .graphicsLayer {
                                scaleX = sizeScale
                                scaleY = sizeScale
                            }
                            .clip(RoundedCornerShape(cornerRadius / sizeScale.coerceAtLeast(0.05f)))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = showLyricsOnClick,
                            ) {
                                showLyrics = !showLyrics
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showLyrics && error == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Lyrics(sliderPositionProvider = sliderPositionProvider)
        }

        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            error?.let { error ->
                ThumbnailPlaybackError(
                    error = error,
                    retry = playerConnection.player::prepare
                )
            }
        }
    }
}
