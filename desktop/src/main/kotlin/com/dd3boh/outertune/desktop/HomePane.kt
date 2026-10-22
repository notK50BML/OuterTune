/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.PlaylistItem
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.HomePage

/**
 * The signed-in home feed: what YouTube thinks you want, in rows.
 *
 * Horizontal carousels rather than one long list, which is how the feed is actually shaped - each
 * section is a handful of related things with a heading, and stacking them vertically would turn
 * twelve sections into a page nobody reaches the bottom of.
 *
 * Every card does something. Items this app has nowhere to put are filtered out upstream in
 * [HomeFeed] rather than drawn and left inert, because a grid where some cards respond and others do
 * not is worse than a smaller grid.
 */
@Composable
fun HomePane(
    state: HomeState,
    currentId: String?,
    /** Saved songs, shown as a row of their own above the feed. */
    liked: List<StoredSong>,
    /** Recently played, likewise. */
    recent: List<StoredSong>,
    onPlaySong: (List<SongItem>, Int) -> Unit,
    onPlayStored: (List<StoredSong>, Int, String) -> Unit,
    onPlayList: (YTItem) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Songs to start from - most played lately, topped up from liked. See Main. */
    quickPicks: List<StoredSong> = emptyList(),
    /** Liked songs that have not been played in a while. */
    forgotten: List<StoredSong> = emptyList(),
    chips: List<HomePage.Chip> = emptyList(),
    selectedChip: HomePage.Chip? = null,
    onChip: (HomePage.Chip?) -> Unit = {},
    /** Asks for the next stretch of the feed; called as the bottom comes into view. */
    onLoadMore: () -> Unit = {},
    loadingMore: Boolean = false,
    /** The playlists this account saved on YouTube - the phone's own row on Home. */
    yourPlaylists: List<PlaylistItem> = emptyList(),
    /** What the account played lately - the phone's Recent activity row, first under the chips. */
    recentActivity: List<YTItem> = emptyList(),
    /** "Similar to ..." shelves - see loadSimilar. */
    similar: List<HomePage.Section> = emptyList(),
    /** Explore's new albums. */
    newReleases: List<AlbumItem> = emptyList(),
) {
    val listState = rememberLazyListState()
    // As on the phone: the feed carries on as it is scrolled, a few shelves before the end.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state) {
        if (nearEnd && state is HomeState.Ready && state.continuation != null) onLoadMore()
    }
    // One scrolling column with the local rows at the top, rather than the feed and the library
    // sharing the window half and half. Splitting it meant neither got enough height to show a row
    // and its labels, and the split did not move when one side had nothing in it.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // In the phone's order: the mood filters, then what you are likely to want right now, then
        // what you were listening to, then what you have not heard in a while, then YouTube's feed.
        if (chips.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp),
                    modifier = Modifier.padding(bottom = 20.dp),
                ) {
                    items(chips, key = { it.title }) { chip ->
                        FilterChip(
                            selected = chip == selectedChip,
                            // Tapping the lit chip again goes back to the whole feed, as on the phone.
                            onClick = { onChip(if (chip == selectedChip) null else chip) },
                            label = { Text(chip.title) },
                        )
                    }
                }
            }
        }
        if (recentActivity.isNotEmpty() && selectedChip == null) {
            item {
                Section(
                    section = HomePage.Section("Recent activity", null, null, null, recentActivity),
                    currentId = currentId,
                    onPlaySong = onPlaySong,
                    onPlayList = onPlayList,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
        if (quickPicks.isNotEmpty()) {
            item {
                SongGrid(
                    title = "Quick picks",
                    songs = quickPicks,
                    currentId = currentId,
                ) { index -> onPlayStored(quickPicks, index, "Quick picks") }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                StoredRow(
                    title = "Keep listening",
                    songs = recent,
                    currentId = currentId,
                ) { index -> onPlayStored(recent, index, "Keep listening") }
            }
        }
        // Not while a mood chip is lit: then the page is that mood, and these are not.
        if (yourPlaylists.isNotEmpty() && selectedChip == null) {
            item {
                Section(
                    section = HomePage.Section("Your YouTube playlists", null, null, null, yourPlaylists),
                    currentId = currentId,
                    onPlaySong = onPlaySong,
                    onPlayList = onPlayList,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
        if (forgotten.isNotEmpty()) {
            item {
                SongGrid(
                    title = "Forgotten favourites",
                    songs = forgotten,
                    currentId = currentId,
                ) { index -> onPlayStored(forgotten, index, "Forgotten favourites") }
            }
        }
        if (liked.isNotEmpty()) {
            item {
                StoredRow(
                    title = "Liked songs",
                    songs = liked,
                    currentId = currentId,
                ) { index -> onPlayStored(liked, index, "Liked songs") }
            }
        }

        when (state) {
            HomeState.Loading -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            is HomeState.Empty -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // Signed out, the fix is somewhere else and saying where is the whole help.
                        // Signed in, this is a request that failed and the only useful offer is to
                        // make it again - a retry button where the pointer already is.
                        if (state.signedOut) {
                            Text(
                                text = "Settings → Account",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Button(onClick = onRetry) { Text("Try again") }
                        }
                    }
                }
            }

            is HomeState.Ready -> {
                // The phone's own shelves woven into YouTube's: the "Similar to" ones after the first
                // two of the feed, new releases after the fourth - or at the end of a short feed.
                // Only on the whole feed; a mood chip's page is about that mood.
                val feed = if (selectedChip != null) state.sections else buildList {
                    val sections = state.sections
                    addAll(sections.take(2))
                    addAll(similar)
                    addAll(sections.drop(2).take(2))
                    if (newReleases.isNotEmpty()) add(HomePage.Section("New releases", null, null, null, newReleases))
                    addAll(sections.drop(4))
                }
                items(feed) { section ->
                    Section(
                        section = section,
                        currentId = currentId,
                        onPlaySong = onPlaySong,
                        onPlayList = onPlayList,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                    )
                }
                if (loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Songs as a grid of compact rows, four deep and scrolling sideways - the phone's Quick picks.
 *
 * Rows rather than cards because these are songs to start, not records to browse: the title is
 * what is being scanned for, and twenty of them fit where five cards would.
 */
@Composable
private fun SongGrid(
    title: String,
    songs: List<StoredSong>,
    currentId: String?,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, bottom = 10.dp),
        )
        val rows = minOf(4, songs.size)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier.height(GRID_ROW_HEIGHT * rows),
        ) {
            gridItemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongMenuArea(song.toItem()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(320.dp)
                        .height(GRID_ROW_HEIGHT)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onPlay(index) }
                        .padding(horizontal = 8.dp),
                ) {
                    SongArtwork(song.thumbnail, size = 48.dp, playing = song.id == currentId, cornerRadius = 6.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            song.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (song.id == currentId) FontWeight.Bold else FontWeight.Normal,
                            color = if (song.id == currentId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            song.artists,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                }
            }
        }
    }
}

private val GRID_ROW_HEIGHT = 60.dp

/**
 * A row of songs the library already holds.
 *
 * Drawn as the same cards as the feed rather than as list rows, so the page reads as one thing.
 * Liked and recent are the two lists looked for by name rather than browsed to, which is why they
 * sit above the recommendations instead of below them.
 */
@Composable
private fun StoredRow(
    title: String,
    songs: List<StoredSong>,
    currentId: String?,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, bottom = 10.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            itemsIndexed(songs) { index, song ->
                StoredCard(song = song, playing = song.id == currentId) { onPlay(index) }
            }
        }
    }
}

@Composable
private fun StoredCard(song: StoredSong, playing: Boolean, onClick: () -> Unit) = SongMenuArea(song.toItem()) {
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    ) {
        SongArtwork(song.thumbnail, size = CARD_WIDTH, playing = playing, cornerRadius = 8.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artists,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Section(
    section: HomePage.Section,
    currentId: String?,
    onPlaySong: (List<SongItem>, Int) -> Unit,
    onPlayList: (YTItem) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    // Only what can be acted on, and recomputed per section rather than once for the page so the
    // song indices below line up with the list actually being shown.
    val items = section.items.filter { it.isSupported }
    if (items.isEmpty()) return
    val songs = items.filterIsInstance<SongItem>()

    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        section.label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp),
            )
        }
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 28.dp, bottom = 10.dp),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 28.dp),
        ) {
            items(items) { item ->
                Card(
                    item = item,
                    playing = item.id == currentId,
                    onClick = {
                        when (item) {
                            // Played with the rest of its row behind it, so a section behaves like
                            // an album rather than a series of one-song sessions.
                            is SongItem -> onPlaySong(songs, songs.indexOf(item).coerceAtLeast(0))
                            is ArtistItem -> onOpenArtist(StoredArtist(item.id, item.title))
                            // An album opens rather than plays, now that there is a page for it -
                            // clicking a cover to be shown the record is what every music app does,
                            // and playing it outright removes the chance to look first.
                            is AlbumItem -> onOpenAlbum(item.browseId)
                            else -> onPlayList(item)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One item in a carousel.
 *
 * Artists are round and everything else is square, which is the convention every music app uses and
 * the only thing distinguishing "this opens a page" from "this starts playing" before it is clicked.
 */
@Composable
internal fun Card(item: YTItem, playing: Boolean, onClick: () -> Unit) {
    // Songs get the right-click menu; albums, playlists and artists open pages instead.
    if (item is SongItem) SongMenuArea(item) { CardBody(item, playing, onClick) } else CardBody(item, playing, onClick)
}

@Composable
private fun CardBody(item: YTItem, playing: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    ) {
        val artist = item is ArtistItem
        Box(
            modifier = Modifier
                .size(CARD_WIDTH)
                .clip(if (artist) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        ) {
            Artwork(
                item.thumbnail,
                size = CARD_WIDTH,
                // The clip above already shapes it; a second rounding inside a circle would show as
                // a seam at the edges.
                cornerRadius = 0.dp,
            )
            if (playing && item is SongItem) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(CARD_WIDTH).background(Color.Black.copy(alpha = 0.45f)),
                ) { PlayingBars(modifier = Modifier.size(CARD_WIDTH * 0.3f)) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        item.subtitle.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (artist) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Wide enough for a cover to be worth looking at, narrow enough that several fit on screen. */
private val CARD_WIDTH = 160.dp
