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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The windows Stats can be read over - the phone's own choices. */
enum class StatsPeriod(val label: String, val days: Long?) {
    Week("Week", 7),
    Month("Month", 30),
    Year("Year", 365),
    All("All time", null),
    ;

    fun sinceMs(now: Long = System.currentTimeMillis()): Long = days?.let { now - it * 86_400_000L } ?: 0L
}

private data class StatsData(
    val totals: PlayTotals,
    val songs: List<Pair<StoredSong, Int>>,
    val artists: List<Pair<StoredArtist, Int>>,
)

/**
 * What has been listened to: totals, most played songs and most played artists, over a period.
 *
 * Read from the play log rather than from "recently played", which keeps only the last play of each
 * song and so cannot count anything.
 */
@Composable
fun StatsPane(
    library: LibraryStore,
    currentId: String?,
    onPlay: (List<StoredSong>, Int) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    var period by remember { mutableStateOf(StatsPeriod.Month) }
    var data by remember { mutableStateOf<StatsData?>(null) }
    // Re-read when the recent list changes too, so a song finishing while this is open is counted.
    val recent by library.recentlyPlayed.collectAsState()
    LaunchedEffect(period, recent) {
        data = withContext(Dispatchers.IO) {
            val since = period.sinceMs()
            StatsData(library.playTotals(since), library.topSongs(since), library.topArtists(since))
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Stats",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPeriod.entries.forEach {
                    FilterChip(selected = period == it, onClick = { period = it }, label = { Text(it.label) })
                }
            }
        }

        val stats = data
        if (stats == null) return@LazyColumn
        if (stats.totals.plays == 0) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing played in this period yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                StatTile("Plays", stats.totals.plays.toString(), Modifier.weight(1f))
                StatTile("Songs", stats.totals.songs.toString(), Modifier.weight(1f))
                StatTile("Listening time", formatListening(stats.totals.listenedMs), Modifier.weight(1f))
            }
        }

        if (stats.artists.isNotEmpty()) {
            item { Heading("Top artists") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(stats.artists, key = { it.first.id }) { (artist, plays) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(120.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onOpenArtist(artist) }
                                .padding(8.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(96.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                            ) {
                                Text(
                                    artist.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            Text(
                                plays(plays),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item { Heading("Top songs") }
        val songs = stats.songs.map { it.first }
        itemsIndexed(stats.songs, key = { _, it -> it.first.id }) { index, (song, count) ->
            SongMenuArea(song.toItem()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlay(songs, index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Artwork(song.thumbnail, size = 44.dp, cornerRadius = 6.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song.title,
                        fontWeight = FontWeight.Medium,
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
                Text(plays(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

/** Hours and minutes, or minutes alone under an hour. */
internal fun formatListening(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
