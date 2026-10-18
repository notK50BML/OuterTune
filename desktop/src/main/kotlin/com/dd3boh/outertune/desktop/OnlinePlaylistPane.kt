/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an online playlist's page is showing. */
sealed interface OnlinePlaylistState {
    data object Loading : OnlinePlaylistState
    data class Ready(val playlist: PlaylistItem, val songs: List<SongItem>) : OnlinePlaylistState
    data class Failed(val message: String) : OnlinePlaylistState
}

/**
 * Fetches a YouTube playlist, all of it.
 *
 * Every continuation is followed, so playing from the page queues the whole playlist rather than
 * the first hundred songs. Capped, because a playlist can hold five thousand and following that
 * many pages would leave the screen on a spinner for a minute.
 */
suspend fun loadOnlinePlaylist(playlistId: String): OnlinePlaylistState = withContext(Dispatchers.IO) {
    // Browse ids for playlists carry "VL" in front; YouTube.playlist adds it itself, so one that
    // arrives with it already would be asked for as "VLVL…" and come back empty.
    val id = playlistId.removePrefix("VL")
    YouTube.playlist(id).fold(
        onSuccess = { page ->
            val songs = page.songs.toMutableList()
            var next = page.songsContinuation ?: page.continuation
            var pages = 0
            while (next != null && pages < MAX_CONTINUATIONS) {
                val more = YouTube.playlistContinuation(next).getOrNull() ?: break
                songs += more.songs
                next = more.continuation
                pages++
            }
            OnlinePlaylistState.Ready(page.playlist, songs.distinctBy { it.id })
        },
        onFailure = {
            OnlinePlaylistState.Failed("Could not load this playlist: ${it.message ?: it::class.simpleName}")
        },
    )
}

private const val MAX_CONTINUATIONS = 30

/** A YouTube playlist: its cover and details, then its songs. Laid out as the album page is. */
@Composable
fun OnlinePlaylistPane(
    state: OnlinePlaylistState,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    /** Downloads every song of the playlist - see BatchDownload. */
    onDownloadAll: ((String, List<SongItem>) -> Unit)? = null,
    downloadedIds: Set<String> = emptySet(),
    downloadingTitle: String? = null,
    onAddToPlaylist: (StoredSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            TextButton(onClick = onBack) { Text("← Playlists") }
        }

        when (state) {
            OnlinePlaylistState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is OnlinePlaylistState.Failed -> Box(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            is OnlinePlaylistState.Ready -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)) {
                        Artwork(state.playlist.thumbnail, size = 220.dp, cornerRadius = 10.dp)
                        Spacer(modifier = Modifier.width(28.dp))
                        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.playlist.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = listOfNotNull(
                                    state.playlist.author?.name,
                                    "${state.songs.size} ${if (state.songs.size == 1) "song" else "songs"}",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { onPlay(state.songs, 0) }, enabled = state.songs.isNotEmpty()) {
                                    Icon(OuterTuneIcons.play, contentDescription = null, modifier = Modifier.width(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Play")
                                }
                                OutlinedButton(onClick = { onShuffle(state.songs) }, enabled = state.songs.isNotEmpty()) {
                                    Icon(OuterTuneIcons.shuffle, contentDescription = null, modifier = Modifier.width(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Shuffle")
                                }
                                if (onDownloadAll != null) {
                                    DownloadAllButton(
                                        songIds = state.songs.map { it.id },
                                        downloadedIds = downloadedIds,
                                        busy = downloadingTitle == state.playlist.title,
                                        onDownload = { onDownloadAll(state.playlist.title, state.songs) },
                                    )
                                }
                            }
                        }
                    }
                }
                itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            SongRow(song, playing = song.id == currentId) { onPlay(state.songs, index) }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}
