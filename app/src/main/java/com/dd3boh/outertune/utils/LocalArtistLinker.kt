/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import com.dd3boh.outertune.constants.LinkLocalArtistsKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.TOPIC_SUFFIX
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.stripTopicSuffix
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Links artists from local files to their YouTube Music channel - the same as the desktop's
 * ArtistLinks, and under [ArtistCreditEnricher]'s rule: nothing is linked unless a search confirms
 * a real artist (exact name, a channel with a picture, the artist's own over a Topic one).
 *
 * A link moves the local artist's songs onto the real channel's artist row, so the artist page,
 * the picture and the library all treat them as one artist with their streamed songs. A rescan
 * that brings the placeholder back is relinked offline, by name, against the row already there.
 *
 * Names with no artist behind them are remembered for a month in a small file beside the
 * database, so a library is not searched again on every scan; names unlinked by hand are
 * remembered for good.
 */
object LocalArtistLinker {
    private const val TAG = "LocalArtistLinker"
    private const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000
    private const val NEVER = Long.MAX_VALUE

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    /** After a scan: links in the background, unless switched off or already running. */
    fun schedule(context: Context, database: MusicDatabase) {
        val app = context.applicationContext
        if (!app.dataStore.get(LinkLocalArtistsKey, true)) return
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                linkAll(app, database)
            } catch (e: Exception) {
                reportException(e)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun linkAll(context: Context, database: MusicDatabase) = withContext(Dispatchers.IO) {
        val misses = Misses(context)
        var searched = 0
        for (local in database.allLocalArtists()) {
            if (local.isYouTubeArtist || local.name.isBlank()) continue
            val k = key(local.name)
            // Unlinked by hand: never linked again automatically, not even offline. Checked first
            // because the name lookup below needs no search - so "Unlink local files" used to last
            // only until the next scan found the channel row still in the library and relinked it.
            if (misses.unlinkedByHand(k)) continue
            // Already in the library as a real artist: linked without asking anyone.
            val target = database.remoteArtistByNameIgnoreCase(local.name)
                ?: run {
                    if (misses.blocks(k)) return@run null
                    val outcome = search(local.name)
                    searched++
                    if (searched > 1) delay(250)
                    when (outcome) {
                        is Outcome.Found -> ArtistEntity(
                            id = outcome.item.id,
                            name = outcome.item.title.stripTopicSuffix(),
                            thumbnailUrl = outcome.item.thumbnail,
                        ).also { database.insert(it) }
                        Outcome.NotFound -> { misses.mark(k, System.currentTimeMillis()); null }
                        Outcome.Failed -> null
                    }
                }
                ?: continue
            merge(database, local, target)
            Timber.tag(TAG).d("linked local \"${local.name}\" -> ${target.id}")
        }
        misses.save()

        // Featured artists in local titles, which playback never enriches.
        database.localSongIdsWithFeaturing().forEach { ArtistCreditEnricher.enrich(database, it) }
    }

    /**
     * Moves [local]'s credits onto [target] and drops the placeholder - through the enricher's
     * merge, so it cannot interleave with an enrich run rewriting the same songs.
     */
    private fun merge(database: MusicDatabase, local: ArtistEntity, target: ArtistEntity) =
        ArtistCreditEnricher.mergeArtistInto(database, local, target.id)

    /** Links [local] to [channel] by hand - from the artist menu. */
    suspend fun linkByHand(context: Context, database: MusicDatabase, local: ArtistEntity, channel: ArtistItem) =
        withContext(Dispatchers.IO) {
            val target = database.artistById(channel.id)
                ?: ArtistEntity(channel.id, channel.title.stripTopicSuffix(), channel.thumbnail).also { database.insert(it) }
            Misses(context).apply { forget(key(local.name)); save() }
            Unit
            merge(database, local, target)
        }

    /**
     * Puts the local songs credited to [artist] back under a local artist of the same name, and
     * stops that name being linked automatically again.
     */
    suspend fun unlinkLocal(context: Context, database: MusicDatabase, artist: ArtistEntity) = withContext(Dispatchers.IO) {
        val placeholder = ArtistEntity(ArtistEntity.generateArtistId(), artist.name, isLocal = true)
        database.runInTransaction {
            database.insert(placeholder)
            database.moveLocalSongCredits(artist.id, placeholder.id)
            database.safeDeleteArtist(placeholder.id)
        }
        Misses(context).apply { mark(key(artist.name), NEVER); save() }
        Unit
    }

    /** Artist search for linking by hand. */
    suspend fun candidates(query: String): List<ArtistItem> = withContext(Dispatchers.IO) {
        YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items.orEmpty()
            .filterIsInstance<ArtistItem>()
    }

    private sealed interface Outcome {
        data class Found(val item: ArtistItem) : Outcome
        data object NotFound : Outcome
        data object Failed : Outcome
    }

    private suspend fun search(name: String): Outcome {
        val result = YouTube.search(name, YouTube.SearchFilter.FILTER_ARTIST).getOrNull() ?: return Outcome.Failed
        val target = key(name)
        val candidates = result.items.filterIsInstance<ArtistItem>()
            .filter { key(it.title.stripTopicSuffix()) == target && !it.thumbnail.isNullOrBlank() }
        val pick = candidates.firstOrNull { !TOPIC_SUFFIX.containsMatchIn(it.title) } ?: candidates.firstOrNull()
        return pick?.let { Outcome.Found(it) } ?: Outcome.NotFound
    }

    /** Names searched with no artist found, or unlinked by hand: name key -> when (or [NEVER]). */
    private class Misses(context: Context) {
        private val file = File(context.filesDir, "artist_link_misses.json")
        private val map: MutableMap<String, Long> = runCatching {
            val json = JSONObject(file.readText())
            json.keys().asSequence().associateWith { json.getLong(it) }.toMutableMap()
        }.getOrDefault(mutableMapOf())

        fun blocks(key: String): Boolean {
            val at = map[key] ?: return false
            return at == NEVER || System.currentTimeMillis() - at < RECHECK_MS
        }

        fun unlinkedByHand(key: String): Boolean = map[key] == NEVER

        fun mark(key: String, at: Long) { map[key] = at }
        fun forget(key: String) { map.remove(key) }

        fun save() {
            runCatching { file.writeText(JSONObject(map as Map<*, *>).toString()) }
        }
    }
}
