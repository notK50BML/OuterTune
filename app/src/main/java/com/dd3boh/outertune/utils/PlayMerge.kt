/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Adds another device's plays to this phone's - another phone's backup, or a backup from
 * OuterTune for Windows - and nothing else: no like, playlist or setting is touched, and nothing
 * here is replaced. This is how Stats gets the plays made elsewhere: the account's YouTube Music
 * history keeps each song once, with no count and no listening time, so it can never carry them.
 *
 * **Never counts a play twice.** A play is its song and the exact instant it was recorded, and one
 * already here is skipped. Both apps keep that instant exactly as it was first written, through any
 * number of merges in either direction, so merging the same backup again - or a computer's backup
 * holding plays it merged from this phone - adds only what is new.
 *
 * Songs this phone already has keep their own details; only songs new to it are added, with their
 * credits and length. Local files are left out: their ids mean nothing on another device.
 */
object PlayMerge {

    data class Result(val plays: Int, val newSongs: Int)

    private class Song(val id: String, val title: String, val thumbnail: String?, val durationSec: Int, val artists: List<MediaMetadata.Artist>)

    private class Play(val songId: String, val at: Long, val playTimeMs: Long)

    suspend fun mergeFrom(context: Context, database: MusicDatabase, uri: Uri): Result = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("merge", ".db", context.cacheDir)
        try {
            var found = false
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        // This phone's song.db, or the desktop's library.db.
                        if (!entry.isDirectory && entry.name.endsWith(".db")) {
                            temp.outputStream().use { zip.copyTo(it) }
                            found = true
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            if (!found) error("not an OuterTune backup")

            val (songs, plays) = SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
                if (hasTable(source, "play_event")) readDesktop(source) else readPhone(source)
            }
            merge(database, songs, plays)
        } finally {
            temp.delete()
            File(temp.path + "-journal").delete()
        }
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    /** Another phone's backup: its played songs, their credits, and every play with its listening time. */
    private fun readPhone(db: SQLiteDatabase): Pair<Map<String, Song>, List<Play>> {
        val credits = HashMap<String, MutableList<MediaMetadata.Artist>>()
        runCatching {
            db.rawQuery(
                "SELECT m.songId, a.id, a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId ORDER BY m.songId, m.position",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val artistId = c.getString(1)
                    // The other phone's own ids for artists with no channel mean nothing here.
                    val id = artistId?.takeUnless { it.startsWith("LA") }
                    credits.getOrPut(c.getString(0)) { mutableListOf() } += MediaMetadata.Artist(id, c.getString(2))
                }
            }
        }
        val songCols = columns(db, "song")
        val duration = if ("duration" in songCols) "duration" else "-1"
        val songs = HashMap<String, Song>()
        db.rawQuery(
            "SELECT id, title, thumbnailUrl, $duration, isLocal FROM song WHERE id IN (SELECT DISTINCT songId FROM event)",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(4) != 0) continue
                val id = c.getString(0) ?: continue
                songs[id] = Song(id, c.getString(1) ?: continue, c.getString(2), c.getInt(3), credits[id].orEmpty())
            }
        }
        val plays = db.rawQuery("SELECT songId, timestamp, playTime FROM event", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val songId = c.getString(0)?.takeIf { it in songs } ?: continue
                    add(Play(songId, c.getLong(1), c.getLong(2)))
                }
            }
        }
        return songs to plays
    }

    /**
     * A computer's library: its played songs (not its local files) and its plays. The desktop keeps
     * no listening time per play, only the song's length, so that is what each play counts as - the
     * same thing the desktop's own Stats count.
     */
    private fun readDesktop(db: SQLiteDatabase): Pair<Map<String, Song>, List<Play>> {
        val credits = HashMap<String, MutableList<MediaMetadata.Artist>>()
        db.rawQuery(
            "SELECT sa.songId, a.id, a.name FROM song_artist sa JOIN artist a ON a.id = sa.artistId ORDER BY sa.songId, sa.position",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                // "local:name" is the desktop's credit with no channel behind it.
                val id = c.getString(1)?.takeUnless { it.startsWith("local:") }
                credits.getOrPut(c.getString(0)) { mutableListOf() } += MediaMetadata.Artist(id, c.getString(2))
            }
        }
        val local = if (hasTable(db, "local_file")) {
            db.rawQuery("SELECT songId FROM local_file", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        } else emptySet()
        val songs = HashMap<String, Song>()
        db.rawQuery(
            "SELECT id, title, thumbnail, durationMs FROM song WHERE id IN (SELECT DISTINCT songId FROM play_event)",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                if (id in local) continue
                songs[id] = Song(
                    id, c.getString(1) ?: continue, c.getString(2)?.takeIf { it.isNotBlank() },
                    (c.getLong(3) / 1000).toInt().takeIf { it > 0 } ?: -1, credits[id].orEmpty(),
                )
            }
        }
        val plays = db.rawQuery("SELECT songId, playedAt FROM play_event", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val song = c.getString(0)?.let(songs::get) ?: continue
                    add(Play(song.id, c.getLong(1), song.durationSec.coerceAtLeast(0) * 1000L))
                }
            }
        }
        return songs to plays
    }

    /** One transaction: new songs, then every play not already here. */
    private fun merge(database: MusicDatabase, songs: Map<String, Song>, plays: List<Play>): Result =
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            var newSongs = 0
            songs.values.forEach { song ->
                val known = db.query("SELECT 1 FROM song WHERE id = ?", arrayOf<Any>(song.id)).use { it.moveToFirst() }
                if (known) return@forEach
                database.insert(
                    MediaMetadata(
                        id = song.id,
                        title = song.title,
                        artists = song.artists,
                        duration = song.durationSec,
                        thumbnailUrl = song.thumbnail,
                        genre = null,
                    )
                )
                newSongs++
            }
            var added = 0
            plays.forEach { play ->
                // The play's identity: its song and its exact instant, as stored.
                val seen = db.query(
                    "SELECT 1 FROM event WHERE songId = ? AND timestamp = ?",
                    arrayOf<Any>(play.songId, play.at),
                ).use { it.moveToFirst() }
                if (seen) return@forEach
                db.execSQL(
                    "INSERT INTO event (songId, timestamp, playTime) VALUES (?, ?, ?)",
                    arrayOf<Any>(play.songId, play.at, play.playTimeMs),
                )
                added++
            }
            Result(plays = added, newSongs = newSongs)
        }
}
