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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The library's three views, as the phone's Library tab splits them. */
enum class LibraryView(val label: String) { Songs("Songs"), Artists("Artists"), Albums("Albums") }

/** Which songs the Songs view lists. */
enum class SongSource(val label: String) {
    Liked("Liked"), History("History"), Downloaded("Downloaded"), Local("On this computer")
}

/** How the Songs view is ordered. "Default" is each source's own order - newest first. */
enum class SongSort(val label: String) { Default("Recently added"), Title("Title"), Artist("Artist") }

/**
 * Songs, artists and albums - the phone's Library, beside a sync with the signed-in account.
 *
 * Songs come from three places the desktop actually keeps: what is liked, what has been played, and
 * what is downloaded. Artists are everyone credited on those, joined by the artists in the account's
 * YouTube library; albums are the account's saved albums. Signed out, artists still fill from what
 * has been played here, and albums say why they are empty.
 */
@Composable
fun LibraryPane(
    library: LibraryStore,
    sync: YouTubeSync,
    signedIn: Boolean,
    downloadedIds: Set<String>,
    currentId: String?,
    onPlay: (List<StoredSong>, Int, String) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf(LibraryView.Songs) }
    var source by remember { mutableStateOf(SongSource.Liked) }
    var sort by remember { mutableStateOf(SongSort.Default) }
    var filter by remember { mutableStateOf("") }
    val liked by library.liked.collectAsState()
    val localTracks by LocalMusic.tracks.collectAsState()
    val recent by library.recentlyPlayed.collectAsState()
    val syncing by sync.syncing.collectAsState()
    val status by sync.status.collectAsState()
    val remoteAlbums by sync.albums.collectAsState()
    val remoteArtists by sync.artists.collectAsState()
    val scope = rememberCoroutineScope()

    var downloaded by remember { mutableStateOf<List<StoredSong>>(emptyList()) }
    LaunchedEffect(downloadedIds) { downloaded = withContext(Dispatchers.IO) { library.songs(downloadedIds) } }
    var localArtists by remember { mutableStateOf<List<Pair<StoredArtist, Int>>>(emptyList()) }
    LaunchedEffect(liked, recent, localTracks) { localArtists = withContext(Dispatchers.IO) { library.libraryArtists() } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (signedIn) {
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                }
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { scope.launch { sync.syncAll() } }) { Text("Sync with YouTube") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryView.entries.forEach { FilterChip(selected = view == it, onClick = { view = it }, label = { Text(it.label) }) }
        }
        Spacer(Modifier.size(8.dp))

        when (view) {
            LibraryView.Songs -> {
                val base = when (source) {
                    SongSource.Liked -> liked
                    SongSource.History -> recent
                    SongSource.Downloaded -> downloaded
                    SongSource.Local -> localTracks.map { it.song }
                }
                val shown = remember(base, sort, filter) {
                    base.filter { filter.isBlank() || it.title.contains(filter, true) || it.artists.contains(filter, true) }
                        .let { list ->
                            when (sort) {
                                SongSort.Default -> list
                                SongSort.Title -> list.sortedBy { it.title.lowercase() }
                                SongSort.Artist -> list.sortedBy { it.artists.lowercase() }
                            }
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SongSource.entries.forEach {
                        FilterChip(selected = source == it, onClick = { source = it }, label = { Text(it.label) })
                    }
                    Spacer(Modifier.weight(1f))
                    SortMenu(sort) { sort = it }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Button(onClick = { onPlay(shown, 0, source.label) }, enabled = shown.isNotEmpty()) {
                        Icon(OuterTuneIcons.play, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play")
                    }
                    OutlinedButton(
                        onClick = { shown.shuffled().takeIf { it.isNotEmpty() }?.let { onPlay(it, 0, source.label) } },
                        enabled = shown.isNotEmpty(),
                    ) {
                        Icon(OuterTuneIcons.shuffle, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Shuffle")
                    }
                    Text(
                        "${shown.size} ${if (shown.size == 1) "song" else "songs"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Filter") },
                        singleLine = true,
                        shape = CircleShape,
                        leadingIcon = { Icon(OuterTuneIcons.search, null, modifier = Modifier.size(18.dp)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.width(260.dp),
                    )
                }
                if (shown.isEmpty()) {
                    Empty(
                        when {
                            filter.isNotBlank() -> "Nothing matches \"$filter\"."
                            source == SongSource.Liked && signedIn -> "No liked songs yet. Sync with YouTube to bring yours in."
                            source == SongSource.Liked -> "No liked songs yet. Like a song from the player, or sign in to bring yours from YouTube."
                            source == SongSource.Downloaded -> "Nothing downloaded yet."
                            source == SongSource.Local -> "No music from this computer yet. Add folders under Settings → Local music."
                            else -> "Nothing played yet."
                        }
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(shown, key = { _, it -> it.id }) { index, song ->
                            StoredSongRow(song, playing = song.id == currentId) { onPlay(shown, index, source.label) }
                        }
                    }
                }
            }

            LibraryView.Artists -> {
                // The account's artists first, as they carry pictures, then everyone else played here.
                val remoteIds = remoteArtists.map { it.id }.toSet()
                val local = localArtists.filterNot { it.first.id in remoteIds }
                if (remoteArtists.isEmpty() && local.isEmpty()) {
                    Empty("Artists appear here once you like or play their songs.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(remoteArtists, key = { "r-${it.id}" }) { artist ->
                            Card(item = artist, playing = false) { onOpenArtist(StoredArtist(artist.id, artist.title)) }
                        }
                        items(local, key = { "l-${it.first.id}" }) { (artist, count) ->
                            InitialCard(artist.name, "$count ${if (count == 1) "song" else "songs"}") { onOpenArtist(artist) }
                        }
                    }
                }
            }

            LibraryView.Albums -> when {
                remoteAlbums.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(176.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(remoteAlbums, key = { it.id }) { album ->
                        Card(item = album, playing = false) { onOpenAlbum(album.browseId) }
                    }
                }
                signedIn -> Empty(if (syncing) "Fetching your albums…" else "No saved albums on your account. Sync to check again.")
                else -> Empty("Saved albums come from your YouTube account. Sign in under Settings → Account.")
            }
        }
    }
}

@Composable
private fun SortMenu(sort: SongSort, onSort: (SongSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Sort: ${sort.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SongSort.entries.forEach {
                DropdownMenuItem(
                    text = { Text(it.label, fontWeight = if (it == sort) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        onSort(it)
                        open = false
                    },
                )
            }
        }
    }
}

/** An artist with no picture to hand: their initial on a disc, the way the phone draws one. */
@Composable
private fun InitialCard(name: String, subtitle: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(128.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Empty(message: String) {
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
