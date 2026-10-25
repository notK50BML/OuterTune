/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.utils.HistoryTally
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

/**
 * One artist credit.
 *
 * [id] is a YouTube channel where there is one. Where there is not - a credit that arrived as a bare
 * name - a `local:` id stands in, so the row has a key without pretending it points anywhere. That
 * distinction is the whole of [linkable]: a name with nowhere to go should not be underlined as
 * though it had somewhere to go.
 */
data class StoredArtist(val id: String, val name: String) {
    val linkable: Boolean get() = !id.startsWith(LOCAL_PREFIX)

    companion object {
        const val LOCAL_PREFIX = "local:"

        /** A credit with no channel behind it, keyed by name so the same name is the same row. */
        fun unlinked(name: String) = StoredArtist(LOCAL_PREFIX + name.lowercase(), name)
    }
}

/** Lyrics as they were stored: the raw LRC (or plain text) and which provider gave it. */
data class CachedLyrics(val text: String, val source: String, val fetchedAt: Long = 0L)

/** One song as the desktop library stores it - enough to show it and to play it again. */
data class StoredSong(
    val id: String,
    val title: String,
    val artists: String,
    /** Optional so a library written before covers existed still loads. */
    val thumbnail: String = "",
    /**
     * The credits as separate entries, where they are known.
     *
     * Kept alongside the display string rather than replacing it: every song already stored has the
     * string and nothing else, and a library that showed "Unknown artist" for everything recorded
     * before this existed would be a poor trade for making names clickable.
     */
    val artistList: List<StoredArtist> = emptyList(),
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
    private val directory: File = defaultDataDirectory(),
    /**
     * Exposed because the signed-in session lives in the same file.
     *
     * One database rather than two: a second file would need its own lock, its own migrations and
     * its own decision about what happens when one exists and the other does not, all so that a
     * single string could live somewhere else.
     */
    val database: Database = Database(DesktopBackup.applyPendingRestore(directory)),
) {
    private val db = database

    /** Downloaded audio, beside the library rather than next to the jar - same reasoning as the database. */
    val downloads: Downloads by lazy { Downloads(File(directory, "downloads"), db) }

    /** Where the library file lives, so the settings screen can say where a backup would come from. */
    val databasePath: String get() = File(directory, "library.db").absolutePath

    /**
     * Settings, stored beside the library.
     *
     * Same file as everything else, so backing up a library backs up how it was set up too - a
     * separate preferences file would be one more thing to remember and one more thing to lose.
     */
    val settings: SettingsStore = object : SettingsStore {
        override fun get(key: String): String? = db.setting(key)

        // Saved on a background thread, in order. A setting is changed from a click on the UI
        // thread, and a database write there - however quick normally - is a click that waits on
        // whatever else has the database, which is how a switch could freeze the window.
        override fun put(key: String, value: String) {
            settingsWriter.execute { runCatching { db.putSetting(key, value) } }
        }
    }

    private val settingsWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "settings-writer").apply { isDaemon = true }
    }

    /** What has already been looked up for a song, or null if nothing has. */
    fun cachedLyrics(songId: String): CachedLyrics? = db.lyrics(songId)

    /**
     * Remembers a lookup, including one that found nothing.
     *
     * Storing the miss matters as much as storing the hit: songs without lyrics are common, and
     * without a record of the miss every play of an instrumental asks both providers again.
     */
    fun cacheLyrics(songId: String, text: String, source: String) = db.putLyrics(songId, text, source)

    fun clearLyrics(songId: String) = db.deleteLyrics(songId)


    val recentlyPlayed = MutableStateFlow<List<StoredSong>>(emptyList())
    val liked = MutableStateFlow<List<StoredSong>>(emptyList())
    val playlists = MutableStateFlow<List<StoredPlaylist>>(emptyList())

    init {
        directory.mkdirs()
        importLegacyFiles(directory)
        refreshAll()
    }

    fun recordPlay(song: StoredSong, durationMs: Long = 0) {
        db.recordPlay(song, System.currentTimeMillis(), durationMs)
        recentlyPlayed.value = db.recentlyPlayed(MAX_RECENT)
    }

    fun clearHistory() {
        db.clearHistory()
        recentlyPlayed.value = emptyList()
    }

    fun topSongs(sinceMs: Long, limit: Int = 50, remote: Map<String, HistoryTally.Plays> = emptyMap()) =
        db.topSongs(sinceMs, limit, remote)
    fun playCounts(sinceMs: Long) = db.playCounts(sinceMs)
    fun topArtists(sinceMs: Long, limit: Int = 30) = db.topArtists(sinceMs, limit)
    fun playTotals(sinceMs: Long) = db.playTotals(sinceMs)

    /**
     * When each song unliked this session had been liked. Liking it again restores that time, so an
     * accidental unlike undone puts the song back where it was in Liked songs instead of on top.
     */
    private val unlikedDates = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun toggleLiked(song: StoredSong) {
        val nowLiked = !isLiked(song.id)
        if (!nowLiked) db.likedAt(song.id)?.let { unlikedDates[song.id] = it }
        val at = if (nowLiked) unlikedDates.remove(song.id) ?: System.currentTimeMillis() else System.currentTimeMillis()
        db.setLiked(song, liked = nowLiked, atMs = at)
        liked.value = db.likedSongs()
    }

    fun isLiked(id: String): Boolean = liked.value.any { it.id == id }

    /** See Database.importLiked. Returns how many were newly liked. */
    fun importLiked(songs: List<StoredSong>): Int {
        val added = db.importLiked(songs, System.currentTimeMillis())
        liked.value = db.likedSongs()
        return added
    }

    fun libraryArtists() = db.libraryArtists()
    fun artistThumbnail(artistId: String) = db.artistThumbnail(artistId)
    fun setArtistThumbnail(artistId: String, url: String) = db.setArtistThumbnail(artistId, url)

    fun localTracks() = db.localTracks()
    fun localModifiedTimes() = db.localModifiedTimes()
    fun putLocalTrack(track: LocalTrack, modifiedAt: Long) = db.putLocalTrack(track, modifiedAt)
    fun putLocalTracks(batch: List<Pair<LocalTrack, Long>>) = db.transaction {
        batch.forEach { (track, modifiedAt) -> db.putLocalTrack(track, modifiedAt) }
    }
    fun removeLocalFilesNotIn(present: Set<String>) = db.removeLocalFilesNotIn(present)

    /** Takes one file out of the library's list of local files. The song row, plays and likes stay. */
    fun removeLocalFile(path: String) = db.removeLocalFile(path)

    /** Songs by id, in the order given, skipping any the library has never seen. */
    fun songs(ids: Collection<String>): List<StoredSong> = ids.mapNotNull { db.song(it) }

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

    /**
     * What the library already holds by an artist.
     *
     * Not a flow, for the same reason a playlist's songs are not: it is read when the artist's page
     * opens and not otherwise.
     */
    fun songsByArtist(artistId: String): List<StoredSong> = db.songsByArtist(artistId)

    fun reorderPlaylist(playlistId: String, songIdsInOrder: List<String>) {
        db.reorderPlaylist(playlistId, songIdsInOrder)
    }

    fun refreshAll() {
        recentlyPlayed.value = db.recentlyPlayed(MAX_RECENT)
        liked.value = db.likedSongs()
        playlists.value = db.playlists()
    }

    fun close() {
        // Let any setting still on its way to disk land before the file is closed under it.
        settingsWriter.shutdown()
        settingsWriter.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        db.close()
    }

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
