package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.normalizeArtistId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ArtistAlbumsViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // Normalised, for the reason spelled out in ArtistViewModel: YouTube has two spellings of a
    // channel id, writes store one of them, and a read using the other silently finds nothing.
    private val artistId = savedStateHandle.get<String>("artistId")!!.normalizeArtistId()
    val artist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val albums = database.artistAlbums(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}