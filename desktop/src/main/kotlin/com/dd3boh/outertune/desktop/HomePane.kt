/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

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
    onPlaySong: (List<SongItem>, Int) -> Unit,
    onPlayList: (YTItem) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        HomeState.Loading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is HomeState.Empty -> Box(
            modifier = modifier.fillMaxSize().padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (state.signedOut) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Settings → Account",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        is HomeState.Ready -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            items(state.sections) { section ->
                Section(
                    section = section,
                    currentId = currentId,
                    onPlaySong = onPlaySong,
                    onPlayList = onPlayList,
                    onOpenArtist = onOpenArtist,
                )
            }
        }
    }
}

@Composable
private fun Section(
    section: HomePage.Section,
    currentId: String?,
    onPlaySong: (List<SongItem>, Int) -> Unit,
    onPlayList: (YTItem) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
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
private fun Card(item: YTItem, playing: Boolean, onClick: () -> Unit) {
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
