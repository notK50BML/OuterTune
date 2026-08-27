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
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
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
        // The one that actually plays a track through. Everything below it is a last resort.
        VISIONOS,
        // Two ANDROID_VR builds because YouTube treats each version differently, so one being
        // refused doesn't cost the client entirely.
        ANDROID_VR_1_65_10,
        ANDROID_VR_1_43_32,
        // Last on purpose. WEB_REMIX's stream URLs are the short-lived ones googlevideo serves
        // for about a minute and then refuses, so it must never win a stream while any other
        // client can supply one - but silence is worse than a track that needs a mid-song
        // re-resolve, so it stays reachable for the case where everything above it has failed.
        // Being last also means it skips validation (see the loop), costing no extra request.
        // It remains the metadata client regardless; that never depended on this list.
        WEB_REMIX,
//        ANDROID, // player request answers 400
//        IOS, // short-lived music URLs; the ones that die partway through a track
    )

    /**
     * How long to stop making player requests after Google answers with its network-level abuse
     * page ("your computer or network may be sending automated queries"). That response is not
     * about one video or one client - it means this IP has been throttled, so every client in the
     * chain will get it too, and each additional request pushes the throttle out further. The only
     * useful response is to stop asking for a while.
     */
    private const val NETWORK_THROTTLE_COOLDOWN_MS = 5 * 60 * 1000L

    /** When the current network-level throttle expires, or 0 when not throttled. */
    @Volatile
    private var networkThrottledUntil = 0L

    /** Remaining throttle in ms, or null when requests are allowed. */
    private fun throttledForMs(): Long? =
        (networkThrottledUntil - System.currentTimeMillis()).takeIf { it > 0 }

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

    /**
     * No-op kept for MusicService's error path. WEB_REMIX is metadata-only now and can never
     * produce a stream URL (see the stream chain's own doc), so there is no longer a
     * WEB_REMIX stream rejection to remember and skip on the next resolve.
     */
    @Suppress("UNUSED_PARAMETER")
    fun markWebRemixStreamFailed(videoId: String) = Unit


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

        // Refuse to add to the pile while Google is rate-limiting this network (see
        // NETWORK_THROTTLE_COOLDOWN_MS). Every request sent during a throttle both fails and
        // deepens it, and resolution is the single biggest request generator in the app.
        throttledForMs()?.let { remaining ->
            throw PlaybackException(
                "YouTube is rate-limiting this network - waiting ${remaining / 1000}s before retrying",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        // Generated once per resolve and carried on both the stream URL and the playback
        // telemetry pings fired later from this data - see PlaybackData.cpn's own doc.
        val cpn = YouTube.generateCpn()

        // Required for some clients to get working streams, but not forced for MAIN_CLIENT: its
        // response is needed even when its streams won't work, so this is allowed to be null.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        val isLoggedIn = YouTube.cookie != null
        // Always visitorData - never dataSyncId, even when logged in. dataSyncId is permanent and
        // tied to the Google account itself; a PoToken minted against it carries that same
        // permanent identity forever, so if BotGuard ever flags anything tied to it, every future
        // PoToken inherits the flag and nothing client-side (including rotateSessionIdentity()
        // below) can shed it - a signed-in "Reset YouTube session" was always a no-op for exactly
        // this reason. visitorData is a rotatable, non-account-tied identifier instead: minting a
        // fresh one (which this app can and does do) gets a genuinely new BotGuard identity rather
        // than reusing whatever this account's dataSyncId has already accumulated. Metrolist does
        // the same - always visitorData, regardless of login state - which is why the identical
        // Google account can play fine there while failing here under the old dataSyncId-when-
        // logged-in logic.
        val sessionId = YouTube.visitorData

        Log.d(TAG, "[$videoId] signatureTimestamp: $signatureTimestamp, isLoggedIn: $isLoggedIn, " +
                "visitorData present: ${!YouTube.visitorData.isNullOrBlank()}")

        val (webPlayerPot, webStreamingPot) = getWebClientPoTokenOrNull(videoId, sessionId)?.let {
            Pair(it.playerRequestPoToken, it.streamingDataPoToken)
        } ?: Pair(null, null).also {
            Log.w(TAG, "[$videoId] No po token")
        }

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
        // MAIN_CLIENT (WEB_REMIX) is metadata-only and is deliberately NOT in this list. Its
        // stream URLs are the short-lived ones googlevideo serves for about a minute and then
        // refuses for good, so having it in the chain meant every VISIONOS failure quietly handed
        // playback to a URL guaranteed to die mid-track - which looked like "VISIONOS doesn't
        // work" when what actually happened was "VISIONOS was skipped and WEB_REMIX took over".
        // mainPlayerResponse (fetched above, unconditionally) still supplies all metadata, so
        // nothing login-gated is affected by keeping it out of the stream path.
        val clientTryOrder = STREAM_FALLBACK_CLIENTS.indices.toList()
        for (clientIndex in clientTryOrder) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient = STREAM_FALLBACK_CLIENTS[clientIndex]
            // At warn: which clients were tried, in order, is the first thing needed to tell
            // "VISIONOS failed" apart from "VISIONOS was never reached", and debug logging is not
            // available in a release build.
            Log.w(TAG, "[$videoId] trying stream client ${clientIndex + 1}/" +
                    "${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

            if (client.loginRequired && !isLoggedIn) {
                // skip client if it requires login but user is not logged in
                Log.w(TAG, "[$videoId] [${client.clientName}] skipped: needs login, not logged in")
                continue
            }

            if (client == MAIN_CLIENT) {
                // Already fetched above for metadata - reuse it rather than asking again. Saves a
                // request on the one path that only runs when everything else has failed, which
                // is exactly when the network is most likely to be throttled already.
                streamPlayerResponse = mainPlayerResponse
            } else {
                val playerResult =
                    YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot)
                playerResult.exceptionOrNull()?.let {
                    // A network-level throttle applies to the whole IP, so every remaining client
                    // would get the same 403 - and asking them would push the throttle out
                    // further. Stop the chain here and let the cooldown run.
                    if (isNetworkAbuseThrottle(it)) {
                        networkThrottledUntil = System.currentTimeMillis() + NETWORK_THROTTLE_COOLDOWN_MS
                        Log.e(TAG, "[$videoId] Google is rate-limiting this network " +
                                "(hit on ${client.clientName}); pausing player requests for " +
                                "${NETWORK_THROTTLE_COOLDOWN_MS / 1000}s", it)
                        throw PlaybackException(
                            "YouTube is rate-limiting this network - try again in a few minutes",
                            it,
                            PlaybackException.ERROR_CODE_REMOTE_ERROR
                        )
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
                    streamUrl += "&pot=$webStreamingPot";
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
     * Not gated on login state: visitorData is what every PoToken is minted against regardless of
     * whether the session is signed in (see playerResponseForPlayback's own doc for why this app
     * never uses dataSyncId for that), so rotating it is exactly as useful logged in as signed out
     * - unlike before, when this only did anything for a signed-out session because dataSyncId,
     * the identifier a logged-in PoToken used to be minted against instead, isn't something minting
     * a fresh visitorData here has any effect on.
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
        // Always visitorData - see playerResponseForPlayback's doc for why, regardless of login.
        val webPlayerPot = getWebClientPoTokenOrNull(videoId, YouTube.visitorData)?.playerRequestPoToken
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
        // Exactly one ranged GET, at the first byte - the same probe yuuichi-s/OuterTune uses and
        // verified on-device. This deliberately does NOT probe deeper to pre-screen URLs whose
        // *continuation* requests get refused: every extra probe here is a network round trip
        // inside the runBlocking that ExoPlayer's ResolvingDataSource is waiting on, and with a
        // six-client chain a second probe per client was enough to blow the player's own timeout
        // (ERROR_CODE_TIMEOUT, 1003) before any stream was ever handed back. A URL that passes here
        // and then dies mid-track is caught by the 403/410 recovery in MusicService.onPlayerError,
        // which is the right place for it: it costs nothing until it actually happens.
        return try {
            val requestBuilder = okhttp3.Request.Builder()
                .header("Range", "bytes=0-0")
                .url(url)
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val ok = response.code in 200..299
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
