/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlayerButtonsKey
import com.dd3boh.outertune.constants.ShowEqualizerButtonKey
import com.dd3boh.outertune.constants.SleepTimerShowOnPlayerKey
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.menu.PlayerActionState
import com.dd3boh.outertune.ui.menu.PlayerMenu
import com.dd3boh.outertune.ui.menu.rememberPlayerActions
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberPreference

/**
 * The buttons as the person has arranged them.
 *
 * Until the setting is first saved, rebuilt from the two switches it replaced, so updating leaves
 * everyone's player exactly as it was.
 */
@Composable
fun rememberPlayerButtons(): List<PlayerButton> {
    val stored by rememberPreference(PlayerButtonsKey, defaultValue = "")
    val legacySleep by rememberPreference(SleepTimerShowOnPlayerKey, defaultValue = true)
    val legacyEqualizer by rememberPreference(ShowEqualizerButtonKey, defaultValue = true)
    return if (stored.isEmpty()) PlayerButton.fromLegacy(legacySleep, legacyEqualizer) else PlayerButton.decode(stored)
}

/** The title-row buttons, left to right in the stored order. */
@Composable
fun PlayerButtonsRow(
    playerSheetState: BottomSheetState,
    navController: NavController,
    buttons: List<PlayerButton> = rememberPlayerButtons(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val metadata = mediaMetadata ?: return

    val actions = rememberPlayerActions(
        mediaMetadata = metadata,
        navController = navController,
        playerBottomSheetState = playerSheetState,
        onDone = {},
        onOpenMenu = {
            menuState.show {
                PlayerMenu(
                    mediaMetadata = metadata,
                    navController = navController,
                    playerBottomSheetState = playerSheetState,
                    onDismiss = menuState::dismiss,
                )
            }
        },
    ) ?: return

    buttons.forEachIndexed { index, button ->
        if (index > 0) Spacer(modifier = Modifier.width(7.dp))
        PlayerButtonDisc(
            button = button,
            state = actions,
            onClick = { actions.run(button) },
            modifier = Modifier.offset(y = 5.dp),
        )
    }
}

/**
 * One button: a filled disc with the action's glyph, showing the action's state where it has one -
 * a filled heart, a tick once downloaded, the time left on a running sleep timer.
 *
 * [state] is null for the settings preview, which draws the resting glyph of each.
 */
@Composable
fun PlayerButtonDisc(
    button: PlayerButton,
    state: PlayerActionState?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val available = state?.isAvailable?.invoke(button) ?: true
    val colours = MaterialTheme.colorScheme
    val base = modifier
        .height(size)
        .clip(RoundedCornerShape(size))
        .background(colours.primary)
        .alpha(if (available) 1f else 0.45f)
        .let { if (onClick != null && available) it.clickable(onClick = onClick) else it }

    if (button == PlayerButton.SLEEP && state?.sleepTimerActive == true) {
        Box(modifier = base.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text = makeTimeString(state.sleepTimerLeftMs),
                color = colours.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
        return
    }

    Box(modifier = base.size(size), contentAlignment = Alignment.Center) {
        val description = stringResource(button.title)
        val tint = colours.onPrimary
        val iconModifier = Modifier.size(size * 2 / 3)
        when (button) {
            PlayerButton.LIKE -> Icon(
                painterResource(if (state?.liked == true) R.drawable.favorite else R.drawable.favorite_border),
                description, tint = tint, modifier = iconModifier,
            )
            PlayerButton.ARTIST -> Icon(painterResource(R.drawable.artist), description, tint = tint, modifier = iconModifier)
            PlayerButton.ALBUM -> Icon(painterResource(R.drawable.album), description, tint = tint, modifier = iconModifier)
            PlayerButton.DOWNLOAD -> when (state?.downloadState) {
                Download.STATE_QUEUED, Download.STATE_DOWNLOADING -> CircularProgressIndicator(
                    color = tint, strokeWidth = 2.dp, modifier = Modifier.size(size / 2),
                )
                Download.STATE_COMPLETED -> Icon(Icons.Rounded.OfflinePin, description, tint = tint, modifier = iconModifier)
                else -> Icon(Icons.Rounded.Download, description, tint = tint, modifier = iconModifier)
            }
            else -> Icon(
                when (button) {
                    PlayerButton.MENU -> Icons.Rounded.MoreVert
                    PlayerButton.SLEEP -> Icons.Rounded.Bedtime
                    PlayerButton.EQUALIZER -> Icons.Rounded.Equalizer
                    PlayerButton.LYRICS -> Icons.Rounded.Lyrics
                    PlayerButton.RADIO -> Icons.Rounded.Radio
                    PlayerButton.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
                    PlayerButton.PLAYLIST -> Icons.AutoMirrored.Rounded.PlaylistAdd
                    PlayerButton.LIBRARY -> if (state?.inLibrary == true) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd
                    PlayerButton.SHARE -> Icons.Rounded.Share
                    PlayerButton.DETAILS -> Icons.Rounded.Info
                    else -> Icons.Rounded.Tune
                },
                description, tint = tint, modifier = iconModifier,
            )
        }
    }
}
