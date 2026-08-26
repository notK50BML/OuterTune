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
 *   Once searching each half actually turns up a real channel, X and Y get added as their own
 *   artists.
 * - An artist named in the title's "(feat. X, Y)"/"(ft. X)" text but missing from the credited
 *   list gets added, once a search confirms the name is a real artist.
 *
 * The song's own inbuilt credit is never touched or replaced by either of these - a search match
 * can be wrong (same-named channel, right title but no real profile behind it), so the credit
 * that came straight from the song's own data stays put and stays what "the artist" resolves to.
 * Anything this class finds is *added*, positioned after every credit the song already had, and
 * only when the match actually looks like a real, populated channel (a non-blank thumbnail) -
 * never as a replacement for what was already linked.
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

        // Everything found below is appended after the song's own inbuilt credits, never in
        // place of them - `nextPosition`/`creditedNames` track that running tail as entries are
        // added, so a song with more than one combined credit or several featured artists still
        // gets each of them positioned behind everything before it instead of colliding on the
        // same position.
        var nextPosition = song.artists.size
        val creditedNames = song.artists.map { it.name.lowercase() }.toMutableSet()

        // Add the real artists behind a combined "X & Y" credit once both halves resolve to a
        // real, populated channel. The combined credit itself is left exactly as it was - it's
        // still the song's own inbuilt link, just not a clickable one.
        for (artist in song.artists) {
            if (artist.isYouTubeArtist) continue
            val candidates = splitAmpersandCandidates(artist.name) ?: continue
            if (candidates.any { it.lowercase() in creditedNames }) continue

            val resolved = candidates.map { resolveArtistEntity(database, it) }
            if (resolved.any { it == null }) continue

            resolved.forEach { item ->
                item!!
                database.insert(item)
                database.insert(SongArtistMap(songId = songId, artistId = item.id, position = nextPosition))
                nextPosition++
                creditedNames += item.name.lowercase()
            }
            Log.d(TAG, "[$songId] added real artists behind combined credit \"${artist.name}\": ${resolved.map { it?.name }}")
        }

        // Add any featured artist named in the title but missing from the credited list.
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
