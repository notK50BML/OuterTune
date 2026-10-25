/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.utils.HistoryTally
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
            if (current < 6) {
                connection.createStatement().use { st ->
                    // An index over the files, not the files themselves. Audio lives on disk where
                    // the operating system can page it; a blob column would load a whole song into
                    // memory to answer "is this downloaded".
                    //
                    // Deliberately not a foreign key onto song: something can be downloaded before
                    // it has ever been played, and a cascade delete would throw away the audio when
                    // the library row was tidied.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS download (
                            songId       TEXT PRIMARY KEY NOT NULL,
                            sizeBytes    INTEGER NOT NULL,
                            downloadedAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }
            }
            if (current < 7) {
                connection.createStatement().use { st ->
                    // Every play, not just the latest. play_history keeps one row per song - right
                    // for "recently played", useless for "most played", which is what Stats needs.
                    // The phone keeps the same thing as its Event table.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS play_event (
                            id       INTEGER PRIMARY KEY AUTOINCREMENT,
                            songId   TEXT NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            playedAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_play_event_at ON play_event(playedAt)")
                    // Seeded from what history there is, so Stats is not empty on the first run after
                    // updating. Guarded so a re-run after a half-finished attempt adds nothing twice.
                    st.executeUpdate(
                        """
                        INSERT INTO play_event (songId, playedAt)
                        SELECT songId, playedAt FROM play_history
                        WHERE NOT EXISTS (SELECT 1 FROM play_event)
                        """.trimIndent()
                    )
                }
            }
            if (current < 8) {
                connection.createStatement().use { st ->
                    // Where each local song's file is. Kept beside the song rather than in it, so a
                    // local song is in every other respect an ordinary one - liked, in playlists,
                    // counted in Stats - and only the player and the scanner need to know the path.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS local_file (
                            songId     TEXT PRIMARY KEY NOT NULL REFERENCES song(id) ON DELETE CASCADE,
                            path       TEXT NOT NULL UNIQUE,
                            album      TEXT NOT NULL DEFAULT '',
                            durationMs INTEGER NOT NULL DEFAULT 0,
                            modifiedAt INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                }
            }
            if (current < 9) {
                connection.createStatement().use { st ->
                    // An artist's picture, fetched once and kept - see ArtistImages.
                    val has = st.executeQuery("PRAGMA table_info(artist)").use { rs ->
                        generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "thumbnail" }
                    }
                    if (!has) st.executeUpdate("ALTER TABLE artist ADD COLUMN thumbnail TEXT")
                }
            }
            if (current < 10) {
                connection.createStatement().use { st ->
                    // What each artist name turned out to be on YouTube Music - see ArtistLinks.
                    // channelId NULL is an answer too: searched, and no such artist.
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS artist_link (
                            nameKey     TEXT PRIMARY KEY NOT NULL,
                            name        TEXT NOT NULL,
                            channelId   TEXT,
                            channelName TEXT,
                            thumbnail   TEXT,
                            checkedAt   INTEGER NOT NULL DEFAULT 0,
                            manual      INTEGER NOT NULL DEFAULT 0
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

    /** The size recorded for a downloaded song, or null if it is not recorded as downloaded. */
    fun download(songId: String): Long? = synchronized(lock) {
        connection.prepareStatement("SELECT sizeBytes FROM download WHERE songId = ?").use { st ->
            st.setString(1, songId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    fun putDownload(songId: String, sizeBytes: Long) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO download (songId, sizeBytes, downloadedAt) VALUES (?, ?, ?)
            ON CONFLICT(songId) DO UPDATE SET
                sizeBytes = excluded.sizeBytes, downloadedAt = excluded.downloadedAt
            """.trimIndent()
        ).use { st ->
            st.setString(1, songId)
            st.setLong(2, sizeBytes)
            st.setLong(3, System.currentTimeMillis())
            st.executeUpdate()
        }
    }

    fun deleteDownload(songId: String) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM download WHERE songId = ?").use { st ->
            st.setString(1, songId)
            st.executeUpdate()
        }
    }

    /** Newest first, which is the order a downloads list wants to be read in. */
    fun downloadIds(): List<String> = synchronized(lock) {
        connection.prepareStatement("SELECT songId FROM download ORDER BY downloadedAt DESC").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
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
        connection.prepareStatement("SELECT text, source, fetchedAt FROM lyrics WHERE songId = ?").use { st ->
            st.setString(1, songId)
            st.executeQuery().use { rs ->
                if (rs.next()) CachedLyrics(rs.getString(1), rs.getString(2), rs.getLong(3)) else null
            }
        }
    }

    fun putLyrics(songId: String, text: String, source: String) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO lyrics (songId, text, source, fetchedAt)
            SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM song WHERE id = ?)
            ON CONFLICT(songId) DO UPDATE SET
                text = excluded.text, source = excluded.source, fetchedAt = excluded.fetchedAt
            """.trimIndent()
        ).use { st ->
            st.setString(1, songId)
            st.setString(2, text)
            st.setString(3, source)
            st.setLong(4, System.currentTimeMillis())
            // Only for songs the library has a row for. Lyrics are fetched as a song starts, which
            // for a search result or a radio pick is before anything has written its row - and the
            // foreign key then failed the insert. Unknown songs are simply looked up again next
            // time; once played, liked or saved they are cached like any other.
            st.setString(5, songId)
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
        relinkLocked(song.id)
    }

    fun recordPlay(song: StoredSong, atMs: Long, durationMs: Long = 0) = synchronized(lock) {
        // Through upsertSong when the length is known, so Stats can total listening time.
        if (durationMs > 0) upsertSong(song, durationMs) else upsertSongLocked(song)
        connection.prepareStatement(
            "INSERT INTO play_history (songId, playedAt) VALUES (?, ?) " +
                "ON CONFLICT(songId) DO UPDATE SET playedAt = excluded.playedAt"
        ).use { st ->
            st.setString(1, song.id)
            st.setLong(2, atMs)
            st.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO play_event (songId, playedAt) VALUES (?, ?)").use { st ->
            st.setString(1, song.id)
            st.setLong(2, atMs)
            st.executeUpdate()
        }
    }

    /**
     * Adds plays recorded elsewhere - a phone backup's history - as (song, when) pairs.
     *
     * Safe to repeat: a play already here at the same instant is not added twice, so importing the
     * same backup again leaves the counts alone. Recently played moves to the latest of either side.
     */
    fun importPlays(plays: List<Pair<String, Long>>): Int = transaction {
        var added = 0
        connection.prepareStatement(
            """
            INSERT INTO play_event (songId, playedAt)
            SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM play_event WHERE songId = ? AND playedAt = ?)
            """.trimIndent()
        ).use { st ->
            plays.forEach { (songId, at) ->
                st.setString(1, songId)
                st.setLong(2, at)
                st.setString(3, songId)
                st.setLong(4, at)
                added += st.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO play_history (songId, playedAt) VALUES (?, ?)
            ON CONFLICT(songId) DO UPDATE SET playedAt = MAX(playedAt, excluded.playedAt)
            """.trimIndent()
        ).use { st ->
            plays.groupBy({ it.first }, { it.second }).forEach { (songId, times) ->
                st.setString(1, songId)
                st.setLong(2, times.max())
                st.executeUpdate()
            }
        }
        added
    }

    fun artistThumbnail(artistId: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT thumbnail FROM artist WHERE id = ?").use { st ->
            st.setString(1, artistId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun setArtistThumbnail(artistId: String, url: String) = synchronized(lock) {
        connection.prepareStatement("UPDATE artist SET thumbnail = ? WHERE id = ?").use { st ->
            st.setString(1, url)
            st.setString(2, artistId)
            st.executeUpdate()
        }
    }

    // ---- artist links ---------------------------------------------------------------------------

    fun artistLink(nameKey: String): ArtistLink? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT nameKey, name, channelId, channelName, thumbnail, checkedAt, manual FROM artist_link WHERE nameKey = ?"
        ).use { st ->
            st.setString(1, nameKey)
            st.executeQuery().use { rs -> if (rs.next()) rs.toArtistLink() else null }
        }
    }

    private fun java.sql.ResultSet.toArtistLink() = ArtistLink(
        nameKey = getString(1), name = getString(2), channelId = getString(3), channelName = getString(4),
        thumbnail = getString(5), checkedAt = getLong(6), manual = getInt(7) != 0,
    )

    /** The whole link table, held in memory - it is read on every song write. */
    private var linkCache: Map<String, ArtistLink>? = null

    private fun allLinksLocked(): Map<String, ArtistLink> = linkCache ?: loadLinksLocked().also { linkCache = it }

    private fun loadLinksLocked(): Map<String, ArtistLink> =
        connection.createStatement().use { st ->
            st.executeQuery("SELECT nameKey, name, channelId, channelName, thumbnail, checkedAt, manual FROM artist_link")
                .use { rs -> buildMap { while (rs.next()) rs.toArtistLink().let { put(it.nameKey, it) } } }
        }

    fun putArtistLink(link: ArtistLink) = synchronized(lock) {
        connection.prepareStatement(
            """
            INSERT INTO artist_link (nameKey, name, channelId, channelName, thumbnail, checkedAt, manual)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(nameKey) DO UPDATE SET name = excluded.name, channelId = excluded.channelId,
                channelName = excluded.channelName, thumbnail = excluded.thumbnail,
                checkedAt = excluded.checkedAt, manual = excluded.manual
            """.trimIndent()
        ).use { st ->
            st.setString(1, link.nameKey)
            st.setString(2, link.name)
            st.setString(3, link.channelId)
            st.setString(4, link.channelName)
            st.setString(5, link.thumbnail)
            st.setLong(6, link.checkedAt)
            st.setInt(7, if (link.manual) 1 else 0)
            st.executeUpdate()
        }
        linkCache = null
    }

    /** Every link pointing at [channelId] - the names that were tied to it, for undoing that. */
    fun linksToChannel(channelId: String): List<ArtistLink> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT nameKey, name, channelId, channelName, thumbnail, checkedAt, manual FROM artist_link WHERE channelId = ?"
        ).use { st ->
            st.setString(1, channelId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toArtistLink()) } }
        }
    }

    /** Ids and titles of songs whose credits [ArtistLinks] might change: local ones, or any with "feat." text. */
    fun songsForLinking(onlyLocal: Boolean): List<Pair<String, String>> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                if (onlyLocal) "SELECT s.id, s.title FROM song s JOIN local_file f ON f.songId = s.id"
                else "SELECT id, title FROM song"
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
    }

    /** Songs credited to [artistId], by id only. */
    fun songIdsCreditedTo(artistId: String): List<String> = synchronized(lock) {
        connection.prepareStatement("SELECT songId FROM song_artist WHERE artistId = ?").use { st ->
            st.setString(1, artistId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun songTitle(songId: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT title FROM song WHERE id = ?").use { st ->
            st.setString(1, songId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * Puts local songs credited to [channelId] back under their own artist name - undoing a link.
     * Only local songs: a streamed song credited to the channel was credited by YouTube itself.
     */
    fun restoreLocalCredits(channelId: String, names: List<String>) = transaction {
        val name = names.firstOrNull() ?: return@transaction
        val ids = connection.prepareStatement(
            "SELECT sa.songId FROM song_artist sa JOIN local_file f ON f.songId = sa.songId WHERE sa.artistId = ?"
        ).use { st ->
            st.setString(1, channelId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        val credits = creditsForLocked(ids)
        val placeholder = StoredArtist.unlinked(name)
        for (id in ids) {
            val after = credits[id].orEmpty().map { if (it.id == channelId) placeholder else it }.distinctBy { it.id }
            writeCreditsLocked(StoredSong(id = id, title = "", artists = "", artistList = after))
            connection.prepareStatement("UPDATE song SET artists = ? WHERE id = ?").use { st ->
                st.setString(1, after.joinToString(", ") { it.name })
                st.setString(2, id)
                st.executeUpdate()
            }
        }
    }

    /** The stored credits of one song, in order. */
    fun songCredits(songId: String): List<StoredArtist> = synchronized(lock) {
        creditsForLocked(listOf(songId))[songId].orEmpty()
    }

    /**
     * Rewrites the credits of [songIds] from what [ArtistLinks] already knows - no network. Run
     * after every credit write too, so a rescan or a re-sync cannot put back what was linked.
     */
    fun applyArtistLinks(songIds: Collection<String>) = transaction {
        if (songIds.isEmpty()) return@transaction
        val links = allLinksLocked()
        if (links.isEmpty()) return@transaction
        val titles = HashMap<String, String>()
        songIds.chunked(500).forEach { chunk ->
            connection.prepareStatement(
                "SELECT id, title FROM song WHERE id IN (${chunk.joinToString(",") { "?" }})"
            ).use { st ->
                chunk.forEachIndexed { i, id -> st.setString(i + 1, id) }
                st.executeQuery().use { rs -> while (rs.next()) titles[rs.getString(1)] = rs.getString(2) }
            }
        }
        val credits = HashMap<String, List<StoredArtist>>()
        songIds.chunked(500).forEach { credits.putAll(creditsForLocked(it)) }
        for (id in songIds) {
            val before = credits[id].orEmpty()
            val after = ArtistLinks.rewrite(before, titles[id].orEmpty(), links)
            if (after == before) continue
            writeCreditsLocked(StoredSong(id = id, title = titles[id].orEmpty(), artists = "", artistList = after))
            connection.prepareStatement("UPDATE song SET artists = ? WHERE id = ?").use { st ->
                st.setString(1, after.joinToString(", ") { it.name })
                st.setString(2, id)
                st.executeUpdate()
            }
            after.forEach { a ->
                val thumb = links.values.firstOrNull { it.channelId == a.id }?.thumbnail ?: return@forEach
                connection.prepareStatement("UPDATE artist SET thumbnail = COALESCE(thumbnail, ?) WHERE id = ?").use { st ->
                    st.setString(1, thumb)
                    st.setString(2, a.id)
                    st.executeUpdate()
                }
            }
        }
    }

    // ---- local files --------------------------------------------------------------------------

    fun putLocalTrack(track: LocalTrack, modifiedAt: Long) = transaction {
        upsertSong(track.song, track.durationMs)
        connection.prepareStatement(
            """
            INSERT INTO local_file (songId, path, album, durationMs, modifiedAt) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(songId) DO UPDATE SET path = excluded.path, album = excluded.album,
                durationMs = excluded.durationMs, modifiedAt = excluded.modifiedAt
            """.trimIndent()
        ).use { st ->
            st.setString(1, track.song.id)
            st.setString(2, track.file.absolutePath)
            st.setString(3, track.album)
            st.setLong(4, track.durationMs)
            st.setLong(5, modifiedAt)
            st.executeUpdate()
        }
    }

    fun localTracks(): List<LocalTrack> = synchronized(lock) {
        val rows = connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT s.id, s.title, s.artists, s.thumbnail, f.path, f.album, f.durationMs
                FROM local_file f JOIN song s ON s.id = f.songId
                ORDER BY s.title COLLATE NOCASE
                """.trimIndent()
            ).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Triple(
                                StoredSong(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                                rs.getString(5),
                                rs.getString(6) to rs.getLong(7),
                            )
                        )
                    }
                }
            }
        }
        val credits = creditsForLocked(rows.map { it.first.id })
        rows.map { (song, path, rest) ->
            LocalTrack(song.copy(artistList = credits[song.id].orEmpty()), java.io.File(path), rest.first, rest.second)
        }
    }

    fun localModifiedTimes(): Map<String, Long> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT path, modifiedAt FROM local_file").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
            }
        }
    }

    /** Drops the file records for paths no longer found; returns how many. The songs stay. */
    fun removeLocalFilesNotIn(present: Set<String>): Int = transaction {
        val gone = connection.createStatement().use { st ->
            st.executeQuery("SELECT path FROM local_file").use { rs ->
                buildList { while (rs.next()) rs.getString(1).takeIf { it !in present }?.let(::add) }
            }
        }
        connection.prepareStatement("DELETE FROM local_file WHERE path = ?").use { st ->
            gone.forEach { st.setString(1, it); st.executeUpdate() }
        }
        gone.size
    }

    fun removeLocalFile(path: String) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM local_file WHERE path = ?").use { st ->
            st.setString(1, path)
            st.executeUpdate()
        }
    }

    /** A clean, compact copy of the whole library at [target] - SQLite's own VACUUM INTO. */
    fun exportTo(target: java.io.File) = synchronized(lock) {
        target.delete()
        connection.createStatement().use { it.execute("VACUUM INTO '${target.absolutePath.replace("'", "''")}'") }
    }

    /** Forgets every play: the recent list and the counts Stats is made of. Songs themselves stay. */
    fun clearHistory() = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeUpdate("DELETE FROM play_history")
            st.executeUpdate("DELETE FROM play_event")
        }
    }

    /**
     * Most played songs since [sinceMs], with how many times each - the same song under several ids
     * counted once (see [mergeSameSongs]).
     */
    fun topSongs(
        sinceMs: Long,
        limit: Int,
        /** Plays from the account's history for the same period - see HistoryTally. */
        remote: Map<String, HistoryTally.Plays> = emptyMap(),
    ): List<Pair<StoredSong, Int>> = synchronized(lock) {
        // Every song played, not the first [limit]: two ids of one song can each sit below the cut
        // and only make it once added together.
        val rows = connection.prepareStatement(
            """
            SELECT s.id, s.title, s.artists, s.thumbnail, COUNT(*) AS plays, MAX(e.playedAt)
            FROM play_event e JOIN song s ON s.id = e.songId
            WHERE e.playedAt >= ?
            GROUP BY s.id
            """.trimIndent()
        ).use { st ->
            st.setLong(1, sinceMs)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PlayedSong(
                                StoredSong(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                                plays = rs.getInt(5),
                                lastPlayedAt = rs.getLong(6),
                            )
                        )
                    }
                }
            }
        }
        val top = mergeSameSongs(withHistoryPlays(rows, remote)).take(limit)
        val credits = creditsForLocked(top.map { it.song.id })
        top.map { it.song.copy(artistList = credits[it.song.id].orEmpty()) to it.plays }
    }

    /** Most played artists since [sinceMs], by plays of songs they are credited on. */
    fun topArtists(sinceMs: Long, limit: Int): List<Pair<StoredArtist, Int>> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT a.id, a.name, COUNT(*) AS plays
            FROM play_event e JOIN song_artist sa ON sa.songId = e.songId JOIN artist a ON a.id = sa.artistId
            WHERE e.playedAt >= ?
            GROUP BY a.id ORDER BY plays DESC LIMIT ?
            """.trimIndent()
        ).use { st ->
            st.setLong(1, sinceMs)
            st.setInt(2, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(StoredArtist(rs.getString(1), rs.getString(2)) to rs.getInt(3)) }
            }
        }
    }

    /** Plays, distinct songs, and listening time (from the songs' lengths) since [sinceMs]. */
    /** How many times each song was played here since [sinceMs]. */
    fun playCounts(sinceMs: Long): Map<String, Int> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT songId, COUNT(*) FROM play_event WHERE playedAt >= ? GROUP BY songId"
        ).use { st ->
            st.setLong(1, sinceMs)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
        }
    }

    fun playTotals(sinceMs: Long): PlayTotals = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT COUNT(*), COUNT(DISTINCT lower(trim(s.title)) || char(31) || lower(trim(s.artists))),
                COALESCE(SUM(s.durationMs), 0)
            FROM play_event e JOIN song s ON s.id = e.songId WHERE e.playedAt >= ?
            """.trimIndent()
        ).use { st ->
            st.setLong(1, sinceMs)
            st.executeQuery().use { rs -> PlayTotals(rs.getInt(1), rs.getInt(2), rs.getLong(3)) }
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

    /** When [songId] was liked, or null when it is not. */
    fun likedAt(songId: String): Long? = synchronized(lock) {
        connection.prepareStatement("SELECT likedAt FROM liked WHERE songId = ?").use { st ->
            st.setString(1, songId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
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

    /** One song with its credits, or null if it was never recorded. */
    fun song(id: String): StoredSong? = synchronized(lock) {
        val song = connection.prepareStatement(
            "SELECT id, title, artists, thumbnail FROM song WHERE id = ?"
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows -> rows.toSongs().firstOrNull() }
        } ?: return@synchronized null
        // Inside the lock, like every other read here: the credits belong to the row just read, and
        // fetching them after releasing it would let a concurrent write separate the two.
        song.copy(artistList = creditsForLocked(listOf(song.id))[song.id].orEmpty())
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

    /**
     * Marks [songs] liked, newest first, keeping YouTube's order: the first is stamped [nowMs], each
     * after it a second earlier, so sorting by when it was liked reproduces the remote list. Songs
     * already liked here keep their own date - an import adds, it does not reshuffle.
     */
    /**
     * The account's liked songs, newest first, as YouTube Music lists them. Each is dated by its place
     * in that list - songs already liked here included - so Liked songs reads in the account's order.
     *
     * It used to date only songs new to this library and leave the rest alone, which mixed three
     * clocks: likes made here, likes brought in by each sync, and whatever the first import stamped
     * - and the list followed none of them. The caller leaves out songs whose own like or unlike is
     * still on its way to YouTube (LikePushes), so those keep the date given here. Returns how many
     * were newly liked.
     */
    fun importLiked(songs: List<StoredSong>, nowMs: Long): Int = transaction {
        var added = 0
        songs.forEachIndexed { index, song ->
            upsertSongLocked(song)
            val known = connection.prepareStatement("SELECT 1 FROM liked WHERE songId = ?").use { st ->
                st.setString(1, song.id)
                st.executeQuery().use { it.next() }
            }
            connection.prepareStatement(
                "INSERT INTO liked (songId, likedAt) VALUES (?, ?) ON CONFLICT(songId) DO UPDATE SET likedAt = excluded.likedAt"
            ).use { st ->
                st.setString(1, song.id)
                st.setLong(2, nowMs - index * 1000L)
                st.executeUpdate()
            }
            if (!known) added++
        }
        added
    }

    /** Artists credited on liked or played songs, with how many of those songs each has. */
    fun libraryArtists(): List<Pair<StoredArtist, Int>> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT a.id, a.name, COUNT(DISTINCT sa.songId) AS songs
                FROM song_artist sa JOIN artist a ON a.id = sa.artistId
                WHERE sa.songId IN (
                    SELECT songId FROM liked UNION SELECT songId FROM play_history UNION SELECT songId FROM local_file
                )
                GROUP BY a.id ORDER BY songs DESC, a.name COLLATE NOCASE
                """.trimIndent()
            ).use { rs ->
                buildList { while (rs.next()) add(StoredArtist(rs.getString(1), rs.getString(2)) to rs.getInt(3)) }
            }
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

    /** A just-written song's credits, put through what is already known about its artists. */
    private fun relinkLocked(songId: String) {
        if (allLinksLocked().isNotEmpty()) applyArtistLinks(listOf(songId))
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

    /**
     * Songs in the library credited to an artist, most recently played first.
     *
     * In the library means kept: liked, in a playlist, downloaded, or a file on this computer. The
     * song table also holds everything ever played, so without this an artist's "In your library"
     * listed the whole history of them - songs heard once on a radio are not in anyone's library.
     */
    fun songsByArtist(artistId: String): List<StoredSong> = synchronized(lock) {
        connection.prepareStatement(
            """
            SELECT s.id, s.title, s.artists, s.thumbnail
            FROM song_artist sa
                JOIN song s ON s.id = sa.songId
                LEFT JOIN play_history h ON h.songId = s.id
            WHERE sa.artistId = ?
              AND (EXISTS (SELECT 1 FROM liked l WHERE l.songId = s.id)
                OR EXISTS (SELECT 1 FROM playlist_song p WHERE p.songId = s.id)
                OR EXISTS (SELECT 1 FROM download d WHERE d.songId = s.id)
                OR EXISTS (SELECT 1 FROM local_file f WHERE f.songId = s.id))
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
        relinkLocked(song.id)
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
        const val SCHEMA_VERSION = 10

        fun defaultFile(): File = File(defaultDataDirectory(), "library.db")
    }
}

/** A playlist as the list screen needs it: enough to show a row without reading its songs. */
data class StoredPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
)

/** What [Database.playTotals] reports. Listening time counts each play as the song's full length. */
data class PlayTotals(val plays: Int, val songs: Int, val listenedMs: Long)

/** One song's plays in a period, before songs that are the same are merged. */
internal data class PlayedSong(val song: StoredSong, val plays: Int, val lastPlayedAt: Long)

/**
 * The same song under different ids, counted as one: the same title by the same artists - a single
 * and its album copy, a re-upload, a local file of a song also played from YouTube. Compared without
 * case or surrounding spaces, and otherwise exactly, so two different songs that merely share a
 * title stay apart. Plays are added together, and the id played most stands for the group, so its
 * cover and the page it opens are the version actually listened to. Ordered by plays, then by the
 * most recent play.
 */
internal fun mergeSameSongs(rows: List<PlayedSong>): List<PlayedSong> =
    rows.groupBy { it.song.title.trim().lowercase() to it.song.artists.trim().lowercase() }
        .values
        .map { same ->
            val main = same.maxWith(compareBy<PlayedSong> { it.plays }.thenBy { it.lastPlayedAt })
            PlayedSong(main.song, plays = same.sumOf { it.plays }, lastPlayedAt = same.maxOf { it.lastPlayedAt })
        }
        .sortedWith(compareByDescending<PlayedSong> { it.plays }.thenByDescending { it.lastPlayedAt })

/**
 * [rows] with what the account's history adds: a song played here gains the history's plays beyond
 * its own (the larger of the two, never the sum - see HistoryTally.extraPlays), and one played only
 * elsewhere joins the chart from the history's own metadata, in memory only.
 */
internal fun withHistoryPlays(rows: List<PlayedSong>, remote: Map<String, HistoryTally.Plays>): List<PlayedSong> {
    if (remote.isEmpty()) return rows
    val known = rows.mapTo(HashSet()) { it.song.id }
    val raised = rows.map { row ->
        val history = remote[row.song.id] ?: return@map row
        row.copy(plays = row.plays + HistoryTally.extraPlays(row.plays, history.plays))
    }
    val elsewhere = remote.values.filter { it.song.id !in known }.map { PlayedSong(it.song.toStored(), it.plays, 0L) }
    return raised + elsewhere
}

/** [totals] with the plays and listening time the history adds over this computer's own. */
internal fun PlayTotals.withHistory(remote: Map<String, HistoryTally.Plays>, localPlays: Map<String, Int>): PlayTotals {
    if (remote.isEmpty()) return this
    var plays = 0
    var songs = 0
    var ms = 0L
    remote.values.forEach { history ->
        val local = localPlays[history.song.id] ?: 0
        val extra = HistoryTally.extraPlays(local, history.plays)
        plays += extra
        if (local == 0 && extra > 0) songs++
        ms += extra * (history.song.duration ?: 0) * 1000L
    }
    return PlayTotals(this.plays + plays, this.songs + songs, listenedMs + ms)
}
