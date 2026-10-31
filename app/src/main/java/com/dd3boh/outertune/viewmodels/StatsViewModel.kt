package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.PlayTotals
import com.zionhuang.innertube.pages.HistoryPage
import com.zionhuang.innertube.utils.HistoryTally
import com.dd3boh.outertune.utils.mergeSameSongs
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.StatMetric
import com.dd3boh.outertune.constants.StatMetricKey
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.constants.StatPeriodKey
import com.dd3boh.outertune.constants.StatSongLimitKey
import com.dd3boh.outertune.constants.StatsIncludeRemoteKey
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.daos.SongPlays
import com.dd3boh.outertune.db.entities.ArtistPlayStats
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongPlayStats
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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
    /**
     * The three chips are preferences rather than state of their own, so Stats opens as it was left
     * (see StatPeriodKey): the screen writes them, and these follow. Each was a MutableStateFlow
     * here, and every visit started again from a week of time listened and 20 songs.
     */
    private val statPeriod = context.dataStore.data
        .map { it[StatPeriodKey].toEnum(DEFAULT_PERIOD) }
        .distinctUntilChanged()

    /**
     * Time listened or number of plays. They rank differently and both are reasonable: a long
     * track you play twice beats a short one you play ten times by time, and loses by count.
     */
    private val statMetric = context.dataStore.data
        .map { it[StatMetricKey].toEnum(DEFAULT_METRIC) }
        .distinctUntilChanged()

    /**
     * How many songs the chart shows - 20, 50, 100 or every one. One chart now: the overview and
     * the "Extended charts" below it showed the same ranking twice, at two lengths. A stored value
     * that is not one of the chips is the default.
     */
    private val songLimit = context.dataStore.data
        .map { prefs -> prefs[StatSongLimitKey]?.takeIf { it in SONG_LIMITS } ?: DEFAULT_SONG_LIMIT }
        .distinctUntilChanged()

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
     * Every song played in the period with its plays and listening time - the whole list, not the
     * top of it. Null until the period's rows have loaded.
     */
    private val periodPlays: StateFlow<List<SongPlays>?> =
        statPeriod.flatMapLatest { period ->
            database.songPlaysSince(period.toTimeMillis())
                .map<List<SongPlays>, List<SongPlays>?> { it }
                .onStart { emit(null) }
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * The whole chart, ranked, with what the history adds - before it is cut to the song count.
     *
     * Ranked from every song played rather than from the top 200 or the count chosen: the history
     * was merged against those, so a song below the cut that also had history plays was taken as
     * played only elsewhere at 20, 50 or 100 and with its own plays at "All", and its numbers, its
     * place and which songs made the chart changed with the count. Two ids of one song below the
     * cut could likewise only add up at "All".
     */
    private val chart: StateFlow<StatsChart?> =
        combine(periodPlays, remoteTally, statMetric) { rows, tally, metric ->
            rows?.let { buildStatsChart(it, tally, metric) }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, null)

    val mostPlayedSongs: StateFlow<List<SongPlayStats>> =
        combine(chart, songLimit) { chart, limit -> chart to limit }
            .mapLatest { (chart, limit) -> chart?.let { showable(it.songs.take(limit), it.localIds) }.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Plays and listening time in the period, for the header beside the title. Summed from the
     * same rows as the chart, history included, so the header is the whole chart added up.
     */
    val totals: StateFlow<PlayTotals?> = chart.map { it?.totals }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * Artists by the same measure as the chart, with the history's plays too - credited the same
     * way as the songs gain them. Without them a song played mostly elsewhere could top the chart
     * while its artist was missing from the row above it.
     */
    private val artistRows: StateFlow<ArtistRows> = combine(
        statPeriod.flatMapLatest { period ->
            // Every artist, not the top of them, for the same reason the chart ranks every song.
            database.mostPlayedArtistStats(period.toTimeMillis(), limit = ALL, byPlayTime = true)
        },
        chart,
        statMetric,
        songLimit,
    ) { local, chart, metric, limit ->
        withHistoryArtists(local, chart?.artistExtras.orEmpty(), metric).let { rows ->
            rows.copy(
                artists = rows.artists
                    .filter { it.artist.isYouTubeArtist }
                    // As long as the chart, but a row of artist tiles has no use for thousands.
                    .take(limit.coerceAtMost(MAX_ARTISTS))
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, ArtistRows(emptyList(), emptySet()))

    val mostPlayedArtists: StateFlow<List<ArtistPlayStats>> = artistRows.map { it.artists }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Albums by the same measure as the chart. This device's plays only: a history entry names its
     * album, but not reliably enough to add its plays to the right one.
     */
    val mostPlayedAlbums = combine(statPeriod, statMetric) { period, metric -> period to metric }
        .flatMapLatest { (period, metric) ->
            database.mostPlayedAlbums(period.toTimeMillis(), byPlayTime = metric == StatMetric.TIME_LISTENED)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * [shown] with each song this device knows read back from the library, for its cover, album
     * and the rest. The chart is ranked from slim rows that carry only what ranking needs.
     */
    private suspend fun showable(shown: List<SongPlayStats>, localIds: Set<String>): List<SongPlayStats> {
        val ids = shown.map { it.song.id }.filter { it in localIds }
        // In chunks: "All" can be thousands of songs, past SQLite's limit on query parameters.
        val songs = ids.chunked(500).flatMap { database.songsByIds(it) }.associateBy { it.id }
        return shown.map { stats -> songs[stats.song.id]?.let { stats.copy(song = it) } ?: stats }
    }

    init {
        // A long chart reaches artists the old six never showed, which are exactly the ones most
        // likely to be missing a picture. Only artists in the library: one known only from the
        // history has no row to update, so it would be fetched again on every play. This was
        // done twice, by two collectors asking for the same artists.
        //
        // Each artist is asked for once per session, and only when the set needing it changes -
        // the same pattern as LibraryArtistsViewModel. Every Room emission used to start the whole
        // pass again, including the ones this pass caused by writing its own results, and an
        // artist YouTube could not return stayed selected and was asked for every time.
        viewModelScope.launch {
            val attempted = mutableSetOf<String>()
            artistRows
                .map { rows ->
                    rows.artists.map { it.artist }.filter { it.id !in rows.historyOnly && it.needsArt() }
                }
                .distinctUntilChanged { old, new -> old.map { it.id }.toSet() == new.map { it.id }.toSet() }
                .collect { stale ->
                    fetchMissingArtistArt(stale.filter { attempted.add(it.id) })
                }
        }
        // fetch missing album metadata - once per album per session, for the same reasons.
        viewModelScope.launch {
            val attempted = mutableSetOf<String>()
            mostPlayedAlbums
                .map { albums -> albums.filter { !it.album.isLocal && it.album.songCount == 0 } }
                .distinctUntilChanged { old, new -> old.map { it.id }.toSet() == new.map { it.id }.toSet() }
                .collect { stale ->
                    stale.filter { attempted.add(it.id) }.forEach { album ->
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

    private fun ArtistEntity.needsArt() =
        thumbnailUrl == null || Duration.between(lastUpdateTime, LocalDateTime.now()) > Duration.ofDays(10)

    private suspend fun fetchMissingArtistArt(artists: List<ArtistEntity>) {
        artists.forEach { artist ->
            YouTube.artist(artist.id).onSuccess { artistPage ->
                database.query {
                    update(artist, artistPage)
                }
            }
        }
    }


    /**
     * Walks YouTube's history, for HistoryTally to count how many times each song appears.
     *
     * Each entry stands for a play of the whole track - the history has no listening time, only
     * that the song was played - so that is what it adds to time listened (see historyPlayTime).
     * In English, whatever the app's content language: the sections are dated by their titles.
     *
     * Bounded by [MAX_HISTORY_PAGES] because history has no end: each page is one round trip, and
     * paging until YouTube runs out would be an unbounded number of them for a diminishing tail.
     */
    private suspend fun fetchRemoteHistory(): List<HistoryPage.HistorySection> {
        val sections = ArrayList<HistoryPage.HistorySection>()
        var page = YouTube.musicHistory(hl = HISTORY_LANGUAGE).getOrElse {
            reportException(it)
            return emptyList()
        }
        var pagesRead = 0
        while (true) {
            sections += page.sections.orEmpty()
            val continuation = page.continuation ?: break
            if (++pagesRead >= MAX_HISTORY_PAGES) break
            page = YouTube.musicHistoryContinuation(continuation, hl = HISTORY_LANGUAGE).getOrNull() ?: break
        }
        return sections
    }

    companion object {
        /** "All" is this: SQLite takes it as no limit worth the name. */
        const val ALL = Int.MAX_VALUE
        val SONG_LIMITS = listOf(20, 50, 100, ALL)

        /** What Stats first opens on, before a chip has been picked. */
        val DEFAULT_PERIOD = StatPeriod.`1_WEEK`
        val DEFAULT_METRIC = StatMetric.TIME_LISTENED
        val DEFAULT_SONG_LIMIT = SONG_LIMITS.first()

        private const val MAX_ARTISTS = 100

        /** ~20 songs a page, so this reaches a few thousand plays back. */
        private const val MAX_HISTORY_PAGES = 40

        /** The language HistoryTally reads section titles in. */
        private const val HISTORY_LANGUAGE = "en"
    }
}

/**
 * The song chart for a period, whole and ranked, with the header's totals and what the history
 * added to each artist - all from one pass, so the three agree. See [buildStatsChart].
 */
internal class StatsChart(
    val songs: List<SongPlayStats>,
    /** The songs played on this device, which the library can show in full. */
    val localIds: Set<String>,
    val totals: PlayTotals,
    val artistExtras: Map<String, ArtistExtra>,
)

/** What the history adds to one artist, with their name for an artist the library does not have. */
internal data class ArtistExtra(val name: String, val plays: Int, val playTime: Long)

/** The artist row, and which of its artists are known only from the history. */
internal data class ArtistRows(val artists: List<ArtistPlayStats>, val historyOnly: Set<String>)

/**
 * Every song in [local] - this device's plays in the period - with what the history [tally] adds,
 * the same song under several ids made one (see mergeSameSongs), ranked by [metric].
 *
 * A song played here gains the history's plays beyond its own - the larger of the two counts, not
 * their sum (see HistoryTally.extraPlays). One never played here is built from the history entry's
 * own metadata, in memory only: appearing in someone's stats is not a reason to start claiming they
 * own a song. Ties are broken by the other measure and then by id, so the same plays always rank
 * the same way. Internal so StatsChartTest runs the real thing.
 */
internal fun buildStatsChart(
    local: List<SongPlays>,
    tally: Map<String, HistoryTally.Plays>,
    metric: StatMetric,
): StatsChart {
    var plays = 0
    var playTime = 0L
    val artistExtras = HashMap<String, ArtistExtra>()
    fun credit(id: String, name: String, extra: Int, extraTime: Long) {
        val seen = artistExtras[id]
        artistExtras[id] = ArtistExtra(
            name = seen?.name ?: name,
            plays = (seen?.plays ?: 0) + extra,
            playTime = (seen?.playTime ?: 0L) + extraTime,
        )
    }

    // In id order, so which id stands for a merged song does not follow the query's row order.
    val rows = local.sortedBy { it.songId }.mapTo(ArrayList()) { row ->
        val remote = tally[row.songId]
        val extra = remote?.let { HistoryTally.extraPlays(row.plays, it.plays) } ?: 0
        val extraTime = historyPlayTime(extra, remote?.song?.duration, row.duration)
        plays += row.plays + extra
        playTime += row.playTime + extraTime
        val ids = row.artistIdList
        val names = row.artistNames
        if (extra > 0) ids.forEachIndexed { i, id -> credit(id, names.getOrElse(i) { "" }, extra, extraTime) }
        SongPlayStats(
            song = Song(
                song = SongEntity(id = row.songId, title = row.title, duration = row.duration, localPath = null),
                artists = ids.zip(names) { id, name -> ArtistEntity(id = id, name = name) },
            ),
            totalPlayTime = row.playTime + extraTime,
            totalPlays = row.plays + extra,
        )
    }
    val localIds = local.mapTo(HashSet()) { it.songId }

    // After the local rows, so on a tie in plays the merge keeps the version the library has.
    tally.values.filterNot { it.song.id in localIds }.sortedBy { it.song.id }.forEach { remote ->
        val metadata = remote.song.toMediaMetadata()
        val time = historyPlayTime(remote.plays, remote.song.duration, null)
        plays += remote.plays
        playTime += time
        remote.song.artists.forEach { artist ->
            artist.id?.let { credit(it, artist.name, remote.plays, time) }
        }
        rows += SongPlayStats(
            song = Song(
                song = metadata.toSongEntity(),
                artists = metadata.artists.map { artist ->
                    ArtistEntity(id = artist.id ?: ArtistEntity.generateArtistId(), name = artist.name)
                },
            ),
            totalPlays = remote.plays,
            totalPlayTime = time,
        )
    }

    val byTime = metric == StatMetric.TIME_LISTENED
    val ranked = mergeSameSongs(rows).sortedWith(
        compareByDescending<SongPlayStats> { if (byTime) it.totalPlayTime else it.totalPlays.toLong() }
            .thenByDescending { if (byTime) it.totalPlays.toLong() else it.totalPlayTime }
            .thenBy { it.song.id }
    )
    return StatsChart(ranked, localIds, PlayTotals(plays = plays, playTime = playTime), artistExtras)
}

/**
 * The listening time of [extra] plays made elsewhere: each a whole play of the track, as YouTube
 * keeps a history entry only once a song has been played through. Its length from the history
 * entry, else the library's; a track of unknown length (a live stream, or a length never read)
 * adds plays but no time. The history-only rows used to take an unknown length's -1 as it was,
 * and so took time away.
 */
internal fun historyPlayTime(extra: Int, historySeconds: Int?, localSeconds: Int?): Long {
    val seconds = historySeconds?.takeIf { it > 0 } ?: localSeconds?.takeIf { it > 0 } ?: 0
    return extra * seconds * 1000L
}

/** [local] artists with the history's [extras] added, and those known only from it, ranked by [metric]. */
internal fun withHistoryArtists(
    local: List<ArtistPlayStats>,
    extras: Map<String, ArtistExtra>,
    metric: StatMetric,
): ArtistRows {
    val known = local.mapTo(HashSet()) { it.artist.id }
    val raised = local.map { stats ->
        val extra = extras[stats.artist.id] ?: return@map stats
        stats.copy(totalPlays = stats.totalPlays + extra.plays, totalPlayTime = stats.totalPlayTime + extra.playTime)
    }
    val elsewhere = extras.filterKeys { it !in known }.map { (id, extra) ->
        ArtistPlayStats(ArtistEntity(id = id, name = extra.name), totalPlayTime = extra.playTime, totalPlays = extra.plays)
    }
    val byTime = metric == StatMetric.TIME_LISTENED
    val ranked = (raised + elsewhere).sortedWith(
        compareByDescending<ArtistPlayStats> { if (byTime) it.totalPlayTime else it.totalPlays.toLong() }
            .thenByDescending { if (byTime) it.totalPlays.toLong() else it.totalPlayTime }
            .thenBy { it.artist.id }
    )
    return ArtistRows(ranked, elsewhere.mapTo(HashSet()) { it.artist.id })
}

/** The period's length in days, or null for all time - what HistoryTally counts sections against. */
private fun StatPeriod.days(): Long? =
    if (this == StatPeriod.ALL) null else Duration.between(toLocalDateTime(), LocalDateTime.now()).toDays()
