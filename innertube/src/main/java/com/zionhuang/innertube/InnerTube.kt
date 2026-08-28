package com.zionhuang.innertube

import com.zionhuang.innertube.models.Context
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.body.*
import com.zionhuang.innertube.utils.parseCookieString
import com.zionhuang.innertube.utils.sha1
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Protocol
import java.io.IOException
import java.net.Proxy
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Provide access to InnerTube endpoints.
 * For making HTTP requests, not parsing response.
 */
class InnerTube {
    private var httpClient = createClient()

    var locale = YouTubeLocale(
        gl = Locale.getDefault().country,
        hl = Locale.getDefault().toLanguageTag()
    )
    var visitorData: String? = null
    var dataSyncId: String? = null
    var cookie: String? = null
        set(value) {
            // Validate before publishing so a parse failure cannot leave an invalid shared cookie.
            if (value != null) parseCookieString(value)
            field = value
        }

    var proxy: Proxy? = null
        set(value) {
            field = value
            httpClient.close()
            httpClient = createClient()
        }

    var useLoginForBrowse: Boolean = false

    @OptIn(ExperimentalSerializationApi::class)
    private fun createClient() = HttpClient(OkHttp) {
        expectSuccess = true

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            })
        }

        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }

        // Ktor's own defaults have no request timeout at all, so a stalled connection on a bad
        // mobile network would otherwise hang indefinitely instead of failing fast enough for
        // the app's own retry/fallback logic (e.g. the client-fallback loop in
        // YTPlayerUtils.playerResponseForPlayback) to get a turn.
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }

        // Transient I/O only (timeouts, connection resets, 5xx) - deliberately NOT retrying 4xx
        // here, since a 403/429 is handled by this app's own PoToken-refresh/session-rotation
        // logic already, and retrying it blindly at this layer would just be redundant requests.
        install(HttpRequestRetry) {
            maxRetries = 3
            retryIf { _, response -> response.status.value in 500..599 }
            retryOnExceptionIf { _, cause -> cause is IOException }
            exponentialDelay(baseDelayMs = 500)
        }

        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
                connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            }

            if (proxy != null) {
                proxy = this@InnerTube.proxy
            }
        }

        defaultRequest {
            url(YouTubeClient.API_URL_YOUTUBE_MUSIC)
        }
    }

    /**
     * @param cookie overrides the shared [InnerTube.cookie] for this request only. Callers that must
     * authenticate as a specific account, rather than as whichever account is currently configured,
     * pass it explicitly.
     */
    /**
     * @param useWwwOrigin sends this request to www.youtube.com instead of music.youtube.com, and
     * matches the Origin/Referer/SAPISIDHASH to it. Passed explicitly rather than inferred from the
     * URL: Ktor's defaultRequest merges its base URL *after* this builder block runs, so the path
     * seen from in here isn't reliably the final one. Only the player endpoint ever sets this - see
     * [YouTubeClient.useMusicPlayerEndpoint].
     */
    private fun HttpRequestBuilder.ytClient(
        client: YouTubeClient,
        setLogin: Boolean = false,
        cookie: String? = this@InnerTube.cookie,
        useWwwOrigin: Boolean = false,
    ) {
        val origin = if (useWwwOrigin) YouTubeClient.ORIGIN_YOUTUBE_WWW else YouTubeClient.ORIGIN_YOUTUBE_MUSIC
        val referer = if (useWwwOrigin) YouTubeClient.REFERER_YOUTUBE_WWW else YouTubeClient.REFERER_YOUTUBE_MUSIC

        contentType(ContentType.Application.Json)
        headers {
            append("X-Goog-Api-Format-Version", "1")
            append("X-YouTube-Client-Name", client.clientId /* Not a typo. The Client-Name header does contain the client id. */)
            append("X-YouTube-Client-Version", client.clientVersion)
            append("X-Origin", origin)
            append("Referer", referer)
            // Real clients send this alongside the same value already in the request body's
            // context.client.visitorData - Metrolist and SimpMusic (siblings sharing this API
            // client's lineage) both set it explicitly rather than relying on the body alone.
            visitorData?.let { append("X-Goog-Visitor-Id", it) }
            if (setLogin && client.loginSupported) {
                cookie?.let { cookie ->
                    val cookieMap = parseCookieString(cookie)
                    append("cookie", cookie)
                    if ("SAPISID" !in cookieMap) return@let
                    val currentTime = System.currentTimeMillis() / 1000
                    // Hashed against the origin this request is actually going to - a SAPISIDHASH
                    // bound to a different origin than the one it is presented to is rejected.
                    val sapisidHash = sha1("$currentTime ${cookieMap["SAPISID"]} $origin")
                    append("Authorization", "SAPISIDHASH ${currentTime}_${sapisidHash} SAPISID1PHASH ${currentTime}_${sapisidHash} SAPISID3PHASH ${currentTime}_${sapisidHash}")
                }
            }
        }
        userAgent(client.userAgent)
        parameter("prettyPrint", false)
    }

    suspend fun search(
        client: YouTubeClient,
        query: String? = null,
        params: String? = null,
        continuation: String? = null,
    ) = httpClient.post("search") {
        ytClient(client, setLogin = useLoginForBrowse)
        setBody(
            SearchBody(
                context = client.toContext(
                    locale,
                    visitorData,
                    if (useLoginForBrowse) dataSyncId else null
                ),
                query = query,
                params = params
            )
        )
        parameter("continuation", continuation)
        parameter("ctoken", continuation)
    }

    suspend fun player(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        signatureTimestamp: Int?,
        webPlayerPot: String?,
    ) = httpClient.post("player") {
        // A device client (ANDROID_VR, IOS, TVHTML5...) asking music.youtube.com for a player
        // response is a client/host pairing that doesn't occur in the wild, and Google's anti-abuse
        // answers it with a network-level 403 abuse page rather than a stream. Route those to
        // www.youtube.com, which is where those clients really live, and keep the music clients on
        // music.youtube.com. Set as an absolute URL so Ktor's defaultRequest (which only fills in
        // an unset host) leaves it alone.
        val useWwwOrigin = !client.useMusicPlayerEndpoint
        if (useWwwOrigin) {
            url(YouTubeClient.API_URL_YOUTUBE_WWW + "player")
        }
        ytClient(client, setLogin = true, useWwwOrigin = useWwwOrigin)
        setBody(
            PlayerBody(
                context = client.toContext(locale, visitorData, dataSyncId).let {
                    if (client.isEmbedded) {
                        it.copy(
                            thirdParty = Context.ThirdParty(
                                embedUrl = "https://www.youtube.com/watch?v=${videoId}"
                            )
                        )
                    } else it
                },
                videoId = videoId,
                playlistId = playlistId,
                playbackContext = if (client.useSignatureTimestamp && signatureTimestamp != null) {
                    PlayerBody.PlaybackContext(
                        PlayerBody.PlaybackContext.ContentPlaybackContext(
                            signatureTimestamp
                        )
                    )
                } else null,
                serviceIntegrityDimensions = if (client.useWebPoTokens && webPlayerPot != null) {
                    PlayerBody.ServiceIntegrityDimensions(webPlayerPot)
                } else null
            )
        )
    }

    suspend fun registerPlayback(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = httpClient.get(url) {
        ytClient(client, true)
        parameter("ver", "2")
        parameter("c", client.clientName)
        parameter("cpn", cpn)

        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    /**
     * A watch-time checkpoint. [startSeconds]/[endSeconds] are the "st"/"et" parameters real
     * clients send - comma-separated lists of seconds-into-playback for multiple checkpoints in
     * one call, or single values for the first one.
     */
    suspend fun registerWatchtime(
        url: String,
        cpn: String,
        startSeconds: String,
        endSeconds: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = httpClient.get(url) {
        ytClient(client, true)
        parameter("ver", "2")
        parameter("c", client.clientName)
        parameter("cpn", cpn)
        parameter("st", startSeconds)
        parameter("et", endSeconds)

        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    /** An "active this response" ping - real clients send this partway into playback, between
     *  the first and second watch-time checkpoints. */
    suspend fun registerAtr(
        url: String,
        cpn: String,
        playlistId: String?,
        client: YouTubeClient = YouTubeClient.WEB_REMIX,
    ) = httpClient.post(url) {
        ytClient(client, true)
        parameter("cpn", cpn)

        if (playlistId != null) {
            parameter("list", playlistId)
            parameter("referrer", "https://music.youtube.com/playlist?list=$playlistId")
        }
    }

    suspend fun browse(
        client: YouTubeClient,
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ) = httpClient.post("browse") {
        ytClient(client, setLogin = setLogin || useLoginForBrowse)
        setBody(
            BrowseBody(
                context = client.toContext(
                    locale,
                    visitorData,
                    if (setLogin || useLoginForBrowse) dataSyncId else null
                ),
                browseId = browseId,
                params = params,
                continuation = continuation
            )
        )
    }

    suspend fun next(
        client: YouTubeClient,
        videoId: String?,
        playlistId: String?,
        playlistSetVideoId: String?,
        index: Int?,
        params: String?,
        continuation: String? = null,
    ) = httpClient.post("next") {
        ytClient(client, setLogin = true)
        setBody(
            NextBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                videoId = videoId,
                playlistId = playlistId,
                playlistSetVideoId = playlistSetVideoId,
                index = index,
                params = params,
                continuation = continuation
            )
        )
    }

    suspend fun getSearchSuggestions(
        client: YouTubeClient,
        input: String,
    ) = httpClient.post("music/get_search_suggestions") {
        ytClient(client)
        setBody(
            GetSearchSuggestionsBody(
                context = client.toContext(locale, visitorData, null),
                input = input
            )
        )
    }

    suspend fun getQueue(
        client: YouTubeClient,
        videoIds: List<String>?,
        playlistId: String?,
    ) = httpClient.post("music/get_queue") {
        ytClient(client)
        setBody(
            GetQueueBody(
                context = client.toContext(locale, visitorData, null),
                videoIds = videoIds,
                playlistId = playlistId
            )
        )
    }

    suspend fun httpGet(url: String, block: HttpRequestBuilder.() -> Unit = {}) = httpClient.get(url, block)

    suspend fun getSwJsData() = httpClient.get("https://music.youtube.com/sw.js_data")

    suspend fun accountMenu(
        client: YouTubeClient,
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
    ) = httpClient.post("account/account_menu") {
        ytClient(client, setLogin = true, cookie = cookie)
        setBody(AccountMenuBody(client.toContext(locale, visitorData, dataSyncId)))
    }

    suspend fun likeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/like") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId)
            )
        )
    }

    suspend fun unlikeVideo(
        client: YouTubeClient,
        videoId: String,
    ) = httpClient.post("like/removelike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.VideoTarget(videoId)
            )
        )
    }

    suspend fun subscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = httpClient.post("subscription/subscribe") {
        ytClient(client, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                channelIds = listOf(channelId)
            )
        )
    }

    suspend fun unsubscribeChannel(
        client: YouTubeClient,
        channelId: String,
    ) = httpClient.post("subscription/unsubscribe") {
        ytClient(client, setLogin = true)
        setBody(
            SubscribeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                channelIds = listOf(channelId)
            )
        )
    }

    suspend fun likePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("like/like") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId)
            )
        )
    }

    suspend fun unlikePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("like/removelike") {
        ytClient(client, setLogin = true)
        setBody(
            LikeBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                target = LikeBody.Target.PlaylistTarget(playlistId)
            )
        )
    }

    suspend fun addToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions = listOf(
                    Action.AddVideoAction(addedVideoId = videoId)
                )
            )
        )
    }

    suspend fun addPlaylistToPlaylist(
        client: YouTubeClient,
        playlistId: String,
        addPlaylistId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions = listOf(
                    Action.AddPlaylistAction(addedFullListId = addPlaylistId)
                )
            )
        )
    }

    suspend fun removeFromPlaylist(
        client: YouTubeClient,
        playlistId: String,
        videoId: String,
        setVideoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId.removePrefix("VL"),
                actions = listOf(
                    Action.RemoveVideoAction(
                        removedVideoId = videoId,
                        setVideoId = setVideoId,
                    )
                )
            )
        )
    }

    suspend fun moveSongPlaylist(
        client: YouTubeClient,
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions = listOf(
                    Action.MoveVideoAction(
                        movedSetVideoIdSuccessor = successorSetVideoId,
                        setVideoId = setVideoId,
                    )
                )

            )
        )
    }

    suspend fun createPlaylist(
        client: YouTubeClient,
        title: String,
    ) = httpClient.post("playlist/create") {
        ytClient(client, true)
        setBody(
            CreatePlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                title = title
            )
        )
    }

    suspend fun renamePlaylist(
        client: YouTubeClient,
        playlistId: String,
        name: String,
    ) = httpClient.post("browse/edit_playlist") {
        ytClient(client, setLogin = true)
        setBody(
            EditPlaylistBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId,
                actions = listOf(
                    Action.RenamePlaylistAction(
                        playlistName = name
                    )
                )
            )
        )
    }

    suspend fun deletePlaylist(
        client: YouTubeClient,
        playlistId: String,
    ) = httpClient.post("playlist/delete") {
        ytClient(client, setLogin = true)
        setBody(
            PlaylistDeleteBody(
                context = client.toContext(locale, visitorData, dataSyncId),
                playlistId = playlistId
            )
        )
    }
}
