/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Fixes two cases where a song's credited artists are less complete than what YouTube Music
 * itself actually knows about it:
 *
 * - A single combined credit like "X & Y" that isn't a real channel - YTM sometimes returns this
 *   as one Run with no navigationEndpoint rather than crediting X and Y separately, which lands in
 *   the database as one ArtistEntity with a generated local id (see [ArtistEntity.isYouTubeArtist]).
 *   "X & Y" might genuinely be one act's own name (a duo whose real channel title contains "&"),
 *   so it's only replaced once confirmed it doesn't exist as an artist in its own right; once
 *   confirmed, it's removed and swapped for whichever of X/Y individually resolve to a real,
 *   populated channel - a half that doesn't resolve is dropped too, never kept as an unconfirmed
 *   guess.
 * - An artist named in the title's "(feat. X, Y)"/"(ft. X)" text but missing from the credited
 *   list gets added, once a search confirms the name is a real artist.
 *
 * The rule behind both: nothing here ever credits a name that hasn't been confirmed to be a real,
 * populated channel (a non-blank thumbnail) - an unconfirmed or nonexistent name is removed/never
 * added, never kept around just because it's what the song's own data happened to say. An artist
 * that IS confirmed real, however (whether it's "X & Y" itself or a split-out half), is linked to
 * its actual channel, not just left as an inert placeholder.
 */
object ArtistCreditEnricher {
    private const val TAG = "ArtistCreditEnricher"

    private val AMPERSAND_SPLIT = Regex("\\s*&\\s*")
    private val FEATURED_ARTISTS = Regex(
        """[(\[]?\s*\b(?:feat\.?|ft\.?)\s+([^)\]]+)[)\]]?""",
        RegexOption.IGNORE_CASE
    )
    private val FEATURED_NAME_SPLIT = Regex("\\s*(?:,|&|\\band\\b)\\s*", RegexOption.IGNORE_CASE)

    /** YouTube's auto-generated channel name for an artist with no official channel of their own. */
    private val TOPIC_SUFFIX = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private fun String.stripTopicSuffix() = replace(TOPIC_SUFFIX, "").trim()

    /**
     * Runs both checks for one song - called once per playback ([com.dd3boh.outertune.playback.MusicService.recoverSong])
     * and once per song when downloads are rescanned ([com.dd3boh.outertune.playback.DownloadUtil.scanDownloads]).
     * Network calls only happen when there's actually a combined credit or featured-artist text to
     * check - a song whose credits are already fully split and complete does nothing here beyond
     * the one initial database read.
     */
    suspend fun enrich(database: MusicDatabase, songId: String): Unit = withContext(Dispatchers.IO) {
        val song = database.song(songId).first() ?: return@withContext

        // Rebuilt from scratch rather than patched in place: once any combined credit's count of
        // real artists differs from 1, every position after it shifts, and rewriting the whole
        // list from here is simpler and safer than adjusting individual positions.
        var changed = false
        val finalArtists = mutableListOf<ArtistEntity>()
        for (artist in song.artists) {
            if (artist.isYouTubeArtist) {
                finalArtists += artist
                continue
            }
            val candidates = splitAmpersandCandidates(artist.name)
            if (candidates == null) {
                finalArtists += artist
                continue
            }

            // "X & Y" might be a real act's own name - only treat it as fake once its own name
            // fails to resolve to a real, populated channel.
            val realCombined = resolveArtistEntity(database, artist.name)
            if (realCombined != null) {
                finalArtists += realCombined
                if (realCombined.id != artist.id) changed = true
                continue
            }

            // Confirmed it doesn't exist as an artist on its own: drop it, keeping only whichever
            // half(s) DO resolve to a real, populated channel - an unresolved half is dropped
            // too, never kept as an unconfirmed guess.
            val realHalves = candidates.mapNotNull { resolveArtistEntity(database, it) }
            finalArtists += realHalves
            changed = true
            Log.d(TAG, "[$songId] combined credit \"${artist.name}\" doesn't exist on its own - replaced with ${realHalves.map { it.name }}")
        }

        // distinctBy guards against two different combined credits independently resolving to
        // the same real artist - rare, but song_artist_map's (songId, artistId) primary key
        // can't hold that same id twice at two different positions.
        val distinctFinalArtists = finalArtists.distinctBy { it.id }

        if (changed) {
            song.artists.forEach { database.deleteSongArtistMap(songId, it.id) }
            song.artists.filterNot { old -> distinctFinalArtists.any { it.id == old.id } }
                .forEach { database.safeDeleteArtist(it.id) }
            distinctFinalArtists.forEachIndexed { index, artist ->
                database.insert(artist)
                database.insert(SongArtistMap(songId = songId, artistId = artist.id, position = index))
            }
        }

        // Add any featured artist named in the title but missing from the (possibly just
        // rewritten) credited list.
        val creditedNames = distinctFinalArtists.map { it.name.lowercase() }.toMutableSet()
        var nextPosition = distinctFinalArtists.size
        val featured = parseFeaturedArtistNames(song.song.title).filter { it.lowercase() !in creditedNames }
        for (name in featured) {
            val resolved = resolveArtistEntity(database, name) ?: continue
            database.insert(resolved)
            database.insert(SongArtistMap(songId = songId, artistId = resolved.id, position = nextPosition))
            nextPosition++
            creditedNames += resolved.name.lowercase()
            Log.d(TAG, "[$songId] added featured artist \"${resolved.name}\"")
        }
    }

    /**
     * Splits a combined credit's name on "&" - null when there's nothing to split (no "&", or
     * splitting would leave a blank half).
     */
    private fun splitAmpersandCandidates(name: String): List<String>? {
        if ("&" !in name) return null
        val parts = name.split(AMPERSAND_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.size >= 2 }
    }

    /** Names mentioned in a "(feat. X, Y)"/"(ft. X)" segment of [title], if any. */
    private fun parseFeaturedArtistNames(title: String): List<String> {
        val match = FEATURED_ARTISTS.find(title) ?: return emptyList()
        return match.groupValues[1]
            .split(FEATURED_NAME_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * The real artist channel [name] resolves to, or null if search finds no matching one. An
     * artist with no official channel is only findable under YouTube's auto-generated "X - Topic"
     * channel, so the match compares against both the raw and Topic-stripped title.
     *
     * A title match with a blank thumbnail is rejected rather than accepted: a real, populated
     * YTM artist channel always has one, so a same-named result with no profile picture behind it
     * reads as a placeholder/junk channel rather than the actual artist - exactly the case this
     * should stay conservative about instead of linking to.
     */
    private suspend fun resolveArtist(name: String): ArtistItem? {
        val results = YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items
            ?: return null
        return results.filterIsInstance<ArtistItem>()
            .firstOrNull {
                it.title.stripTopicSuffix().equals(name, ignoreCase = true) && !it.thumbnail.isNullOrBlank()
            }
    }

    /**
     * [resolveArtist], but resolved to a real ArtistEntity ready to credit: its title has the
     * "- Topic" suffix stripped (see [resolveArtist]'s own doc), and if an artist already exists
     * in the database under that clean name, its existing id/name are reused instead of minting a
     * second entity for the same real-world artist under a different id.
     */
    private suspend fun resolveArtistEntity(database: MusicDatabase, name: String): ArtistEntity? {
        val resolved = resolveArtist(name) ?: return null
        val cleanName = resolved.title.stripTopicSuffix()
        val existing = database.artistByNameIgnoreCase(cleanName)
        return existing ?: ArtistEntity(id = resolved.id, name = cleanName)
    }
}
