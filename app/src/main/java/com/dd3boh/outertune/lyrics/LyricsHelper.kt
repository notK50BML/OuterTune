package com.dd3boh.outertune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.dd3boh.betterlyrics.TTMLParser
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.LyricsFetchMode
import com.dd3boh.outertune.constants.LyricsFetchModeKey
import com.dd3boh.outertune.constants.LyricsProviderOrderKey
import com.dd3boh.outertune.constants.LyricsRefreshInterval
import com.dd3boh.outertune.constants.LyricsRefreshIntervalKey
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.utils.cleanLyrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Role of a remote fetch, used to correlate log lines when several fetches run for different songs.
 */
enum class LyricsFetchRole(val log: String) {
    CURRENT("current"),
    PREFETCH("prefetch"),
    MANUAL("manual"),
}

@Singleton
class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    private val lyricsProviders = REMOTE_LYRICS_PROVIDERS
    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)

    /**
     * Per-videoId mutexes that make [fetchAndStoreRemote] single-flight: at most one fetch runs for a
     * given videoId at a time, and each fetch re-checks the database under the lock before starting.
     * Entries are never removed, so the map grows by the number of songs fetched during the process
     * lifetime; it is released when the process ends.
     */
    private val fetchMutexes = HashMap<String, Mutex>()
    private val fetchMutexesGuard = Mutex()

    private suspend fun fetchMutexFor(videoId: String): Mutex =
        fetchMutexesGuard.withLock { fetchMutexes.getOrPut(videoId) { Mutex() } }

    /**
     * VideoIds already given one automatic refetch after their stored lyrics failed to parse for
     * display (see [resolveForDisplay]). One extra attempt per song per process lifetime is enough
     * to recover from a transient bad response; without this guard, a provider that keeps returning
     * content nothing here can read would be retried on every single recomposition instead of once.
     */
    private val autoRefetchedOnParseFailure = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Resolve lyrics for a song from the stored database row, the local .lrc file and the remote
     * providers, in an order controlled by the source preference (LyricSourcePrefKey): when local
     * lyrics are preferred the local file is tried first; otherwise the stored row wins and
     * LYRICS_NOT_FOUND resolves to null. When neither source has lyrics, a remote fetch runs and
     * the freshly stored result is returned, falling back to the local file when remote lyrics
     * are preferred but none are found.
     *
     * @param mediaMetadata song to resolve lyrics for
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? {
        val parserOptions = getParserOptions()
        val prefLocal = isLocalPreferred()

        val dbLyrics = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        val hasPositive = dbLyrics != null && dbLyrics != LYRICS_NOT_FOUND
        if (hasPositive && !prefLocal) {
            return parseResilient(dbLyrics, parserOptions)
        }

        val localLyrics: SemanticLyrics? = getLocalLyrics(mediaMetadata, parserOptions)

        // fallback to secondary provider when primary is unavailable
        if (prefLocal) {
            if (localLyrics != null) {
                return localLyrics
            }
            if (hasPositive) {
                return parseResilient(dbLyrics, parserOptions)
            }
        }

        // No usable positive cache in the preferred source. Fetch only when there is no row or the
        // negative cache is stale/invalid; a fresh negative cache is not re-fetched. LYRICS_NOT_FOUND is
        // never treated as a plain "row exists".
        // A stored "not found" is also checked again once a session: showing no lyrics is the moment
        // to look again, rather than trusting a miss up to a week old for a song whose lyrics have
        // since been added.
        val recheckMiss = dbLyrics == LYRICS_NOT_FOUND && missesRechecked.add(mediaMetadata.id)
        if (recheckMiss || shouldFetch(mediaMetadata.id)) {
            fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.MANUAL, forceRefresh = recheckMiss)
            val fetched = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
            if (fetched != null && fetched != LYRICS_NOT_FOUND) {
                return parseResilient(fetched, parserOptions)
            }
        }
        return if (!prefLocal) localLyrics else null
    }

    /**
     * Whether a remote fetch should run for [videoId] right now: true when there is no row, when
     * [forceRefresh] is set, or when the stored row is a negative cache that is stale, was written under
     * a different provider configuration, or predates the signature columns. A positive cache is kept.
     */
    suspend fun shouldFetch(videoId: String, forceRefresh: Boolean = false): Boolean {
        val entity = database.lyrics(videoId).first()
        val signature = ProviderSelection.snapshot(context, lyricsProviders).signature
        return shouldFetchLyrics(entity, signature, System.currentTimeMillis(), forceRefresh)
    }

    /**
     * Resolve lyrics for [mediaMetadata] from the remote providers and store the outcome.
     *
     * Single-flight per videoId: runs under a per-videoId lock and re-checks [shouldFetchLyrics] under
     * the lock so a concurrent fetch that already resolved this song is not repeated. A usable result
     * (Found) is stored with its provider and metadata, and a unanimous absence (DefinitiveNotFound) is
     * stored as a negative cache; Indeterminate and Skipped leave any existing row untouched, so a
     * transient failure never becomes a persistent negative cache, even with [forceRefresh].
     *
     * @param role which caller started this fetch, used only for log correlation
     */
    suspend fun fetchAndStoreRemote(
        mediaMetadata: MediaMetadata,
        role: LyricsFetchRole,
        forceRefresh: Boolean = false,
    ) {
        fetchMutexFor(mediaMetadata.id).withLock {
            try {
                // The enabled providers and their signature are pinned once here so the search set, the
                // all-NotFound decision and the stored signature all use the same snapshot.
                val selection = ProviderSelection.snapshot(context, lyricsProviders)
                val existing = database.lyrics(mediaMetadata.id).first()
                val positiveTtl = readLyricsRefreshInterval(context).days * DAY_MS
                if (!shouldFetchLyrics(
                        existing,
                        selection.signature,
                        System.currentTimeMillis(),
                        forceRefresh,
                        positiveTtlMs = positiveTtl,
                    )
                ) {
                    return
                }
                val result = getRemoteLyrics(mediaMetadata, role, selection)
                val now = System.currentTimeMillis()
                val entity = when (result) {
                    is RemoteLyricsResult.Found ->
                        // Bound here rather than asserted below: the downgrade test already implies
                        // a stored row, and tying the two together means a later edit cannot leave
                        // a !! standing on an invariant it no longer holds.
                        existing?.takeIf { isDowngrade(it, result) }?.let { kept ->
                            // A scheduled re-check that came back with less than is already
                            // stored. The whole point of re-checking is that providers improve;
                            // replacing word-timed lyrics with a plain text sheet because today's
                            // race went differently is the one outcome that would make the feature
                            // worse than not having it. Keep what is there and note the check, so
                            // the next one is a interval away rather than immediate.
                            Log.d(TAG, "kept existing: videoId=${mediaMetadata.id} role=${role.log} refresh returned less than stored")
                            kept.copy(lastCheckedAt = now)
                        } ?: run {
                            LyricsEntity(
                                id = mediaMetadata.id,
                                lyrics = result.raw,
                                provider = result.provider,
                                lastCheckedAt = now,
                                providerSignature = selection.signature,
                            )
                        }

                    RemoteLyricsResult.DefinitiveNotFound ->
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = LYRICS_NOT_FOUND,
                            provider = null,
                            lastCheckedAt = now,
                            providerSignature = selection.signature,
                        )

                    RemoteLyricsResult.Indeterminate, RemoteLyricsResult.Skipped -> null
                }
                if (entity != null) {
                    withContext(Dispatchers.IO) {
                        database.upsert(entity)
                    }
                    Log.d(TAG, "saved: videoId=${mediaMetadata.id} role=${role.log} provider=${(result as? RemoteLyricsResult.Found)?.provider ?: "NOT_FOUND"}")
                } else {
                    Log.d(TAG, "not saved: videoId=${mediaMetadata.id} role=${role.log} result=${result::class.simpleName}")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "cancelled: videoId=${mediaMetadata.id} role=${role.log}")
                throw e
            }
        }
    }

    /**
     * Parse [raw] into [SemanticLyrics], with a fallback for TTML the strict parser rejects.
     *
     * OuterTune's own TTML reader is a strict TTML2 implementation: among other things it requires a
     * `<head>` element immediately after `<tt>`. A good number of BetterLyrics responses do not have
     * one, so the strict parser throws and — because the shared parser options carry an errorText —
     * the failure was being turned into an "Unable to parse lyrics" placeholder and, on the fetch
     * path, into an UNPARSEABLE classification that discarded the result entirely. That is why
     * BetterLyrics appeared not to work at all.
     *
     * So: try the strict parsers first with errorText suppressed; otherwise re-read the TTML
     * leniently and hand the strict parser Enhanced LRC instead. The lenient re-read keeps the
     * word timings it found — they survive the round trip as `<mm:ss.cc>` sync points — so a
     * document only this parser can read still renders word by word rather than dropping to
     * line-level.
     */
    private fun parseResilient(raw: String, parserOptions: LrcUtils.LrcParserOptions): SemanticLyrics? {
        val strictOptions = parserOptions.copy(errorText = null)
        val isTtml = TTMLParser.looksLikeTtml(raw)

        // Cleaned on the way to the parser, not only on the way to the database.
        //
        // The fetch path already cleans what it stores, which covers everything fetched from now
        // on and nothing else: a row written before that existed keeps its `[Music]` and its `>>`
        // forever, and a local .lrc file never went through the fetch path at all. Both of those
        // are what someone actually looking at the screen sees. Doing it here instead covers every
        // source and every vintage, and costs nothing twice over - the cleanup is idempotent, so
        // running it again on an already-clean row changes nothing.
        //
        // Not on TTML, though. It is markup, and the cleanup works on lines of text - the
        // `>`-at-the-start rule in particular would eat the start of any wrapped tag. The lenient
        // path below converts it to LRC first, and that is where it gets cleaned.
        val text = if (isTtml) raw else cleanLyrics(raw)

        runCatching { LrcUtils.parseLyrics(text, null, strictOptions, null) }
            .getOrNull()
            ?.let { return it }

        if (isTtml) {
            TTMLParser.ttmlToLrc(raw)?.let { lrc ->
                val cleanedLrc = cleanLyrics(lrc)
                runCatching {
                    LrcUtils.parseLyrics(cleanedLrc, null, strictOptions, LrcUtils.LyricFormat.LRC)
                }.getOrNull()?.let {
                    Log.d(TAG, "recovered TTML via lenient parser (${cleanedLrc.count { c -> c == '\n' } + 1} lines)")
                    return it
                }
            }
        }

        // Nothing could read it. Fall back to the original call so callers that asked for an error
        // placeholder still get one.
        return runCatching { LrcUtils.parseLyrics(text, null, parserOptions, null) }.getOrNull()
    }

    /**
     * Resolve a stored lyrics row for display via [parseResilient] - the same lenient recovery a
     * fetch already used to decide this content was WORD_SYNCED/SYNCED in the first place (the
     * classifyFound check inside getRemoteLyrics). The display path used to call the strict-only
     * parser directly instead, so content a fetch had just judged usable could still show "Unable
     * to parse lyrics" the moment it was actually displayed - the fetch and the display were
     * reading the exact same string with two different levels of leniency.
     *
     * When even [parseResilient] can't read it, this kicks off exactly one automatic refetch for
     * [mediaMetadata]'s id per process lifetime (see [autoRefetchedOnParseFailure]) before falling
     * back to the error placeholder - a different provider, or the same one on a second attempt,
     * may well succeed where the stored content didn't. The refetch runs to completion here rather
     * than being fired in the background: its own DB write, if any, reaches the caller through the
     * normal reactive flow this is already invoked from (a new emission re-runs this function with
     * the fresh row), not through this call's return value.
     */
    suspend fun resolveForDisplay(mediaMetadata: MediaMetadata, raw: String): SemanticLyrics? {
        val parserOptions = getParserOptions()
        parseResilient(raw, parserOptions.copy(errorText = null))?.let { return it }

        if (autoRefetchedOnParseFailure.add(mediaMetadata.id)) {
            Log.w(TAG, "unparseable, forcing one refetch: videoId=${mediaMetadata.id}")
            fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.CURRENT, forceRefresh = true)
        }
        return runCatching { LrcUtils.parseLyrics(raw, null, parserOptions, null) }.getOrNull()
    }

    /**
     * Read the lyric parsing preferences (trim / multiline) shared by all resolution paths
     */
    suspend fun getParserOptions(): LrcUtils.LrcParserOptions {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)
        return LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics")
    }

    suspend fun isLocalPreferred(): Boolean = context.dataStore.get(LyricSourcePrefKey, true)

    /**
     * Run a single provider lookup behind the isolation boundary for the parallel fetch path. A
     * provider that honours the contract returns [LyricsFetchResult]; one that throws instead has its
     * exception normalized to [LyricsFetchResult.Failed]. Cancellation is always re-thrown.
     */
    private suspend fun LyricsProvider.fetchIsolated(
        mediaMetadata: MediaMetadata,
        artistName: String,
    ): LyricsFetchResult =
        try {
            getLyrics(mediaMetadata.id, mediaMetadata.title, artistName, mediaMetadata.duration)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LyricsFetchResult.Failed(e)
        }

    /**
     * Run one provider lookup with a timeout, and log how it went.
     *
     * A timeout is reported as [LyricsFetchResult.Failed] rather than an absence, so a provider that
     * was merely slow can never contribute to a negative cache.
     */
    private suspend fun runProvider(
        provider: LyricsProvider,
        mediaMetadata: MediaMetadata,
        artistName: String,
        role: LyricsFetchRole,
        timeoutMs: Long,
    ): LyricsFetchResult {
        val t0 = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) { provider.fetchIsolated(mediaMetadata, artistName) }
        val elapsed = System.currentTimeMillis() - t0
        return when (result) {
            null -> {
                Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: timeout after ${timeoutMs}ms")
                LyricsFetchResult.Failed()
            }

            is LyricsFetchResult.Found -> {
                Log.d(TAG, "${provider.name} SUCCESS in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}")
                result
            }

            LyricsFetchResult.NotFound -> {
                Log.d(TAG, "${provider.name} NOT_FOUND in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}")
                result
            }

            is LyricsFetchResult.Failed -> {
                Log.d(TAG, "${provider.name} FAILURE in ${elapsed}ms videoId=${mediaMetadata.id} role=${role.log}: ${result.cause?.message}")
                result
            }
        }
    }

    /**
     * Lookup lyrics from remote providers.
     *
     * In [LyricsFetchMode.AUTO] every provider in [selection] runs at once and results are judged as
     * they arrive: the first synced result wins immediately and the remaining providers are
     * cancelled. In [LyricsFetchMode.MANUAL] the providers are tried one at a time in the user's
     * order and the first synced result wins; nothing is raced, because the whole point of an order
     * is that the earlier entries are preferred even when a later one would have answered sooner. In
     * both modes an unsynced result is kept as a fallback and used only if no synced result turns up.
     *
     * Each provider has an individual timeout, and the resolution as a whole is bounded: by a fixed
     * cap when racing, and by a per-provider budget when going in order, since a fixed cap there
     * would quietly mean the last entries in the user's order never get asked at all.
     *
     * The possible outcomes are: [RemoteLyricsResult.Found] when a usable result was adopted,
     * [RemoteLyricsResult.DefinitiveNotFound] only when every provider reported a definitive absence,
     * [RemoteLyricsResult.Indeterminate] when no usable result was found but at least one provider failed,
     * never reported, or returned an unparseable result, and [RemoteLyricsResult.Skipped] when no
     * provider was enabled.
     */
    private suspend fun getRemoteLyrics(
        mediaMetadata: MediaMetadata,
        role: LyricsFetchRole,
        selection: ProviderSelection,
    ): RemoteLyricsResult {
        val artistName = mediaMetadata.artists
            .filter { it.id != null }
            .joinToString { it.name.stripTopicSuffix() }
            .ifEmpty { mediaMetadata.artists.joinToString { it.name } }
        val start = System.currentTimeMillis()
        Log.d(TAG, "start: videoId=${mediaMetadata.id} role=${role.log} title=\"${mediaMetadata.title}\" artist=\"${artistName}\"")

        lyricsProviders.filterNot { it in selection.providers }.forEach { provider ->
            Log.d(TAG, "${provider.name} SKIPPED (disabled) videoId=${mediaMetadata.id} role=${role.log}")
        }
        val enabled = selection.providers
        if (enabled.isEmpty()) {
            Log.d(TAG, "end: skipped videoId=${mediaMetadata.id} role=${role.log} total=0ms (no enabled providers)")
            return RemoteLyricsResult.Skipped
        }

        // errorText = null so adoption sees an unparseable input as null/exception rather than a
        // synthesized UnsyncedLyrics; the user-facing errorText is only used by the display path.
        val verifyOptions = getParserOptions().copy(errorText = null)

        val aggregator = RemoteLyricsAggregator(enabled.size, enabled.map { it.name })
        // Classify a Found result for adoption. errorText = null means an unparseable input surfaces
        // as null or an exception rather than a synthesized UnsyncedLyrics.
        val classifyFound: (String) -> FoundKind = { raw ->
            val parsed = try {
                parseResilient(raw, verifyOptions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (parsed) {
                is SemanticLyrics.SyncedLyrics ->
                    if (parsed.text.any { !it.words.isNullOrEmpty() }) FoundKind.WORD_SYNCED
                    else FoundKind.SYNCED

                is SemanticLyrics.UnsyncedLyrics -> FoundKind.UNSYNCED
                null -> FoundKind.UNPARSEABLE
            }
        }
        val deadline = start + when (selection.mode) {
            LyricsFetchMode.AUTO -> OVERALL_TIMEOUT_MS
            LyricsFetchMode.MANUAL -> enabled.size * MANUAL_TIMEOUT_PER_PROVIDER_MS
        }

        when (selection.mode) {
            LyricsFetchMode.AUTO ->
                raceProviders(enabled, mediaMetadata, artistName, role, deadline, aggregator, classifyFound)

            LyricsFetchMode.MANUAL ->
                askProvidersInOrder(enabled, mediaMetadata, artistName, role, deadline, aggregator, classifyFound)
        }

        val totalMs = System.currentTimeMillis() - start
        val result = aggregator.result()
        when (result) {
            is RemoteLyricsResult.Found ->
                Log.d(TAG, "adopted: videoId=${mediaMetadata.id} role=${role.log} provider=${result.provider} synced=${result.synced} mode=${selection.mode} total=${totalMs}ms")

            RemoteLyricsResult.DefinitiveNotFound ->
                Log.d(TAG, "end: not found videoId=${mediaMetadata.id} role=${role.log} mode=${selection.mode} total=${totalMs}ms")

            RemoteLyricsResult.Indeterminate ->
                Log.d(TAG, "end: indeterminate videoId=${mediaMetadata.id} role=${role.log} mode=${selection.mode} total=${totalMs}ms")

            RemoteLyricsResult.Skipped -> {} // handled above
        }
        return result
    }

    /**
     * [LyricsFetchMode.AUTO]: start every provider at once and fold outcomes into [aggregator] as
     * they arrive, stopping as soon as one is adopted.
     */
    private suspend fun raceProviders(
        providers: List<LyricsProvider>,
        mediaMetadata: MediaMetadata,
        artistName: String,
        role: LyricsFetchRole,
        deadline: Long,
        aggregator: RemoteLyricsAggregator,
        classifyFound: (String) -> FoundKind,
    ) = coroutineScope {
        val channel = Channel<ProviderOutcome>(Channel.UNLIMITED)
        val fetchJobs = providers.map { provider ->
            launch {
                val outcome = runProvider(provider, mediaMetadata, artistName, role, PROVIDER_TIMEOUT_MS)
                channel.send(ProviderOutcome(provider.name, outcome))
            }
        }
        // Close the channel once every provider has reported so exhaustion is detected promptly
        // instead of waiting for the overall timeout.
        val closer = launch {
            fetchJobs.joinAll()
            channel.close()
        }

        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val outcome = withTimeoutOrNull(remaining) {
                    channel.receiveCatching().getOrNull()
                } ?: break // overall timeout, or every provider has reported
                // A synced result was adopted: stop and cancel the remaining providers.
                if (aggregator.offer(outcome.providerName, outcome.result, classifyFound)) break
            }
        } finally {
            fetchJobs.forEach { it.cancel() }
            closer.cancel()
            channel.close()
        }
    }

    /**
     * [LyricsFetchMode.MANUAL]: ask [providers] one at a time in the given order and stop at the
     * first adopted result.
     *
     * A provider that cannot be asked before [deadline] simply never reports, which leaves the
     * outcome [RemoteLyricsResult.Indeterminate] rather than a negative cache — the correct answer,
     * since the providers further down the order were never given the chance to say otherwise.
     */
    private suspend fun askProvidersInOrder(
        providers: List<LyricsProvider>,
        mediaMetadata: MediaMetadata,
        artistName: String,
        role: LyricsFetchRole,
        deadline: Long,
        aggregator: RemoteLyricsAggregator,
        classifyFound: (String) -> FoundKind,
    ) {
        for (provider in providers) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                Log.d(TAG, "${provider.name} SKIPPED (out of time) videoId=${mediaMetadata.id} role=${role.log}")
                continue
            }
            val outcome = runProvider(
                provider, mediaMetadata, artistName, role,
                timeoutMs = minOf(PROVIDER_TIMEOUT_MS, remaining),
            )
            if (aggregator.offer(provider.name, outcome, classifyFound)) break
        }
    }

    /**
     * Lookup lyrics from local disk (.lrc) file
     */
    fun getLocalLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        if (LocalLyricsProvider.isEnabled(context) && mediaMetadata.localPath != null) {
            return LocalLyricsProvider.getLyricsNew(
                mediaMetadata.localPath,
                parserOptions
            )
        }

        return null
    }

    /**
     * Run a single provider's candidate search behind the manual-search isolation boundary. Each
     * provider is tried even if an earlier one threw: a contract-breaking exception is swallowed (after
     * re-throwing cancellation) so the sequential search continues to the next provider and any
     * candidates already delivered by callback are kept.
     */
    private suspend fun LyricsProvider.searchIsolated(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        try {
            getAllLyrics(mediaId, songTitle, songArtists, duration, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportException(e)
        }
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }
        val allResult = mutableListOf<LyricsResult>()
        // Same order the fetch path uses, so the candidates are listed the way the user ranked them.
        orderProviders(lyricsProviders, readProviderOrder(context)).forEach { provider ->
            if (provider.isEnabled(context)) {
                provider.searchIsolated(mediaId, songTitle, songArtists, duration) { lyrics ->
                    val result = LyricsResult(provider.name, lyrics)
                    allResult += result
                    callback(result)
                }
            }
        }
        cache.put(cacheKey, allResult)
    }

    companion object {
        private const val TAG = "LyricsHelper"
        private const val MAX_CACHE_SIZE = 3

        /** Per-provider timeout for a single remote lookup. */
        private const val PROVIDER_TIMEOUT_MS = 8000L

        /** Upper bound for resolving lyrics across all providers of a single song, when racing them. */
        private const val OVERALL_TIMEOUT_MS = 12000L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Budget per provider when going through them in order. The overall bound is this times the
         * number of enabled providers rather than a fixed wall, because a fixed wall would silently
         * mean the last few entries in the user's order are never asked.
         */
        private const val MANUAL_TIMEOUT_PER_PROVIDER_MS = PROVIDER_TIMEOUT_MS + 1000L
    }
}

/** Songs whose stored "not found" has already been checked again this session - see LyricsHelper.getLyrics. */
private val missesRechecked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/** Time a negative cache (LYRICS_NOT_FOUND) is trusted before a fresh remote fetch is attempted. */
const val NEGATIVE_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Pure decision for whether a remote fetch should run for one song.
 *
 * A positive cache (real lyrics) is always kept. A negative cache is re-fetched when it is stale past
 * [ttlMs], was written under a different provider [signature], predates the signature columns (null
 * fields), or the device clock moved backwards ([now] earlier than the stored timestamp). [forceRefresh]
 * and a missing row always fetch.
 */
internal fun shouldFetchLyrics(
    entity: LyricsEntity?,
    signature: String,
    now: Long,
    forceRefresh: Boolean,
    ttlMs: Long = NEGATIVE_CACHE_TTL_MS,
    /** 0 means never re-check a result that was found - see [LyricsRefreshInterval]. */
    positiveTtlMs: Long = 0L,
): Boolean {
    if (forceRefresh) return true
    if (entity == null) return true

    val lastChecked = entity.lastCheckedAt
    if (entity.lyrics != LYRICS_NOT_FOUND) {
        // A result that was found. Re-checked only when the user has asked for it, and then only
        // on age - the signature is deliberately not consulted here. Enabling a provider should
        // not throw away lyrics that are already in hand and already good; if the new provider has
        // something better, the next scheduled re-check finds it, and that is the whole difference
        // between a preference change and a cache invalidation.
        if (positiveTtlMs <= 0L) return false
        if (lastChecked == null) return true
        if (now < lastChecked) return true
        return now - lastChecked >= positiveTtlMs
    }

    if (lastChecked == null) return true
    val storedSignature = entity.providerSignature ?: return true
    if (storedSignature != signature) return true
    if (now < lastChecked) return true
    return now - lastChecked >= ttlMs
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

/**
 * Outcome reported by a single provider during parallel resolution.
 */
private data class ProviderOutcome(
    val providerName: String,
    val result: LyricsFetchResult,
)

/**
 * Snapshot of the enabled providers, their order and the fetch mode, taken once at the start of a
 * fetch. The same snapshot drives the search set, the all-NotFound decision and the stored
 * signature, so a settings change during a fetch cannot desync them.
 *
 * The signature is what stops a cached answer outliving the settings it was produced under. It
 * carries the mode and the enabled ids **in the user's order, in both modes**.
 *
 * AUTO used to sort them, on the reasoning that order could not change the answer there because
 * the providers were raced. That reasoning was correct about the code and wrong about what the
 * code should do - the race adopted whichever provider was quickest, so the preference decided
 * only who was asked. Now that order breaks ties in AUTO as well (see [RemoteLyricsAggregator]),
 * it genuinely does change the answer, and a cache produced under one order really is stale under
 * another. The cost is that reordering providers re-fetches; that is the correct cost, and it is
 * what reordering them is for.
 */
data class ProviderSelection(
    val providers: List<LyricsProvider>,
    val mode: LyricsFetchMode,
    val signature: String,
) {
    companion object {
        fun snapshot(context: Context, all: List<LyricsProvider>): ProviderSelection {
            val mode = readFetchMode(context)
            val enabled = orderProviders(all, readProviderOrder(context)).filter { it.isEnabled(context) }
            // In the user's order in both modes - see the class doc for why AUTO no longer sorts.
            val ids = enabled.map { it.id }
            return ProviderSelection(enabled, mode, "${mode.name}:${ids.joinToString(",")}")
        }
    }
}

/**
 * Whether [fresh] is worth less than what is already stored.
 *
 * Only asked on a scheduled re-check, where there is something to lose. Judged on sync level,
 * because that is the difference anyone would notice: word timings, then line timings, then plain
 * text. Two results at the same level are treated as equal rather than compared on length or
 * provider - the fresh one came from the current preference order and the stored one may not have,
 * so preferring the fresh one there is how a reordering eventually takes effect.
 *
 * A stored row that cannot be read at all is never worth keeping, whatever the fresh one is.
 */
internal fun isDowngrade(existing: LyricsEntity?, fresh: RemoteLyricsResult.Found): Boolean {
    val stored = existing?.lyrics ?: return false
    if (stored == LYRICS_NOT_FOUND || stored.isBlank()) return false

    val storedLevel = syncLevelOf(stored)
    val freshLevel = if (fresh.synced) syncLevelOf(fresh.raw) else 1
    return freshLevel < storedLevel
}

/**
 * How timed a raw lyrics document is: 3 word, 2 line, 1 neither.
 *
 * Read off the text rather than parsed, because this runs on a result that has already been
 * classified once and the only question left is coarse. A `<mm:ss.cc>` inside a line is the
 * enhanced-LRC word mark; a `[mm:ss.cc]` at the start of one is a line timestamp.
 */
private fun syncLevelOf(raw: String): Int = when {
    WORD_TIMING.containsMatchIn(raw) -> 3
    LINE_TIMING.containsMatchIn(raw) -> 2
    else -> 1
}

private val WORD_TIMING = Regex("""<\d{1,2}:\d{2}([.:]\d{1,3})?>""")
private val LINE_TIMING = Regex("""(^|\n)\s*\[\d{1,2}:\d{2}([.:]\d{1,3})?]""")

/** How often a song that already has lyrics is re-checked, as stored. */
fun readLyricsRefreshInterval(context: Context): LyricsRefreshInterval =
    context.dataStore[LyricsRefreshIntervalKey]
        ?.let { stored -> LyricsRefreshInterval.entries.find { it.name == stored } }
        ?: LyricsRefreshInterval.NEVER

/** Fetch mode as stored, falling back to [LyricsFetchMode.AUTO] for an unset or unknown value. */
fun readFetchMode(context: Context): LyricsFetchMode =
    context.dataStore[LyricsFetchModeKey]
        ?.let { stored -> LyricsFetchMode.entries.find { it.name == stored } }
        ?: LyricsFetchMode.AUTO

/** The stored provider order as a list of ids, empty when the user has never set one. */
fun readProviderOrder(context: Context): List<String> =
    (context.dataStore[LyricsProviderOrderKey] ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * [all] arranged by [order].
 *
 * Ids in [order] that name no provider are dropped and providers [order] does not mention keep
 * their built-in position relative to each other and go last. That means the preference survives a
 * provider being added, removed or renamed without ever losing one: a stored order written before a
 * new provider existed simply leaves the newcomer at the end rather than hiding it.
 */
fun orderProviders(all: List<LyricsProvider>, order: List<String>): List<LyricsProvider> {
    if (order.isEmpty()) return all
    val byId = all.associateBy { it.id }
    val ranked = order.distinct().mapNotNull { byId[it] }
    return ranked + all.filterNot { it in ranked }
}

/**
 * Aggregate outcome of resolving lyrics across every enabled provider for one song.
 */
sealed interface RemoteLyricsResult {
    data class Found(val provider: String, val raw: String, val synced: Boolean) : RemoteLyricsResult
    data object DefinitiveNotFound : RemoteLyricsResult
    data object Indeterminate : RemoteLyricsResult
    data object Skipped : RemoteLyricsResult
}

/**
 * How a [LyricsFetchResult.Found] parses when judged for adoption, best first.
 *
 * [WORD_SYNCED] is separated from [SYNCED] because it is what the word-by-word renderer needs, and
 * without the distinction it could not win: providers were raced and the first synced answer took
 * it, which in practice is whichever one is quickest, not whichever one carries word timings. A
 * song whose lyrics exist in both forms would get the line-level version essentially every time.
 */
enum class FoundKind { WORD_SYNCED, SYNCED, UNSYNCED, UNPARSEABLE }

/**
 * Accumulates provider outcomes and derives the aggregate [RemoteLyricsResult]. The rules, independent
 * of the concurrency around it: the first synced Found wins; an unsynced Found is held as a fallback; a
 * DefinitiveNotFound is reported only when every enabled provider reported a definitive NotFound (a
 * Failed, an unparseable Found, or a provider that never reported all block it, yielding Indeterminate).
 *
 * @param enabledCount number of providers that were expected to report
 */
/**
 * Folds the providers' outcomes into one answer.
 *
 * Two things decide which result wins, in this order: **how good it is**, and then **where its
 * provider sits in the user's order**.
 *
 * The second half is new, and its absence was a real complaint. In AUTO mode the providers are
 * raced, and this used to keep the first result of each quality class to arrive - `if (heldSynced
 * == null)`. Arrival order is network order, so the fastest provider won every tie and the
 * preference list only decided who was *asked*, never who was believed. A song whose lyrics all
 * three providers had would come back from whichever answered quickest, which is why the lyrics
 * were "not from the top provider" often enough to notice but not always.
 *
 * Stopping early still happens, and still costs nothing: the race ends as soon as a word-synced
 * result is in hand *and every provider ranked above it has already reported*. When the top
 * provider answers first - the common case - that condition is satisfied immediately and nothing
 * is slower than it was. When a lower-ranked one answers first, the wait is bounded by the overall
 * deadline that was already there.
 *
 * @param orderedProviderNames enabled providers, in the user's own order. Rank is the index.
 */
class RemoteLyricsAggregator(
    private val enabledCount: Int,
    private val orderedProviderNames: List<String> = emptyList(),
) {
    private var best: RemoteLyricsResult.Found? = null
    private var bestKind: FoundKind? = null
    private var bestRank = Int.MAX_VALUE
    private val reportedRanks = HashSet<Int>()
    private var notFoundCount = 0
    private var nonNotFoundCount = 0

    private fun rankOf(providerName: String): Int =
        orderedProviderNames.indexOf(providerName).takeIf { it >= 0 } ?: Int.MAX_VALUE

    /** Word timings are strictly richer than line timings, which are richer than none. */
    private fun quality(kind: FoundKind): Int = when (kind) {
        FoundKind.WORD_SYNCED -> 3
        FoundKind.SYNCED -> 2
        FoundKind.UNSYNCED -> 1
        FoundKind.UNPARSEABLE -> 0
    }

    /**
     * Fold one provider outcome into the running result.
     *
     * @return true once nothing still outstanding could improve on what is held.
     */
    fun offer(providerName: String, result: LyricsFetchResult, classifyFound: (String) -> FoundKind): Boolean {
        reportedRanks += rankOf(providerName)
        when (result) {
            // Cleaned here rather than in each provider, so every source gets the same treatment
            // through one path. Only the universally safe part runs - speaker markers and music
            // annotations - because the caption-specific line re-breaking would damage a real
            // lyrics file, which is already broken where its author meant it to be. See cleanLyrics.
            //
            // Cleaned before classification as well as before storage: an annotation-only line is
            // not evidence of lyrics, so judging the raw text could adopt a "lyric sheet" that is
            // nothing but [Music] over and over.
            is LyricsFetchResult.Found -> {
                val cleaned = cleanLyrics(result.raw)
                val kind = classifyFound(cleaned)
                if (kind == FoundKind.UNPARSEABLE) {
                    // Not a usable result, but not an absence either.
                    nonNotFoundCount++
                } else {
                    val rank = rankOf(providerName)
                    val currentKind = bestKind
                    // Better class wins outright; within a class the user's order breaks the tie.
                    // Without the second clause this is arrival order, which is network latency.
                    val better = currentKind == null ||
                        quality(kind) > quality(currentKind) ||
                        (quality(kind) == quality(currentKind) && rank < bestRank)
                    if (better) {
                        best = RemoteLyricsResult.Found(
                            providerName,
                            cleaned,
                            synced = kind == FoundKind.WORD_SYNCED || kind == FoundKind.SYNCED,
                        )
                        bestKind = kind
                        bestRank = rank
                    }
                }
            }

            LyricsFetchResult.NotFound -> notFoundCount++
            is LyricsFetchResult.Failed -> nonNotFoundCount++
        }
        return canStop()
    }

    /**
     * Whether anything still outstanding could beat what is held.
     *
     * Only a word-synced result can end the race early, because a line-synced one can still be
     * improved on by a slower provider returning word timings - which is the wait the previous
     * version already judged worth paying. And only once every provider the user ranked above it
     * has reported, since one of those could return word timings too and would then win the tie.
     */
    private fun canStop(): Boolean {
        if (bestKind != FoundKind.WORD_SYNCED) return false
        return (0 until minOf(bestRank, orderedProviderNames.size)).all { it in reportedRanks }
    }

    fun result(): RemoteLyricsResult = when {
        best != null -> best as RemoteLyricsResult.Found
        nonNotFoundCount == 0 && notFoundCount == enabledCount -> RemoteLyricsResult.DefinitiveNotFound
        else -> RemoteLyricsResult.Indeterminate
    }
}
