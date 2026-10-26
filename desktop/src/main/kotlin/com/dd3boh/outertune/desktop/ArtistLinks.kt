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
 * - A combined credit "X & Y" that is not itself an artist is split, when every half is one.
 *
 * Two halves. [rewrite] applies what is already known and costs nothing, so the database runs it
 * after every credit write - a rescan or a re-sync cannot quietly undo a link. [enrich] does the
 * searching, one name once, and records every answer (including "no such artist") so a name is
 * not searched for again for a month.
 */
object ArtistLinks {

    // The phone's own patterns, character for character (ArtistCreditEnricher): "featuring",
    // "feat." / "ft." with or without the dot, and "w/"; the names split on commas, "&" and "and".
    // Deliberately not "with", "x" or "vs", which are far more often part of a title.
    private val FEATURED_KEYWORD = Regex("""\b(?:featuring|feat\.?|ft\.?|w/)\s+""", RegexOption.IGNORE_CASE)
    private val FEATURED_SPLIT = Regex("""\s*(?:,|&|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val AMPERSAND = Regex("""\s*&\s*""")
    private val TOPIC = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
    private const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * A name as the phone compares names: ignoring case and surrounding space, nothing more - so
     * "Beyoncé" and "Beyonce" stay two names, exactly as they do there.
     */
    fun key(name: String): String = name.trim().lowercase()

    /**
     * Whether two credits on one song name the same artist - the phone's creditKey: lower case, no
     * "- Topic", letters and digits only, so "i-dle", "I-DLE" and "idle" are one. A name of
     * punctuation alone keeps itself rather than sharing an empty key with every other.
     */
    fun creditKey(name: String): String =
        name.replace(TOPIC, "").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { name.trim().lowercase() }

    /** Every name in the title's feat. clauses, in order, each once. */
    fun featuredNames(title: String): List<String> =
        FEATURED_KEYWORD.findAll(title)
            .flatMap { clauseAt(title, it.range.last + 1).split(FEATURED_SPLIT).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()

    /** A stretch of a feat. clause the song does not credit yet: as written, and cut into names. */
    data class FeaturedGroup(val whole: String, val parts: List<String>)

    /**
     * The title's feat. clauses with every name in [credited] taken out, compared by [creditKey] -
     * the phone's featuredGroups. Taken out whole and before anything is split, so with "Tyler,
     * The Creator" credited "(feat. Tyler, The Creator)" leaves nothing, rather than "Tyler" and
     * "The Creator" to be looked up as two more artists.
     */
    fun featuredGroups(title: String, credited: Collection<String>): List<FeaturedGroup> {
        val creditedKeys = credited.mapTo(HashSet()) { creditKey(it) }
        return FEATURED_KEYWORD.findAll(title)
            .flatMap { uncreditedRuns(clauseAt(title, it.range.last + 1), creditedKeys).asSequence() }
            .distinctBy { creditKey(it.whole) }
            .toList()
    }

    private fun uncreditedRuns(clause: String, creditedKeys: Set<String>): List<FeaturedGroup> {
        val names = mutableListOf<IntRange>()
        var from = 0
        fun addName(end: Int) {
            var s = from
            var e = end
            while (s < e && clause[s].isWhitespace()) s++
            while (e > s && clause[e - 1].isWhitespace()) e--
            if (s < e) names += s until e
        }
        for (separator in FEATURED_SPLIT.findAll(clause)) {
            addName(separator.range.first)
            from = separator.range.last + 1
        }
        addName(clause.length)
        fun text(first: Int, last: Int) = clause.substring(names[first].first, names[last].last + 1)

        // Longest stretch first, so a credited name is recognised whole before any piece of it is.
        val taken = BooleanArray(names.size)
        for (length in names.size downTo 1) {
            for (first in 0..names.size - length) {
                val last = first + length - 1
                if ((first..last).any { taken[it] }) continue
                if (creditKey(text(first, last)) in creditedKeys) (first..last).forEach { taken[it] = true }
            }
        }

        val runs = mutableListOf<FeaturedGroup>()
        var i = 0
        while (i < names.size) {
            if (taken[i]) {
                i++
                continue
            }
            var j = i
            while (j + 1 < names.size && !taken[j + 1]) j++
            runs += FeaturedGroup(text(i, j), (i..j).map { text(it, it) })
            i = j + 1
        }
        return runs
    }

    /**
     * The names after a feat. keyword, up to the end of the clause - the phone's featuredClauseAt.
     * A closing bracket ends it unless it closes one opened inside the name; an opening bracket is
     * part of the name when it starts the name or follows a letter ("(G)I-DLE"), and starts a new
     * section after a space ("B (ft. C)", "A [Deluxe]"); a spaced dash ends an unbracketed clause
     * ("ft. B - Remix").
     */
    private fun clauseAt(title: String, start: Int): String {
        var depth = 0
        var i = start
        while (i < title.length) {
            when (title[i]) {
                '(', '[' -> {
                    val soFar = title.substring(start, i)
                    if (depth == 0 && soFar.isNotBlank() && soFar.last().isWhitespace()) break
                    depth++
                }
                ')', ']' -> {
                    if (depth == 0) break
                    depth--
                }
                '-' -> if (depth == 0 && i > start && title[i - 1] == ' ' && title.getOrNull(i + 1) == ' ') break
            }
            i++
        }
        return title.substring(start, i)
    }

    /**
     * [credits] with each artist once: the same id once, and the same name - by [creditKey] - once,
     * keeping a linked credit over an unlinked one at the place the name first appears. Two credits
     * reading the same name are one artist listed twice, whatever ids they carry.
     */
    fun dedupeCredits(credits: List<StoredArtist>): List<StoredArtist> {
        val distinct = credits.distinctBy { it.id }
        val byName = distinct.groupBy { creditKey(it.name) }
        val seen = HashSet<String>()
        val out = mutableListOf<StoredArtist>()
        for (credit in distinct) {
            val k = creditKey(credit.name)
            if (!seen.add(k)) continue
            val same = byName.getValue(k)
            out += same.firstOrNull { it.linkable } ?: same.first()
        }
        return out
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
            // "X & Y", once confirmed not to be an artist in its own right, is replaced by its halves
            // - but only when every half is a real artist. One that is not puts the split itself
            // in doubt ("Mumford & Sons" missing an exact match while some "Sons" exists), and
            // keeping just the halves that matched traded one wrong credit for another.
            val halves = ampersandHalves(credit.name)
            if (halves != null && confirmedNotAnArtist(links[key(credit.name)])) {
                val real = halves.map(::linked)
                if (real.all { it != null }) {
                    out += real.filterNotNull()
                    continue
                }
            }
            out += credit
        }
        // Two credits with the same name are one artist listed twice: the real channel stays.
        val deduped = dedupeCredits(out).toMutableList()
        // Featured artists named in the title and missing from the credits, when confirmed real -
        // the whole of what is left of a clause first, and its pieces only once the whole is
        // confirmed not to be an artist. See featuredGroups.
        val ids = deduped.mapTo(HashSet()) { it.id }
        val keys = deduped.mapTo(HashSet()) { creditKey(it.name) }
        fun take(real: StoredArtist) {
            if (real.id in ids || creditKey(real.name) in keys) return
            ids += real.id
            keys += creditKey(real.name)
            deduped += real
        }
        for (group in featuredGroups(title, deduped.map { it.name })) {
            val whole = linked(group.whole)
            if (whole != null) {
                take(whole)
                continue
            }
            if (group.parts.size < 2 || !confirmedNotAnArtist(links[key(group.whole)])) continue
            group.parts.mapNotNull(::linked).forEach(::take)
        }
        val result = deduped.distinctBy { it.id }
        // The one place this departs from the phone: a local file is never left with no artist at
        // all. The phone falls back to the album's artists there; a file on disk has nothing to fall
        // back to, so it keeps what its tags said.
        if (result.isEmpty() && credits.isNotEmpty()) return credits
        return if (result == credits) credits else result
    }

    /**
     * A search that answered "no such artist". Not a name unlinked by hand: that is recorded the
     * same way, with no channel, but it means "not that channel", not "not an artist" - and treating
     * it as the latter split or dropped an "X & Y" the user had only meant to unlink.
     */
    private fun confirmedNotAnArtist(link: ArtistLink?): Boolean = link != null && link.channelId == null && !link.manual

    /**
     * The names in these credits and this title that still need asking about. A feat. clause's
     * pieces are only asked about once the whole has been, and found not to be an artist - see
     * [enrich], which asks twice for that reason.
     */
    private fun namesToResolve(
        credits: List<StoredArtist>,
        title: String,
        known: (String) -> ArtistLink?,
    ): List<String> = buildList {
        for (credit in credits) {
            if (credit.linkable) continue
            add(credit.name)
            ampersandHalves(credit.name)?.let(::addAll)
        }
        for (group in featuredGroups(title, credits.map { it.name })) {
            add(group.whole)
            if (group.parts.size > 1 && confirmedNotAnArtist(known(key(group.whole)))) addAll(group.parts)
        }
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
        fun names() = work.flatMap { (_, credits, title) -> namesToResolve(credits, title.orEmpty(), db::artistLink) }
            .distinctBy(::key)
        // Twice: the second pass picks up the pieces of any feat. clause the first found not to be
        // one artist. Names already answered cost nothing the second time.
        val first = names()
        resolveAll(library, first)
        val second = names()
        resolveAll(library, second)
        db.applyArtistLinks(songIds)
        if (first.isNotEmpty()) library.refreshAll()
    }

    /**
     * For a song as it starts playing, which may not be stored yet: its names are looked up now, so
     * whenever it is written - on being counted as played - its credits come out complete.
     */
    suspend fun prepare(library: LibraryStore, song: StoredSong) = withContext(Dispatchers.IO) {
        val db = library.database
        val names = namesToResolve(song.artistList, song.title, db::artistLink).distinctBy(::key)
        if (names.isEmpty()) return@withContext
        resolveAll(library, names)
        // A second pass for feat. pieces, as in enrich.
        resolveAll(library, namesToResolve(song.artistList, song.title, db::artistLink).distinctBy(::key))
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
