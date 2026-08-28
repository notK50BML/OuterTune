package com.dd3boh.outertune.utils

import android.util.Log
import com.metrolist.innertubex.InnerTube as MetrolistInnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult as MetrolistPoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.models.YouTubeLocale as MetrolistLocale
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Stream resolution backed by MetrolistGroup/innertubex, tried ahead of [YTPlayerUtils]' own client
 * loop.
 *
 * Why a second engine rather than more repair to the first: the PoToken rules this app kept getting
 * wrong by hand - which token binds to the video, which to the session, which clients require one at
 * all - are encoded as data in innertubex's PlaybackClientCatalog and covered by its tests. It also
 * carries SABR support, which this app has none of, and which is not optional for much longer:
 * ANDROID_VR past 1.65 already answers SABR-only, so the reachable share of the fallback chain
 * shrinks on YouTube's schedule rather than ours.
 *
 * Deliberately scoped to the player path. innertubex has no metadata layer to adopt - its InnerTube
 * exposes the same raw transport as this app's own (browse/search/next/playlists) and nothing above
 * it - so search, home, library and artist pages stay on the existing, years-proven code.
 */
object MetrolistStreamResolver {
    private const val TAG = "MetrolistStream"

    private const val DEFAULT_EXPIRY_SECONDS = 21_540

    // One generator for both engines - see YTPlayerUtils.poTokenGenerator.
    private val poTokenGenerator get() = YTPlayerUtils.poTokenGenerator

    /**
     * Bridges innertubex's token contract onto this app's existing WebView minting. Which binding
     * goes where is the whole point of the contract and the two are not interchangeable: the player
     * request carries a token bound to visitorData, the stream url one bound to the video id.
     */
    private object OuterTuneTokenProvider : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): MetrolistPoTokenResult? {
            val minted = poTokenGenerator.getWebClientPoToken(videoId, visitorData) ?: return null
            return MetrolistPoTokenResult(
                playerRequestToken = minted.playerRequestPoToken,
                streamingDataToken = minted.streamingDataPoToken,
                visitorData = visitorData,
            )
        }

        override suspend fun invalidateAttestation() {
            poTokenGenerator.invalidate()
        }
    }

    // Mirrors the existing module's client configuration rather than inventing a second one, so both
    // engines fail the same way on a bad network and neither hangs without a timeout.
    private val httpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    }
                )
            }
            install(ContentEncoding) {
                gzip(0.9F)
                deflate(0.8F)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
            install(HttpRequestRetry) {
                maxRetries = 3
                retryIf { _, response -> response.status.value in 500..599 }
                retryOnExceptionIf { _, cause -> cause is IOException }
                exponentialDelay(baseDelayMs = 500)
            }
        }
    }

    private val innerTube by lazy { MetrolistInnerTube(httpClient) }

    /**
     * Cipher configs (sig, nClass, sts, aliases) for whichever player.js YouTube is currently
     * serving, pulled from MetrolistGroup/faraday - which watches for player rotations, derives the
     * configs and validates each one against a real CDN range request before publishing it.
     *
     * This is the part of signature handling that otherwise rots on YouTube's schedule: a rotation
     * invalidates hand-written configs (see the upstream "add cipher configs for current YouTube
     * players" fixes) and leaves local deobfuscation guessing until someone patches it by hand.
     * Faraday's table is the same source Metrolist itself consumes, and innertubex accepts a
     * Zemer-style aggregated table at any url ending in /player_configs.json.
     *
     * Held in memory only, so a cold start pays one small fetch before the first track resolves.
     * innertubex handles the six-hour refresh and the failure-triggered refreshes (unknown player
     * hash, CDN rejection) on top of that, each single-flight and rate-limited.
     */
    private val playerConfigRepository = object : PlayerConfigRepository {
        override val enabled = true
        override val defaultSourceUrl =
            "https://cdn.jsdelivr.net/gh/MetrolistGroup/faraday@main/registry/player_configs.json"
        override val sourceUrl get() = defaultSourceUrl

        // In-memory for now: losing it costs one fetch on next launch, not correctness.
        override var cachedJson: String = ""
        override var cachedAtMs: Long = 0L
        override var cachedSourceUrl: String = ""
        override var cachedEtag: String = ""
    }

    private val remotePlayerConfigStore by lazy {
        RemotePlayerConfigStore(httpClient, playerConfigRepository)
    }

    private val extractor by lazy {
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(httpClient, innerTube, remotePlayerConfigStore),
            cipherService = YouTubeCipherService(httpClient, remotePlayerConfigStore),
            innerTube = innerTube,
            tokenProvider = OuterTuneTokenProvider,
        )
    }

    /**
     * Copies the session this app already owns onto the second engine. Kept in one place and called
     * on every resolve: signing in, signing out and rotating visitorData all happen behind this
     * object's back, and a stale identity here would resolve urls for the wrong session.
     */
    private fun syncSession() {
        innerTube.visitorData = YouTube.visitorData
        innerTube.dataSyncId = YouTube.dataSyncId
        innerTube.cookie = YouTube.cookie
        innerTube.locale = MetrolistLocale(
            gl = YouTube.locale.gl,
            hl = YouTube.locale.hl,
        )
    }

    /**
     * Returns null - rather than throwing - on anything short of a usable audio stream, so the
     * caller falls through to the existing client loop. A second engine is only an improvement if
     * its failures are no worse than those of what it supplements.
     */
    suspend fun resolve(videoId: String, cpn: String): YTPlayerUtils.PlaybackData? {
        val extracted = try {
            syncSession()
            extractor.extract(videoId = videoId, hints = ContentHints())
        } catch (e: Exception) {
            Log.w(TAG, "[$videoId] extraction failed", e)
            null
        } ?: return null

        if (extracted.audioUrl.isBlank()) {
            Log.w(TAG, "[$videoId] extraction returned no audio url")
            return null
        }

        Log.i(TAG, "[$videoId] resolved via ${extracted.clientName} (${extracted.profileId})")
        return extracted.toPlaybackData(cpn)
    }

    private fun ExtractedStream.toPlaybackData(cpn: String): YTPlayerUtils.PlaybackData {
        // expiresAt is absolute; this app's cache still speaks in "seconds from now". Floored at
        // zero so a clock skew can only shorten how long the url is trusted, never extend it.
        val expiresInSeconds = expiresAt
            ?.let { (it.toEpochMilliseconds() - System.currentTimeMillis()) / 1000L }
            ?.coerceAtLeast(0L)
            ?.toInt()
            ?: DEFAULT_EXPIRY_SECONDS

        val format = PlayerResponse.StreamingData.Format(
            itag = itag,
            url = audioUrl,
            mimeType = mimeType ?: "audio/mp4",
            bitrate = bitrate ?: 0,
            width = null,
            height = null,
            contentLength = contentLengthBytes,
            quality = "unknown",
            fps = null,
            qualityLabel = null,
            averageBitrate = bitrate,
            audioQuality = null,
            approxDurationMs = null,
            audioSampleRate = sampleRate,
            audioChannels = null,
            loudnessDb = loudnessDb,
            lastModified = null,
            signatureCipher = null,
        )

        return YTPlayerUtils.PlaybackData(
            // Left null deliberately: the caller still makes its own MAIN_CLIENT metadata request
            // for audioConfig/videoDetails/playbackTracking, and innertubex reports nothing this app
            // reads from those beyond the loudness already carried on the format above.
            audioConfig = null,
            videoDetails = null,
            playbackTracking = null,
            format = format,
            streamUrl = audioUrl,
            streamExpiresInSeconds = expiresInSeconds,
            streamHeaders = headers,
            cpn = cpn,
            clientName = clientName,
        )
    }
}
