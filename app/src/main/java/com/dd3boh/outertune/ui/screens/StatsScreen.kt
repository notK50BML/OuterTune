package com.dd3boh.outertune.ui.screens

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
    val showExtended by viewModel.showExtended.collectAsState()
    val extendedLimit by viewModel.extendedLimit.collectAsState()
    val extendedSongs by viewModel.extendedSongs.collectAsState()
    val extendedArtists by viewModel.extendedArtists.collectAsState()
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

        item(key = "statMetric") {
            ChipsRow(
                chips = listOf(
                    StatMetric.TIME_LISTENED to stringResource(R.string.stats_by_time),
                    StatMetric.TIMES_PLAYED to stringResource(R.string.stats_by_count),
                ),
                currentValue = statMetric,
                onValueUpdate = { viewModel.statMetric.value = it },
                modifier = Modifier.animateItem()
            )
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

        item(key = "mostPlayedArtists") {
            NavigationTitle(
                title = stringResource(R.string.most_played_artists),
                modifier = Modifier.animateItem()
            )

            LazyRow(
                modifier = Modifier.animateItem()
            ) {
                items(
                    items = mostPlayedArtists,
                    key = { it.id }
                ) { artist ->
                    ArtistGridItem(
                        artist = artist,
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

        item(key = "extendedHeader") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.showExtended.value = !showExtended }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .animateItem()
            ) {
                Text(
                    text = stringResource(R.string.stats_extended_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (showExtended) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null
                )
            }
        }

        if (showExtended) {
            item(key = "extendedLimits") {
                ChipsRow(
                    chips = StatsViewModel.EXTENDED_LIMITS.map { it to it.toString() },
                    currentValue = extendedLimit,
                    onValueUpdate = { viewModel.extendedLimit.value = it },
                    modifier = Modifier.animateItem()
                )
            }

            item(key = "extendedSongsTitle") {
                NavigationTitle(
                    title = stringResource(R.string.stats_extended_songs),
                    modifier = Modifier.animateItem()
                )
            }
            itemsIndexed(
                items = extendedSongs,
                // Prefixed because the overview above holds the same songs, and two items sharing
                // a key in one LazyColumn is a crash, not a cosmetic problem.
                key = { _, songStat -> "ext-song-" + songStat.song.id }
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
                    thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt(),
                    extraInfo = statValueText(statMetric, songStat),
                    onPlay = {
                        playerConnection.playQueue(
                            ListQueue(
                                title = mostPlayedSongTitle,
                                items = extendedSongs.map { it.song.toMediaMetadata() },
                                startIndex = index
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }

            item(key = "extendedArtists") {
                NavigationTitle(
                    title = stringResource(R.string.stats_extended_artists),
                    modifier = Modifier.animateItem()
                )
                LazyRow(modifier = Modifier.animateItem()) {
                    items(
                        items = extendedArtists,
                        key = { "ext-artist-" + it.id }
                    ) { artist ->
                        ArtistGridItem(
                            artist = artist,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { navController.navigate("artist/${artist.id}") },
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
        }
    }
    LazyColumnScrollbar(
        state = lazyListState,
    )

    TopAppBar(
        title = { Text(stringResource(R.string.stats)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
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
