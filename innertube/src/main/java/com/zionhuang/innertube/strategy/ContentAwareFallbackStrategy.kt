package com.zionhuang.innertube.strategy

import com.zionhuang.innertube.models.YouTubeClient

/**
 * Ported from Metrolist v13.6.3, whose stream engine this app now uses. The client order is the
 * substance of it: which clients YouTube will actually serve depends on what the content is, and a
 * single fixed chain gets turned away on whichever case it wasn't written for.
 *
 * The one rename against the original is TVHTML5_SIMPLY, which this module has always called
 * TVHTML5_SIMPLY_EMBEDDED_PLAYER; it is the same client.
 */
data class ContentHints(
    val isExplicit: Boolean? = null,
    val isKidsContent: Boolean? = null,
    val isLive: Boolean? = null,
    val isUploaded: Boolean? = null,
)

class ContentAwareFallbackStrategy {
    fun resolveClients(hints: ContentHints): List<YouTubeClient> =
        when {
            hints.isUploaded == true -> uploadedClients
            hints.isLive == true -> liveClients
            hints.isKidsContent == true -> kidsClients
            hints.isExplicit == true -> explicitClients
            else -> defaultClients
        }

    private companion object {
        val uploadedClients = listOf(
            YouTubeClient.TVHTML5,
            YouTubeClient.WEB_REMIX,
            YouTubeClient.WEB_CREATOR,
        )

        val defaultClients = listOf(
            YouTubeClient.VISIONOS,
            YouTubeClient.ANDROID_VR_1_65_10,
            YouTubeClient.ANDROID_VR_1_43_32,
            YouTubeClient.WEB_REMIX,
            YouTubeClient.TVHTML5,
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        )

        val explicitClients = listOf(
            YouTubeClient.VISIONOS,
            YouTubeClient.TVHTML5,
            YouTubeClient.WEB_REMIX,
        )

        val kidsClients = listOf(
            YouTubeClient.TVHTML5,
            YouTubeClient.WEB_REMIX,
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            YouTubeClient.WEB_CREATOR,
        )

        val liveClients = listOf(
            YouTubeClient.TVHTML5,
            YouTubeClient.WEB_REMIX,
            YouTubeClient.WEB_CREATOR,
            YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        )
    }
}
