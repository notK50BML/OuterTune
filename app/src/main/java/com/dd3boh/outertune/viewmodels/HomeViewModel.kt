package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.R
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import android.widget.Toast
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.SimilarRecommendation
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.syncCoroutine
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.ExplorePage
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.utils.completed
import com.zionhuang.innertube.utils.parseCookieString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    val homePage = MutableStateFlow<HomePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)

    /**
     * Each chip's page, fetched in the background once Home has loaded - see [prefetchChips]. A tap
     * then shows it at once and refreshes it behind, rather than waiting a round trip with nothing
     * on screen.
     */
    private val chipPages = java.util.concurrent.ConcurrentHashMap<String, HomePage>()

    /**
     * Chips whose page holds nothing this app can show - Podcasts, today, whose shelves parse to
     * nothing. Hidden rather than offered: a chip that only ever answers "nothing here" is noise.
     * Only a page that came back empty counts; one that failed to load is left alone.
     */
    val emptyChips = MutableStateFlow<Set<String>>(emptySet())
    private val previousHomePage = MutableStateFlow<HomePage?>(null)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val playlists = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val recentActivity = database.recentActivity()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    private suspend fun load() {
        isLoading.value = true

        quickPicks.value = database.quickPicks()
            .first().shuffled().take(20)

        forgottenFavorites.value = database.forgottenFavorites()
            .first().shuffled().take(20)

        val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2
        val keepListeningSongs = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
            .first().shuffled().take(10)
        val keepListeningAlbums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
            .first().filter { it.album.thumbnailUrl != null }.shuffled().take(5)
        val keepListeningArtists = database.mostPlayedArtists(0L)
            .first().filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }.shuffled().take(5)
        keepListening.value = (keepListeningSongs + keepListeningAlbums + keepListeningArtists).shuffled()

        allLocalItems.value =
            (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                .filter { it is Song || it is Album }

        // Everything past this point is network, and none of it depends on any of the rest.
        //
        // It used to run strictly in order: the liked playlists, then three artist pages one after
        // another, then two songs each needing a next() before its related(), then home, then
        // explore, then the recent-activity sync. Nine or more round trips end to end, so a
        // refresh took as long as all of them added together - which is where the ten seconds came
        // from. Run together, it takes as long as the slowest one instead.
        //
        // Each branch catches its own failures. A failing async cancels its parent scope and takes
        // its siblings with it, so one provider being down would otherwise empty the whole screen
        // rather than leaving a gap where its own section goes.
        coroutineScope {
            val accountPlaylistsJob = async {
                if (YouTube.cookie == null) return@async
                runCatching {
                    // InnerTune way is YouTube.likedPlaylists().onSuccess { ... }
                    // OuterTune uses YouTube.library("FEmusic_liked_playlists").completedL()
                    YouTube.library("FEmusic_liked_playlists").completed().onSuccess {
                        accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
                    }.onFailure { reportException(it) }
                }.onFailure { reportException(it) }
            }

            // Similar to artists - the three pages fetched at once rather than in turn.
            val artistRecommendationsJob = async {
                runCatching {
                    database.mostPlayedArtists(0L, limit = 10).first()
                        .filter { it.artist.isYouTubeArtist }
                        .shuffled().take(3)
                        .map { artist ->
                            async {
                                val items = mutableListOf<YTItem>()
                                YouTube.artist(artist.id).onSuccess { page ->
                                    items += page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty()
                                    items += page.sections.lastOrNull()?.items.orEmpty()
                                }
                                items.shuffled().ifEmpty { null }
                                    ?.let { SimilarRecommendation(title = artist, items = it) }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                }.getOrElse {
                    reportException(it)
                    emptyList()
                }
            }

            // Similar to songs. The next() and related() for one song stay in order - the second
            // needs the first's endpoint - but the two songs no longer wait for each other.
            val songRecommendationsJob = async {
                runCatching {
                    database.mostPlayedSongs(fromTimeStamp, limit = 10).first()
                        .filter { it.album != null }
                        .shuffled().take(2)
                        .map { song ->
                            async {
                                val endpoint = YouTube.next(WatchEndpoint(videoId = song.id))
                                    .getOrNull()?.relatedEndpoint ?: return@async null
                                val page = YouTube.related(endpoint).getOrNull() ?: return@async null
                                (page.songs.shuffled().take(8) +
                                    page.albums.shuffled().take(4) +
                                    page.artists.shuffled().take(4) +
                                    page.playlists.shuffled().take(4))
                                    .shuffled()
                                    .ifEmpty { null }
                                    ?.let { SimilarRecommendation(title = song, items = it) }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                }.getOrElse {
                    reportException(it)
                    emptyList()
                }
            }

            val homeJob = async {
                runCatching {
                    YouTube.home().onSuccess { page ->
                        homePage.value = page
                        prefetchChips(page.chips.orEmpty())
                    }
                        .onFailure { reportException(it) }
                }.onFailure { reportException(it) }
            }

            val exploreJob = async {
                runCatching {
                    YouTube.explore().onSuccess { page -> explorePage.value = page }
                        .onFailure { reportException(it) }
                }.onFailure { reportException(it) }
            }

            // Not waited for: it writes the database, and the Recent activity row follows the
            // database on its own. Waiting kept the refresh spinner going for a whole sync.
            viewModelScope.launch(syncCoroutine) {
                runCatching { syncUtils.syncRecentActivity() }.onFailure { reportException(it) }
            }

            accountPlaylistsJob.await()
            similarRecommendations.value =
                (artistRecommendationsJob.await() + songRecommendationsJob.await()).shuffled()
            homeJob.await()
            exploreJob.await()
        }

        allYtItems.value = similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                homePage.value?.sections?.flatMap { it.items }.orEmpty()

        isLoading.value = false
    }

    private val _isLoadingMore = MutableStateFlow(false)
    fun loadMoreYouTubeItems(continuation: String?) {
        if (continuation == null) return
        val requestedHomePage = homePage.value?.takeIf { it.continuation == continuation } ?: return

        viewModelScope.launch(Dispatchers.IO) {
            if (!_isLoadingMore.compareAndSet(false, true)) return@launch

            try {
                if (homePage.value !== requestedHomePage) return@launch

                val nextSections = YouTube.home(continuation).getOrNull() ?: return@launch
                homePage.update { currentHomePage ->
                    if (currentHomePage !== requestedHomePage) {
                        currentHomePage
                    } else {
                        nextSections.copy(
                            chips = currentHomePage.chips,
                            sections = currentHomePage.sections + nextSections.sections
                        )
                    }
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Every chip's page, all at once and in the background, after Home itself is on screen. Fills
     * [chipPages] so a tap is instant, and [emptyChips] so chips with nothing to show are hidden.
     */
    private fun prefetchChips(chips: List<HomePage.Chip>) {
        chipPages.clear()
        emptyChips.value = emptySet()
        if (chips.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            chips.map { chip ->
                async {
                    val page = YouTube.home(params = chip.endpoint?.params).getOrNull() ?: return@async
                    if (page.sections.isEmpty()) {
                        emptyChips.update { it + chip.title }
                    } else {
                        chipPages[chip.title] = page
                    }
                }
            }.awaitAll()
        }
    }

    /** The chip request in flight, so a second tap replaces the first rather than racing it. */
    private var chipJob: Job? = null

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
            chipJob?.cancel()
            previousHomePage.value?.let { homePage.value = it }
            previousHomePage.value = null
            selectedChip.value = null
            return
        }

        val selectedBefore = selectedChip.value
        if (selectedBefore == null) {
            // store the actual homepage for deselecting chips
            previousHomePage.value = homePage.value
        }
        // Lit as soon as it is tapped. It used to light only once the request came back, so for a
        // second or two a tap looked like it had done nothing at all.
        selectedChip.value = chip
        chipJob?.cancel()
        // Fetched already: shown now, and refreshed below like any other tap.
        chipPages[chip.title]?.let { cached ->
            homePage.value = cached.copy(chips = homePage.value?.chips)
        }
        chipJob = viewModelScope.launch(Dispatchers.IO) {
            val nextSections = YouTube.home(params = chip.endpoint?.params).getOrNull()
            // Some chips - Podcasts, today - come back as shelves this app does not draw, which
            // parse to nothing. Showing an empty home for those reads as broken; saying so and
            // staying where you were does not.
            // Already showing its fetched page: a refresh that fails changes nothing.
            if (nextSections == null && chipPages.containsKey(chip.title)) return@launch
            if (nextSections == null || nextSections.sections.isEmpty()) {
                if (selectedChip.value == chip) {
                    selectedChip.value = selectedBefore
                    if (selectedBefore == null) {
                        previousHomePage.value?.let { homePage.value = it }
                        previousHomePage.value = null
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(
                            if (nextSections == null) R.string.home_chip_failed else R.string.home_chip_empty,
                            chip.title,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            chipPages[chip.title] = nextSections
            if (selectedChip.value != chip) return@launch
            homePage.value = nextSections.copy(
                chips = homePage.value?.chips,
                sections = nextSections.sections,
                continuation = nextSections.continuation
            )
        }
    }

    fun refresh() {
        if (isRefreshing.value) return
        viewModelScope.launch(syncCoroutine) {
            isRefreshing.value = true
            load()
            isRefreshing.value = false
        }
    }

    init {
        refresh()
        viewModelScope.launch(syncCoroutine) {
            syncUtils.tryAutoSync()
        }
        // Reload when the sign-in state changes so the home reflects login without a manual refresh.
        viewModelScope.launch {
            context.dataStore.data
                .map { "SAPISID" in parseCookieString(it[InnerTubeCookieKey] ?: "") }
                .distinctUntilChanged()
                .drop(1) // skip the current state already loaded by refresh() above
                .collect { refresh() }
        }
    }
}
