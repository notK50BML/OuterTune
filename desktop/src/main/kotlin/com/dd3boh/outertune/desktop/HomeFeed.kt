/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.HomePage
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
    data class Ready(val sections: List<HomePage.Section>) : HomeState

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

    suspend fun load(signedIn: Boolean): HomeState = withContext(Dispatchers.IO) {
        val page = YouTube.home().getOrElse { error ->
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
                message = if (signedIn) {
                    "YouTube returned nothing for this account yet. Play a few things and it fills in."
                } else {
                    "Sign in to see recommendations made for you."
                },
                signedOut = !signedIn,
            )
        }
        HomeState.Ready(usable)
    }
}

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

/** A line under the title: who it is by, or what it is. */
val YTItem.subtitle: String
    get() = when (this) {
        is SongItem -> artists.joinToString { it.name }
        is AlbumItem -> artists?.joinToString { it.name }.orEmpty()
        is PlaylistItem -> author?.name ?: songCountText.orEmpty()
        is ArtistItem -> "Artist"
        else -> ""
    }
