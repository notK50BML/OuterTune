package com.dd3boh.outertune.viewmodels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.normalizeArtistId
import com.dd3boh.outertune.db.TOPIC_SUFFIX
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.ArtistPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    /** Exactly as navigated, kept only as a fallback for the remote fetch. */
    private val requestedArtistId = savedStateHandle.get<String>("artistId")!!

    /**
     * The id this screen was navigated with, in the spelling the database stores.
     *
     * YouTube gives an artist's channel two spellings - the bare `UC…` and an `MPLAUC…` form it
     * returns in album and playlist contexts - and every write path normalises to the bare one
     * before storing. Reads did not. So arriving here from anywhere that hands over YouTube's own
     * id, such as an online album or a search result, queried the library for a spelling no row is
     * stored under: no artist, no songs, no albums, while the remote fetch still resolved the name
     * perfectly well. The result is the page the artist's own songs claim to belong to, showing
     * their name and nothing else.
     */
    val artistId = requestedArtistId.normalizeArtistId()
    var artistPage by mutableStateOf<ArtistPage?>(null)

    /**
     * Which artist row the library sections read from.
     *
     * Normally [artistId]. It moves only when that id turns out to hold nothing and another row
     * with the same name holds the songs - see [resolveLibraryArtist]. Reactive rather than fixed
     * at construction because the answer is not known until the page has been fetched and its real
     * name is in hand.
     */
    private val libraryArtistId = MutableStateFlow(artistId)

    val libraryArtist = libraryArtistId
        .flatMapLatest { database.artist(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = libraryArtistId
        .flatMapLatest { database.artistSongs(it, ArtistSongSortType.CREATE_DATE, descending = true) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = libraryArtistId
        .flatMapLatest { database.artistAlbumsPreview(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isLoading = MutableStateFlow(true)

    init {
        fetchArtistsFromYTM()
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            isLoading.value = true
            // Normalised first, since that is the canonical browse id. The raw spelling is tried
            // only if that fails, so an artist YouTube will answer for under one form and not the
            // other still resolves rather than showing an empty page.
            YouTube.artist(artistId)
                .recoverCatching { failure ->
                    if (requestedArtistId == artistId) throw failure
                    YouTube.artist(requestedArtistId).getOrThrow()
                }
                .map { page -> preferChannelWithSongs(page) }
                .onSuccess {
                    artistPage = it
                    // Write the authoritative name/picture back to the library row. This screen
                    // displays artistPage's title in preference to the stored one, so without
                    // this an artist renamed upstream reads correctly *here* while every song
                    // credit elsewhere in the app keeps showing the old name - tap "X" on a song
                    // and land on a page headed "Y". Only the artist-list refresh in
                    // LibraryViewModels did this, and only for artists with no thumbnail or not
                    // touched in 10 days, so opening the page itself never fixed it.
                    refreshLibraryArtist(it)
                    resolveLibraryArtist(it.artist.title)
                }.onFailure {
                    reportException(it)
                    // Even with no page, the stored row may name the artist well enough to find
                    // where their songs actually live.
                    database.artist(artistId).first()?.artist?.name?.let { name ->
                        resolveLibraryArtist(name)
                    }
                }

            isLoading.value = false
        }
    }

    /**
     * Updates the stored artist from [page] when something actually differs, so a rename or a new
     * picture propagates to every song credit. No-ops for an artist that isn't in the library, and
     * skips the write entirely when nothing changed so lastUpdateTime isn't churned on every visit.
     */
    private suspend fun refreshLibraryArtist(page: ArtistPage) {
        // Read straight from the database rather than libraryArtist.value: that flow is
        // SharingStarted.Lazily and this runs immediately after init's fetch, so its cached value
        // can still be null here even for an artist that is in the library - which would make this
        // silently do nothing.
        val stored = database.artist(artistId).first()?.artist ?: return
        val freshName = page.artist.title.stripTopicSuffix()
        val freshThumbnail = page.artist.thumbnail?.resize(544, 544)
        // A channel titled exactly "- Topic" names nobody. stripTopicSuffix leaves such a title
        // alone rather than reducing it to an empty string - stripping is meant to tidy a name, not
        // delete one - which means it now arrives here looking like an ordinary name and would
        // overwrite a perfectly good stored one. So the suffix surviving the strip is the tell that
        // there was no name in front of it, and whatever is already stored is worth more.
        val namesSomebody = freshName.isNotBlank() && !TOPIC_SUFFIX.containsMatchIn(freshName)
        val nameChanged = namesSomebody && stored.name != freshName
        val thumbnailChanged = freshThumbnail != null && stored.thumbnailUrl != freshThumbnail
        if (!nameChanged && !thumbnailChanged) return

        if (nameChanged) {
            Log.i(TAG, "Artist ${stored.id} renamed upstream: \"${stored.name}\" -> \"$freshName\"")
        }
        // query() already runs on Room's own executor, so no dispatcher hop needed here.
        database.query { update(stored, page) }
    }

    /**
     * Swaps to a same-named channel that actually lists songs, when this one does not.
     *
     * YouTube gives one real-world artist several channels, and they are not equivalent. A song can
     * be credited to a channel whose page carries only Singles and EPs while another channel, named
     * identically and equally real, carries the full Songs listing including that very song. Landing
     * on the first is technically correct and useless: the song that was tapped is nowhere on the
     * page it led to.
     *
     * So a page with no Songs section is treated as a candidate rather than an answer, and the other
     * channels of that name are tried. The first that lists songs wins; if none does, the original
     * stands, because a page with singles on it beats no page at all.
     *
     * A Songs section is recognised the same way the screen recognises one - items that are songs
     * carrying an album - so this cannot disagree with what is about to be rendered.
     *
     * Bounded to [MAX_CHANNEL_CANDIDATES] extra fetches. This runs while the user is looking at a
     * loading spinner, and a name shared by many artists must not turn opening a page into a survey
     * of all of them.
     */
    private suspend fun preferChannelWithSongs(page: ArtistPage): ArtistPage {
        if (page.hasSongsSection()) return page
        val name = page.artist.title.stripTopicSuffix()
        if (name.isBlank() || TOPIC_SUFFIX.containsMatchIn(name)) return page

        val candidates = withContext(Dispatchers.IO) {
            database.artistsByNameIgnoreCase(name, artistId, MAX_CHANNEL_CANDIDATES)
        }
        for (candidate in candidates) {
            if (!candidate.isYouTubeArtist) continue
            val better = YouTube.artist(candidate.id.normalizeArtistId()).getOrNull() ?: continue
            if (!better.hasSongsSection()) continue
            Log.i(TAG, "\"$name\" $artistId lists no songs; ${candidate.id} does - showing that")
            libraryArtistId.value = candidate.id
            return better
        }
        return page
    }

    /** The same test the screen uses to decide whether a section is the Songs shelf. */
    private fun ArtistPage.hasSongsSection(): Boolean =
        sections.any { (it.items.firstOrNull() as? SongItem)?.album != null }

    /**
     * Points the library sections at the row that actually holds this artist's songs.
     *
     * YouTube gives one real-world artist more than one channel - an auto-generated "- Topic" one
     * alongside their real one, most often - and the library keeps whichever id it saw first. Every
     * song is then mapped to that id, so opening the page for the *other* channel shows the right
     * name over an empty library: the songs are right there, filed under a different id.
     *
     * Only the library sections move. The page itself stays the artist that was asked for, so this
     * cannot misrepresent whose page is being shown - and the query it uses returns nothing unless
     * the other row genuinely has songs, so the worst case is that the page is left exactly as it
     * was.
     */
    private suspend fun resolveLibraryArtist(name: String) {
        // Only ever a repair. An id with songs of its own is already correct and must not be
        // second-guessed - two artists really can share a name.
        if (database.artistSongs(artistId, ArtistSongSortType.CREATE_DATE, descending = true).first().isNotEmpty()) return

        val cleanName = name.stripTopicSuffix()
        if (cleanName.isBlank() || TOPIC_SUFFIX.containsMatchIn(cleanName)) return

        // Directly rather than through database.query, which dispatches onto Room's executor and
        // returns nothing - this needs the row back. So it takes the IO hop itself, since a blocking
        // Room read must not happen on the main thread.
        val match = withContext(Dispatchers.IO) {
            database.artistWithSongsByNameIgnoreCase(cleanName, artistId)
        } ?: return
        Log.i(TAG, "Artist $artistId has no songs; \"$cleanName\" is filed under ${match.id} - showing those")
        libraryArtistId.value = match.id
    }

    companion object {
        private const val TAG = "ArtistViewModel"

        /**
         * Other channels of the same name to try before giving up.
         *
         * Each one is a network round trip taken while the user watches a spinner. Two is enough for
         * the case this exists for - an artist with a topic channel, a real channel and occasionally
         * a second real one - without turning a common name into a survey.
         */
        private const val MAX_CHANNEL_CANDIDATES = 2
    }
}
