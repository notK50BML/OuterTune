/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** One song as the desktop library stores it - enough to show it and to play it again. */
data class StoredSong(
    val id: String,
    val title: String,
    val artists: String,
)

/**
 * The desktop library: what has been played, and what has been liked.
 *
 * Backed by a small text file on purpose, and behind an interface narrow enough to replace.
 *
 * Choosing the real storage engine - Room's KMP support, SQLDelight, something else - is a decision
 * with consequences for schema migrations, threading and how much of the Android app's data layer
 * can eventually be shared. It is not a decision worth making as a side effect of wanting a
 * recently-played list, and it is not one that has to be made before anything else can be built. So
 * this stores what is needed now in the simplest thing that persists, and keeps the surface small
 * enough that swapping the backing store touches this file alone.
 *
 * What that costs, stated plainly rather than discovered later: no queries, no indices, no
 * migrations, and the whole library is held in memory and rewritten on every change. That is fine
 * for hundreds of songs and wrong for thousands, which is the point at which the real engine has to
 * be chosen rather than a moment sooner.
 */
class LibraryStore(directory: File = defaultDirectory()) {

    private val recentFile = File(directory, "recently-played.tsv")
    private val likedFile = File(directory, "liked.tsv")

    val recentlyPlayed = MutableStateFlow<List<StoredSong>>(emptyList())
    val liked = MutableStateFlow<List<StoredSong>>(emptyList())

    init {
        directory.mkdirs()
        recentlyPlayed.value = read(recentFile)
        liked.value = read(likedFile)
    }

    /**
     * Records a play, most recent first, without duplicating a song already in the list.
     *
     * Re-playing something moves it to the top rather than adding a second entry: a list of the last
     * fifty plays is far less useful than a list of the last fifty *songs* when one track has been
     * on repeat.
     */
    fun recordPlay(song: StoredSong) {
        val updated = (listOf(song) + recentlyPlayed.value.filterNot { it.id == song.id }).take(MAX_RECENT)
        recentlyPlayed.value = updated
        write(recentFile, updated)
    }

    fun toggleLiked(song: StoredSong) {
        val current = liked.value
        val updated = if (current.any { it.id == song.id }) {
            current.filterNot { it.id == song.id }
        } else {
            listOf(song) + current
        }
        liked.value = updated
        write(likedFile, updated)
    }

    fun isLiked(id: String): Boolean = liked.value.any { it.id == id }

    /**
     * Tab-separated, one song per line.
     *
     * Tabs rather than commas because titles and artist names contain commas constantly and tabs
     * essentially never; any that do appear are stripped on write, so a stray tab cannot shift every
     * later field on the line. A malformed line is skipped rather than aborting the load - a library
     * that half-loads is better than one that refuses to open because of a single bad row.
     */
    private fun read(file: File): List<StoredSong> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3 || parts[0].isBlank()) null
                else StoredSong(parts[0], parts[1], parts[2])
            }
        }.getOrDefault(emptyList())
    }

    private fun write(file: File, songs: List<StoredSong>) {
        runCatching {
            file.writeText(
                songs.joinToString("\n") { song ->
                    listOf(song.id, song.title, song.artists).joinToString("\t") { it.replace('\t', ' ') }
                }
            )
        }
    }

    private companion object {
        const val MAX_RECENT = 50

        /**
         * Beside the user's other application data rather than next to the jar, so the library
         * survives the app being moved or reinstalled.
         */
        fun defaultDirectory(): File {
            val base = System.getenv("LOCALAPPDATA")
                ?: System.getenv("XDG_DATA_HOME")
                ?: System.getProperty("user.home")
            return File(base, "OuterTune")
        }
    }
}
