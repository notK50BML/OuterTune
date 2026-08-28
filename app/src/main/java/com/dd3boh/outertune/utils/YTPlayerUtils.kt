/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.App
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.utils.YTPlayerUtils.MAIN_CLIENT
import com.dd3boh.outertune.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.dd3boh.outertune.utils.YTPlayerUtils.validateStatus
import com.dd3boh.outertune.utils.cipher.SignatureCipherManager
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS_0_1
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * Byte offset used by [validateStatus]. Must stay at or beyond MusicService.CHUNK_LENGTH so the
     * probe exercises a continuation request rather than the first chunk.
     */
    private const val PROBE_OFFSET = 256 * 1024L



    /**
     * Client used for metadata and the initial stream response. Other clients are not used here
     * because their metadata can differ (e.g. different loudnessDb normalization targets).
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     * VISIONOS goes first: like the ANDROID_VR clients, it needs no PoToken/BotGuard machinery
     * (loginSupported/useSignatureTimestamp both false) despite being a real browser user agent,
     * so it's not exposed to WEB_REMIX's own PoToken staleness/rejection failure modes - the
     * client yuuichi-s/OuterTune's fork relies on first for exactly that reason.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        // The chain as it stood in Core-v0.18.2, when VISIONOS was first ported and playback
        // worked. VISIONOS is tried ahead of all of these (and ahead of MAIN_CLIENT) in the loop
        // below; MAIN_CLIENT (WEB_REMIX) is tried second, then these in order.
        // The older visionOS build, as a second shot before leaving the client behind entirely -
        // same reasoning as keeping several ANDROID_VR versions.
        VISIONOS_0_1,
        ANDROID_VR_NO_AUTH, // no PoToken support: streams die at the first continuation request
        // Different ANDROID_VR client versions are tracked separately by YouTube - keeping more
        // than one gives the chain somewhere to go when one build is turned away instead of losing
        // this client entirely.
        ANDROID_VR_1_65_10,
        ANDROID_VR_1_43_32,
//        ANDROID,
//        TVHTML5,
//        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
    )

    /**
     * True when [error] is Google's network-level "automated queries" abuse page rather than a
     * per-video or per-client rejection. Matched on the body text because it arrives as a plain
     * HTML 403 from Google's generic abuse system, not as an innertube error with a status field.
     */
    private fun isNetworkAbuseThrottle(error: Throwable): Boolean {
        val message = error.message ?: return false
        return "automated queries" in message ||
            ("403" in message && "We're sorry" in message)
    }

    private const val WEB_REMIX_FAILURE_TTL_MS = 5 * 60 * 1000L

    /**
     * videoId -> when its WEB_REMIX (MAIN_CLIENT) stream was last rejected during actual playback.
     * MusicService's retry-on-source-error re-resolves from scratch, which would otherwise try
     * WEB_REMIX again and, if the rejection wasn't fixed by the accompanying identity rotation,
     * spend that retry repeating the same failure instead of reaching a fallback client. A short
     * TTL rather than a permanent skip: the rejection is often transient (a stale PoToken, a
     * session flagged only briefly), and WEB_REMIX is the only client with premium formats and
     * correct metadata, worth trying again soon rather than avoiding indefinitely.
     */
    private val webRemixFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markWebRemixStreamFailed(videoId: String) {
        webRemixFailures[videoId] = System.currentTimeMillis()
    }

    private fun hasRecentWebRemixFailure(videoId: String): Boolean {
        val failedAt = webRemixFailures[videoId] ?: return false
        if (System.currentTimeMillis() - failedAt >= WEB_REMIX_FAILURE_TTL_MS) {
            webRemixFailures.remove(videoId, failedAt)
            return false
        }
        return true
    }


    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        /**
         * Headers of whichever client (MAIN_CLIENT or a STREAM_FALLBACK_CLIENTS entry) actually
         * produced [streamUrl]. googlevideo.com's CDN has been observed rejecting a fetch whose
         * User-Agent doesn't match the client the URL was signed for - and, for WEB_REMIX
         * specifically, one lacking a matching Referer/Origin, since that URL was signed for a
         * browser-origin client - so these have to travel with the URL to wherever it's actually
         * GETed from - the request that resolved it isn't the same request that plays it.
         */
        val streamHeaders: Map<String, String>,
        /**
         * The nonce tying this stream fetch to its own playback telemetry (see
         * [com.zionhuang.innertube.YouTube.initPlayback]) - a real client's audio request and its
         * tracking pings for the same playback share one cpn; embedding it here too rather than
         * generating a throwaway one only for the pings keeps that correlation intact.
         */
        val cpn: String,
        /** Name of whichever client actually produced [streamUrl] - see [markWebRemixStreamFailed]. */
        val clientName: String,
    )

    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Log.d(TAG, "Playback info requested: $videoId")


        // Generated once per resolve and carried on both the stream URL and the playback
        // telemetry pings fired later from this data - see PlaybackData.cpn's own doc.
        val cpn = YouTube.generateCpn()

        // Required for some clients to get working streams, but not forced for MAIN_CLIENT: its
        // response is needed even when its streams won't work, so this is allowed to be null.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        val isLoggedIn = YouTube.cookie != null

        Log.d(TAG, "[$videoId] signatureTimestamp: $signatureTimestamp, isLoggedIn: $isLoggedIn, " +
                "visitorData present: ${!YouTube.visitorData.isNullOrBlank()}")

        // visitorData, regardless of login state - the session token's binding, per Metrolist's
        // PlaybackClientCatalog (PoTokenBinding.VISITOR_DATA), which is the implementation this was
        // checked against after several wrong guesses here. An earlier attempt bound this to
        // dataSyncId when signed in on the theory that the token had to match the identity the
        // request authenticated with; that changed nothing, because the binding that was actually
        // wrong was which token rode on which request - see PoTokenGenerator.
        val sessionId = YouTube.visitorData

        val pot = if (sessionId.isNullOrBlank()) {
            Log.w(TAG, "[$videoId] no visitorData to bind a po token to")
            null
        } else {
            getWebClientPoTokenOrNull(videoId, sessionId).also {
                if (it == null) Log.w(TAG, "[$videoId] no po token minted")
            }
        }
        val webPlayerPot = pot?.playerRequestPoToken
        val webStreamingPot = pot?.streamingDataPoToken

        val mainPlayerResponse =
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, webPlayerPot)
                .getOrThrow()

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamClient: YouTubeClient = MAIN_CLIENT

        var streamPlayerResponse: PlayerResponse? = null
        // -2 is VISIONOS, tried before MAIN_CLIENT itself and before every fallback: its urls need
        // no signature deobfuscation, no n-transform and no PO token, and - unlike every other
        // client here, MAIN_CLIENT included - serve a whole track instead of being cut off partway
        // through. -1 is MAIN_CLIENT (WEB_REMIX), which stays the metadata client regardless; see
        // its own doc for why metadata specifically has to come from the signed-in client. This is
        // the ordering from Core-v0.18.2, restored: the interim rewrites that reshuffled it were
        // chasing a failure whose real cause was VideoDetails rejecting VISIONOS's response.
        //
        // A WEB_REMIX stream this video just had rejected moves it to the *back* of the order
        // rather than out of it. Dropping it entirely was wrong: WEB_REMIX is routinely the only
        // client YouTube will serve this session at all - every anonymous one gets turned away with
        // a bot challenge - so removing it from the retry guaranteed the retry failed, burning
        // through five dead clients and then stopping playback outright. Last place gets the same
        // "try a fallback first" benefit without betting the whole retry on a fallback working.
        val webRemixDeprioritised = hasRecentWebRemixFailure(videoId)
        val clientTryOrder = if (webRemixDeprioritised) {
            listOf(-2) + STREAM_FALLBACK_CLIENTS.indices + listOf(-1)
        } else {
            listOf(-2, -1) + STREAM_FALLBACK_CLIENTS.indices
        }
        for (clientIndex in clientTryOrder) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient = when (clientIndex) {
                -2 -> VISIONOS
                -1 -> MAIN_CLIENT
                else -> STREAM_FALLBACK_CLIENTS[clientIndex]
            }
            // At warn: which clients were tried, in order, is the first thing needed to tell
            // "VISIONOS failed" apart from "VISIONOS was never reached", and debug logging is not
            // available in a release build.
            Log.w(TAG, "[$videoId] trying stream client: ${client.clientName}")

            if (client.loginRequired && !isLoggedIn) {
                // skip client if it requires login but user is not logged in
                Log.w(TAG, "[$videoId] [${client.clientName}] skipped: needs login, not logged in")
                continue
            }

            if (clientIndex == -1) {
                if (webRemixDeprioritised) {
                    // Reusing mainPlayerResponse here would hand back the very url that was just
                    // rejected, so this asks again for a fresh one. That is the whole point of the
                    // retry when the cause was a stale or wrongly-bound url rather than a video
                    // this client genuinely cannot serve.
                    Log.w(TAG, "[$videoId] [${client.clientName}] re-resolving: previous stream was rejected")
                    val refreshed = YouTube.player(
                        videoId, playlistId, client, signatureTimestamp, webPlayerPot
                    )
                    refreshed.exceptionOrNull()?.let {
                        Log.e(TAG, "[$videoId] [${client.clientName}] re-resolve failed", it)
                    }
                    streamPlayerResponse = refreshed.getOrNull()
                } else {
                    // MAIN_CLIENT's response was already fetched above for metadata - reuse it
                    // rather than asking again.
                    streamPlayerResponse = mainPlayerResponse
                }
            } else {
                val playerResult =
                    YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot)
                playerResult.exceptionOrNull()?.let {
                    // Logged, not acted on: a network-level throttle is worth naming in the log
                    // (it looks nothing like a per-video rejection and would otherwise be
                    // mystifying), but refusing to make requests for minutes afterward just turns
                    // a throttle that may already have passed into guaranteed silence. Fall
                    // through to the next client exactly as with any other failure.
                    if (isNetworkAbuseThrottle(it)) {
                        Log.e(TAG, "[$videoId] [${client.clientName}] Google is rate-limiting " +
                                "this network (\"automated queries\") - not a per-video block")
                    }
                    Log.e(TAG, "[$videoId] [${client.clientName}] player request failed", it)
                }
                streamPlayerResponse = playerResult.getOrNull()
            }

            Log.d(TAG, "[$videoId] stream client: ${client.clientName}, " +
                    "playabilityStatus: ${streamPlayerResponse?.playabilityStatus?.let {
                        it.status + (it.reason?.let { " - $it" } ?: "")
                    }}")

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                format = findFormat(streamPlayerResponse, audioQuality, connectivityManager)
                if (format == null) {
                    Log.w(TAG, "[$videoId] [${client.clientName}] OK but no audio format found")
                    continue
                }
                streamUrl = findUrlOrNull(format, videoId)
                if (streamUrl == null) {
                    Log.w(TAG, "[$videoId] [${client.clientName}] OK but failed to build stream url (deobfuscation?)")
                    continue
                }
                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Log.w(TAG, "[$videoId] [${client.clientName}] OK but missing expiresInSeconds")
                    continue
                }
                streamClient = client

                if (client.useWebPoTokens && webStreamingPot != null) {
                    streamUrl += "&pot=$webStreamingPot"
                }
                // At warn, and naming only which identity was used rather than any token material:
                // a stream url whose pot is bound to the wrong identity resolves and probes exactly
                // like a healthy one and only fails a minute later, so without this the single
                // thing needed to tell those apart is invisible in a release logcat.
                if (client.useWebPoTokens) {
                    Log.w(TAG, "[$videoId] [${client.clientName}] gvs pot (video-bound) " +
                            "present=${webStreamingPot != null}")
                }
                streamUrl += "&cpn=$cpn"

                // Nothing left to fall back to, so validating buys nothing - a rejection here
                // would only throw away the one URL still on the table. Skipping it also drops a
                // round trip from the worst case, which is exactly the path that was timing out.
                if (clientIndex == clientTryOrder.last()) {
                    Log.i(TAG, "[$videoId] [${client.clientName}] last client, using without validation")
                    break
                }
                if (validateStatus(streamUrl, client.streamHeaders(), client.clientName)) {
                    // working stream found
                    Log.i(TAG, "[$videoId] [${client.clientName}] found working stream")
                    break
                } else {
                    Log.w(TAG, "[$videoId] [${client.clientName}] rejected: stream validation failed")
                }
            } else {
                // At warn, not just the debug line above: a client turned away by playabilityStatus
                // (a bot check, a region block, an age gate) is otherwise completely invisible in a
                // release logcat, which makes "why didn't this client win?" unanswerable.
                Log.w(TAG, "[$videoId] [${client.clientName}] rejected: playabilityStatus=" +
                        (streamPlayerResponse?.playabilityStatus?.let {
                            it.status + (it.reason?.let { r -> " - $r" } ?: "")
                        } ?: "no response"))
            }
        }

        if (streamPlayerResponse == null) {
            throw Exception("Bad stream player response")
        }
        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            throw PlaybackException(
                streamPlayerResponse.playabilityStatus.reason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }
        if (streamExpiresInSeconds == null) {
            throw Exception("Missing stream expire time")
        }
        if (format == null) {
            throw Exception("Could not find format")
        }
        if (streamUrl == null) {
            throw Exception("Could not find stream url")
        }

        // At warn so it shows in a release logcat without turning on verbose logging: which client
        // actually won is the single most useful fact when a stream dies partway through a track,
        // since every client in the chain fails differently and by different means.
        Log.w(TAG, "[$videoId] RESOLVED via ${streamClient.clientName}, expires in ${streamExpiresInSeconds}s")
        Log.d(TAG, "[$videoId] stream url: $streamUrl")

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            streamClient.streamHeaders(),
            cpn,
            streamClient.clientName,
        )
    }

    /**
     * Mints a fresh visitorData and invalidates the cached WebView PoToken session - the same pair
     * of things Settings > Experimental > "Delete VisitorData" does by hand. This is complementary
     * to the retry in onPlayerError: that retry re-resolves the URL and matches the User-Agent to
     * whichever client answers (fixing a rejected/expired URL or a UA mismatch), while this
     * targets a third, distinct cause - YouTube's bot-detection having flagged this client's
     * identity, which a differently-resolved URL under the *same* identity would not fix.
     *
     * Not gated on login state: every PoToken here is bound to visitorData or to a video id, and
     * never to the account's dataSyncId, so a fresh visitorData genuinely does buy a new BotGuard
     * identity whether or not the session is signed in. Invalidating the generator alongside it
     * discards every cached token and rebuilds the WebView, which is what clears a merely stale
     * token as opposed to a flagged identity.
     */
    suspend fun rotateSessionIdentity() {
        poTokenGenerator.invalidate()
        YouTube.visitorData().onSuccess { newVisitorData ->
            YouTube.visitorData = newVisitorData
            App.instance.dataStore.edit { it[VisitorDataKey] = newVisitorData }
        }
    }

    /**
     * Invalidates just the cached streaming PoToken session, without also rotating visitorData
     * (that's [rotateSessionIdentity], which additionally costs a network round-trip). The
     * streaming PoToken embedded in a resolved stream URL has a real-world validity far shorter
     * than streamExpiresInSeconds claims - confirmed independently of connection duration or chunk
     * size, since tightening both made no difference to a fixed ~60s cutoff. PoTokenGenerator
     * caches the streaming pot at the session level and has no reason to know to mint a new one
     * just because playerResponseForPlayback is called again, so a proactive refresh of an aging
     * cached URL needs to force this explicitly rather than hoping its own internal expiry check
     * happens to line up.
     */
    fun invalidatePoTokenSession() {
        poTokenGenerator.invalidate()
    }

    /**
     * Fetches a WEB_REMIX player response for non-streaming data, including
     * video metadata and playback tracking.
     *
     * Streaming URLs from this response are not guaranteed to be playable.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        // WEB_REMIX provides the playback tracking URL required for history registration.
        // Include the web player integrity fields because omitting the player PoToken may
        // cause the request to return UNPLAYABLE.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        // The player request's token is session-bound - always visitorData; see
        // playerResponseForPlayback for the binding rules and why they are not interchangeable.
        val webPlayerPot = YouTube.visitorData
            ?.let { getWebClientPoTokenOrNull(videoId, it)?.playerRequestPoToken }
        return YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp, webPlayerPot)
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? =
        playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     *
     * [headers] must match the client the URL was signed for - googlevideo.com has been
     * observed rejecting requests whose User-Agent (or, for a browser-origin client like
     * WEB_REMIX, Referer/Origin) doesn't match, so probing without them risked this validation
     * itself failing (or wrongly passing) independent of whether the URL is actually good.
     */
    private fun validateStatus(url: String, headers: Map<String, String>, clientName: String): Boolean {
        // Exactly one ranged GET - never more. Each probe is a network round trip inside the
        // runBlocking that ExoPlayer's ResolvingDataSource is waiting on, and a second probe per
        // client was enough to blow the player's own timeout (ERROR_CODE_TIMEOUT, 1003) before any
        // stream got handed back.
        //
        // Deliberately past the first chunk (PROBE_OFFSET), as it has been since before the
        // VISIONOS port: a probe landing inside the first chunk succeeds on URLs whose
        // *continuation* requests are refused, which is exactly the failure this screens for -
        // playback that starts cleanly and dies the moment the first chunk runs out.
        return try {
            val requestBuilder = okhttp3.Request.Builder()
                .header("Range", "bytes=$PROBE_OFFSET-${PROBE_OFFSET + 1}")
                .url(url)
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                // 416 is a pass, not a rejection: it means there is no byte at PROBE_OFFSET
                // because the track's audio stream is simply shorter than the probe offset. The
                // URL is fine; there is just less of it than the probe assumed.
                val ok = response.code in 200..299 || response.code == 416
                if (!ok) {
                    // At warn so a release logcat shows why a client was dropped, with the code.
                    Log.w(TAG, "[$clientName] stream validation failed: HTTP ${response.code}")
                }
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$clientName] stream validation request failed", e)
            reportException(e)
            false
        }
    }

    /**
     * Headers a stream request needs beyond the URL itself. googlevideo.com has been observed
     * checking Referer/Origin against the client the URL was signed for, not just User-Agent -
     * relevant now that WEB_REMIX (a browser-origin client) is MAIN_CLIENT. Mirrors what an
     * actual browser/app request for that client would send.
     */
    private fun YouTubeClient.streamHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        // Sent by every real client's media fetch; googlevideo is checking that the request looks
        // like the client the URL was issued for, so an incomplete header set is part of what it
        // weighs. Matches yuuichi-s/OuterTune's own set exactly.
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        when (clientName) {
            "WEB_REMIX" -> {
                put("Referer", "https://music.youtube.com/")
                put("Origin", "https://music.youtube.com")
            }
            else -> {
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
            }
        }
    }

    // Reports exceptions; returns null on failure.
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Resolves the playable stream URL for the given audio [format].
     *
     * @param videoId the id of the video [format] belongs to
     * @return the stream URL, or null if it could not be resolved; any error is reported, not thrown
     */
    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        val npResult = NewPipeUtils.getStreamUrl(format, videoId)
        npResult.getOrNull()?.let { return it }

        // fallback for cipher formats: deobfuscate in a WebView (see SignatureCipherManager)
        val signatureCipher = format.signatureCipher
        if (signatureCipher != null) {
            val url = SignatureCipherManager.deobfuscateStreamUrl(signatureCipher, videoId)
            if (url != null) return url
        }

        npResult.exceptionOrNull()?.let {
            Log.e(TAG, "[$videoId] getStreamUrl failed (itag=${format.itag}, hasUrl=${format.url != null}, hasCipher=${format.signatureCipher != null})", it)
            reportException(it)
        }
        return null
    }

    // Reports exceptions; returns null on failure.
    private fun getWebClientPoTokenOrNull(videoId: String, sessionId: String?): PoTokenResult? {
        if (sessionId == null) {
            Log.d(TAG, "[$videoId] Session identifier is null")
            return null
        }
        try {
            return poTokenGenerator.getWebClientPoToken(videoId, sessionId)
        } catch (e: Exception) {
            reportException(e)
        }
        return null
    }
}
