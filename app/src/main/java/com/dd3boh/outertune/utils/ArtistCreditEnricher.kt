/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.normalizeArtistId
import com.dd3boh.outertune.db.stripTopicSuffix
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

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

    /**
     * Runs both checks for one song - called once per playback ([com.dd3boh.outertune.playback.MusicService.recoverSong])
     * and once per song when downloads are rescanned ([com.dd3boh.outertune.playback.DownloadUtil.scanDownloads]).
     * Network calls only happen when there's actually a combined credit or featured-artist text to
     * check - a song whose credits are already fully split and complete does nothing here beyond
     * the one initial database read.
     */
    suspend fun enrich(database: MusicDatabase, songId: String): Unit = withContext(Dispatchers.IO) {
        val song = database.song(songId).first() ?: return@withContext

        // Logged unconditionally, and at the top, because the interesting question when a credit
        // has not corrected itself is which of two things happened: this never ran for that song,
        // or it ran and decided to leave it alone. Every other line here only fires when something
        // changed, so their absence answered neither.
        Timber.tag(TAG).d(
            "[$songId] enriching \"${song.song.title}\": " +
                song.artists.joinToString { "${it.name}(${it.id})" }.ifEmpty { "no credits" }
        )

        // A song credited to nobody at all takes the artists of the album it belongs to. YTM
        // regularly returns album tracks with the album set but no per-track artist runs, and the
        // result was a blank artist line in the player with nothing to tap.
        //
        // Written into song_artist_map rather than substituted at display time. Doing it here means
        // one rule covers the player, the library lists, the song menus and search alike instead of
        // each screen needing its own fallback, and the credit that results is an ordinary
        // ArtistEntity - the album's own artists are already real channels - so it links exactly
        // like a directly-credited one. Everything below this point assumes a non-empty credit
        // list, and there is nothing for it to split or de-duplicate here anyway, so this returns.
        if (song.artists.isEmpty()) {
            creditAlbumArtists(database, song)
            return@withContext
        }

        // Rebuilt from scratch rather than patched in place: once any combined credit's count of
        // real artists differs from 1, every position after it shifts, and rewriting the whole
        // list from here is simpler and safer than adjusting individual positions.
        var changed = false
        val finalArtists = mutableListOf<ArtistEntity>()
        for (artist in song.artists) {
            // Cleans up rows already written by the blank-name bug in stripTopicSuffix, which
            // turned a channel titled exactly "- Topic" into an artist with no name at all. Such a
            // credit renders as nothing, sorts nowhere, and links to the junk channel it came from,
            // so there is nothing to preserve by keeping it. A song left with no credits at all
            // then picks its album's up on the next pass, which is a better answer than a blank.
            if (artist.name.isBlank()) {
                Timber.tag(TAG).d("[$songId] dropping nameless credit ${artist.id}")
                changed = true
                continue
            }
            // Same artist, YouTube's other spelling of its id - see normalizeArtistId. Rewritten
            // here rather than merely tolerated, so the credit becomes a real channel that links,
            // and so it collides with its own bare-id twin below instead of showing twice.
            val normalizedId = artist.id.normalizeArtistId()
            if (normalizedId != artist.id) {
                Timber.tag(TAG).d("[$songId] \"${artist.name}\" ${artist.id} -> $normalizedId")
                finalArtists += artist.copy(id = normalizedId)
                changed = true
                continue
            }
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
            Timber.tag(TAG).d("[$songId] combined credit \"${artist.name}\" doesn't exist on its own - replaced with ${realHalves.map { it.name }}")
        }

        // Two credits reading the same name are one artist listed twice, whatever ids they carry.
        // It happens after a rebrand once the real channel's name catches up with the placeholder's
        // (both then read i-dle), and it happens when a song is credited to both an artist's real
        // channel and the auto-generated Topic one YouTube made for them, which carry the same name
        // and different ids.
        //
        // Which to keep is not a coin toss: it is the one whose channel actually has this song,
        // because that is the page a listener tapping the name is trying to reach. That is what the
        // name resolving to a real, populated channel establishes - it returns the channel YouTube
        // itself answers with for that name, which is the one carrying the catalogue. A credit
        // matching it wins; failing that a real channel beats a local placeholder, which has no
        // page at all and so cannot be the one holding anything.
        //
        // Costs one search per duplicated name, and only when a song actually has one.
        val duplicatedNames = finalArtists
            .groupBy { it.name.trim().lowercase() }
            .filterValues { it.size > 1 }
            .keys
        if (duplicatedNames.isNotEmpty()) {
            val winners = HashMap<String, ArtistEntity>()
            for (key in duplicatedNames) {
                val dupes = finalArtists.filter { it.name.trim().lowercase() == key }
                val canonicalId = resolveArtist(dupes.first().name)?.id
                val winner = dupes.firstOrNull { it.id == canonicalId }
                    ?: dupes.firstOrNull { it.isYouTubeArtist }
                    ?: dupes.first()
                winners[key] = winner
                Timber.tag(TAG).d(
                    "[$songId] \"${dupes.first().name}\" credited ${dupes.size}x " +
                        "(${dupes.joinToString { it.id }}) - keeping ${winner.id}"
                )
            }

            val deduped = mutableListOf<ArtistEntity>()
            for (artist in finalArtists) {
                val key = artist.name.trim().lowercase()
                if (key !in duplicatedNames) {
                    deduped += artist
                } else if (winners[key]?.id == artist.id && deduped.none { it.id == artist.id }) {
                    deduped += artist
                }
            }
            if (deduped.size != finalArtists.size) changed = true
            finalArtists.clear()
            finalArtists += deduped
        }

        // An artist that renamed itself upstream can end up credited twice on one song, once under
        // each name, and the two credits are not equally useful. ((G)I-DLE -> i-dle is the case
        // this was written against.) YTM keeps returning the old name against the real channel, so
        // that credit links correctly but reads wrongly, while the new name arrives as a bare Run
        // with no navigationEndpoint and lands as a local placeholder - which reads correctly and
        // links nowhere, so it shows greyed out. The song then displays both.
        //
        // Neither is right on its own and the fix is not to pick one: it is to notice they are the
        // same artist. A placeholder whose name resolves to a channel already credited on this song
        // is that channel under its current name, so the placeholder is dropped and its name is
        // written onto the real credit - which is the one the tap already went to. The result is a
        // single credit, named as the page it opens.
        //
        // Only attempted when the song holds both kinds at once, so the search this costs is paid
        // on the rare shape that can actually be a rebrand rather than on every song.
        if (finalArtists.any { it.isYouTubeArtist } && finalArtists.any { !it.isYouTubeArtist }) {
            val collapsed = mutableListOf<ArtistEntity>()
            for (artist in finalArtists) {
                if (artist.isYouTubeArtist) {
                    collapsed += artist
                    continue
                }

                // resolveArtist, not resolveArtistEntity. The latter prefers an existing library row
                // with the resolved name - and in exactly this case that row is the placeholder
                // being examined, so it handed back the placeholder's own local id, which of course
                // never matched a real channel and the collapse never fired. What is needed here is
                // the channel the name resolves to upstream, regardless of what is already stored.
                val resolved = resolveArtist(artist.name)
                val cleanName = resolved?.title?.stripTopicSuffix()
                val sameChannel = resolved?.let { r -> finalArtists.firstOrNull { it.id == r.id && it.isYouTubeArtist } }
                if (sameChannel == null || cleanName == null) {
                    collapsed += artist
                    continue
                }
                if (sameChannel.name != cleanName) {
                    Timber.tag(TAG).d("[$songId] ${sameChannel.id} renamed upstream: \"${sameChannel.name}\" -> \"$cleanName\"")
                    database.update(sameChannel.copy(name = cleanName))
                    collapsed.replaceAll { if (it.id == sameChannel.id) it.copy(name = cleanName) else it }
                }
                Timber.tag(TAG).d("[$songId] dropping \"${artist.name}\", a placeholder for ${sameChannel.id} under its new name")
                changed = true
            }
            finalArtists.clear()
            finalArtists += collapsed
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
            Timber.tag(TAG).d("[$songId] added featured artist \"${resolved.name}\"")
        }

        backfillAlbumArtists(database, song)
    }

    /**
     * Credits the album this song is on with the artists its tracks agree on, when the album itself
     * has none. The mirror of [creditAlbumArtists], for the case where the album is the side missing
     * the data rather than the song.
     *
     * Only artists credited on *every* track are used. Taking one track's credits would be wrong on
     * anything that is not a single-artist record: on a compilation, or on an album with one guest
     * feature, whichever track happened to be looked at would put its guest on the album as though
     * they had made all of it. An intersection can only ever yield artists the whole record shares,
     * which on a normal album is exactly its artist and on a compilation is correctly nothing.
     *
     * Runs only when the album has no artists at all, so it never argues with data YTM did supply.
     * It cannot recurse into [creditAlbumArtists] either: that one returns before reaching here, and
     * only ever runs for a song with no artists, which contributes nothing to an intersection.
     */
    private suspend fun backfillAlbumArtists(database: MusicDatabase, song: Song) {
        val albumId = song.album?.id ?: song.song.albumId ?: return
        val album = database.album(albumId).first() ?: return
        if (album.artists.isNotEmpty()) return

        val tracks = database.albumSongs(albumId).first().filter { it.artists.isNotEmpty() }
        if (tracks.isEmpty()) return

        val shared = tracks.first().artists.filter { candidate ->
            tracks.all { track -> track.artists.any { it.id == candidate.id } }
        }
        if (shared.isEmpty()) {
            Timber.tag(TAG).d("[$albumId] tracks share no artist - leaving the album uncredited")
            return
        }

        shared.forEachIndexed { index, artist ->
            database.insert(AlbumArtistMap(albumId = albumId, artistId = artist.id, order = index))
        }
        Timber.tag(TAG).d("[$albumId] album had no artists - took ${shared.map { it.name }} from its ${tracks.size} tracks")
    }

    /**
     * Credits [song] with the artists of the album it belongs to. No-op when it isn't on an album,
     * or when the album itself has no credited artists either.
     *
     * The album is taken from the song_album_map relation where there is one and from the song row's
     * own albumId otherwise - the same pair of sources, in the same order, that
     * [com.dd3boh.outertune.models.MediaMetadata] resolves an album from, so a song shows an album
     * here exactly when it shows one in the player.
     */
    private suspend fun creditAlbumArtists(database: MusicDatabase, song: Song) {
        val albumId = song.album?.id ?: song.song.albumId ?: return
        val albumArtists = database.album(albumId).first()?.artists.orEmpty()
        if (albumArtists.isEmpty()) return

        albumArtists.forEachIndexed { index, artist ->
            // Both inserts are OnConflictStrategy.IGNORE, so an artist already in the library keeps
            // the row it has (name, thumbnail, bookmark and all) rather than being overwritten by
            // the album's copy of it.
            database.insert(artist)
            database.insert(SongArtistMap(songId = song.song.id, artistId = artist.id, position = index))
        }
        Timber.tag(TAG).d("[${song.song.id}] no credited artists - inherited ${albumArtists.map { it.name }} from album $albumId")
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
