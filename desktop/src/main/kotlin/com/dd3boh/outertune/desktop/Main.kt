/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop client: a content area, a queue beside it, and a player bar along the bottom.
 *
 * Laid out the way the Android app is - the player is always present rather than appearing only
 * once something is playing. An earlier version hid the bar while idle, which meant a fresh window
 * had no player in it at all and no way to reach the full-screen one; a music player that only looks
 * like a music player after you have already started a song is the wrong way round.
 *
 * The Android screens themselves could not be recompiled here: they reach for Hilt, Room, DataStore
 * and MediaSession through composition locals, none of which exist off Android. So this is the same
 * shape rebuilt on the desktop plumbing, not a port of that code.
 *
 * Deliberately missing, so it is not mistaken for finished: no real database (the library is a text
 * file - see [LibraryStore]) and no playlists. A song is fetched in full before it starts, which
 * costs a second or two on a big track and buys two things: no network remains to fail once audio
 * starts, so a mid-song 403 cannot happen, and seeking is arithmetic rather than a new request.
 */
fun main() = application {
    // Set here because it needs no network call, and search will not work without it - YouTube
    // answers 400 INVALID_ARGUMENT, which reads like a platform problem and is not one.
    remember { YouTube.locale = YouTubeLocale(gl = "US", hl = "en") }

    val player = remember { DesktopPlayer() }
    Window(
        onCloseRequest = { player.stop(); exitApplication() },
        title = "OuterTune",
        state = rememberWindowState(width = 1000.dp, height = 700.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                App(player)
            }
        }
    }
}

@Composable
private fun App(player: DesktopPlayer) {
    val scope = rememberCoroutineScope()
    val library = remember { LibraryStore() }
    val playerQueue = remember { PlayerQueue(player, scope, onPlayed = { library.recordPlay(it.stored()) }) }

    val playback by player.state.collectAsState()
    val queue by playerQueue.state.collectAsState()
    val recent by library.recentlyPlayed.collectAsState()
    val liked by library.liked.collectAsState()
    val position by player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    var showFullPlayer by remember { mutableStateOf(false) }

    // visitorData needs a network call, so it cannot be set the way locale is. Its failure used to
    // be silent, which does not look like a failure: search keeps working because search only needs
    // the locale, while every player request comes back refused.
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
            withContext(Dispatchers.IO) { YouTube.search(text, YouTube.SearchFilter.FILTER_SONG) }
                .onSuccess { page ->
                    results = page.items.filterIsInstance<SongItem>()
                    if (results.isEmpty()) error = "No songs matched that."
                }
                .onFailure { error = "${it::class.simpleName}: ${it.message}" }
            searching = false
        }
    }

    val isLiked = queue.current?.let { current -> liked.any { it.id == current.id } } == true
    val toggleLike = { queue.current?.let { library.toggleLiked(it.stored()) }; Unit }

    if (showFullPlayer) {
        NowPlayingScreen(
            playback = playback,
            queue = queue,
            positionMs = position,
            durationMs = duration,
            liked = isLiked,
            onSeek = player::seekTo,
            onTogglePause = player::togglePause,
            onNext = playerQueue::next,
            onPrevious = playerQueue::previous,
            onToggleLike = toggleLike,
            onToggleShuffle = playerQueue::toggleShuffle,
            onCycleRepeat = playerQueue::cycleRepeat,
            onClose = { showFullPlayer = false },
        )
        return
    }

    // Media keys, but only while the search box does not have focus: space has to keep typing a
    // space and the arrows have to keep moving the caret. A shortcut that eats the text field is
    // worse than no shortcut.
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keys)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (searchFocused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Spacebar, Key.MediaPlayPause -> { player.togglePause(); true }
                    Key.DirectionRight -> { player.seekTo(position + 10_000); true }
                    Key.DirectionLeft -> { player.seekTo(position - 10_000); true }
                    Key.MediaNext -> { playerQueue.next(); true }
                    Key.MediaPrevious -> { playerQueue.previous(); true }
                    else -> false
                }
            },
    ) {
        // Content and queue share the space above the player bar; the bar owns the bottom edge and
        // is always there.
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.weight(2f).padding(16.dp)) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = ::search,
                    onFocusChange = { searchFocused = it },
                    searching = searching,
                )
                SessionStatus(session)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                Content(
                    results = results,
                    liked = liked,
                    recent = recent,
                    currentId = queue.current?.id,
                    onPlayResult = { index -> playerQueue.play(results, index) },
                    onPlayStored = { songs, index -> playerQueue.play(songs.map { it.toSongItem() }, index) },
                )
            }

            if (queue.songs.isNotEmpty()) {
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
                QueuePane(queue) { playerQueue.jumpTo(it) }
            }
        }

        PlayerBar(
            playback = playback,
            queue = queue,
            positionMs = position,
            durationMs = duration,
            liked = isLiked,
            onTogglePause = player::togglePause,
            onNext = playerQueue::next,
            onPrevious = playerQueue::previous,
            onToggleLike = toggleLike,
            onExpand = { showFullPlayer = true },
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    searching: Boolean,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search YouTube Music") },
        singleLine = true,
        trailingIcon = {
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
            }
        },
        // Enter searches, so the button is not the only way and the keyboard flow is unbroken.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.isFocused) },
    )
}

/** Only speaks up while connecting or when it failed; a working session needs no announcement. */
@Composable
private fun SessionStatus(session: String?) {
    when (session) {
        null -> Text(
            "connecting…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        "ready" -> Unit
        else -> Text(
            session,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Content(
    results: List<SongItem>,
    liked: List<StoredSong>,
    recent: List<StoredSong>,
    currentId: String?,
    onPlayResult: (Int) -> Unit,
    onPlayStored: (List<StoredSong>, Int) -> Unit,
) {
    if (results.isNotEmpty()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(results) { index, song ->
                SongRow(song, playing = song.id == currentId) { onPlayResult(index) }
            }
        }
        return
    }

    val shelves = listOfNotNull(
        ("Liked" to liked).takeIf { liked.isNotEmpty() },
        ("Recently played" to recent).takeIf { recent.isNotEmpty() },
    )
    if (shelves.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Search for something to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        shelves.forEach { (heading, songs) ->
            item {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            // The whole shelf is queued, so playing from Liked plays the lot rather than one song
            // followed by silence.
            itemsIndexed(songs) { index, song ->
                StoredSongRow(song, playing = song.id == currentId) { onPlayStored(songs, index) }
            }
        }
    }
}

@Composable
private fun QueuePane(queue: QueueState, onJump: (Int) -> Unit) {
    Column(modifier = Modifier.width(300.dp).padding(16.dp)) {
        Text(
            "Queue  ${queue.orderPosition + 1}/${queue.songs.size}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn {
            // Shown in play order rather than the order added, so with shuffle on the list reads as
            // what is coming next - which is the only thing a queue is for.
            itemsIndexed(queue.order) { position, index ->
                val song = queue.songs.getOrNull(index) ?: return@itemsIndexed
                SongRow(song, playing = position == queue.orderPosition) { onJump(index) }
            }
        }
    }
}

/**
 * The bar along the bottom. Always present, whether or not anything is playing.
 *
 * Clicking it opens the full player, so there is always a way through to it - which there was not
 * when this only appeared once a song had started.
 */
@Composable
private fun PlayerBar(
    playback: PlaybackState,
    queue: QueueState,
    positionMs: Long,
    durationMs: Long,
    liked: Boolean,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onExpand: () -> Unit,
) {
    val song = queue.current
    Column {
        // A thin progress line rather than a slider: the bar is for glancing at, and the full
        // player is where scrubbing belongs.
        LinearProgressIndicator(
            progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Artwork(song?.thumbnail, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title ?: "Nothing playing",
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (song == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
                Text(
                    text = when (playback) {
                        is PlaybackState.Loading -> "Loading…"
                        is PlaybackState.Failed -> playback.reason
                        else -> song?.artists?.joinToString { it.name }?.ifBlank { "Unknown artist" }
                            ?: "Search for something to play"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playback is PlaybackState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (song != null) {
                Text(
                    "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onToggleLike) { Text(if (liked) "♥" else "♡") }
            }
            TextButton(onClick = onPrevious, enabled = queue.hasPrevious) { Text("⏮") }
            TextButton(onClick = onTogglePause, enabled = song != null) {
                Text(if (playback is PlaybackState.Paused) "▶" else "⏸")
            }
            TextButton(onClick = onNext, enabled = queue.hasNext) { Text("⏭") }
        }
    }
}

@Composable
private fun SongRow(song: SongItem, playing: Boolean = false, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Artwork(song.thumbnail)
        Column {
            Text(
                song.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artists.joinToString { it.name }.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A row for a song out of the library, which keeps artists as one display string. */
@Composable
private fun StoredSongRow(song: StoredSong, playing: Boolean = false, onPlay: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Artwork(song.thumbnail)
        Column {
            Text(
                song.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artists.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The stored shape of a search result - only what the library needs to show it and replay it. */
private fun SongItem.stored() = StoredSong(
    id = id,
    title = title,
    artists = artists.joinToString { it.name },
    thumbnail = thumbnail,
)

/**
 * A stored song back as a playable item.
 *
 * The stored artists are one display string rather than structured entries, so they come back as a
 * single unlinked artist. That is lossy - there is no channel to navigate to - but it is the name,
 * and returning an empty list instead made every song played from the library show "Unknown artist"
 * while it was playing, which is worse than lossy: it is wrong about something it knows.
 */
private fun StoredSong.toSongItem() = SongItem(
    id = id,
    title = title,
    artists = artists.takeIf { it.isNotBlank() }?.let { listOf(Artist(name = it, id = null)) }.orEmpty(),
    album = null,
    duration = null,
    thumbnail = thumbnail,
    explicit = false,
    endpoint = null,
)
