/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.db.normalizeArtistId
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.utils.ArtistNameRefresher

/**
 * Every known artist's name as the library has it, by channel id - the name the artist's own page
 * shows, kept current by ArtistNameRefresher and the artist page itself. Provided once at the top of
 * the app, so a song from YouTube can be shown with its artists under the same names as everywhere
 * else rather than as its own data happened to spell them ("(G)I-DLE" for "i-dle").
 */
val LocalArtistNames = compositionLocalOf<Map<String, String>> { emptyMap() }

/** One credited artist as shown: its channel id, when it has one, and its name. */
data class ShownArtist(val id: String?, val name: String) {
    /** Whether there is a page to open - the same rule as MediaMetadata.Artist.hasArtistPage. */
    val hasArtistPage: Boolean
        get() = id != null && (id.startsWith("UC") || id.startsWith("MPLAUC") ||
            id.startsWith("FEmusic_library_privately_owned_artist"))
}

/**
 * A song's artists as they should be shown wherever it appears: the library's credits when the
 * library has the song - repaired by ArtistCreditEnricher, so a combined "X & Y" is two artists and
 * a "(feat. Z)" is credited - and otherwise its own credits under each artist's library name.
 *
 * Every song row, card, queue entry and player reads this, so a song looks the same in a search
 * result, on the home screen, in the queue and in the player, and its artists carry the names their
 * pages open under. It used to be only the library's own lists and (sometimes) the player.
 */
@Composable
fun shownArtists(songId: String, credits: List<Pair<String?, String>>): List<ShownArtist> {
    val database = LocalDatabase.current
    val names = LocalArtistNames.current
    val stored by database.song(songId).collectAsState(initial = null)
    val library = stored?.artists.orEmpty()
    // The library's own: checked for a current name in the background, as its rows are.
    LaunchedEffect(songId, library.size) { if (library.isNotEmpty()) ArtistNameRefresher.enqueue(library) }
    if (library.isNotEmpty()) return library.map { ShownArtist(it.id, it.name) }
    return credits.map { (id, name) ->
        ShownArtist(id, id?.let { names[it.normalizeArtistId()] } ?: name.stripTopicSuffix())
    }
}

/** [shownArtists], joined for a subtitle. */
@Composable
fun shownArtistsText(songId: String, credits: List<Pair<String?, String>>): String =
    shownArtists(songId, credits).joinToString { it.name }
