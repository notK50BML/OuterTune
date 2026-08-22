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
 *   the database as one ArtistEntity with a generated local id (see [ArtistEntity.isYouTubeArtist])
 *   - gets split into its own artists once searching each half actually turns up a real one.
 * - An artist named in the title's "(feat. X, Y)"/"(ft. X)" text but missing from the credited
 *   list gets added, once a search confirms the name is a real artist.
 *
 * Deliberately conservative: nothing here changes a song's credits unless the corresponding
 * search actually resolves to a real channel, so a genuine single-name act whose real channel
 * name happens to contain "&" is never incorrectly split, and title text that merely looks like a
 * feature credit but isn't a real artist is never added.
 */
object ArtistCreditEnricher {
    private const val TAG = "ArtistCreditEnricher"

    private val AMPERSAND_SPLIT = Regex("\\s*&\\s*")
    private val FEATURED_ARTISTS = Regex(
        """[(\[]?\s*(?:feat\.?|ft\.?)\s+([^)\]]+)[)\]]?""",
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

        // Split a combined "X & Y" credit once both halves resolve to real artists. Position is
        // preserved: the split-out names replace the combined one at its own index rather than
        // being appended, so ordering among the OTHER credited artists doesn't shift.
        for ((index, artist) in song.artists.withIndex()) {
            if (artist.isYouTubeArtist) continue
            val candidates = splitAmpersandCandidates(artist.name) ?: continue
            val resolved = candidates.map { resolveArtistEntity(database, it) }
            if (resolved.any { it == null }) continue

            database.deleteSongArtistMap(songId, artist.id)
            database.safeDeleteArtist(artist.id)
            resolved.forEachIndexed { offset, item ->
                item!!
                database.insert(item)
                database.insert(SongArtistMap(songId = songId, artistId = item.id, position = index + offset))
            }
            Log.d(TAG, "[$songId] split combined credit \"${artist.name}\" into ${resolved.map { it?.name }}")
        }

        // Add any featured artist named in the title but missing from the credited list. Read
        // fresh rather than reusing `song.artists`: the split above may have just changed it.
        val currentNames = database.song(songId).first()?.artists?.map { it.name.lowercase() }?.toSet()
            ?: return@withContext
        val featured = parseFeaturedArtistNames(song.song.title).filter { it.lowercase() !in currentNames }
        if (featured.isEmpty()) return@withContext

        var nextPosition = database.song(songId).first()?.artists?.size ?: return@withContext
        for (name in featured) {
            val resolved = resolveArtistEntity(database, name) ?: continue
            database.insert(resolved)
            database.insert(SongArtistMap(songId = songId, artistId = resolved.id, position = nextPosition))
            nextPosition++
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
     */
    private suspend fun resolveArtist(name: String): ArtistItem? {
        val results = YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items
            ?: return null
        return results.filterIsInstance<ArtistItem>()
            .firstOrNull { it.title.stripTopicSuffix().equals(name, ignoreCase = true) }
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
