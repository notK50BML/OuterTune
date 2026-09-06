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
            if (current < 2) {
                connection.createStatement().use { st ->
                    // Small, opaque values that are neither library content nor user settings -
                    // today the signed-in session, tomorrow whatever else has to survive a restart
                    // without earning a table of its own.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS credential (
                            key   TEXT PRIMARY KEY NOT NULL,
                            value TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
            if (current < 3) {
                connection.createStatement().use { st ->
                    // Artists as rows rather than a joined string on the song. The string is still
                    // there and still shown - see StoredSong - but a name cannot be clicked until
                    // something knows which channel it belongs to.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS artist (
                            id   TEXT PRIMARY KEY NOT NULL,
                            name TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS song_artist (
                            songId   TEXT NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            artistId TEXT NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
                            position INTEGER NOT NULL,
                            PRIMARY KEY (songId, artistId)
                        )
                        """.trimIndent()
                    )
                    // Read one song at a time, in credit order - "who is on this track" is the only
                    // question this table is ever asked from the player.
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_song_artist ON song_artist(songId, position)"
                    )
                    // And the reverse, for an artist's own page.
                    st.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_song_artist_by_artist ON song_artist(artistId)"
                    )
                }
            }
            if (current < 4) {
                connection.createStatement().use { st ->
                    // Cached because a song is played more than once and its words do not change.
                    // Without this, opening the lyrics pane is two network round trips every time,
                    // and the pane sits blank for a second on a song already looked up.
                    //
                    // A miss is cached too, as an empty text with source 'none'. Songs without
                    // lyrics are common, and re-asking both providers on every play of an
                    // instrumental is the most wasteful case there is.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS lyrics (
                            songId    TEXT PRIMARY KEY NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            text      TEXT NOT NULL,
                            source    TEXT NOT NULL,
                            fetchedAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
            if (current < 5) {
                connection.createStatement().use { st ->
                    // A key/value table rather than a column per setting. Settings are read one at a
                    // time by name and never queried against each other, and a column per setting
                    // would mean a migration for every new checkbox.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS setting (
                            key   TEXT PRIMARY KEY NOT NULL,
                            value TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
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

    /** One stored setting, or null if it has never been written. */
    fun setting(key: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT value FROM setting WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun putSetting(key: String, value: String) = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO setting (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { st ->
            st.setString(1, key)
            st.setString(2, value)
            st.executeUpdate()
        }
    }

    /** What is stored for a song's lyrics, if anything has been looked up. */
    fun lyrics(songId: String): CachedLyrics? = synchronized(lock) {
        connection.prepareStatement("SELECT text, source FROM lyrics WHERE songId = ?").use { st ->
            st.setString(1, songId)
            st.executeQuery().use { rs ->
                if (rs.next()) CachedLyrics(rs.getString(1), rs.getString(2)) else null
            }
        }
    }

    fun putLyrics(songId: String, text: String, source: String) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO lyrics (songId, text, source, fetchedAt)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(songId) DO UPDATE SET
                text = excluded.text, source = excluded.source, fetchedAt = excluded.fetchedAt
            """.trimIndent()
        ).use { st ->
            st.setString(1, songId)
            st.setString(2, text)
            st.setString(3, source)
            st.setLong(4, System.currentTimeMillis())
            st.executeUpdate()
        }
    }

    fun deleteLyrics(songId: String) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM lyrics WHERE songId = ?").use { st ->
            st.setString(1, songId)
            st.executeUpdate()
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
        writeCreditsLocked(song)
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

    // ---- credentials -----------------------------------------------------------------------

    /**
     * Stores a small opaque value, or removes it when [value] is null.
     *
     * Deliberately not encrypted, and deliberately said out loud rather than implied. Encrypting it
     * needs a key, the key would have to live beside the thing it protects, and a lock whose key is
     * taped to the door is decoration - it would only make the storage look safer than it is.
     * Anything able to read this file can already read everything else in the user's profile.
     */
    fun putCredential(key: String, value: String?) = synchronized(lock) {
        if (value == null) {
            connection.prepareStatement("DELETE FROM credential WHERE key = ?").use { st ->
                st.setString(1, key)
                st.executeUpdate()
            }
        } else {
            connection.prepareStatement(
                "INSERT INTO credential (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
            ).use { st ->
                st.setString(1, key)
                st.setString(2, value)
                st.executeUpdate()
            }
        }
        Unit
    }

    fun credential(key: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT value FROM credential WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    fun close() = synchronized(lock) { connection.close() }

    // ---- internals -------------------------------------------------------------------------

    /** [upsertSong]'s body, for callers already holding the lock - it is not reentrant-safe to nest. */
    /**
     * Writes a song's credits, when it has any.
     *
     * Skipped entirely for a song with none rather than clearing what is there. A song arriving from
     * a sparse source carries no structured artists, and treating that as "this song has no artists"
     * would erase credits a richer source had already supplied - the same rule the thumbnail follows
     * a few lines up, and for the same reason.
     */
    private fun writeCreditsLocked(song: StoredSong) {
        if (song.artistList.isEmpty()) return

        connection.prepareStatement(
            "INSERT INTO artist (id, name) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET name = excluded.name"
        ).use { st ->
            song.artistList.forEach { artist ->
                st.setString(1, artist.id)
                st.setString(2, artist.name)
                st.addBatch()
            }
            st.executeBatch()
        }
        // Replaced rather than merged: the credits that came with the song are the credits, and
        // leaving an old one behind would show somebody who is no longer on the track.
        connection.prepareStatement("DELETE FROM song_artist WHERE songId = ?").use { st ->
            st.setString(1, song.id)
            st.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO song_artist (songId, artistId, position) VALUES (?, ?, ?)"
        ).use { st ->
            song.artistList.forEachIndexed { index, artist ->
                st.setString(1, song.id)
                st.setString(2, artist.id)
                st.setInt(3, index)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    /** The credits for a set of songs, in one query rather than one per song. */
    private fun creditsForLocked(songIds: List<String>): Map<String, List<StoredArtist>> {
        if (songIds.isEmpty()) return emptyMap()
        val placeholders = songIds.joinToString(",") { "?" }
        val out = HashMap<String, MutableList<StoredArtist>>()
        connection.prepareStatement(
            """
            SELECT sa.songId, a.id, a.name
            FROM song_artist sa JOIN artist a ON a.id = sa.artistId
            WHERE sa.songId IN ($placeholders)
            ORDER BY sa.songId, sa.position
            """.trimIndent()
        ).use { st ->
            songIds.forEachIndexed { index, id -> st.setString(index + 1, id) }
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    out.getOrPut(rs.getString(1)) { mutableListOf() }
                        .add(StoredArtist(rs.getString(2), rs.getString(3)))
                }
            }
        }
        return out
    }

    /** Songs in the library credited to an artist, most recently played first. */
    fun songsByArtist(artistId: String): List<StoredSong> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT s.id, s.title, s.artists, s.thumbnail
            FROM song_artist sa
                JOIN song s ON s.id = sa.songId
                LEFT JOIN play_history h ON h.songId = s.id
            WHERE sa.artistId = ?
            ORDER BY COALESCE(h.playedAt, 0) DESC
            """.trimIndent()
        ).use { st ->
            st.setString(1, artistId)
            st.executeQuery().use { it.toSongs() }
        }
    }

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
        writeCreditsLocked(song)
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

    /**
     * Rows to songs, with their credits attached.
     *
     * The credits are fetched in one further query for the whole page rather than one per song. A
     * shelf of fifty songs would otherwise be fifty-one round trips to satisfy a list nobody has
     * scrolled yet.
     */
    private fun ResultSet.toSongs(): List<StoredSong> {
        val songs = buildList {
            while (next()) {
                add(StoredSong(getString(1), getString(2), getString(3), getString(4)))
            }
        }
        if (songs.isEmpty()) return songs
        val credits = creditsForLocked(songs.map { it.id })
        return songs.map { song -> song.copy(artistList = credits[song.id].orEmpty()) }
    }

    companion object {
        /** Bumped whenever [migrate] gains a step. */
        const val SCHEMA_VERSION = 5

        fun defaultFile(): File = File(defaultDataDirectory(), "library.db")
    }
}

/** A playlist as the list screen needs it: enough to show a row without reading its songs. */
data class StoredPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
)
