/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.utils.completed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the home screen is currently showing.
 *
 * A single sealed state rather than a bag of flags, because these really are exclusive - the screen
 * is loading, or it has sections, or it has a reason it has none - and rendering two of them at once
 * is always a bug.
 */
sealed interface HomeState {
    data object Loading : HomeState
    data class Ready(
        val sections: List<HomePage.Section>,
        /** YouTube's mood filters - Relax, Workout, Sleep... - offered above the feed. */
        val chips: List<HomePage.Chip> = emptyList(),
        /** Where the feed carries on, for loading more as it is scrolled; null at the end. */
        val continuation: String? = null,
    ) : HomeState

    /**
     * Nothing to show, with something to say about why.
     *
     * [signedOut] separates "YouTube has no recommendations for you" from "you have not signed in",
     * which need completely different things from the reader: one is a shrug, the other is a button.
     */
    data class Empty(val message: String, val signedOut: Boolean) : HomeState
}

/**
 * The signed-in home feed.
 *
 * `YouTube.home()` already exists in the shared `:innertube` module and already reads
 * `YouTube.cookie`, which the desktop sets at startup - so a signed-in feed needs no new networking
 * and no new auth, only somewhere to put it. Until now the account was signed in and then nothing
 * used it, which made four working sign-in routes worth nothing at all.
 *
 * Sections are handed back as the module models them rather than flattened into a local type. They
 * carry four different kinds of item and the screen wants to treat each differently; converting them
 * into one shape here would only mean reconstructing the distinction on the other side.
 */
class HomeFeed {

    /**
     * The next stretch of the feed, appended to [current] - the phone's Home, which keeps loading
     * as it is scrolled. Unusable shelves are dropped as on the first page; a failure just ends it.
     */
    suspend fun more(current: HomeState.Ready): HomeState.Ready = withContext(Dispatchers.IO) {
        val continuation = current.continuation ?: return@withContext current
        val page = YouTube.home(continuation = continuation).getOrNull()
            ?: return@withContext current.copy(continuation = null)
        val usable = page.sections.filter { section -> section.items.any { it.isSupported } }
        current.copy(sections = current.sections + usable, continuation = page.continuation)
    }

    /** The feed, or with [chip] the feed filtered to one mood, as the phone's chips do. */
    suspend fun load(signedIn: Boolean, chip: HomePage.Chip? = null): HomeState = withContext(Dispatchers.IO) {
        val page = YouTube.home(params = chip?.endpoint?.params).getOrElse { error ->
            return@withContext HomeState.Empty(
                message = if (signedIn) {
                    "Could not load your home feed: ${error.message ?: error::class.simpleName}"
                } else {
                    "Sign in to see recommendations made for you."
                },
                signedOut = !signedIn,
            )
        }

        // Sections with nothing playable in them are dropped rather than rendered as empty headings.
        // The feed regularly carries a shelf this app cannot represent, and a title with a blank
        // strip under it reads as something having failed.
        val usable = page.sections.filter { section -> section.items.any { it.isSupported } }
        if (usable.isEmpty()) {
            return@withContext HomeState.Empty(
                message = if (chip != null) {
                    "Nothing here for ${chip.title} that this app can play."
                } else if (signedIn) {
                    "YouTube returned nothing for this account yet. Play a few things and it fills in."
                } else {
                    "Sign in to see recommendations made for you."
                },
                signedOut = !signedIn,
            )
        }
        HomeState.Ready(usable, page.chips.orEmpty(), page.continuation)
    }
}

/**
 * "Similar to ..." shelves, as the phone's Home builds them (HomeViewModel): three of the artists
 * played most get the related sections of their own artist pages, and two of the songs played most
 * lately get YouTube's related tab. Labelled "Similar to" with the artist or song as the title.
 *
 * Every request runs at once rather than in turn, and each catches its own failure, so one slow or
 * missing page leaves a gap rather than holding up or emptying the rest.
 */
suspend fun loadSimilar(library: LibraryStore): List<HomePage.Section> = withContext(Dispatchers.IO) {
    coroutineScope {
        val now = System.currentTimeMillis()
        val byArtist = async {
            runCatching {
                library.topArtists(0L, 10).map { it.artist }
                    .filter { it.id.startsWith("UC") }
                    .shuffled().take(3)
                    .map { artist ->
                        async {
                            val page = YouTube.artist(artist.id).getOrNull() ?: return@async null
                            val items = (page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty() +
                                page.sections.lastOrNull()?.items.orEmpty())
                                .filter { it.isSupported }
                                .distinctBy { it.id }
                                .shuffled()
                            if (items.isEmpty()) null else HomePage.Section(artist.name, "Similar to", null, null, items)
                        }
                    }.awaitAll().filterNotNull()
            }.getOrDefault(emptyList())
        }
        val bySong = async {
            runCatching {
                library.topSongs(now - 14L * 24 * 60 * 60 * 1000, 10).map { it.first }
                    .filterNot { LocalMusic.isLocal(it.id) }
                    .shuffled().take(2)
                    .map { song ->
                        async {
                            val endpoint = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                                ?: return@async null
                            val related = YouTube.related(endpoint).getOrNull() ?: return@async null
                            val items = (related.songs.shuffled().take(8) + related.albums.shuffled().take(4) +
                                related.artists.shuffled().take(4) + related.playlists.shuffled().take(4)).shuffled()
                            if (items.isEmpty()) null else HomePage.Section(song.title, "Similar to", song.thumbnail, null, items)
                        }
                    }.awaitAll().filterNotNull()
            }.getOrDefault(emptyList())
        }
        (byArtist.await() + bySong.await()).shuffled()
    }
}

/** The phone's "New releases" shelf, from Explore. Empty when YouTube does not answer. */
suspend fun loadNewReleases(): List<AlbumItem> = withContext(Dispatchers.IO) {
    YouTube.explore().getOrNull()?.newReleaseAlbums.orEmpty()
}

/**
 * The playlists this account has saved on YouTube.
 *
 * A different question from the home feed, and worth asking separately: the feed is what YouTube
 * suggests, this is what the person chose and expects to find again. It needs the session, so signed
 * out it is empty rather than an error - there is nothing wrong, there is just nobody to ask about.
 *
 * `completed()` follows the continuations, because a library is exactly the case where the first
 * page is not the answer - anyone with more than a couple of dozen would silently lose the rest.
 */
suspend fun loadSavedPlaylists(): List<PlaylistItem> = withContext(Dispatchers.IO) {
    YouTube.library(SAVED_PLAYLISTS_BROWSE_ID).completed().getOrNull()
        ?.items
        ?.filterIsInstance<PlaylistItem>()
        .orEmpty()
}

/** YouTube Music's own id for "playlists you saved", which is what the app asks for too. */
private const val SAVED_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists"

/**
 * Whether this app can do anything with an item.
 *
 * Everything the feed sends is shown in the Android app, which has pages for all of it. Here an
 * album or a playlist can be played but not browsed, an artist can be opened, and anything else -
 * a video, a podcast episode - has nowhere to go and nothing to play. Filtering those out is better
 * than drawing cards that do nothing when clicked.
 */
val YTItem.isSupported: Boolean
    get() = this is SongItem || this is AlbumItem || this is PlaylistItem || this is ArtistItem

/**
 * The id to queue for an item that plays as a list.
 *
 * Albums and playlists are both a playlist id underneath, which is what lets them be played without
 * an album page to open first - `YouTube.queue(playlistId = ...)` returns their songs directly.
 */
val YTItem.playlistIdOrNull: String?
    get() = when (this) {
        is AlbumItem -> playlistId
        is PlaylistItem -> id
        else -> null
    }

/**
 * A home card that plays a list of its own: a station, a mix, a song's radio. YouTube sends each
 * as a song - its first - with the list as the playlist to play it in. Played as a song, a station
 * gave one track and then went on to the next card's, since the rest of its row was queued behind
 * it. A song radio's list (RDAMVM + its id) counts too: it was left out at first as "just a song",
 * and that left the radio stations doing exactly that. The phone plays every such card this way.
 */
val YTItem.isStation: Boolean
    get() = this is SongItem && endpoint?.playlistId != null

/** [subtitle], with a song's artists as they are shown everywhere - see SongItem.shown. */
@androidx.compose.runtime.Composable
fun YTItem.shownSubtitle(): String = if (this is SongItem) shown().subtitle else subtitle

/** A line under the title: who it is by, or what it is. */
val YTItem.subtitle: String
    get() = when (this) {
        is SongItem -> artists.joinToString { it.name }
        is AlbumItem -> artists?.joinToString { it.name }.orEmpty()
        is PlaylistItem -> author?.name ?: songCountText.orEmpty()
        is ArtistItem -> "Artist"
    }
