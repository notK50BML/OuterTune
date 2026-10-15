/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * One song's own equaliser, overriding the global one while that song plays.
 *
 * A record rather than a bare [EqualizerSettings] because eviction needs to know which overrides
 * are still being used - see [SongEqualizerProfiles.MAX_SONGS].
 */
data class SongEqualizerProfile(
    val songId: String,
    val settings: EqualizerSettings,
    /** Epoch millis of the last time this override was written or applied. */
    val updatedAt: Long,
)

/**
 * Per-song equaliser overrides, by song id.
 *
 * The override is **complete and authoritative**, its own `enabled` flag included. That is
 * deliberate and it is the whole point: the thing people want from this is usually "leave the
 * equaliser off, except on this one badly-mastered track", and a version where the global switch
 * still had to be on could not express that. The cost is that a song with an override ignores the
 * master switch in both directions, which is why the panel says so rather than leaving it to be
 * discovered.
 *
 * Stored as a preference string rather than a table. A row per song with a foreign key would scale
 * further and would clean itself up when a song is deleted, and if this ever holds hundreds of
 * entries that is the right answer - but it costs a schema migration, and the realistic shape of
 * this feature is a handful of songs somebody has bothered to dial in.
 */
object SongEqualizerProfiles {
    private const val SCHEMA_VERSION = 1

    /**
     * How many overrides are kept.
     *
     * Bounded because this is one preference string, rewritten whole on every change: left to grow
     * it would eventually make saving an equaliser setting a disk write of hundreds of kilobytes.
     * The oldest by [SongEqualizerProfile.updatedAt] goes first, so what gets dropped is what has
     * not been touched in longest.
     */
    const val MAX_SONGS = 100

    fun toJson(profiles: Collection<SongEqualizerProfile>): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject()
            obj.put("songId", profile.songId)
            obj.put("updatedAt", profile.updatedAt)
            obj.put("settings", JSONObject(profile.settings.toJson()))
            array.put(obj)
        }
        root.put("songs", array)
        return root.toString()
    }

    /**
     * Never throws. A corrupt or newer-schema value means no overrides, which is the same as the
     * state before anyone set one - the global equaliser, which is a working app.
     */
    fun fromJson(json: String): Map<String, SongEqualizerProfile> = runCatching {
        if (json.isBlank()) return emptyMap()
        val root = JSONObject(json)
        if (root.optInt("schemaVersion", 1) > SCHEMA_VERSION) return emptyMap()

        val array = root.optJSONArray("songs") ?: return emptyMap()
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val songId = obj.optString("songId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val settingsObj = obj.optJSONObject("settings") ?: return@mapNotNull null
            val settings = EqualizerSettings.parse(settingsObj.toString()).getOrNull()
                ?: return@mapNotNull null
            SongEqualizerProfile(
                songId = songId,
                settings = settings,
                updatedAt = obj.optLong("updatedAt", 0L),
            )
        }.associateBy { it.songId }
    }.getOrDefault(emptyMap())

    /**
     * [existing] with [songId] set to [settings], evicting the least recently touched if that puts
     * it over [MAX_SONGS].
     */
    fun put(
        existing: Map<String, SongEqualizerProfile>,
        songId: String,
        settings: EqualizerSettings,
        now: Long,
    ): Map<String, SongEqualizerProfile> {
        val updated = existing + (songId to SongEqualizerProfile(songId, settings, now))
        if (updated.size <= MAX_SONGS) return updated
        // Sorted oldest-first and dropped from the front, so the song just written is never the
        // one evicted - it carries the newest timestamp by construction.
        return updated.values
            .sortedByDescending { it.updatedAt }
            .take(MAX_SONGS)
            .associateBy { it.songId }
    }

    fun remove(
        existing: Map<String, SongEqualizerProfile>,
        songId: String,
    ): Map<String, SongEqualizerProfile> = existing - songId
}
