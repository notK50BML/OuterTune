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
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
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
    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_NO_AUTH

    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        WEB_REMIX, // premium formats and correct metadata; requires working signature deobfuscation
//        ANDROID,
//        TVHTML5,
//        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
    )


    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        /**
         * User-Agent of whichever client (MAIN_CLIENT or a STREAM_FALLBACK_CLIENTS entry) actually
         * produced [streamUrl]. googlevideo.com's CDN has been observed rejecting a fetch whose
         * User-Agent doesn't match the client the URL was signed for, so this has to travel with
         * the URL to wherever it's actually GETed from - the request that resolved it isn't the
         * same request that plays it.
         */
        val streamUserAgent: String,
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

        // Required for some clients to get working streams, but not forced for MAIN_CLIENT: its
        // response is needed even when its streams won't work, so this is allowed to be null.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        val isLoggedIn = YouTube.cookie != null
        val sessionId =
            if (isLoggedIn) {
                // signed in sessions use dataSyncId as identifier
                YouTube.dataSyncId
            } else {
                // signed out sessions use visitorData as identifier
                YouTube.visitorData
            }

        Log.d(TAG, "[$videoId] signatureTimestamp: $signatureTimestamp, isLoggedIn: $isLoggedIn, " +
                "dataSyncId present: ${!YouTube.dataSyncId.isNullOrBlank()} (len=${YouTube.dataSyncId?.length ?: 0}), " +
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
        for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                Log.d(TAG, "Trying client: ${MAIN_CLIENT.clientName}")
                // try with streams from main client first
                client = MAIN_CLIENT
                streamPlayerResponse = mainPlayerResponse
            } else {
                Log.d(TAG, "Trying fallback client: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]

                if (client.loginRequired && !isLoggedIn) {
                    // skip client if it requires login but user is not logged in
                    continue
                }

                val playerResult =
                    YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot)
                playerResult.exceptionOrNull()?.let {
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

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    // skip validateStatus for the last client
                    break
                }
                if (validateStatus(streamUrl, client.userAgent)) {
                    // working stream found
                    Log.i(TAG, "[$videoId] [${client.clientName}] found working stream")
                    break
                } else {
                    Log.w(TAG, "[$videoId] [${client.clientName}] got bad http status code")
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

        Log.d(TAG, "[$videoId] stream url: $streamUrl")

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            streamClient.userAgent,
        )
    }

    /**
     * Mints a fresh visitorData and invalidates the cached WebView PoToken session - the same pair
     * of things Settings > Experimental > "Delete VisitorData" does by hand. This is complementary
     * to the retry in onPlayerError: that retry re-resolves the URL and matches the User-Agent to
     * whichever client answers (fixing a rejected/expired URL or a UA mismatch), while this
     * targets a third, distinct cause - YouTube's bot-detection having flagged this client's
     * identity, which a differently-resolved URL under the *same* identity would not fix. Logged-in
     * sessions are identified by dataSyncId instead, which isn't something this can rotate, so this
     * only does anything useful when signed out.
     */
    suspend fun rotateSessionIdentity() {
        poTokenGenerator.invalidate()
        if (YouTube.cookie == null) {
            YouTube.visitorData().onSuccess { newVisitorData ->
                YouTube.visitorData = newVisitorData
                App.instance.dataStore.edit { it[VisitorDataKey] = newVisitorData }
            }
        }
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
        val sessionId = if (YouTube.cookie != null) YouTube.dataSyncId else YouTube.visitorData
        val webPlayerPot = getWebClientPoTokenOrNull(videoId, sessionId)?.playerRequestPoToken
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
     * [userAgent] must match the client the URL was signed for - googlevideo.com has been
     * observed rejecting requests whose User-Agent doesn't match, so probing without it risked
     * this validation itself failing (or wrongly passing) independent of whether the URL is
     * actually good.
     */
    private fun validateStatus(url: String, userAgent: String): Boolean {
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .header("User-Agent", userAgent)
                .url(url)
            val response = httpClient.newCall(requestBuilder.build()).execute()
            return response.isSuccessful
        } catch (e: Exception) {
            reportException(e)
        }
        return false
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
