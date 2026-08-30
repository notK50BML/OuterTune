package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.StatMetric
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.constants.StatsIncludeRemoteKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongPlayStats
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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
    @ApplicationContext private val context: Context,
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

    /**
     * Plays this account made anywhere else - the YouTube Music app, another phone, the web - read
     * from YouTube's own history, tallied by song.
     *
     * Empty unless the user turns it on, because it costs a run of sequential network requests and
     * most of what it adds is already counted locally. Off, this whole feature is one map lookup
     * against an empty map.
     */
    private val remoteTally: StateFlow<Map<String, RemotePlays>> =
        context.dataStore.data
            .map { it[StatsIncludeRemoteKey] ?: false }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) flowOf(emptyMap()) else flow { emit(fetchRemoteTally()) }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val mostPlayedSongs = combine(
        overviewRequest.flatMapLatest { (period, metric) ->
            // A wider local set than the six shown, so the merge below has something to rank
            // against - taking the local top six and then adding remote plays to them would let a
            // song that is seventh locally but first overall stay invisible.
            database.mostPlayedSongsWithStats(
                period.toTimeMillis(),
                limit = MERGE_POOL,
                byPlayTime = metric == StatMetric.TIME_LISTENED,
            )
        },
        remoteTally,
        statMetric,
    ) { local, tally, metric -> mergeRemotePlays(local, tally, metric, limit = 6) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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
            database.mostPlayedSongsWithStats(
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

    /** One song's plays from elsewhere, with the metadata to show it if it isn't known locally. */
    private data class RemotePlays(val song: SongItem, val plays: Int)

    /**
     * Walks YouTube's history and counts how many times each song appears.
     *
     * The count is the whole measurement. YouTube writes a history entry only once a song has
     * finished, so an entry is a completed play and the time it represents is the track's own
     * length - there is no partial listening to account for, and no timestamp needed to do it.
     * That is why this can contribute to time-listened at all, which the history's coarse "Today"
     * / "This week" section labels could never support on their own.
     *
     * Bounded by [MAX_HISTORY_PAGES] because history has no end: each page is one round trip, and
     * paging until YouTube runs out would be an unbounded number of them for a diminishing tail.
     */
    private suspend fun fetchRemoteTally(): Map<String, RemotePlays> {
        val tally = HashMap<String, RemotePlays>()
        var page = YouTube.musicHistory().getOrElse {
            reportException(it)
            return emptyMap()
        }
        var pagesRead = 0
        while (true) {
            page.sections.orEmpty().flatMap { it.songs }.forEach { song ->
                val seen = tally[song.id]
                tally[song.id] = seen?.copy(plays = seen.plays + 1) ?: RemotePlays(song, 1)
            }
            val continuation = page.continuation ?: break
            if (++pagesRead >= MAX_HISTORY_PAGES) break
            page = YouTube.musicHistoryContinuation(continuation).getOrNull() ?: break
        }
        return tally
    }

    /**
     * Folds [tally] into [local] and re-ranks.
     *
     * A song already played on this device keeps its local row and gains the remote plays on top.
     * One never played here has no local row at all - so it is built from the history entry's own
     * metadata, in memory only. Nothing is written to the library: appearing in someone's stats is
     * not a reason to start claiming they own a song.
     */
    private fun mergeRemotePlays(
        local: List<SongPlayStats>,
        tally: Map<String, RemotePlays>,
        metric: StatMetric,
        limit: Int,
    ): List<SongPlayStats> {
        if (tally.isEmpty()) return local.take(limit)

        val merged = local.map { stats ->
            val remote = tally[stats.song.id] ?: return@map stats
            stats.copy(
                totalPlays = stats.totalPlays + remote.plays,
                totalPlayTime = stats.totalPlayTime + remote.plays * stats.song.song.duration * 1000L,
            )
        }.toMutableList()

        val known = local.mapTo(HashSet()) { it.song.id }
        tally.values.filterNot { it.song.id in known }.forEach { remote ->
            val metadata = remote.song.toMediaMetadata()
            merged += SongPlayStats(
                song = Song(
                    song = metadata.toSongEntity(),
                    artists = metadata.artists.map { artist ->
                        ArtistEntity(id = artist.id ?: ArtistEntity.generateArtistId(), name = artist.name)
                    },
                ),
                totalPlays = remote.plays,
                totalPlayTime = remote.plays * metadata.duration * 1000L,
            )
        }

        return merged
            .sortedByDescending {
                if (metric == StatMetric.TIME_LISTENED) it.totalPlayTime else it.totalPlays.toLong()
            }
            .take(limit)
    }

    companion object {
        val EXTENDED_LIMITS = listOf(20, 50, 100)

        /** How many local rows to rank against before trimming to what is shown - see the call site. */
        private const val MERGE_POOL = 200

        /** ~20 songs a page, so this reaches a few thousand plays back. */
        private const val MAX_HISTORY_PAGES = 40
    }
}
