/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.ArtistSection

/**
 * The songs an artist page lists as that artist's own.
 *
 * Not simply every song on the page. An artist page is a stack of shelves, and only some of them
 * are about the artist: alongside their songs and records sit carousels of other people's music -
 * "fans might also like", playlists the artist appears on, recommendations. A song in one of those
 * is on the page for a reason that has nothing to do with who performs it, and
 * [ArtistCreditEnricher.creditFromArtistPage] writes credits to the database, so the difference
 * between "on this page" and "by this artist" is the difference between a repair and a fabricated
 * credit that nothing will ever remove.
 *
 * The tell is the album column, and it is structural rather than a guess. A song shelf - the
 * "Songs" listing - is parsed from a `musicShelfRenderer`, whose rows carry an album; the
 * carousels are `musicCarouselShelfRenderer`, and `ArtistPage.fromMusicTwoRowItemRenderer` builds
 * every song it produces with `album = null`, unconditionally. So a section containing a song that
 * knows its album cannot be a carousel.
 *
 * Deliberately not narrowed any further than that. It would be possible to also check each row's
 * own credit runs and drop any that name somebody else, and it would be wrong: the case this
 * feature exists for is precisely a song whose stored credits are incomplete or point at the wrong
 * channel, and a guest artist is often absent from the row's credits on their own page. Tightening
 * there would refuse the repairs worth making. The shelf is the boundary that separates the
 * artist's catalogue from everyone else's; within it, being listed is the evidence.
 *
 * Section titles are what a person would reach for first and are not usable: they are localised,
 * so "Songs" is "Canciones" or "曲" depending on the account, and matching on them would quietly
 * do nothing for most of the world.
 */
fun ArtistPage.songShelfIds(): List<String> =
    sections
        .filter { it.isSongShelf() }
        .flatMap { section -> section.items.filterIsInstance<SongItem>() }
        .map { it.id }
        .distinct()

/** See [songShelfIds] for why the album column is the test. */
private fun ArtistSection.isSongShelf(): Boolean =
    items.any { it is SongItem && it.album != null }
