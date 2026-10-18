/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What can sit beside the song title on the player - the desktop's half of the phone's
 * PlayerButton, with the same stored codes so the two read the same.
 *
 * The phone's full set, with the same codes, so an arrangement moves between the two in a settings
 * profile. Tempo and pitch opens the equaliser, which is where the desktop keeps those knobs.
 */
enum class PlayerButtonKind(val code: Char, val label: String) {
    LIKE('L', "Like"),
    MENU('M', "More options"),
    SLEEP('S', "Sleep timer"),
    EQUALIZER('E', "Equaliser"),
    DOWNLOAD('D', "Download"),
    LYRICS('Y', "Lyrics"),
    PLAYLIST('P', "Add to playlist"),
    ARTIST('A', "View artist"),
    ALBUM('U', "View album"),
    SHARE('H', "Copy link"),
    DETAILS('I', "Details"),
    RADIO('R', "Start radio"),
    LIBRARY('B', "Add to library"),
    ADVANCED('T', "Tempo and pitch"),
    ;

    val icon: ImageVector
        get() = when (this) {
            LIKE -> OuterTuneIcons.favoriteBorder
            MENU -> OuterTuneIcons.moreVert
            SLEEP -> OuterTuneIcons.bedtime
            EQUALIZER -> OuterTuneIcons.equalizer
            DOWNLOAD -> OuterTuneIcons.download
            LYRICS -> OuterTuneIcons.lyrics
            PLAYLIST -> OuterTuneIcons.playlistAdd
            ARTIST -> OuterTuneIcons.person
            ALBUM -> OuterTuneIcons.album
            SHARE -> OuterTuneIcons.share
            DETAILS -> OuterTuneIcons.info
            RADIO -> OuterTuneIcons.radio
            LIBRARY -> OuterTuneIcons.libraryAdd
            ADVANCED -> OuterTuneIcons.tune
        }

    companion object {
        const val MAX = 5

        /** Reset: the player's original pair. */
        const val DEFAULT = "LM"

        /** What the desktop player showed before this was a setting, so updating changes nothing. */
        const val LEGACY = "SLDEM"

        fun decode(stored: String): List<PlayerButtonKind> =
            stored.ifEmpty { LEGACY }.mapNotNull { c -> entries.firstOrNull { it.code == c } }.distinct().take(MAX)

        fun encode(buttons: List<PlayerButtonKind>): String =
            buttons.distinct().take(MAX).joinToString("") { it.code.toString() }
    }
}
