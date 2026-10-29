package com.dd3boh.outertune.ui.screens

import com.dd3boh.outertune.db.entities.Artist
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.StatMetric
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.db.entities.SongPlayStats
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.ChipsRow
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.ArtistGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.menu.AlbumMenu
import com.dd3boh.outertune.ui.menu.ArtistMenu
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.StatsViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val statPeriod by viewModel.statPeriod.collectAsState()
    val mostPlayedSongs by viewModel.mostPlayedSongs.collectAsState()
    val mostPlayedArtists by viewModel.mostPlayedArtists.collectAsState()
    val mostPlayedAlbums by viewModel.mostPlayedAlbums.collectAsState()
    val songLimit by viewModel.songLimit.collectAsState()
    val totals by viewModel.totals.collectAsState()
    val statMetric by viewModel.statMetric.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val mostPlayedSongTitle = stringResource(R.string.most_played_songs)

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            .asPaddingValues(),
        modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top))
    ) {
        item {
            ChipsRow(
                chips = listOf(
                    StatPeriod.`1_WEEK` to pluralStringResource(R.plurals.n_week, 1, 1),
                    StatPeriod.`1_MONTH` to pluralStringResource(R.plurals.n_month, 1, 1),
                    StatPeriod.`3_MONTH` to pluralStringResource(R.plurals.n_month, 3, 3),
                    StatPeriod.`6_MONTH` to pluralStringResource(R.plurals.n_month, 6, 6),
                    StatPeriod.`1_YEAR` to pluralStringResource(R.plurals.n_year, 1, 1),
                    StatPeriod.ALL to stringResource(R.string.filter_all)
                ),
                currentValue = statPeriod,
                onValueUpdate = { viewModel.statPeriod.value = it }
            )
        }

        // How to rank, then how far: time listened or times played | 20, 50, 100 or all - one row,
        // as one question about the chart below.
        item(key = "statMetric") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .animateItem()
            ) {
                listOf(
                    StatMetric.TIME_LISTENED to stringResource(R.string.stats_by_time),
                    StatMetric.TIMES_PLAYED to stringResource(R.string.stats_by_count),
                ).forEach { (metric, label) ->
                    FilterChip(
                        selected = statMetric == metric,
                        onClick = { viewModel.statMetric.value = metric },
                        label = { Text(label) },
                    )
                }
                Text("|", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleMedium)
                StatsViewModel.SONG_LIMITS.forEach { limit ->
                    FilterChip(
                        selected = songLimit == limit,
                        onClick = { viewModel.songLimit.value = limit },
                        label = {
                            Text(if (limit == StatsViewModel.ALL) stringResource(R.string.stats_limit_all) else limit.toString())
                        },
                    )
                }
            }
        }

        // The period at a glance, in two boxes - the desktop's tiles. The one the chart is ranked
        // by is lit, so the boxes and the choice above them read as one thing.
        totals?.let { t ->
            item(key = "statTotals") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .animateItem()
                ) {
                    StatTile(
                        label = stringResource(R.string.stats_tile_plays),
                        value = java.text.NumberFormat.getIntegerInstance().format(t.plays),
                        highlighted = statMetric == StatMetric.TIMES_PLAYED,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = stringResource(R.string.stats_tile_time),
                        value = formatListening(t.playTime),
                        highlighted = statMetric == StatMetric.TIME_LISTENED,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Top artists first, in one sideways row, each with the plays or listening time the chart
        // is ranked by.
        if (mostPlayedArtists.isNotEmpty()) item(key = "mostPlayedArtists") {
            NavigationTitle(
                title = stringResource(R.string.most_played_artists),
                modifier = Modifier.animateItem()
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.animateItem()
            ) {
                items(
                    items = mostPlayedArtists,
                    key = { it.artist.id }
                ) { stats ->
                    val artist = remember(stats) { Artist(artist = stats.artist, songCount = 0, downloadCount = 0) }
                    ArtistGridItem(
                        artist = artist,
                        subtitle = when (statMetric) {
                            StatMetric.TIME_LISTENED -> formatListening(stats.totalPlayTime)
                            StatMetric.TIMES_PLAYED -> pluralStringResource(R.plurals.n_play, stats.totalPlays, stats.totalPlays)
                        },
                        // One height for every card, so the row - and the songs below it - stay
                        // put while it is scrolled.
                        uniformHeight = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    navController.navigate("artist/${artist.id}")
                                },
                                onLongClick = {
                                    menuState.show {
                                        ArtistMenu(
                                            originalArtist = artist,
                                            coroutineScope = coroutineScope,
                                            onDismiss = menuState::dismiss
                                        )
                                    }
                                }
                            )
                            .animateItem()
                    )
                }
            }
        }

        item(key = "mostPlayedSongs") {
            NavigationTitle(
                title = stringResource(R.string.most_played_songs),
                modifier = Modifier.animateItem()
            )
        }

        val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
        itemsIndexed(
            items = mostPlayedSongs,
            key = { _, songStat -> songStat.song.id }
        ) { index, songStat ->
            SongListItem(
                song = songStat.song,
                navController = navController,

                isActive = songStat.song.id == mediaMetadata?.id,
                isPlaying = isPlaying,
                inSelectMode = false,
                isSelected = false,
                onSelectedChange = {},
                swipeEnabled = swipeEnabled,

                thumbnailSize = thumbnailSize,
                extraInfo = statValueText(statMetric, songStat),
                onPlay = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = mostPlayedSongTitle,
                            items = mostPlayedSongs.map { it.song.toMediaMetadata() },
                            // Without this the queue always started at its first entry, so every
                            // song in the chart played the number one track instead of itself.
                            startIndex = index
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
            )
        }

        if (mostPlayedAlbums.isNotEmpty()) {
            item(key = "mostPlayedAlbums") {
                NavigationTitle(
                    title = stringResource(R.string.most_played_albums),
                    modifier = Modifier.animateItem()
                )

                LazyRow(
                    modifier = Modifier.animateItem()
                ) {
                    items(
                        items = mostPlayedAlbums,
                        key = { it.id }
                    ) { album ->
                        AlbumGridItem(
                            album = album,
                            isActive = album.id == mediaMetadata?.album?.id,
                            isPlaying = isPlaying,
                            coroutineScope = coroutineScope,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        navController.navigate("album/${album.id}")
                                    },
                                    onLongClick = {
                                        menuState.show {
                                            AlbumMenu(
                                                originalAlbum = album,
                                                navController = navController,
                                                onDismiss = menuState::dismiss
                                            )
                                        }
                                    }
                                )
                                .animateItem()
                        )
                    }
                }
            }
        }

    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    TopAppBar(
        title = {
            Text(stringResource(R.string.stats))
        },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
        },
        windowInsets = TopBarInsets,
    )
}

/** The per-song value shown beside the 3-dot menu, matching whichever ranking metric is active. */
@Composable
private fun statValueText(metric: StatMetric, songStat: SongPlayStats): String = when (metric) {
    StatMetric.TIME_LISTENED -> makeTimeString(songStat.totalPlayTime)
    StatMetric.TIMES_PLAYED -> pluralStringResource(R.plurals.n_play, songStat.totalPlays, songStat.totalPlays)
}

/** One number in a rounded box, its label above - lit when it is what the chart is ranked by. */
@Composable
private fun StatTile(label: String, value: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Listening time as people say it: "56h 12m", "12m", "45s" - not a clock reading. */
private fun formatListening(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "${ms / 1000}s"
    }
}
