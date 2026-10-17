/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.utils.completed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The signed-in account's library, brought into the desktop: liked songs, saved albums, and the
 * artists in it - what the phone's SyncUtils fetches, from the same places.
 *
 * Liked songs are merged in rather than mirrored. A song liked here and not yet on YouTube stays
 * liked; one liked on YouTube becomes liked here, in YouTube's order. Liking or unliking here is sent
 * to YouTube as it happens (see [pushLike]), so the two converge without a sync ever having to
 * decide which side to throw away.
 *
 * Albums and artists are held in memory, not stored: they are YouTube's lists, shown as YouTube has
 * them, and refreshed each time rather than allowed to go stale in a table.
 */
class YouTubeSync(private val library: LibraryStore) {

    val syncing = MutableStateFlow(false)

    /** What the last sync did, in a sentence, or null before the first. */
    val status = MutableStateFlow<String?>(null)

    val albums = MutableStateFlow<List<AlbumItem>>(emptyList())
    val artists = MutableStateFlow<List<ArtistItem>>(emptyList())

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        if (syncing.value) return@withContext
        syncing.value = true
        try {
            coroutineScope {
                val liked = async { YouTube.playlist("LM").completed().getOrNull()?.songs }
                val savedAlbums = async {
                    YouTube.library("FEmusic_liked_albums").completed().getOrNull()?.items?.filterIsInstance<AlbumItem>()
                }
                val savedArtists = async {
                    YouTube.library("FEmusic_library_corpus_track_artists").completed().getOrNull()
                        ?.items?.filterIsInstance<ArtistItem>()
                }

                val songs = liked.await()
                // Remote order: index 0 is the most recently liked.
                val added = songs?.let { library.importLiked(it.map { song -> song.toStored() }) }
                savedAlbums.await()?.let { albums.value = it }
                savedArtists.await()?.let { artists.value = it }

                status.value = when {
                    songs == null -> "Could not reach your YouTube library."
                    added == 0 -> "Up to date - ${songs.size} liked songs."
                    else -> "Added $added liked ${if (added == 1) "song" else "songs"} from YouTube."
                }
            }
        } finally {
            syncing.value = false
        }
    }

    /** Sends a like or unlike made here to the account, so YouTube's list follows this one. */
    suspend fun pushLike(songId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        YouTube.likeVideo(songId, liked)
    }

    /** Forgets the account's lists, on sign-out. Liked songs already brought in stay. */
    fun clear() {
        albums.value = emptyList()
        artists.value = emptyList()
        status.value = null
    }
}
