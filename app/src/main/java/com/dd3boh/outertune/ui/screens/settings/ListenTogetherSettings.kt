/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.listentogether.ListenTogetherMode
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.viewmodels.ListenTogetherViewModel
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ListenTogetherViewModel = hiltViewModel(),
) {
    val manager = viewModel.manager
    val mode by manager.mode.collectAsStateWithLifecycle()
    val listeners by manager.listeners.collectAsStateWithLifecycle()
    val follower by manager.followerState.collectAsStateWithLifecycle()
    val error by manager.error.collectAsStateWithLifecycle()

    // Keyed on mode, not remembered once. Browsing needs to know this device's own advertised name
    // in order to leave it out, and that name does not exist until hosting starts - a flow built
    // before then would offer the host the chance to follow itself.
    val hostsFlow = remember(mode) { viewModel.discoverHosts() }
    val hosts by hostsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // Resolved once. It reads a system setting through the ContentResolver, and this screen
    // recomposes every second as the drift figure updates - so left inline it would be main-thread
    // IO on a one-second timer.
    val deviceName = remember { manager.deviceName() }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        error?.let {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                PreferenceEntry(
                    title = { Text(it) },
                    icon = { Icon(Icons.Rounded.Info, null) },
                    onClick = {},
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (mode) {
            ListenTogetherMode.HOSTING -> {
                PreferenceGroupTitle(title = stringResource(R.string.lt_sharing))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    PreferenceEntry(
                        title = { Text(deviceName) },
                        description = stringResource(R.string.lt_sharing_as),
                        icon = { Icon(Icons.Rounded.CastConnected, null) },
                        onClick = {},
                    )
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.lt_stop_sharing)) },
                        icon = { Icon(Icons.Rounded.Stop, null) },
                        onClick = { manager.stop() },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                PreferenceGroupTitle(
                    title = stringResource(R.string.lt_listeners, listeners.size)
                )
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    if (listeners.isEmpty()) {
                        // Not an error, and worth saying so. The most common reason nobody has
                        // joined is simply that nobody has opened this screen on the other device.
                        Hint(stringResource(R.string.lt_waiting_for_listeners))
                    } else {
                        listeners.forEach { listener ->
                            PreferenceEntry(
                                title = { Text(listener.name) },
                                description = listener.address,
                                icon = { Icon(Icons.Rounded.Person, null) },
                                onClick = {},
                            )
                        }
                    }
                }
            }

            ListenTogetherMode.FOLLOWING -> {
                PreferenceGroupTitle(title = stringResource(R.string.lt_following))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    PreferenceEntry(
                        title = { Text(follower.hostName ?: stringResource(R.string.lt_connecting)) },
                        description = followerStatus(follower.synced, follower.driftMs),
                        icon = { Icon(Icons.Rounded.Cast, null) },
                        onClick = {},
                    )
                    follower.track?.let { track ->
                        PreferenceEntry(
                            title = { Text(track.title) },
                            description = track.artist,
                            icon = { Icon(Icons.Rounded.MusicNote, null) },
                            onClick = {},
                        )
                    }
                    if (follower.unavailable) {
                        // A file on the host's own storage. There is nothing to fetch, so saying so
                        // is the whole of the correct behaviour - retrying would never succeed.
                        Hint(stringResource(R.string.lt_host_local_file))
                    }
                    if (follower.missingTrack) {
                        Hint(stringResource(R.string.lt_track_unavailable))
                    }
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.lt_leave)) },
                        icon = { Icon(Icons.Rounded.Stop, null) },
                        onClick = { manager.stop() },
                    )
                }
            }

            ListenTogetherMode.OFF -> {
                PreferenceGroupTitle(title = stringResource(R.string.lt_share))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.lt_start_sharing)) },
                        description = stringResource(R.string.lt_start_sharing_summary),
                        icon = { Icon(Icons.Rounded.Cast, null) },
                        onClick = { manager.startHosting() },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                PreferenceGroupTitle(title = stringResource(R.string.lt_nearby))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    if (hosts.isEmpty()) {
                        // The list only ever contains devices already sharing, so an empty list is
                        // ambiguous between "still looking" and "nobody is sharing". Saying both,
                        // with the one thing the user can actually check, beats an endless spinner.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.lt_looking),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.lt_looking_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        hosts.forEach { host ->
                            PreferenceEntry(
                                title = { Text(host.name) },
                                description = host.address.hostAddress,
                                icon = { Icon(Icons.Rounded.Devices, null) },
                                onClick = { manager.join(host) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.lt_how_it_works)) },
                description = stringResource(R.string.lt_how_it_works_summary),
                icon = { Icon(Icons.Rounded.Wifi, null) },
                onClick = {},
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.lt_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
        },
        scrollBehavior = scrollBehavior,
        windowInsets = TopBarInsets,
    )
}

/**
 * How well the follower is keeping up, in words rather than a number.
 *
 * A raw millisecond figure invites the reader to worry about a value they cannot act on, and the
 * only distinction that matters to a listener is whether the two devices sound like one.
 */
@Composable
private fun followerStatus(synced: Boolean, driftMs: Long): String = when {
    !synced -> stringResource(R.string.lt_measuring)
    abs(driftMs) < 40 -> stringResource(R.string.lt_in_sync)
    abs(driftMs) < 250 -> stringResource(R.string.lt_adjusting)
    else -> stringResource(R.string.lt_catching_up)
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
    )
}
