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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * What is deliberately missing, so it is not mistaken for finished: no real database - the library
 * is a text file, see [LibraryStore] - no playlists, no seeking. A song is also fetched in full before it starts, so there is a wait of a
 * second or two on a big track - the upside being that once audio starts there is no network left
 * to fail, so a mid-song 403 cannot happen. Streaming playback would remove the wait and introduce
 * exactly that failure, and is a change to [DesktopPlayer] rather than to this file.
 */
fun main() = application {
    // Set here because it needs no network call, and search will not work without it - YouTube
    // answers 400 INVALID_ARGUMENT, which reads like a platform problem and is not one.
    remember { YouTube.locale = YouTubeLocale(gl = "US", hl = "en") }

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

    val library = remember { LibraryStore() }
    val playerQueue = remember { PlayerQueue(player, scope, onPlayed = { library.recordPlay(it.stored()) }) }
    val queue by playerQueue.state.collectAsState()
    val recent by library.recentlyPlayed.collectAsState()
    // Read so that liking a song recomposes the heart; the store's own flow is the source of truth.
    val likedSongs by library.liked.collectAsState()

    // visitorData needs a network call, so it cannot be set the way locale is. It used to be
    // fetched with runBlocking during composition, on the AWT thread, where a failure was silent -
    // and a silent failure here does not look like one: search still works, because search only
    // needs the locale, while every player request comes back refused. "Found the track, cannot
    // play it" was that, and it was invisible because nothing reported the state of this call.
    var session by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) {
            YouTube.visitorData().fold(
                onSuccess = { YouTube.visitorData = it; "ready" },
                onFailure = { "no session - playback will be refused (${it::class.simpleName}: ${it.message})" },
            )
        }
    }

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

        // Shown only while connecting or when it failed - a working session needs no announcement.
        when (val status = session) {
            null -> Text(
                "connecting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            "ready" -> Unit
            else -> Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        NowPlaying(
            playback = playback,
            queue = queue,
            onStop = playerQueue::clear,
            onTogglePause = player::togglePause,
            onNext = playerQueue::next,
            onPrevious = playerQueue::previous,
            liked = queue.current?.let { library.isLiked(it.id) } == true,
            onToggleLike = { queue.current?.let { library.toggleLiked(it.stored()) } },
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (searching) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // Results, or - before a search has been run - what was played last time. An empty
            // window with nothing but a search box gives no reason to believe the app remembers
            // anything, which it now does.
            Column(modifier = Modifier.weight(2f)) {
                if (results.isNotEmpty()) {
                    LazyColumn {
                        itemsIndexed(results) { index, song ->
                            SongRow(song, playing = song.id == queue.current?.id) {
                                playerQueue.play(results, index)
                            }
                        }
                    }
                } else {
                    RecentlyPlayed(recent) { stored ->
                        playerQueue.play(listOf(stored.toSongItem()), 0)
                    }
                }
            }

            if (queue.songs.isNotEmpty()) {
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        "Queue (${queue.index + 1}/${queue.songs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn {
                        itemsIndexed(queue.songs) { index, song ->
                            SongRow(song, playing = index == queue.index) { playerQueue.jumpTo(index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyPlayed(recent: List<StoredSong>, onPlay: (StoredSong) -> Unit) {
    if (recent.isEmpty()) return
    Column {
        Text(
            "Recently played",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn {
            items(recent) { song ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(song) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Text(song.title, fontWeight = FontWeight.Medium)
                    Text(
                        text = song.artists.ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(
    playback: PlaybackState,
    queue: QueueState,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    liked: Boolean,
    onToggleLike: () -> Unit,
) {
    val label = when (playback) {
        is PlaybackState.Idle -> null
        is PlaybackState.Loading -> "Loading \"${playback.title}\"…"
        is PlaybackState.Playing -> "Playing \"${playback.title}\""
        is PlaybackState.Paused -> "Paused \"${playback.title}\""
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
        val active = playback is PlaybackState.Playing || playback is PlaybackState.Paused ||
            playback is PlaybackState.Loading

        if (active) {
            TextButton(onClick = onToggleLike) { Text(if (liked) "♥" else "♡") }
        }
        Button(onClick = onPrevious, enabled = queue.hasPrevious) { Text("Prev") }
        if (playback is PlaybackState.Playing || playback is PlaybackState.Paused) {
            Button(onClick = onTogglePause) {
                Text(if (playback is PlaybackState.Paused) "Resume" else "Pause")
            }
        }
        Button(onClick = onNext, enabled = queue.hasNext) { Text("Next") }
        if (active) {
            Button(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun SongRow(song: SongItem, playing: Boolean = false, onPlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            song.title,
            fontWeight = FontWeight.Medium,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = song.artists.joinToString { it.name }.ifBlank { "Unknown artist" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The stored shape of a search result - only what the library needs to show it and replay it. */
private fun SongItem.stored() = StoredSong(
    id = id,
    title = title,
    artists = artists.joinToString { it.name },
)

/**
 * A stored song back as a playable item.
 *
 * The artist list collapses to a single unnamed entry: the store keeps artist names as one display
 * string rather than as structured artists, which is enough to show a row and enough to play it,
 * and is exactly the kind of thing a real database would keep properly.
 */
private fun StoredSong.toSongItem() = SongItem(
    id = id,
    title = title,
    artists = emptyList(),
    album = null,
    duration = null,
    thumbnail = "",
    explicit = false,
    endpoint = null,
)
