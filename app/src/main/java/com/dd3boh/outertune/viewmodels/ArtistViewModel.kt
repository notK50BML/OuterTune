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
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.ArtistPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = database.artistSongs(artistId, ArtistSongSortType.CREATE_DATE, descending = true)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = database.artistAlbumsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isLoading = MutableStateFlow(true)

    init {
        fetchArtistsFromYTM()
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            isLoading.value = true
            YouTube.artist(artistId)
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
                }.onFailure {
                    reportException(it)
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
        val nameChanged = freshName.isNotBlank() && stored.name != freshName
        val thumbnailChanged = freshThumbnail != null && stored.thumbnailUrl != freshThumbnail
        if (!nameChanged && !thumbnailChanged) return

        if (nameChanged) {
            Log.i(TAG, "Artist ${stored.id} renamed upstream: \"${stored.name}\" -> \"$freshName\"")
        }
        // query() already runs on Room's own executor, so no dispatcher hop needed here.
        database.query { update(stored, page) }
    }

    companion object {
        private const val TAG = "ArtistViewModel"
    }
}
