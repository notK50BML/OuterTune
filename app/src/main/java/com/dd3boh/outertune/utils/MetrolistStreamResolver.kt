package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.util.Log
import com.dd3boh.outertune.constants.AudioQuality
import com.metrolist.innertubex.InnerTube as MetrolistInnerTube
import com.metrolist.innertubex.extraction.AudioQuality as MetrolistAudioQuality
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
import kotlinx.coroutines.CancellationException
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

            // Mapped by *binding*, not by field name, and that is why these look crossed over.
            //
            // PoTokenResult's two fields are named for the roles the native client loop in
            // YTPlayerUtils gives them, where the orientation was settled by experiment:
            // playerRequestPoToken holds the video-id-bound token, streamingDataPoToken the
            // visitorData-bound one. innertubex names its two by the request they ride on and
            // decides the binding itself, in PlaybackClientCatalog: WEB_REMIX (the only client
            // this resolver has been observed to return) carries webGvsRequired, which is
            // PoTokenRule(REQUIRED, VIDEO_ID, ...) - the stream url's token must be bound to the
            // video id - alongside webSessionPlayerRequired's PoTokenRule(REQUIRED, VISITOR_DATA)
            // for the player request.
            //
            // Passing these straight through by name therefore put the visitorData-bound token on
            // the stream url, where googlevideo wants the video-bound one. It is not refused at
            // resolve time - the url comes back looking fine - so it fails later, as a 403 on a
            // media request about a minute in, or immediately on stricter videos, and on every
            // download because a download reads the whole file. Crossing them here restores the
            // contract this doc comment above already describes, and leaves the native path's
            // verified orientation untouched.
            return MetrolistPoTokenResult(
                playerRequestToken = minted.streamingDataPoToken,
                streamingDataToken = minted.playerRequestPoToken,
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
    suspend fun resolve(
        videoId: String,
        cpn: String,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        allowBoundedRange: Boolean = true,
        excludedClients: Set<String> = emptySet(),
    ): YTPlayerUtils.PlaybackData? {
        // extract() takes five arguments and this used to pass two, leaving the other three on
        // their defaults. Each of those defaults is wrong for this app:
        //
        // allowSabr defaults to true, and this app has no SABR support at all - the class doc
        // above says so in as many words. A SABR stream's url is a bootstrap for a protocol the
        // player cannot speak, so handing one to a plain OkHttp data source does not fail, it
        // stalls: the track buffers to wherever the first fetch got to and then waits forever.
        // allowHls defaults to true and is the same story, since nothing here reads a media
        // playlist either. Both are switched off, and the sabrBootstrap check below is a second
        // line of defence in case a client answers with one anyway.
        //
        // allowBoundedRange is what a download has to turn off. A client whose url is only served
        // for an explicitly bounded range cannot supply a whole file in one request, and one
        // request is exactly what DownloadUtil issues - so picking such a client guarantees the
        // download is refused. Playback leaves it on, since it reads progressively and can honour
        // the bound.
        val hints = ContentHints().withStreamCapabilities(
            allowHls = false,
            allowSabr = false,
            allowBoundedRange = allowBoundedRange,
        )

        val extracted = try {
            syncSession()
            extractor.extract(
                videoId = videoId,
                hints = hints,
                // Refusing a client here costs nothing; refusing its result afterwards throws away
                // a whole extraction and can leave nothing to play.
                excludedClients = excludedClients,
                // The user's audio quality preference reached the app's own client loop but never
                // this engine, so every innertubex-resolved stream ignored the setting entirely.
                audioQuality = audioQuality.toInnerTubeX(connectivityManager),
                // The same cpn the caller already generated, rather than one minted inside the
                // extractor: it is carried on the stream url *and* on the playback telemetry fired
                // later from this data, and those two have to agree for a play to register.
                clientPlaybackNonce = cpn,
            )
        } catch (e: CancellationException) {
            // Never swallowed. Catching this alongside everything else meant that when the player
            // abandoned a load - a seek, a skip, a stop - the cancellation was reported as a failed
            // extraction and the caller carried on into its own client loop inside an already
            // cancelled scope. Every client there then failed instantly with "no response", six of
            // them inside ten milliseconds, and what had merely been an abandoned load was
            // reported as a track that could not be played at all.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[$videoId] extraction failed", e)
            null
        } ?: return null

        if (extracted.audioUrl.isBlank()) {
            Log.w(TAG, "[$videoId] extraction returned no audio url")
            return null
        }

        // Belt and braces against the allowSabr hint above. A SABR stream is not a url this player
        // can fetch, and taking one would look like a track that buffers forever rather than one
        // that failed - so it is refused here and the caller falls through to its own client loop.
        if (extracted.sabrBootstrap != null) {
            Log.w(TAG, "[$videoId] ${extracted.clientName} answered SABR, which this player cannot fetch")
            return null
        }

        Log.i(TAG, "[$videoId] resolved via ${extracted.clientName} (${extracted.profileId})")
        return extracted.toPlaybackData(cpn)
    }

    /** Mirrors the app's own quality preference onto innertubex's, metered-aware like the rest. */
    private fun AudioQuality.toInnerTubeX(connectivityManager: ConnectivityManager): MetrolistAudioQuality =
        when (this) {
            AudioQuality.HIGH -> MetrolistAudioQuality.HIGH
            AudioQuality.LOW -> MetrolistAudioQuality.LOW
            AudioQuality.AUTO ->
                if (connectivityManager.isActiveNetworkMetered) MetrolistAudioQuality.LOW
                else MetrolistAudioQuality.AUTO
        }

    private fun ExtractedStream.toPlaybackData(cpn: String): YTPlayerUtils.PlaybackData {
        // expiresAt is absolute; this app's cache still speaks in "seconds from now". Floored at
        // zero so a clock skew can only shorten how long the url is trusted, never extend it.
        val expiresInSeconds = expiresAt
            ?.let { (it.toEpochMilliseconds() - System.currentTimeMillis()) / 1000L }
            ?.coerceAtLeast(0L)
            ?.toInt()
            ?: DEFAULT_EXPIRY_SECONDS

        // Recomposed rather than passed through. innertubex reports mimeType and codecs as separate
        // fields, but this app's own format handling assumes YouTube's single combined header
        // (audio/mp4; codecs="mp4a.40.2") and splits on "codecs=" to recover the codec. Handing it a
        // bare "audio/mp4" makes that split return a one-element list, and indexing [1] threw
        // IndexOutOfBoundsException on Room's disk-io thread while writing the FormatEntity.
        val combinedMimeType = buildString {
            append(mimeType ?: "audio/mp4")
            if (!codecs.isNullOrBlank() && !contains("codecs=")) {
                append("; codecs=\"").append(codecs).append('"')
            }
        }

        val format = PlayerResponse.StreamingData.Format(
            itag = itag,
            url = audioUrl,
            mimeType = combinedMimeType,
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
            // innertubex's own values, not re-derived from the client name: it decides these and a
            // future rule change should arrive with the library rather than needing to be noticed
            // and mirrored here.
            requireBoundedRange = requireBoundedRange,
            useRangeChunks = useRangeChunks,
            rangeChunkSizeBytes = rangeChunkSizeBytes,
        )
    }
}
