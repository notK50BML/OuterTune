/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipFile

/** What an import did, so the screen can say something specific rather than "done". */
data class ImportSummary(
    val songs: Int = 0,
    val liked: Int = 0,
    val playlists: Int = 0,
    val playlistSongs: Int = 0,
    val skippedLocal: Int = 0,
    val error: String? = null,
) {
    val failed: Boolean get() = error != null

    fun describe(): String = when {
        error != null -> error
        songs == 0 && playlists == 0 -> "Nothing to import - the backup held no songs or playlists."
        else -> buildString {
            append("Imported $songs ${if (songs == 1) "song" else "songs"}")
            if (liked > 0) append(", $liked liked")
            if (playlists > 0) {
                append(", $playlists ${if (playlists == 1) "playlist" else "playlists"}")
                append(" ($playlistSongs ${if (playlistSongs == 1) "entry" else "entries"})")
            }
            if (skippedLocal > 0) append(". Skipped $skippedLocal local ${if (skippedLocal == 1) "file" else "files"}")
            append(".")
        }
    }
}

/**
 * Reads a backup written by the phone into this library.
 *
 * The backup is a zip holding two things: the settings DataStore as a protobuf, and a checkpointed
 * copy of the app's Room database. Only the database is read. The settings are deliberately ignored
 * - they describe a different application with different options, and applying a phone's player
 * layout or audio preferences to this would be worse than not trying.
 *
 * **Additive, and safe to run twice.** Songs are keyed by their YouTube id, so re-importing the same
 * backup updates rather than duplicates; playlists are matched by name for the same reason. The
 * alternative - replacing the local library - would make an import that was meant to merge two
 * devices into one that quietly destroys whichever was imported into.
 *
 * Local files are counted and skipped. The phone can play a file on its own storage and this cannot,
 * so importing those rows would fill the library with entries that fail when clicked. Saying how
 * many were left behind is more honest than silently dropping them.
 */
object BackupImport {

    suspend fun importFrom(backup: File, library: LibraryStore): ImportSummary =
        withContext(Dispatchers.IO) {
            val extracted = runCatching { extractDatabase(backup) }.getOrElse {
                return@withContext ImportSummary(error = "Could not read the backup: ${it.message}")
            } ?: return@withContext ImportSummary(
                error = "That zip does not contain an OuterTune database.",
            )

            try {
                openReadOnly(extracted).use { connection -> read(connection, library) }
            } catch (e: Exception) {
                ImportSummary(error = "Could not read the backup's database: ${e.message}")
            } finally {
                extracted.delete()
            }
        }

    /**
     * Pulls the Room database out of the zip into a file of its own.
     *
     * Extracted rather than read in place because SQLite needs a real file - it seeks, and it wants
     * to manage its own journal. Matched by name suffix rather than exact name so a backup written
     * by a build that renamed the file still opens.
     */
    private fun extractDatabase(backup: File): File? {
        ZipFile(backup).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith(".db")
            } ?: return null
            val out = File.createTempFile("outertune-import", ".db")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            return out
        }
    }

    private fun openReadOnly(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also {
            it.createStatement().use { statement -> statement.execute("PRAGMA query_only=true") }
        }

    private fun read(connection: Connection, library: LibraryStore): ImportSummary {
        val credits = readCredits(connection)

        var songs = 0
        var liked = 0
        var skippedLocal = 0
        val imported = HashMap<String, StoredSong>()

        connection.prepareStatement(
            "SELECT id, title, thumbnailUrl, liked, isLocal FROM song"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    if (rows.getInt("isLocal") != 0) {
                        skippedLocal++
                        continue
                    }
                    val id = rows.getString("id") ?: continue
                    val artists = credits[id].orEmpty()
                    val song = StoredSong(
                        id = id,
                        title = rows.getString("title") ?: continue,
                        artists = artists.joinToString { it.name },
                        thumbnail = rows.getString("thumbnailUrl").orEmpty(),
                        artistList = artists,
                    )
                    library.database.upsertSong(song)
                    imported[id] = song
                    songs++

                    // States the flag rather than toggling it. toggleLiked would turn every liked
                    // song *off* again on a second import - the worst possible outcome for a
                    // "just to be safe" re-run - and asking isLiked first would work but leaves the
                    // toggle sitting there for someone to use later without the guard.
                    if (rows.getInt("liked") != 0) {
                        if (!library.isLiked(id)) liked++
                        library.database.setLiked(song, liked = true, atMs = System.currentTimeMillis())
                    }
                }
            }
        }

        val (playlists, playlistSongs) = readPlaylists(connection, library, imported)
        library.refreshAll()
        return ImportSummary(
            songs = songs,
            liked = liked,
            playlists = playlists,
            playlistSongs = playlistSongs,
            skippedLocal = skippedLocal,
        )
    }

    /**
     * Every song's credits, read in one pass.
     *
     * One query rather than one per song: a library of a few thousand would otherwise make a few
     * thousand round trips, and this is already the slowest part of an import.
     */
    private fun readCredits(connection: Connection): Map<String, List<StoredArtist>> {
        val credits = HashMap<String, MutableList<StoredArtist>>()
        runCatching {
            connection.prepareStatement(
                """
                SELECT m.songId AS songId, a.id AS artistId, a.name AS name
                FROM song_artist_map m JOIN artist a ON a.id = m.artistId
                ORDER BY m.songId, m.position
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val songId = rows.getString("songId") ?: continue
                        val name = rows.getString("name") ?: continue
                        val artistId = rows.getString("artistId")
                        credits.getOrPut(songId) { mutableListOf() } += when {
                            // The phone gives local-only artists ids of its own. Those mean nothing
                            // here, so they become unlinked credits keyed by name - which is what
                            // this library already does for a credit with no channel behind it.
                            artistId == null || artistId.startsWith("LA") -> StoredArtist.unlinked(name)
                            else -> StoredArtist(artistId, name)
                        }
                    }
                }
            }
        }
        return credits
    }

    private fun readPlaylists(
        connection: Connection,
        library: LibraryStore,
        imported: Map<String, StoredSong>,
    ): Pair<Int, Int> {
        var added = 0
        var entries = 0

        val existing = library.playlists.value.associateBy { it.name }
        val toImport = mutableListOf<Pair<String, String>>()
        runCatching {
            connection.prepareStatement("SELECT id, name FROM playlist").use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val id = rows.getString("id") ?: continue
                        val name = rows.getString("name")?.takeIf { it.isNotBlank() } ?: continue
                        toImport += id to name
                    }
                }
            }
        }

        toImport.forEach { (remoteId, name) ->
            // Matched by name, so importing the same backup twice fills one playlist rather than
            // making a second with the same title.
            val localId = existing[name]?.id ?: library.createPlaylist(name).also { added++ }
            val alreadyIn = library.playlistSongs(localId).map { it.id }.toSet()

            connection.prepareStatement(
                "SELECT songId FROM playlist_song_map WHERE playlistId = ? ORDER BY position"
            ).use { statement ->
                statement.setString(1, remoteId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val songId = rows.getString("songId") ?: continue
                        if (songId in alreadyIn) continue
                        // Only songs that made it in. A playlist entry pointing at a local file
                        // would otherwise become a row with nothing behind it.
                        val song = imported[songId] ?: continue
                        library.addToPlaylist(localId, song)
                        entries++
                    }
                }
            }
        }
        return added to entries
    }
}
