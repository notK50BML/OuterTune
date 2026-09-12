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
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the album screen is showing. Exclusive states, for the same reason as [HomeState]. */
sealed interface AlbumState {
    data object Loading : AlbumState
    data class Ready(val page: AlbumPage) : AlbumState
    data class Failed(val message: String) : AlbumState
}

/**
 * Fetches one album.
 *
 * A browse id rather than a playlist id, because that is what every link into an album carries -
 * a song's `album.id`, a home feed card's `browseId` - and `YouTube.album` returns the songs along
 * with the metadata, so one request answers the whole screen.
 */
suspend fun loadAlbum(browseId: String): AlbumState = withContext(Dispatchers.IO) {
    YouTube.album(browseId).fold(
        onSuccess = { AlbumState.Ready(it) },
        onFailure = {
            AlbumState.Failed("Could not load this album: ${it.message ?: it::class.simpleName}")
        },
    )
}

/**
 * One album: its cover and details, then its tracks.
 *
 * The header is wide rather than stacked, which is the shape a desktop window wants - a phone puts
 * the cover above the title because it has no width to spare, and copying that here would leave two
 * thirds of the row empty beside a square.
 *
 * Track numbers rather than thumbnails in the list. Every song on an album shares the album's cover,
 * so a column of forty identical thumbnails carries no information and takes the width that the
 * titles want.
 */
@Composable
fun AlbumPane(
    state: AlbumState,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int) -> Unit,
    onShuffle: (List<SongItem>) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    onAddToPlaylist: (StoredSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            TextButton(onClick = onBack) { Text("← Back") }
        }

        when (state) {
            AlbumState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is AlbumState.Failed -> Box(
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

            is AlbumState.Ready -> {
                val page = state.page
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Header(
                            page = page,
                            onPlay = { onPlay(page.songs, 0) },
                            onShuffle = { onShuffle(page.songs) },
                            onOpenArtist = onOpenArtist,
                        )
                    }
                    itemsIndexed(page.songs) { index, song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(44.dp),
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                SongRow(song, playing = song.id == currentId) {
                                    onPlay(page.songs, index)
                                }
                            }
                            TextButton(onClick = { onAddToPlaylist(song.toStored()) }) { Text("+") }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Header(
    page: AlbumPage,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp)) {
        Artwork(page.album.thumbnail, size = 220.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(28.dp))
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
            Text(
                text = page.album.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            ArtistNames(
                artists = page.album.artists.orEmpty().map { artist ->
                    artist.id?.let { StoredArtist(it, artist.name) } ?: StoredArtist.unlinked(artist.name)
                },
                fallback = page.album.artists?.joinToString { it.name }.orEmpty(),
                colour = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                onClick = onOpenArtist,
            )
            Text(
                text = listOfNotNull(
                    page.album.year?.toString(),
                    "${page.songs.size} ${if (page.songs.size == 1) "track" else "tracks"}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlay, enabled = page.songs.isNotEmpty()) {
                    Icon(OuterTuneIcons.play, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onShuffle, enabled = page.songs.isNotEmpty()) {
                    Icon(OuterTuneIcons.shuffle, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Shuffle")
                }
            }
        }
    }
}
