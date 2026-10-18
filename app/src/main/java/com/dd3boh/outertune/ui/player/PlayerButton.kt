/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.annotation.StringRes
import com.dd3boh.outertune.R

/**
 * Everything that can sit beside the song title on the player.
 *
 * Each is one of the ⋮ menu's own actions (plus Like and the menu itself), so any of them can be
 * promoted to a one-tap button. Stored as one character each, in order, the same way the bottom
 * tabs are - see [decode]. The characters are part of the stored format and must not change.
 */
enum class PlayerButton(val code: Char, @StringRes val title: Int) {
    LIKE('L', R.string.action_like),
    MENU('M', R.string.player_button_menu),
    SLEEP('S', R.string.sleep_timer),
    EQUALIZER('E', R.string.equalizer),
    DOWNLOAD('D', R.string.action_download),
    LYRICS('Y', R.string.toggle_lyrics),
    RADIO('R', R.string.start_radio),
    QUEUE('Q', R.string.add_to_queue),
    PLAYLIST('P', R.string.add_to_playlist),
    LIBRARY('B', R.string.player_button_library),
    ARTIST('A', R.string.view_artist),
    ALBUM('U', R.string.view_album),
    SHARE('H', R.string.share),
    DETAILS('I', R.string.details),
    ADVANCED('T', R.string.advanced),
    ;

    companion object {
        /** The most that fit beside a title on a phone before the title is squeezed to nothing. */
        const val MAX = 5

        /** What Reset goes back to: the player's original pair. */
        const val DEFAULT = "LM"

        /** The stored string as buttons, in order. Unknown or repeated characters are skipped. */
        fun decode(stored: String): List<PlayerButton> =
            stored.mapNotNull { c -> CHOOSABLE.firstOrNull { it.code == c } }.distinct().take(MAX)

        /**
         * What can sit beside the menu. Not "add to queue": with the song already playing, queueing
         * it again from its own player makes no sense - it stays a PlayerActions action, for the menu.
         */
        val CHOOSABLE: List<PlayerButton> get() = entries.filter { it != QUEUE }

        fun encode(buttons: List<PlayerButton>): String = buttons.distinct().take(MAX).joinToString("") { it.code.toString() }

        /**
         * The arrangement for someone who has never opened the new setting, rebuilt from the two
         * switches it replaces - so updating changes nothing on anyone's player until they choose to.
         */
        fun fromLegacy(showSleepTimer: Boolean, showEqualizer: Boolean): List<PlayerButton> = buildList {
            if (showSleepTimer) add(SLEEP)
            add(LIKE)
            if (showEqualizer) add(EQUALIZER)
            add(MENU)
        }
    }
}
