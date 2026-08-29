package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val useWebPoTokens: Boolean = false,
    /**
     * Whether this client is not worth attempting at all without a PoToken, as opposed to merely
     * benefiting from one. The ported stream engine skips such a client outright when minting
     * failed, rather than spending a player request on a response it already knows will be refused.
     */
    val requirePoToken: Boolean = false,
    val isEmbedded: Boolean = false,
    /** Whether [userAgent] also belongs in the request body's context.client - see that field's own doc. */
    val includeUserAgentInContext: Boolean = false,
    /**
     * Whether this client's *player* request belongs on music.youtube.com rather than
     * www.youtube.com.
     *
     * This matters more than it looks. Every client used to be sent to music.youtube.com, which is
     * wrong for the device clients: an Oculus VR app or an iOS app asking music.youtube.com for a
     * player response is a client/host combination that does not occur in the wild, and Google's
     * anti-abuse answers it with a network-level 403 ("your computer or network may be sending
     * automated queries") rather than a stream. MetrolistGroup/innertubex routes per client for
     * exactly this reason - music.youtube.com for the browser-origin music clients and for the
     * ones that genuinely present as music clients, www.youtube.com for everything else - and its
     * extraction is the one known to work here.
     */
    val useMusicPlayerEndpoint: Boolean = false,
) {
    fun toContext(locale: YouTubeLocale, visitorData: String?, dataSyncId: String?) = Context(
        client = Context.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            userAgent = if (includeUserAgentInContext) userAgent else null,
            osName = osName,
            osVersion = osVersion,
            deviceMake = deviceMake,
            deviceModel = deviceModel,
            androidSdkVersion = androidSdkVersion,
            gl = locale.gl,
            hl = locale.hl,
            visitorData = visitorData
        ),
        user = Context.User(
            onBehalfOfUser = if (loginSupported) dataSyncId else null
        ),
    )

    companion object {
        /**
         * Should be the latest Firefox ESR version.
         */
        const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        const val ORIGIN_YOUTUBE_WWW = "https://www.youtube.com"
        const val REFERER_YOUTUBE_WWW = "$ORIGIN_YOUTUBE_WWW/"
        const val API_URL_YOUTUBE_WWW = "$ORIGIN_YOUTUBE_WWW/youtubei/v1/"

        val WEB = YouTubeClient(
            clientName = "WEB",
            clientVersion = "2.20260114.08.00",
            clientId = "1",
            userAgent = USER_AGENT_WEB,
        )

        val WEB_REMIX = YouTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = "1.20260114.03.00",
            clientId = "67",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
            // It *is* the YouTube Music web client - music.youtube.com is its real home.
            useMusicPlayerEndpoint = true,
        )

        val WEB_CREATOR = YouTubeClient(
            clientName = "WEB_CREATOR",
            clientVersion = "1.20260114.05.00",
            clientId = "62",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
        )

        val TVHTML5 = YouTubeClient(
            clientName = "TVHTML5",
            clientVersion = "7.20260114.12.00",
            clientId = "7",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
            loginSupported = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
            includeUserAgentInContext = true,
        )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER = YouTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            loginSupported = true,
            // Doesn't actually require login for age-restricted content - this client's whole
            // point is bypassing that as a logged-out fallback, which loginRequired=true defeated.
            loginRequired = false,
            useSignatureTimestamp = true,
            // Matches Metrolist v13.6.3, where this client carries both flags: it is served only
            // with a web PoToken, so without one there is nothing to gain by asking.
            useWebPoTokens = true,
            requirePoToken = true,
            isEmbedded = true,
        )

        val IOS = YouTubeClient(
            clientName = "IOS",
            clientVersion = "21.03.1",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            osVersion = "18.2.22C152",
        )

        val ANDROID = YouTubeClient(
            clientName = "ANDROID",
            clientVersion = "21.03.38",
            clientId = "3",
            userAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
            loginSupported = true,
            useSignatureTimestamp = true
        )

        val ANDROID_VR_NO_AUTH = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            loginSupported = false,
            useSignatureTimestamp = false,
            includeUserAgentInContext = true,
        )

        /**
         * Apple Vision Pro's Safari-based web client. Not gated behind the WEB_REMIX-style
         * PoToken/BotGuard machinery (loginSupported/useSignatureTimestamp both false, same as the
         * ANDROID_VR clients below) despite being a genuine browser user agent, which is what makes
         * it a reliable stream fallback independent of WEB_REMIX's own PoToken staleness/rejection
         * issues. Field values (including the fixed clientVersion "0.1" and clientId "101") are
         * exactly what yuuichi-s/OuterTune's fork uses, not a guess.
         */
        val VISIONOS = YouTubeClient(
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientId = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            loginSupported = false,
            useSignatureTimestamp = false,
            // Deliberately no useWebPoTokens. An earlier attempt attached the WebView-minted token
            // here, reasoning that a bot challenge exists to be satisfied by a PoToken. It never
            // helped: that token comes from YouTube's *web* BotGuard, and current web PoToken
            // enforcement is GVS-only and web-client-only, so presenting one from a visionOS client
            // is a mismatch rather than an answer. Metrolist's VISIONOS carries no PoToken either,
            // and theirs plays.
            useMusicPlayerEndpoint = true,
        )

        /**
         * The older visionOS 0.1 build. Kept as a second shot for the same reason two ANDROID_VR
         * versions are kept: YouTube treats each client version separately, so one being refused
         * doesn't have to cost the client entirely. MetrolistGroup/innertubex flags this build as
         * needing its player response accepted without the usual validation, which is a hint that
         * what it returns is thinner than the newer build's - hence the nullable videoDetails
         * fields this codebase now has.
         */
        val VISIONOS_0_1 = YouTubeClient(
            clientName = "VISIONOS",
            clientVersion = "0.1",
            clientId = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 " +
                    "(KHTML, like Gecko) Version/17.5 Safari/605.1.15",
            osName = "VISION_OS",
            osVersion = "1.3",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            loginSupported = false,
            useSignatureTimestamp = false,
            // No PoToken - see VISIONOS above.
            useMusicPlayerEndpoint = true,
        )

        /**
         * Newer Oculus build of the same client. YouTube treats each ANDROID_VR version
         * differently, so keeping a second one gives the chain somewhere to go when
         * [ANDROID_VR_NO_AUTH] is turned away instead of losing the client entirely.
         */
        val ANDROID_VR_1_65_10 = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            loginSupported = false,
            useSignatureTimestamp = false,
            includeUserAgentInContext = true,
        )

        /**
         * Uses non-adaptive bitrate, which fixes audio stuttering with YT Music streams that the
         * regular (adaptive) ANDROID_VR_NO_AUTH client can exhibit. Does not use AV1.
         */
        val ANDROID_VR_1_43_32 = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = "32",
            loginSupported = false,
            useSignatureTimestamp = false,
            includeUserAgentInContext = true,
        )
    }
}
