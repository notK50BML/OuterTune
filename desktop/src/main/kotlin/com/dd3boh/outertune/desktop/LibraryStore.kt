/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

/** One song as the desktop library stores it - enough to show it and to play it again. */
data class StoredSong(
    val id: String,
    val title: String,
    val artists: String,
    /** Optional so a library written before covers existed still loads. */
    val thumbnail: String = "",
)

/**
 * Beside the user's other application data rather than next to the jar, so the library survives the
 * app being moved or reinstalled.
 */
fun defaultDataDirectory(): File {
    val base = System.getenv("LOCALAPPDATA")
        ?: System.getenv("XDG_DATA_HOME")
        ?: System.getProperty("user.home")
    return File(base, "OuterTune")
}

/**
 * The desktop library: what has been played, what has been liked, and playlists.
 *
 * Backed by SQLite now rather than by a pair of text files. The interface is unchanged, which was
 * the point of keeping it narrow in the first place - the file store said the real engine had to be
 * chosen at the point where "the whole library is held in memory and rewritten on every change"
 * stopped being acceptable, and playlists are that point: a reorder rewriting every playlist to disk
 * is not a design anyone would choose on purpose.
 *
 * The flows stay. Reads go to the database and the results are published through them, so the UI
 * observes exactly as before, and nothing above this file knows the storage changed. What is no
 * longer true is that everything lives in memory: the flows are a cache of the last read, not the
 * library itself.
 *
 * Any existing text-file library is imported once on first run - see [importLegacyFiles]. Throwing
 * away someone's liked songs because the storage improved would be a poor trade.
 */
class LibraryStore(
    directory: File = defaultDataDirectory(),
    private val db: Database = Database(File(directory, "library.db")),
) {

    val recentlyPlayed = MutableStateFlow<List<StoredSong>>(emptyList())
    val liked = MutableStateFlow<List<StoredSong>>(emptyList())
    val playlists = MutableStateFlow<List<StoredPlaylist>>(emptyList())

    init {
        directory.mkdirs()
        importLegacyFiles(directory)
        refreshAll()
    }

    fun recordPlay(song: StoredSong) {
        db.recordPlay(song, System.currentTimeMillis())
        recentlyPlayed.value = db.recentlyPlayed(MAX_RECENT)
    }

    fun toggleLiked(song: StoredSong) {
        db.setLiked(song, liked = !isLiked(song.id), atMs = System.currentTimeMillis())
        liked.value = db.likedSongs()
    }

    fun isLiked(id: String): Boolean = liked.value.any { it.id == id }

    // ---- playlists -------------------------------------------------------------------------

    fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        db.createPlaylist(id, name, System.currentTimeMillis())
        playlists.value = db.playlists()
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        db.renamePlaylist(id, name)
        playlists.value = db.playlists()
    }

    fun deletePlaylist(id: String) {
        db.deletePlaylist(id)
        playlists.value = db.playlists()
    }

    fun addToPlaylist(playlistId: String, song: StoredSong) {
        db.addToPlaylist(playlistId, song)
        playlists.value = db.playlists()
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        db.removeFromPlaylist(playlistId, songId)
        playlists.value = db.playlists()
    }

    /**
     * Not a flow.
     *
     * A playlist's contents are read when its screen opens and not otherwise, so a flow per playlist
     * would be state to keep in step for no benefit. The list of playlists is a flow because it is
     * on screen while it changes.
     */
    fun playlistSongs(playlistId: String): List<StoredSong> = db.playlistSongs(playlistId)

    fun reorderPlaylist(playlistId: String, songIdsInOrder: List<String>) {
        db.reorderPlaylist(playlistId, songIdsInOrder)
    }

    fun refreshAll() {
        recentlyPlayed.value = db.recentlyPlayed(MAX_RECENT)
        liked.value = db.likedSongs()
        playlists.value = db.playlists()
    }

    fun close() = db.close()

    /**
     * Moves a text-file library into the database, once.
     *
     * The files are renamed rather than deleted, so a failed import can be looked at, and so this
     * cannot run twice and duplicate anything. Timestamps are synthesised from list order, which is
     * all the old format recorded - it kept most-recent-first and nothing else.
     */
    private fun importLegacyFiles(directory: File) {
        val recent = File(directory, "recently-played.tsv")
        val likedFile = File(directory, "liked.tsv")
        if (!recent.exists() && !likedFile.exists()) return

        val now = System.currentTimeMillis()
        db.transaction {
            readLegacy(recent).forEachIndexed { index, song ->
                // Descending, so the first line - the most recently played - keeps that position.
                db.recordPlay(song, now - index)
            }
            readLegacy(likedFile).forEachIndexed { index, song ->
                db.setLiked(song, liked = true, atMs = now - index)
            }
        }
        runCatching { recent.renameTo(File(directory, "recently-played.tsv.imported")) }
        runCatching { likedFile.renameTo(File(directory, "liked.tsv.imported")) }
    }

    private fun readLegacy(file: File): List<StoredSong> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                StoredSong(parts[0], parts[1], parts[2], parts.getOrElse(3) { "" })
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_RECENT = 50
    }
}
