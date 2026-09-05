/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The desktop library's storage.
 *
 * SQLite through the JDBC driver, with the SQL written out rather than generated. No Gradle plugin
 * and no code generator, for the same reason this module takes Compose as artifacts rather than as
 * a plugin: both pin versions that drift against the Kotlin the project builds with, and neither
 * earns that for a schema this size.
 *
 * **Everything goes through one connection, guarded by one lock.** SQLite permits several
 * connections, but then a write in one is invisible to a read already in flight in another, and the
 * failure mode is a lock timeout under exactly the conditions that are hardest to reproduce - two
 * things happening at once. One connection makes the ordering obvious and costs nothing at this
 * size; several would be an optimisation for a problem this does not have.
 *
 * The lock is held for the duration of each call, so callers must not do slow work inside a
 * [transaction] block.
 */
class Database(file: File) {

    private val lock = Any()
    private val connection: Connection

    /** Depth of nested [transaction] calls, so an inner one does not commit an outer one's work. */
    private var transactionDepth = 0

    init {
        file.parentFile?.mkdirs()
        // The driver is loaded explicitly. Service loading works from a normal classpath and does
        // not always survive being merged into a fat jar, which is how this is actually shipped -
        // and the failure is at the first query, far from the cause.
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        synchronized(lock) {
            connection.createStatement().use { st ->
                // Write-ahead logging: a reader no longer blocks a writer, which matters because the
                // UI reads on the main thread while playback records history from another.
                st.execute("PRAGMA journal_mode=WAL")
                // Off by default in SQLite, and the reason a "deleted" playlist can leave its songs
                // behind as rows nothing points at.
                st.execute("PRAGMA foreign_keys=ON")
            }
            migrate()
        }
    }

    /**
     * Brings the schema up to date.
     *
     * Versioned with SQLite's own `user_version` rather than a table of migrations, because it is
     * already there, it is atomic with the transaction that sets it, and it cannot itself need a
     * migration.
     *
     * Each step is written to be safe to re-run. A migration that fails halfway leaves the version
     * unchanged, so it will be attempted again on the next start - and the second attempt must not
     * fail merely because the first one got partway.
     */
    private fun migrate() {
        val current = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { it.getInt(1) }
        }
        if (current >= SCHEMA_VERSION) return

        connection.autoCommit = false
        try {
            if (current < 1) {
                connection.createStatement().use { st ->
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS song (
                            id        TEXT PRIMARY KEY NOT NULL,
                            title     TEXT NOT NULL,
                            artists   TEXT NOT NULL,
                            thumbnail TEXT NOT NULL DEFAULT '',
                            durationMs INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS play_history (
                            songId   TEXT PRIMARY KEY NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            playedAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    // Indexed because every read of this table is "most recent first", and without
                    // it that is a full scan and a sort on every visit to the home screen.
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_play_history_at ON play_history(playedAt DESC)")
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS liked (
                            songId  TEXT PRIMARY KEY NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            likedAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS playlist (
                            id        TEXT PRIMARY KEY NOT NULL,
                            name      TEXT NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS playlist_song (
                            playlistId TEXT NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
                            songId     TEXT NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            position   INTEGER NOT NULL,
                            PRIMARY KEY (playlistId, songId)
                        )
                        """.trimIndent()
                    )
                    // A playlist is read in order, always, and always for one playlist at a time.
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playlist_song ON playlist_song(playlistId, position)")
                }
            }
            connection.createStatement().use { it.executeUpdate("PRAGMA user_version=$SCHEMA_VERSION") }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Runs [block] as one unit, rolling back if it throws.
     *
     * Matters most for a playlist reorder, which is a delete and a series of inserts: interrupted
     * halfway without this, the playlist is left with some songs at their old positions and some at
     * their new ones, which is worse than either.
     */
    fun <T> transaction(block: () -> T): T = synchronized(lock) {
        // Nesting joins the outer transaction rather than starting one. The lock is reentrant, so
        // nesting is easy to do by accident - and without this the inner block would commit the
        // outer one's work on its way out, which is the opposite of what a transaction is for.
        if (transactionDepth > 0) {
            transactionDepth++
            try {
                return@synchronized block()
            } finally {
                transactionDepth--
            }
        }
        transactionDepth = 1
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            transactionDepth = 0
            connection.autoCommit = true
        }
    }

    /** Records a song, or updates what is known about one already recorded. */
    fun upsertSong(song: StoredSong, durationMs: Long = 0) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO song (id, title, artists, thumbnail, durationMs)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                artists = excluded.artists,
                -- Only overwritten when the new value has something in it. A song re-encountered
                -- from a sparse source - a queue entry, a search result - would otherwise erase a
                -- cover that a richer source had already supplied.
                thumbnail = CASE WHEN excluded.thumbnail <> '' THEN excluded.thumbnail ELSE song.thumbnail END,
                durationMs = CASE WHEN excluded.durationMs > 0 THEN excluded.durationMs ELSE song.durationMs END
            """.trimIndent()
        ).use { st ->
            st.setString(1, song.id)
            st.setString(2, song.title)
            st.setString(3, song.artists)
            st.setString(4, song.thumbnail)
            st.setLong(5, durationMs)
            st.executeUpdate()
        }
    }

    fun recordPlay(song: StoredSong, atMs: Long) = synchronized(lock) {
        upsertSongLocked(song)
        connection.prepareStatement(
            "INSERT INTO play_history (songId, playedAt) VALUES (?, ?) " +
                "ON CONFLICT(songId) DO UPDATE SET playedAt = excluded.playedAt"
        ).use { st ->
            st.setString(1, song.id)
            st.setLong(2, atMs)
            st.executeUpdate()
        }
    }

    fun recentlyPlayed(limit: Int): List<StoredSong> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT s.id, s.title, s.artists, s.thumbnail
            FROM play_history h JOIN song s ON s.id = h.songId
            ORDER BY h.playedAt DESC LIMIT ?
            """.trimIndent()
        ).use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { it.toSongs() }
        }
    }

    fun setLiked(song: StoredSong, liked: Boolean, atMs: Long) = synchronized(lock) {
        upsertSongLocked(song)
        if (liked) {
            connection.prepareStatement(
                "INSERT INTO liked (songId, likedAt) VALUES (?, ?) ON CONFLICT(songId) DO NOTHING"
            ).use { st ->
                st.setString(1, song.id)
                st.setLong(2, atMs)
                st.executeUpdate()
            }
        } else {
            connection.prepareStatement("DELETE FROM liked WHERE songId = ?").use { st ->
                st.setString(1, song.id)
                st.executeUpdate()
            }
        }
    }

    fun likedSongs(): List<StoredSong> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT s.id, s.title, s.artists, s.thumbnail
                FROM liked l JOIN song s ON s.id = l.songId
                ORDER BY l.likedAt DESC
                """.trimIndent()
            ).use { it.toSongs() }
        }
    }

    // ---- playlists -------------------------------------------------------------------------

    fun createPlaylist(id: String, name: String, atMs: Long) = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO playlist (id, name, createdAt) VALUES (?, ?, ?)"
        ).use { st ->
            st.setString(1, id)
            st.setString(2, name)
            st.setLong(3, atMs)
            st.executeUpdate()
        }
    }

    fun renamePlaylist(id: String, name: String) = synchronized(lock) {
        connection.prepareStatement("UPDATE playlist SET name = ? WHERE id = ?").use { st ->
            st.setString(1, name)
            st.setString(2, id)
            st.executeUpdate()
        }
    }

    /** Removes a playlist. Its songs stay in the library; only the membership goes. */
    fun deletePlaylist(id: String) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM playlist WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeUpdate()
        }
    }

    fun playlists(): List<StoredPlaylist> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT p.id, p.name, COUNT(ps.songId) AS songCount
                FROM playlist p LEFT JOIN playlist_song ps ON ps.playlistId = p.id
                GROUP BY p.id ORDER BY p.createdAt DESC
                """.trimIndent()
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(StoredPlaylist(rs.getString(1), rs.getString(2), rs.getInt(3)))
                    }
                }
            }
        }
    }

    /**
     * Appends a song, or leaves it where it is if the playlist already has it.
     *
     * Adding a duplicate would be the other reasonable choice, and is not what anybody means by
     * "add to playlist" when the song is visibly already in it.
     */
    fun addToPlaylist(playlistId: String, song: StoredSong) = synchronized(lock) {
        upsertSongLocked(song)
        connection.prepareStatement(
            """
            INSERT INTO playlist_song (playlistId, songId, position)
            VALUES (?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_song WHERE playlistId = ?))
            ON CONFLICT(playlistId, songId) DO NOTHING
            """.trimIndent()
        ).use { st ->
            st.setString(1, playlistId)
            st.setString(2, song.id)
            st.setString(3, playlistId)
            st.executeUpdate()
        }
    }

    fun removeFromPlaylist(playlistId: String, songId: String) = synchronized(lock) {
        connection.prepareStatement(
            "DELETE FROM playlist_song WHERE playlistId = ? AND songId = ?"
        ).use { st ->
            st.setString(1, playlistId)
            st.setString(2, songId)
            st.executeUpdate()
        }
        // Renumbered so positions stay contiguous. Leaving a hole works for reading in order, but
        // any later insert computed from MAX(position) then leaves a growing gap, and a reorder
        // written against stale indices puts songs somewhere nobody asked for.
        compactPositionsLocked(playlistId)
    }

    fun playlistSongs(playlistId: String): List<StoredSong> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT s.id, s.title, s.artists, s.thumbnail
            FROM playlist_song ps JOIN song s ON s.id = ps.songId
            WHERE ps.playlistId = ? ORDER BY ps.position
            """.trimIndent()
        ).use { st ->
            st.setString(1, playlistId)
            st.executeQuery().use { it.toSongs() }
        }
    }

    /**
     * Rewrites the whole order of a playlist.
     *
     * As one transaction, and as a delete followed by inserts rather than a series of updates,
     * because the position column is part of no unique constraint but the songs are - moving one
     * song at a time would collide with whatever currently holds its destination.
     */
    fun reorderPlaylist(playlistId: String, songIdsInOrder: List<String>) = transaction {
        connection.prepareStatement("DELETE FROM playlist_song WHERE playlistId = ?").use { st ->
            st.setString(1, playlistId)
            st.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO playlist_song (playlistId, songId, position) VALUES (?, ?, ?)"
        ).use { st ->
            songIdsInOrder.forEachIndexed { index, songId ->
                st.setString(1, playlistId)
                st.setString(2, songId)
                st.setInt(3, index)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    fun close() = synchronized(lock) { connection.close() }

    // ---- internals -------------------------------------------------------------------------

    /** [upsertSong]'s body, for callers already holding the lock - it is not reentrant-safe to nest. */
    private fun upsertSongLocked(song: StoredSong) {
        connection.prepareStatement(
            """
            INSERT INTO song (id, title, artists, thumbnail)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                artists = excluded.artists,
                thumbnail = CASE WHEN excluded.thumbnail <> '' THEN excluded.thumbnail ELSE song.thumbnail END
            """.trimIndent()
        ).use { st ->
            st.setString(1, song.id)
            st.setString(2, song.title)
            st.setString(3, song.artists)
            st.setString(4, song.thumbnail)
            st.executeUpdate()
        }
    }

    private fun compactPositionsLocked(playlistId: String) {
        val ids = connection.prepareStatement(
            "SELECT songId FROM playlist_song WHERE playlistId = ? ORDER BY position"
        ).use { st ->
            st.setString(1, playlistId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        connection.prepareStatement(
            "UPDATE playlist_song SET position = ? WHERE playlistId = ? AND songId = ?"
        ).use { st ->
            ids.forEachIndexed { index, songId ->
                st.setInt(1, index)
                st.setString(2, playlistId)
                st.setString(3, songId)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun ResultSet.toSongs(): List<StoredSong> = buildList {
        while (next()) {
            add(StoredSong(getString(1), getString(2), getString(3), getString(4)))
        }
    }

    companion object {
        /** Bumped whenever [migrate] gains a step. */
        const val SCHEMA_VERSION = 1

        fun defaultFile(): File = File(defaultDataDirectory(), "library.db")
    }
}

/** A playlist as the list screen needs it: enough to show a row without reading its songs. */
data class StoredPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
)
