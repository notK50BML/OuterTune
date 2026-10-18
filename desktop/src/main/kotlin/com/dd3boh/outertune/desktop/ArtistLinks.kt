/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one artist name turned out to be on YouTube Music. [channelId] null: searched, nothing there. */
data class ArtistLink(
    val nameKey: String,
    val name: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnail: String?,
    val checkedAt: Long,
    /** Set by hand on the artist page; never overwritten by an automatic search. */
    val manual: Boolean,
)

/**
 * Credits made as complete as YouTube Music knows them - the desktop's ArtistCreditEnricher, with
 * the same rule: nothing is credited unless a search confirms it is a real artist (an exact name
 * match on a channel with a picture).
 *
 * - A local file's artist ("Coldplay" from its tags) is linked to the real channel, so it opens
 *   the full artist page, shows their picture, and counts as one artist with their streamed songs.
 * - An artist named in the title - "(feat. X, Y)", "(ft. X)" - and missing from the credits is
 *   added.
 * - A combined credit "X & Y" that is not itself an artist is split into whichever halves are.
 *
 * Two halves. [rewrite] applies what is already known and costs nothing, so the database runs it
 * after every credit write - a rescan or a re-sync cannot quietly undo a link. [enrich] does the
 * searching, one name once, and records every answer (including "no such artist") so a name is
 * not searched for again for a month.
 */
object ArtistLinks {

    // The phone's own patterns, character for character (ArtistCreditEnricher): "feat." / "ft."
    // with or without the dot, the names split on commas, "&" and "and".
    private val FEATURED = Regex("""[(\[]?\s*\b(?:feat\.?|ft\.?)\s+([^)\]]+)[)\]]?""", RegexOption.IGNORE_CASE)
    private val FEATURED_SPLIT = Regex("""\s*(?:,|&|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val AMPERSAND = Regex("""\s*&\s*""")
    private val TOPIC = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * A name as the phone compares names: ignoring case and surrounding space, nothing more - so
     * "Beyoncé" and "Beyonce" stay two names, exactly as they do there.
     */
    fun key(name: String): String = name.trim().lowercase()

    fun featuredNames(title: String): List<String> {
        val match = FEATURED.find(title) ?: return emptyList()
        return match.groupValues[1].split(FEATURED_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun ampersandHalves(name: String): List<String>? {
        if ("&" !in name) return null
        return name.split(AMPERSAND).map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size >= 2 }
    }

    /** [credits] rewritten from [links] alone. Returns [credits] itself when nothing changes. */
    fun rewrite(credits: List<StoredArtist>, title: String, links: Map<String, ArtistLink>): List<StoredArtist> {
        fun linked(name: String) = links[key(name)]?.takeIf { it.channelId != null }
            ?.let { StoredArtist(it.channelId!!, it.channelName ?: it.name) }

        val out = mutableListOf<StoredArtist>()
        for (credit in credits) {
            if (credit.linkable) { out += credit; continue }
            // A placeholder whose name is a real artist: that artist. If the channel is already on
            // the song, this is the phone's "rebrand" case - one credit, under the channel's name.
            linked(credit.name)?.let { real ->
                val at = out.indexOfFirst { it.id == real.id }
                if (at >= 0) out[at] = real else out += real
                continue
            }
            // "X & Y", once confirmed not to be an artist in its own right, is replaced by whichever
            // halves are real - and only those: a half that is not a real artist is dropped, never
            // kept as a guess. Decided only once every half has been looked up.
            val halves = ampersandHalves(credit.name)
            val known = links[key(credit.name)]
            if (halves != null && known != null && known.channelId == null &&
                halves.all { links[key(it)] != null }
            ) {
                out += halves.mapNotNull(::linked)
                continue
            }
            out += credit
        }
        // Two credits with the same name are one artist listed twice: the real channel stays.
        val byName = out.groupBy { it.name.trim().lowercase() }
        val deduped = out.filter { a ->
            val same = byName[a.name.trim().lowercase()].orEmpty()
            same.size == 1 || a == (same.firstOrNull { it.linkable } ?: same.first())
        }.toMutableList()
        // Featured artists named in the title and missing from the credits, when confirmed real.
        val credited = deduped.map { it.name.lowercase() }.toMutableSet()
        for (name in featuredNames(title)) {
            if (name.lowercase() in credited) continue
            val real = linked(name) ?: continue
            if (deduped.any { it.id == real.id }) continue
            deduped += real
            credited += real.name.lowercase()
        }
        val result = deduped.distinctBy { it.id }
        // The one place this departs from the phone: a local file is never left with no artist at
        // all. The phone falls back to the album's artists there; a file on disk has nothing to fall
        // back to, so it keeps what its tags said.
        if (result.isEmpty() && credits.isNotEmpty()) return credits
        return if (result == credits) credits else result
    }

    /** The names in these credits and this title that still need asking about. */
    private fun namesToResolve(credits: List<StoredArtist>, title: String): List<String> = buildList {
        for (credit in credits) {
            if (credit.linkable) continue
            add(credit.name)
            ampersandHalves(credit.name)?.let(::addAll)
        }
        val credited = credits.map { it.name.lowercase() }.toSet()
        featuredNames(title).filter { it.lowercase() !in credited }.forEach(::add)
    }

    private val searching = Mutex()

    /** Looks up [name] unless already known (or looked up recently and not found). */
    private suspend fun resolve(library: LibraryStore, name: String): ArtistLink? {
        val k = key(name)
        if (k.isEmpty()) return null
        library.database.artistLink(k)?.let { known ->
            if (known.manual || known.channelId != null) return known
            if (System.currentTimeMillis() - known.checkedAt < RECHECK_MS) return known
        }
        val found = search(name)
        val link = ArtistLink(
            nameKey = k,
            name = name,
            channelId = found?.id,
            channelName = found?.title?.replace(TOPIC, ""),
            thumbnail = found?.thumbnail,
            checkedAt = System.currentTimeMillis(),
            manual = false,
        )
        // A failed request is not an answer: nothing is recorded, so it is asked again next time.
        if (found != null || lastSearchSucceeded) library.database.putArtistLink(link)
        found?.thumbnail?.let { ArtistImages.remember(found.id, it) }
        return link
    }

    @Volatile
    private var lastSearchSucceeded = false

    /** The real channel for [name]: exact match, has a picture, and the artist's own over a Topic one. */
    private suspend fun search(name: String): ArtistItem? {
        val results = YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
        lastSearchSucceeded = results != null
        // The phone's rule exactly: the title, less " - Topic", equal to the name ignoring case,
        // and a picture behind it. The artist's own channel over a Topic one when both come back.
        val candidates = results?.items.orEmpty().filterIsInstance<ArtistItem>()
            .filter { it.title.replace(TOPIC, "").trim().equals(name.trim(), ignoreCase = true) && !it.thumbnail.isNullOrBlank() }
        return candidates.firstOrNull { !TOPIC.containsMatchIn(it.title) } ?: candidates.firstOrNull()
    }

    /** Searches what [songIds] need, then rewrites their credits. For a song as it starts playing. */
    suspend fun enrich(library: LibraryStore, songIds: List<String>) = withContext(Dispatchers.IO) {
        val db = library.database
        val work = songIds.map { id -> Triple(id, db.songCredits(id), db.songTitle(id)) }
        val names = work.flatMap { (_, credits, title) -> namesToResolve(credits, title.orEmpty()) }
            .distinctBy(::key)
        resolveAll(library, names)
        db.applyArtistLinks(songIds)
        if (names.isNotEmpty()) library.refreshAll()
    }

    /**
     * For a song as it starts playing, which may not be stored yet: its names are looked up now, so
     * whenever it is written - on being counted as played - its credits come out complete.
     */
    suspend fun prepare(library: LibraryStore, song: StoredSong) = withContext(Dispatchers.IO) {
        val names = namesToResolve(song.artistList, song.title).distinctBy(::key)
        if (names.isEmpty()) return@withContext
        resolveAll(library, names)
        library.database.applyArtistLinks(listOf(song.id))
        library.refreshAll()
    }

    private suspend fun resolveAll(library: LibraryStore, names: List<String>) {
        val db = library.database
        searching.withLock {
            for (name in names) {
                val known = db.artistLink(key(name))
                val fresh = known != null && (known.manual || known.channelId != null ||
                    System.currentTimeMillis() - known.checkedAt < RECHECK_MS)
                if (fresh) continue
                resolve(library, name)
                // Gentle with YouTube when there are many to do - a whole folder's worth of names.
                if (names.size > 3) delay(250)
            }
        }
    }

    /** Every local song, in the background after a scan. */
    suspend fun enrichLocal(library: LibraryStore) {
        val ids = withContext(Dispatchers.IO) { library.database.songsForLinking(onlyLocal = true).map { it.first } }
        if (ids.isNotEmpty()) enrich(library, ids)
    }

    /** Ties [localName] to [channel] by hand, and rewrites every song credited to the name. */
    suspend fun linkByHand(library: LibraryStore, localName: String, channel: ArtistItem) = withContext(Dispatchers.IO) {
        val db = library.database
        db.putArtistLink(
            ArtistLink(
                key(localName), localName, channel.id, channel.title.replace(TOPIC, ""), channel.thumbnail,
                System.currentTimeMillis(), manual = true,
            )
        )
        channel.thumbnail?.let { ArtistImages.remember(channel.id, it) }
        db.applyArtistLinks(db.songIdsCreditedTo(StoredArtist.unlinked(localName).id))
        library.refreshAll()
    }

    /**
     * Undoes every link to [channelId]: local songs go back to their own artist name, and the name
     * is marked so it is not linked again automatically.
     */
    suspend fun unlink(library: LibraryStore, channelId: String) = withContext(Dispatchers.IO) {
        val db = library.database
        val links = db.linksToChannel(channelId)
        for (link in links) db.putArtistLink(link.copy(channelId = null, channelName = null, thumbnail = null, manual = true))
        db.restoreLocalCredits(channelId, links.map { it.name })
        library.refreshAll()
    }

    /** Artist search for linking by hand. */
    suspend fun candidates(query: String): List<ArtistItem> = withContext(Dispatchers.IO) {
        YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items.orEmpty()
            .filterIsInstance<ArtistItem>()
    }
}
