/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.SongItem
import com.dd3boh.outertune.db.TOPIC_SUFFIX
import android.content.Context
import android.content.SharedPreferences
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.namesAnArtist
import com.dd3boh.outertune.db.normalizeArtistId
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.ui.utils.resize
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps artist names current, everywhere, without anyone opening the artist's page.
 *
 * A credit carries whatever name its source held when it was written, and for a renamed channel
 * that is the old one - "(G)I-DLE" long after the channel became "i-dle". The artist page fetch
 * already writes the current name (ArtistsDao.update with an ArtistPage), but only for artists
 * someone happens to open. Here, any artist whose song is shown in a list is queued and checked:
 *
 * - A real channel: its page is fetched and the name updated. If YouTube now sends that channel id
 *   to a different channel - an old channel merged into a new one - every credit is moved onto the
 *   new channel and the old row removed.
 * - A placeholder (a credit no channel was ever matched to): its name is searched, and when that
 *   finds the channel, the placeholder is folded into it.
 * - After each batch the duplicate sweep runs, so a renamed channel and a placeholder that now
 *   share its name become one artist.
 *
 * Gentle on purpose. YouTube throttles a client that sends too many automated requests, and that
 * throttle takes playback down with it, so: one request at a time, [GAP_MS] apart; each artist at
 * most once a week ([RECHECK_MS], remembered across launches); and nothing at all while artist
 * credit enrichment is switched off. A library of a few hundred artists costs a few hundred
 * requests spread across a week of browsing - fewer than playing the same songs used to cost.
 */
object ArtistNameRefresher {

    private const val TAG = "ArtistNameRefresher"
    private const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_FAILED_MS = 24L * 60 * 60 * 1000
    private const val GAP_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var database: MusicDatabase? = null
    @Volatile private var checks: SharedPreferences? = null

    /** Starts the worker. Safe to call more than once - only the first does anything. */
    fun start(context: Context, database: MusicDatabase) {
        if (this.database != null) return
        synchronized(this) {
            if (this.database != null) return
            checks = context.applicationContext.getSharedPreferences("artist_name_checks", Context.MODE_PRIVATE)
            this.database = database
            scope.launch { work(database) }
            // The whole library, not only artists that happen to be on screen - so an old name or a
            // duplicate is put right without anyone scrolling past it. Most likely to be wrong
            // first: credits no channel was matched to, then names more than one row shares.
            scope.launch {
                runCatching {
                    val all = database.allArtistsOnce().filterNot { it.isLocal }
                    val shared = all.groupBy { ArtistCreditEnricher.artistKey(it.name) }
                        .filterValues { it.size > 1 }.values.flatten().mapTo(HashSet()) { it.id }
                    enqueue(all.sortedBy {
                        when {
                            !it.isYouTubeArtist -> 0
                            it.id in shared -> 1
                            else -> 2
                        }
                    })
                }.onFailure { Timber.tag(TAG).w(it, "could not queue the library") }
            }
        }
    }

    /** Queues [artists] for a check, skipping any checked recently or already waiting. */
    fun enqueue(artists: List<ArtistEntity>) {
        if (!ArtistCreditEnricher.enabled || database == null) return
        val now = System.currentTimeMillis()
        for (artist in artists) {
            if (artist.isLocal || artist.name.isBlank()) continue
            if (now < (checks?.getLong(artist.id, 0L) ?: 0L)) continue
            if (pending.add(artist.id)) queue.trySend(artist.id)
        }
    }

    private suspend fun work(database: MusicDatabase) {
        var renamedSinceSweep = false
        for (id in queue) {
            try {
                if (!ArtistCreditEnricher.enabled) continue
                val checked = runCatching { refresh(database, id) }
                    .onFailure { Timber.tag(TAG).w(it, "refresh failed for $id") }
                    .getOrDefault(false)
                // Remembered either way, so an artist that cannot be checked - a deleted channel,
                // a name search finds nothing for - is not asked about on every scroll. A failure
                // is retried sooner than a success is re-checked.
                val next = System.currentTimeMillis() + if (checked) RECHECK_MS else RETRY_FAILED_MS
                checks?.edit()?.putLong(id, next)?.apply()
                renamedSinceSweep = renamedSinceSweep || checked
                delay(GAP_MS)
            } finally {
                pending.remove(id)
            }
            // A batch is done when nothing more is waiting: fold together whatever now shares a name.
            if (renamedSinceSweep && pending.isEmpty()) {
                runCatching { ArtistCreditEnricher.mergeDuplicates(database) }
                    .onFailure { Timber.tag(TAG).w(it, "duplicate sweep failed") }
                renamedSinceSweep = false
            }
        }
    }

    /** Checks one artist. True when YouTube answered, whatever it said. */
    private suspend fun refresh(database: MusicDatabase, id: String): Boolean {
        val artist = database.artistById(id) ?: return true
        if (artist.isLocal) return true

        if (artist.id.startsWith("UC") || artist.id.startsWith("MPLAUC")) {
            val ownId = artist.id.normalizeArtistId()
            val page = YouTube.artist(ownId).getOrNull() ?: return false
            val pageId = page.artist.id.normalizeArtistId()

            // Whose songs this page holds: each credited channel and how often.
            val credits = page.sections.flatMap { it.items }.filterIsInstance<SongItem>()
                .flatMap { it.artists }
                .filter { it.id?.normalizeArtistId()?.startsWith("UC") == true }
            val counts = credits.groupingBy { it.id!!.normalizeArtistId() }.eachCount()
            val own = counts[ownId] ?: 0
            val isTopic = TOPIC_SUFFIX.containsMatchIn(page.artist.title)

            // One artist, two real channels with the same name - i-dle has two, both titled
            // "i-dle", as does LE SSERAFIM; a Topic channel is the same thing with a suffix. The
            // sweep rightly will not merge two real channels on a name alone, so the page decides:
            // a channel whose songs are mostly credited to its namesake is that namesake's alias,
            // and is folded into it. Two channels that each credit themselves (two different bands
            // that share a name) stay apart.
            //
            // Only on positive evidence. A page with no songs on it used to count as an alias too,
            // and an empty page is what a channel whose songs sit on album shelves, or one that
            // only posts videos, looks like - so a real band was folded into a namesake for having
            // nothing to show here. A Topic channel with no songs of its own is still merged, being
            // auto-generated by definition, but only into a sibling whose name is its own.
            val key = ArtistCreditEnricher.artistKey(page.artist.title).ifEmpty { ArtistCreditEnricher.artistKey(artist.name) }
            val siblings = database.allArtistsOnce().filter {
                it.id != artist.id && !it.isLocal && it.isYouTubeArtist &&
                    it.id.normalizeArtistId() != ownId &&
                    (ArtistCreditEnricher.artistKey(it.name) == key || ArtistCreditEnricher.artistKey(it.name) == ArtistCreditEnricher.artistKey(artist.name))
            }
            if (siblings.isNotEmpty()) {
                val credited = siblings.maxByOrNull { counts[it.id.normalizeArtistId()] ?: 0 }!!
                val creditedCount = counts[credited.id.normalizeArtistId()] ?: 0
                val target = when {
                    creditedCount > own -> credited
                    own == 0 && isTopic && page.artist.title.namesAnArtist() ->
                        siblings.filter { ArtistCreditEnricher.artistKey(it.name) == key }
                            .maxByOrNull { database.songCountForArtistOnce(it.id) }
                    else -> null
                }
                if (target != null) {
                    Timber.tag(TAG).d("\"${artist.name}\" ($ownId) is an alias of ${target.id} (credits $own vs $creditedCount) - merging")
                    ArtistCreditEnricher.mergeArtistInto(database, artist, target.id)
                    return true
                }
            }

            // A Topic channel whose artist is not in the library yet: its songs name the artist.
            // Only a credit carrying the Topic channel's own name, though. The most-credited
            // channel on the page is otherwise as likely to be a frequent collaborator as the
            // artist, and merging into them filed every one of this artist's songs under someone
            // else.
            if (isTopic && page.artist.title.namesAnArtist()) {
                val ownName = ArtistCreditEnricher.artistKey(page.artist.title)
                val nameOf = credits.associate { it.id!!.normalizeArtistId() to it.name }
                val main = counts
                    .filterKeys { it != ownId && ArtistCreditEnricher.artistKey(nameOf[it].orEmpty()) == ownName }
                    .maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
                if (main != null) {
                    val name = nameOf[main].orEmpty()
                    Timber.tag(TAG).d("\"${artist.name}\" ($ownId) is a Topic channel of $main - merging")
                    database.insertOrFillThumbnail(ArtistEntity(id = main, name = name.ifBlank { artist.name }))
                    ArtistCreditEnricher.mergeArtistInto(database, artist, main)
                    return true
                }
            }

            if (pageId.startsWith("UC") && pageId != ownId) {
                // Redirected: this channel id now opens a different channel. That one is the
                // artist; this row is a stale alias of it.
                Timber.tag(TAG).d("\"${artist.name}\" ($ownId) now opens $pageId - moving its credits")
                database.insertOrFillThumbnail(
                    ArtistEntity(
                        id = pageId,
                        name = page.artist.title.stripTopicSuffix().ifBlank { artist.name },
                        thumbnailUrl = page.artist.thumbnail?.resize(544, 544),
                    )
                )
                database.artistById(pageId)?.let { database.update(it, page) }
                ArtistCreditEnricher.mergeArtistInto(database, artist, pageId)
            } else {
                if (page.artist.title.stripTopicSuffix() != artist.name) {
                    Timber.tag(TAG).d("\"${artist.name}\" is now \"${page.artist.title}\"")
                }
                // Onto the row as it is now, not as it was read before the page fetch: a bookmark
                // toggled in the meantime would otherwise be written straight back off it.
                database.artistById(artist.id)?.let { database.update(it, page) }
            }
            return true
        }

        if (!artist.isYouTubeArtist) {
            // A placeholder: no channel was ever matched to this name. Search finds it or not.
            // A search that failed is retried sooner than one that found nothing - see work().
            val resolved = when (val found = ArtistCreditEnricher.lookUpArtist(artist.name)) {
                is ArtistCreditEnricher.Lookup.Found -> found.item
                ArtistCreditEnricher.Lookup.NotFound -> return true
                ArtistCreditEnricher.Lookup.Unknown -> return false
            }
            val channelId = resolved.id.normalizeArtistId()
            if (!channelId.startsWith("UC")) return true
            database.insertOrFillThumbnail(
                ArtistEntity(
                    id = channelId,
                    name = resolved.title.stripTopicSuffix().ifBlank { artist.name },
                    thumbnailUrl = resolved.thumbnail,
                )
            )
            Timber.tag(TAG).d("placeholder \"${artist.name}\" is channel $channelId - merging")
            ArtistCreditEnricher.mergeArtistInto(database, artist, channelId)
        }
        return true
    }
}
