/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.makeTimeString

/**
 * What can show under a song's title on its row: "Daft Punk • Random Access Memories • 5:37".
 *
 * Stored as one character each, in order, the way the player buttons are - see [decode]. The
 * characters are part of the stored format, and the desktop app uses the same ones, so they must
 * not change.
 */
enum class SongDetail(val code: Char, @StringRes val title: Int) {
    ARTISTS('A', R.string.song_detail_artists),
    ALBUM('L', R.string.song_detail_album),
    LENGTH('D', R.string.song_detail_length),
    ;

    companion object {
        /**
         * What the rows have always shown: the artists, then the length. Album is there to switch
         * on, so updating changes nothing on anyone's rows until they choose to.
         */
        const val DEFAULT = "AD"

        /** Stored for "none at all" - an empty string is "never chosen", which means [DEFAULT]. */
        const val NONE = "-"

        /** The stored string as details, in order. Unknown or repeated characters are skipped. */
        fun decode(stored: String): List<SongDetail> =
            stored.ifEmpty { DEFAULT }.mapNotNull { c -> entries.firstOrNull { it.code == c } }.distinct()

        fun encode(details: List<SongDetail>): String =
            details.distinct().joinToString("") { it.code.toString() }.ifEmpty { NONE }

        /**
         * The line itself: each chosen detail the song has, in the chosen order, joined by " • ".
         * A song with no album, or no known length, simply leaves that part out.
         */
        fun line(details: List<SongDetail>, artists: String?, album: String?, length: String?): String =
            joinByBullet(
                *details.map {
                    when (it) {
                        ARTISTS -> artists
                        ALBUM -> album?.takeIf { name -> name.isNotBlank() }
                        LENGTH -> length
                    }
                }.toTypedArray()
            )
    }
}

/** The chosen details, in order - provided once at the top of the app, from the setting. */
val LocalSongDetails = compositionLocalOf { SongDetail.decode(SongDetail.DEFAULT) }

/**
 * The line under a song's title, as Library and content > Song details has it. Every song row, card
 * and menu header builds its subtitle here, so the setting reaches all of them alike.
 *
 * [artists] is already the shown credit - shownArtists, or the library's own - so the repaired names
 * are what this joins. [showAlbum] is false where the album is the page itself: an album's own
 * track list would only repeat its title on every row.
 */
@Composable
fun songSubtitle(
    artists: String?,
    album: String?,
    durationSeconds: Int?,
    showAlbum: Boolean = true,
): String = SongDetail.line(
    details = LocalSongDetails.current.filter { showAlbum || it != SongDetail.ALBUM },
    artists = artists,
    album = album,
    // A length of -1 is "not known yet", as everywhere else; makeTimeString shows it as nothing.
    length = durationSeconds?.let { makeTimeString(it * 1000L) },
)
