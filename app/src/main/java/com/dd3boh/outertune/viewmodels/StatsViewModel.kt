package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.PlayTotals
import com.zionhuang.innertube.pages.HistoryPage
import com.zionhuang.innertube.utils.HistoryTally
import com.dd3boh.outertune.utils.mergeSameSongs
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

    /**
     * How many songs the chart shows - 20, 50, 100 or every one. One chart now: the overview and
     * the "Extended charts" below it showed the same ranking twice, at two lengths.
     */
    val songLimit = MutableStateFlow(SONG_LIMITS.first())

    private val overviewRequest = combine(statPeriod, statMetric, songLimit) { period, metric, limit ->
        Triple(period, metric, limit)
    }


    /**
     * Plays this account made anywhere else - the YouTube Music app, another phone, the web - read
     * from YouTube's own history, tallied by song.
     *
     * Empty unless the user turns it on, because it costs a run of sequential network requests and
     * most of what it adds is already counted locally. Off, this whole feature is one map lookup
     * against an empty map.
     */
    private val remoteHistory: StateFlow<List<HistoryPage.HistorySection>> =
        context.dataStore.data
            .map { it[StatsIncludeRemoteKey] ?: false }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) flowOf(emptyList()) else flow { emit(fetchRemoteHistory()) }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Each song's plays in the history, for the period shown. Only sections inside the period count
     * (see HistoryTally) - the whole history used to be added to every period, so a week's chart
     * gained plays from months ago.
     */
    private val remoteTally: StateFlow<Map<String, HistoryTally.Plays>> =
        combine(remoteHistory, statPeriod) { sections, period ->
            if (sections.isEmpty()) emptyMap() else HistoryTally.tally(sections, period.days())
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /**
     * A wider local set than the chart shows, so the merge below has something to rank against -
     * taking the local top N and then adding remote plays to them would let a song just outside it
     * locally but first overall stay invisible. Also what the totals' history plays are measured
     * against.
     */
    private val localPool = overviewRequest.flatMapLatest { (period, metric, limit) ->
        database.mostPlayedSongsWithStats(
            period.toTimeMillis(),
            limit = maxOf(limit, MERGE_POOL),
            byPlayTime = metric == StatMetric.TIME_LISTENED,
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedSongs = combine(
        localPool,
        remoteTally,
        statMetric,
        songLimit,
    ) { local, tally, metric, limit -> mergeRemotePlays(local, tally, metric, limit = limit) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Plays and listening time in the period, for the header beside the title - with what the
     * history adds over this device's own plays, so the totals agree with the chart under them.
     */
    val totals = combine(
        statPeriod.flatMapLatest { period -> database.playTotals(period.toTimeMillis()) },
        remoteTally,
        localPool,
    ) { local, tally, pool ->
        if (local == null || tally.isEmpty()) return@combine local
        val localPlays = pool.associate { it.song.id to it.totalPlays }
        var plays = 0
        var time = 0L
        tally.values.forEach { remote ->
            val extra = HistoryTally.extraPlays(localPlays[remote.song.id] ?: 0, remote.plays)
            plays += extra
            time += extra * (remote.song.duration ?: 0) * 1000L
        }
        PlayTotals(plays = local.plays + plays, playTime = local.playTime + time)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val mostPlayedArtists = overviewRequest.flatMapLatest { (period, metric, limit) ->
        database.mostPlayedArtistStats(
            period.toTimeMillis(),
            // As long as the chart, but a row of artist tiles has no use for thousands.
            limit = limit.coerceAtMost(MAX_ARTISTS),
            byPlayTime = metric == StatMetric.TIME_LISTENED,
        ).map { artists ->
            artists.filter { it.artist.isYouTubeArtist }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    val mostPlayedAlbums = statPeriod.flatMapLatest { period ->
        database.mostPlayedAlbums(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // A long chart reaches artists the old six never showed, which are exactly the ones most
        // likely to be missing a picture.
        viewModelScope.launch {
            mostPlayedArtists.collect { artists ->
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
    private suspend fun fetchRemoteHistory(): List<HistoryPage.HistorySection> {
        val sections = ArrayList<HistoryPage.HistorySection>()
        var page = YouTube.musicHistory().getOrElse {
            reportException(it)
            return emptyList()
        }
        var pagesRead = 0
        while (true) {
            sections += page.sections.orEmpty()
            val continuation = page.continuation ?: break
            if (++pagesRead >= MAX_HISTORY_PAGES) break
            page = YouTube.musicHistoryContinuation(continuation).getOrNull() ?: break
        }
        return sections
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
        tally: Map<String, HistoryTally.Plays>,
        metric: StatMetric,
        limit: Int,
    ): List<SongPlayStats> {
        // The same song under two ids counts once - see mergeSameSongs. After the remote plays are
        // added, so a song's plays from everywhere end up in the one entry.
        if (tally.isEmpty()) return rank(mergeSameSongs(local), metric, limit)

        // The larger of the local and history counts, not their sum: plays this device shared with
        // YouTube Music are in both - see HistoryTally.extraPlays.
        val merged = local.map { stats ->
            val remote = tally[stats.song.id] ?: return@map stats
            val extra = HistoryTally.extraPlays(stats.totalPlays, remote.plays)
            stats.copy(
                totalPlays = stats.totalPlays + extra,
                totalPlayTime = stats.totalPlayTime + extra * stats.song.song.duration.coerceAtLeast(0) * 1000L,
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

        return rank(mergeSameSongs(merged), metric, limit)
    }

    private fun rank(songs: List<SongPlayStats>, metric: StatMetric, limit: Int): List<SongPlayStats> =
        songs
            .sortedByDescending {
                if (metric == StatMetric.TIME_LISTENED) it.totalPlayTime else it.totalPlays.toLong()
            }
            .take(limit)

    companion object {
        /** "All" is this: SQLite takes it as no limit worth the name. */
        const val ALL = Int.MAX_VALUE
        val SONG_LIMITS = listOf(20, 50, 100, ALL)

        private const val MAX_ARTISTS = 100

        /** How many local rows to rank against before trimming to what is shown - see the call site. */
        private const val MERGE_POOL = 200

        /** ~20 songs a page, so this reaches a few thousand plays back. */
        private const val MAX_HISTORY_PAGES = 40
    }
}

/** The period's length in days, or null for all time - what HistoryTally counts sections against. */
private fun StatPeriod.days(): Long? =
    if (this == StatPeriod.ALL) null else Duration.between(toLocalDateTime(), LocalDateTime.now()).toDays()
