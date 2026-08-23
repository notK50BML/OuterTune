package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    private val request: Request = Request(),
    private val user: User = User(),
) {
    /**
     * Everything past the client name describes the device the request claims to come from. A
     * request that only names the client is served, but the stream urls it hands back are short
     * lived; identifying as a specific device is what gets a url that lasts a whole track.
     */
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String? = null,
        val osName: String? = null,
        val osVersion: String?,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val androidSdkVersion: String? = null,
        val gl: String,
        val hl: String,
        val visitorData: String?,
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String,
    )

    @Serializable
    data class Request(
        val internalExperimentFlags: Array<String> = emptyArray(),
        val useSsl: Boolean = true,
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false,
        val onBehalfOfUser: String? = null,
    )
}
