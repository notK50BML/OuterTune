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
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.stripTopicSuffix
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
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
        if (shouldFetch(mediaMetadata.id)) {
            fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.MANUAL)
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
                if (!shouldFetchLyrics(existing, selection.signature, System.currentTimeMillis(), forceRefresh)) {
                    return
                }
                val result = getRemoteLyrics(mediaMetadata, role, selection)
                val now = System.currentTimeMillis()
                val entity = when (result) {
                    is RemoteLyricsResult.Found ->
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = result.raw,
                            provider = result.provider,
                            lastCheckedAt = now,
                            providerSignature = selection.signature,
                        )

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

        runCatching { LrcUtils.parseLyrics(raw, null, strictOptions, null) }
            .getOrNull()
            ?.let { return it }

        if (TTMLParser.looksLikeTtml(raw)) {
            TTMLParser.ttmlToLrc(raw)?.let { lrc ->
                runCatching {
                    LrcUtils.parseLyrics(lrc, null, strictOptions, LrcUtils.LyricFormat.LRC)
                }.getOrNull()?.let {
                    Log.d(TAG, "recovered TTML via lenient parser (${lrc.count { c -> c == '\n' } + 1} lines)")
                    return it
                }
            }
        }

        // Nothing could read it. Fall back to the original call so callers that asked for an error
        // placeholder still get one.
        return runCatching { LrcUtils.parseLyrics(raw, null, parserOptions, null) }.getOrNull()
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

        val aggregator = RemoteLyricsAggregator(enabled.size)
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

        /**
         * Budget per provider when going through them in order. The overall bound is this times the
         * number of enabled providers rather than a fixed wall, because a fixed wall would silently
         * mean the last few entries in the user's order are never asked.
         */
        private const val MANUAL_TIMEOUT_PER_PROVIDER_MS = PROVIDER_TIMEOUT_MS + 1000L
    }
}

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
): Boolean {
    if (forceRefresh) return true
    if (entity == null) return true
    if (entity.lyrics != LYRICS_NOT_FOUND) return false
    val lastChecked = entity.lastCheckedAt ?: return true
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
 * carries the mode and the enabled ids; in [LyricsFetchMode.MANUAL] the ids are in the user's order,
 * because there the order changes which provider answers, while in [LyricsFetchMode.AUTO] they are
 * sorted, because there it does not and reordering should not throw away perfectly good caches.
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
            val ids = enabled.map { it.id }.let { if (mode == LyricsFetchMode.MANUAL) it else it.sorted() }
            return ProviderSelection(enabled, mode, "${mode.name}:${ids.joinToString(",")}")
        }
    }
}

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
class RemoteLyricsAggregator(private val enabledCount: Int) {
    private var adoptedWordSynced: RemoteLyricsResult.Found? = null
    private var heldSynced: RemoteLyricsResult.Found? = null
    private var heldUnsynced: RemoteLyricsResult.Found? = null
    private var notFoundCount = 0
    private var nonNotFoundCount = 0

    /**
     * Fold one provider outcome into the running result.
     *
     * @return true once a synced result has been adopted, signalling that no further outcomes are needed.
     */
    fun offer(providerName: String, result: LyricsFetchResult, classifyFound: (String) -> FoundKind): Boolean {
        when (result) {
            is LyricsFetchResult.Found -> when (classifyFound(result.raw)) {
                // Word timings are strictly richer - a line-level renderer ignores the extra marks -
                // so this is the one result worth stopping everything for.
                FoundKind.WORD_SYNCED -> {
                    if (adoptedWordSynced == null) {
                        adoptedWordSynced = RemoteLyricsResult.Found(providerName, result.raw, synced = true)
                    }
                    return true
                }

                // Held rather than adopted: a slower provider may still come back with the same
                // song in word-timed form, and that is worth the wait it costs.
                FoundKind.SYNCED -> if (heldSynced == null) {
                    heldSynced = RemoteLyricsResult.Found(providerName, result.raw, synced = true)
                }

                FoundKind.UNSYNCED -> if (heldUnsynced == null) {
                    heldUnsynced = RemoteLyricsResult.Found(providerName, result.raw, synced = false)
                }

                FoundKind.UNPARSEABLE -> nonNotFoundCount++ // not a usable result, but not an absence either
            }

            LyricsFetchResult.NotFound -> notFoundCount++
            is LyricsFetchResult.Failed -> nonNotFoundCount++
        }
        return false
    }

    fun result(): RemoteLyricsResult = when {
        adoptedWordSynced != null -> adoptedWordSynced as RemoteLyricsResult.Found
        heldSynced != null -> heldSynced as RemoteLyricsResult.Found
        heldUnsynced != null -> heldUnsynced as RemoteLyricsResult.Found
        nonNotFoundCount == 0 && notFoundCount == enabledCount -> RemoteLyricsResult.DefinitiveNotFound
        else -> RemoteLyricsResult.Indeterminate
    }
}
