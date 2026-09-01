/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A vertical slice of the desktop client: search YouTube Music, pick a song, hear it.
 *
 * Deliberately one thin path rather than a scaffold. The Android app's screens cannot simply be
 * recompiled here - they reach for Hilt, Room, DataStore and MediaSession through composition
 * locals, none of which exist off Android - so the real work of porting the UI is rebuilding that
 * plumbing underneath the same composables. Taking one path all the way to audio first surfaces
 * exactly which pieces of plumbing are needed, instead of discovering them after a week of
 * scaffolding.
 *
 * What is deliberately missing, so it is not mistaken for finished: no queue, no library, no
 * database, no seeking, no pause. A song is fetched in full before it starts, so there is a pause
 * of a second or two on a big track - streaming playback is a later change to [DesktopPlayer], not
 * to this file.
 */
fun main() = application {
    // The Android app does this in App.onCreate. Without it YouTube answers 400 INVALID_ARGUMENT,
    // which reads like a platform problem and is not one.
    remember {
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        runCatching { kotlinx.coroutines.runBlocking { YouTube.visitorData().getOrNull() } }
            .getOrNull()?.let { YouTube.visitorData = it }
    }

    val player = remember { DesktopPlayer() }
    Window(
        onCloseRequest = { player.stop(); exitApplication() },
        title = "OuterTune",
        state = rememberWindowState(width = 900.dp, height = 640.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                SearchAndPlay(player)
            }
        }
    }
}

@Composable
private fun SearchAndPlay(player: DesktopPlayer) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val playback by player.state.collectAsState()

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        searching = true
        error = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                YouTube.search(text, YouTube.SearchFilter.FILTER_SONG)
            }
            outcome.onSuccess { page ->
                results = page.items.filterIsInstance<SongItem>()
                if (results.isEmpty()) error = "No songs matched that."
            }.onFailure {
                error = "${it::class.simpleName}: ${it.message}"
            }
            searching = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search YouTube Music") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = ::search, enabled = !searching) { Text("Search") }
        }

        NowPlaying(playback, onStop = player::stop)

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (searching) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results) { song ->
                SongRow(song) { player.play(scope, song.id, song.title) }
            }
        }
    }
}

@Composable
private fun NowPlaying(playback: PlaybackState, onStop: () -> Unit) {
    val label = when (playback) {
        is PlaybackState.Idle -> null
        is PlaybackState.Loading -> "Loading \"${playback.title}\"…"
        is PlaybackState.Playing -> "Playing \"${playback.title}\""
        is PlaybackState.Failed -> "Could not play \"${playback.title}\" - ${playback.reason}"
    } ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (playback is PlaybackState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (playback is PlaybackState.Playing || playback is PlaybackState.Loading) {
            Button(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun SongRow(song: SongItem, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(song.title, fontWeight = FontWeight.Medium)
        Text(
            text = song.artists.joinToString { it.name }.ifBlank { "Unknown artist" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
