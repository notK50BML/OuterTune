/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.dd3boh.outertune.R

@Immutable
sealed class Screens(
    @StringRes val titleId: Int,
    val icon: ImageVector,
    val route: String,
) {
    data object Home : Screens(R.string.home, Icons.Rounded.Home, "home")
    data object Songs : Screens(R.string.songs, Icons.Rounded.MusicNote, "songs")
    data object Folders : Screens(R.string.folders, Icons.Rounded.Folder, "folders")
    data object LocalFiles : Screens(R.string.local_files, Icons.Rounded.SdCard, "local_files")
    data object Artists : Screens(R.string.artists, Icons.Rounded.Person, "artists")
    data object Albums : Screens(R.string.albums, Icons.Rounded.Album, "albums")
    data object Playlists : Screens(R.string.playlists, Icons.AutoMirrored.Rounded.QueueMusic, "playlists")
    data object Library : Screens(R.string.library, Icons.Rounded.LibraryMusic, "library")
    data object Player : Screens(R.string.player, Icons.Rounded.PlayCircle, "player")

    /**
     * Search as a tab of its own. With it on, the search bar at the top of the other tabs goes, and
     * Stats / Settings / Account sit at the end of Home's chips row instead.
     */
    data object Search : Screens(R.string.search, Icons.Rounded.Search, "search_tab")

    enum class LibraryFilter {
        ALL, ALBUMS, ARTISTS, PLAYLISTS, SONGS, FOLDERS
    }

    companion object {
        /*
        Screens
         */

        /**
         * H: Home
         * S: Songs
         * F: Folders
         * O: Local Files
         * A: Artists
         * B: Albums
         * L: Playlists
         * M: Library
         * P: Player
         * E: Search
         *
         * Not/won't implement
         * Q: Queue
         */
        val screenPairs = listOf(
            Home to 'H',
            Songs to 'S',
            Folders to 'F',
            LocalFiles to 'O',
            Artists to 'A',
            Albums to 'B',
            Playlists to 'L',
            Library to 'M',
            Search to 'E',
//            Player to 'P',
        )

        /** Whether the Search tab is switched on in an enabled-tabs string. */
        fun hasSearchTab(enabledTabs: String) = 'E' in enabledTabs

        fun getAllScreens() = screenPairs.map { it.first }

        fun getScreens(screens: String): List<Screens> {
            val charToScreenMap = screenPairs.associate { (screen, char) -> char to screen }

            return screens.toCharArray().map { char -> charToScreenMap[char] ?: Home }
        }

        fun encodeScreens(list: List<Screens>): String {
            val charToScreenMap = screenPairs.associate { (screen, char) -> char to screen }

            return list.distinct().joinToString("") { screen ->
                charToScreenMap.entries.first { it.value == screen }.key.toString()
            }
        }

        /*
        Filters
         */

        /**
         * A: Albums
         * R: Artists
         * P: Playlists
         * S: Songs
         * F: Folders
         * L: All
         */
        val filterPairs = listOf(
            LibraryFilter.ALBUMS to 'A',
            LibraryFilter.ARTISTS to 'R',
            LibraryFilter.PLAYLISTS to 'P',
            LibraryFilter.SONGS to 'S',
            LibraryFilter.FOLDERS to 'F',
            LibraryFilter.ALL to 'L'
        )

        fun getAllFilters() = filterPairs.map { it.first }

        fun getFilters(filters: String): List<LibraryFilter> {
            val charToFilterMap = filterPairs.associate { (screen, char) -> char to screen }

            return filters.toCharArray().map { char -> charToFilterMap[char] ?: LibraryFilter.ALL }
        }

        fun encodeFilters(list: List<LibraryFilter>): String {
            val charToFilterMap = filterPairs.associate { (screen, char) -> char to screen }

            return list.distinct().joinToString("") { filter ->
                charToFilterMap.entries.first { it.value == filter }.key.toString()
            }
        }
    }
}
