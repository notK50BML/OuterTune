package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.StatMetric
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

// redoing this whole feature later, plz ignore the slop code
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    val statPeriod = MutableStateFlow(StatPeriod.`1_WEEK`)

    /**
     * Time listened or number of plays. They rank differently and both are reasonable: a long
     * track you play twice beats a short one you play ten times by time, and loses by count.
     */
    val statMetric = MutableStateFlow(StatMetric.TIME_LISTENED)

    /** Whether the longer charts below the overview are open. */
    val showExtended = MutableStateFlow(false)

    /** How far the longer charts go. */
    val extendedLimit = MutableStateFlow(EXTENDED_LIMITS.first())

    private val overviewRequest = combine(statPeriod, statMetric) { period, metric -> period to metric }

    val mostPlayedSongs = overviewRequest.flatMapLatest { (period, metric) ->
        database.mostPlayedSongs(period.toTimeMillis(), byPlayTime = metric == StatMetric.TIME_LISTENED)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedArtists = overviewRequest.flatMapLatest { (period, metric) ->
        database.mostPlayedArtists(
            period.toTimeMillis(),
            byPlayTime = metric == StatMetric.TIME_LISTENED,
        ).map { artists ->
            artists.filter { it.artist.isYouTubeArtist }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    val mostPlayedAlbums = statPeriod.flatMapLatest { period ->
        database.mostPlayedAlbums(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * The longer charts, queried only while the section is open.
     *
     * Collapsed emits an empty list rather than querying and hiding the result: these are the
     * same joins as the overview but with the row cap lifted, and running them on every period
     * change for a section nobody has opened is work for nothing.
     */
    private data class ExtendedRequest(
        val period: StatPeriod,
        val limit: Int,
        val show: Boolean,
        val metric: StatMetric,
    )

    private val extendedRequest =
        combine(statPeriod, extendedLimit, showExtended, statMetric, ::ExtendedRequest)

    val extendedSongs = extendedRequest.flatMapLatest { r ->
        if (!r.show) {
            flowOf(emptyList())
        } else {
            database.mostPlayedSongs(
                r.period.toTimeMillis(),
                limit = r.limit,
                byPlayTime = r.metric == StatMetric.TIME_LISTENED,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val extendedArtists = extendedRequest.flatMapLatest { r ->
        if (!r.show) {
            flowOf(emptyList())
        } else {
            database.mostPlayedArtists(
                r.period.toTimeMillis(),
                limit = r.limit,
                byPlayTime = r.metric == StatMetric.TIME_LISTENED,
            ).map { artists ->
                artists.filter { it.artist.isYouTubeArtist }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // The extended chart reaches artists the overview never showed, which are exactly the
        // ones most likely to be missing a picture.
        viewModelScope.launch {
            extendedArtists.collect { artists ->
                fetchMissingArtistArt(artists.map { it.artist })
            }
        }
        // fetch missing artist metadata
        viewModelScope.launch {
            mostPlayedArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(it.lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10)
                    }
                    .forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
        // fetch missing album metadata
        viewModelScope.launch {
            mostPlayedAlbums.collect { albums ->
                albums.filter {
                    it.album.songCount == 0
                }.forEach { album ->
                    YouTube.album(album.id).onSuccess { albumPage ->
                        database.query {
                            update(album.album, albumPage)
                        }
                    }.onFailure {
                        reportException(it)
                        if (it.message?.contains("NOT_FOUND") == true) {
                            database.query {
                                delete(album.album)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchMissingArtistArt(artists: List<com.dd3boh.outertune.db.entities.ArtistEntity>) {
        artists
            .filter {
                it.thumbnailUrl == null ||
                        Duration.between(it.lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10)
            }
            .forEach { artist ->
                YouTube.artist(artist.id).onSuccess { artistPage ->
                    database.query {
                        update(artist, artistPage)
                    }
                }
            }
    }

    companion object {
        val EXTENDED_LIMITS = listOf(20, 50, 100)
    }
}
