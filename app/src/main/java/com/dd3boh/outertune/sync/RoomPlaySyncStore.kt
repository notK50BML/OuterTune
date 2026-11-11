/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.MediaMetadata

/**
 * The phone's plays, for play sync: its event table, read and added to directly. A play is its song
 * and its timestamp exactly as stored, never converted, so a play that comes back from another
 * device is recognised (see SyncPlay).
 */
class RoomPlaySyncStore(private val database: MusicDatabase) : PlaySyncStore {

    override fun plays(): List<SyncPlay> =
        database.openHelper.readableDatabase.query(
            "SELECT e.songId, e.timestamp, e.playTime FROM event e JOIN song s ON s.id = e.songId WHERE s.isLocal = 0"
        ).use { c ->
            buildList { while (c.moveToNext()) add(SyncPlay(c.getString(0), c.getLong(1), c.getLong(2))) }
        }

    override fun playsSince(afterRow: Long): Pair<List<SyncPlay>, Long> {
        val db = database.openHelper.readableDatabase
        val plays = db.query(
            "SELECT e.songId, e.timestamp, e.playTime FROM event e JOIN song s ON s.id = e.songId " +
                "WHERE e.id > ? AND s.isLocal = 0",
            arrayOf<Any>(afterRow),
        ).use { c -> buildList { while (c.moveToNext()) add(SyncPlay(c.getString(0), c.getLong(1), c.getLong(2))) } }
        val newest = db.query("SELECT COALESCE(MAX(id), 0) FROM event").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return plays to maxOf(newest, afterRow)
    }

    override fun songs(ids: Collection<String>): List<SyncSong> {
        val db = database.openHelper.readableDatabase
        val out = ArrayList<SyncSong>(ids.size)
        ids.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray<Any>()
            val credits = HashMap<String, MutableList<SyncArtist>>()
            db.query(
                "SELECT m.songId, a.id, a.name, a.isLocal FROM song_artist_map m JOIN artist a ON a.id = m.artistId " +
                    "WHERE m.songId IN ($marks) ORDER BY m.songId, m.position",
                args,
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(1)
                    // This phone's own ids for artists with no channel mean nothing elsewhere.
                    val channel = id?.takeIf { c.getInt(3) == 0 && !it.startsWith("LA") }
                    credits.getOrPut(c.getString(0)) { mutableListOf() } += SyncArtist(channel, c.getString(2))
                }
            }
            db.query("SELECT id, title, thumbnailUrl, duration FROM song WHERE id IN ($marks)", args).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    out += SyncSong(id, c.getString(1), c.getString(2).orEmpty(), c.getInt(3), credits[id].orEmpty())
                }
            }
        }
        return out
    }

    override fun merge(songs: List<SyncSong>, plays: List<SyncPlay>): Int = database.runInTransaction {
        val db = database.openHelper.writableDatabase
        val known = HashSet<String>()
        db.query("SELECT id FROM song").use { c -> while (c.moveToNext()) known += c.getString(0) }
        songs.forEach { song ->
            if (!known.add(song.id)) return@forEach
            database.insert(
                MediaMetadata(
                    id = song.id,
                    title = song.title,
                    artists = song.artists.map { MediaMetadata.Artist(it.id, it.name) },
                    duration = song.durationSec.takeIf { it > 0 } ?: -1,
                    thumbnailUrl = song.thumbnail.ifBlank { null },
                    genre = null,
                )
            )
        }
        val seen = HashSet<String>()
        db.query("SELECT songId, timestamp FROM event").use { c -> while (c.moveToNext()) seen += "${c.getString(0)}|${c.getLong(1)}" }
        var added = 0
        plays.forEach { play ->
            if (play.songId !in known || !seen.add("${play.songId}|${play.at}")) return@forEach
            db.execSQL(
                "INSERT INTO event (songId, timestamp, playTime) VALUES (?, ?, ?)",
                arrayOf<Any>(play.songId, play.at, play.playTimeMs),
            )
            added++
        }
        added
    }
}
