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
import java.text.Normalizer

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

    private val FEATURED = Regex("""[(\[]?\s*\b(?:feat\.?|ft\.?|featuring)\s+([^)\]]+)[)\]]?""", RegexOption.IGNORE_CASE)
    private val FEATURED_SPLIT = Regex("""\s*(?:,|&|\band\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)
    private val AMPERSAND = Regex("""\s*&\s*""")
    private val TOPIC = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000

    /** Case, accents and spacing folded away, so "Beyoncé" and "beyonce " are one name. */
    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

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
            linked(credit.name)?.let { out += it; continue }
            // "X & Y" confirmed not to be an artist itself: whichever halves are. With none of them
            // known either, the credit stays as it was - a local file keeps its artist offline.
            val halves = ampersandHalves(credit.name)
            val known = links[key(credit.name)]
            if (halves != null && known != null && known.channelId == null) {
                val real = halves.mapNotNull(::linked)
                if (real.isNotEmpty()) { out += real; continue }
            }
            out += credit
        }
        val names = out.map { key(it.name) }.toMutableSet()
        for (name in featuredNames(title)) {
            if (key(name) in names) continue
            val real = linked(name) ?: continue
            if (out.any { it.id == real.id }) continue
            out += real
            names += key(real.name)
        }
        val result = out.distinctBy { it.id }
        return if (result == credits) credits else result
    }

    /** The names in these credits and this title that still need asking about. */
    private fun namesToResolve(credits: List<StoredArtist>, title: String): List<String> = buildList {
        for (credit in credits) {
            if (credit.linkable) continue
            add(credit.name)
            ampersandHalves(credit.name)?.let(::addAll)
        }
        val credited = credits.map { key(it.name) }.toSet()
        featuredNames(title).filter { key(it) !in credited }.forEach(::add)
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
        val target = key(name)
        val candidates = results?.items.orEmpty().filterIsInstance<ArtistItem>()
            .filter { key(it.title.replace(TOPIC, "")) == target && !it.thumbnail.isNullOrBlank() }
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
