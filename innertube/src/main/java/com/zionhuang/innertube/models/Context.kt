package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    private val request: Request = Request(),
    private val user: User = User(),
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        // Real TV/VR clients embed their User-Agent and device identity in the JSON body itself,
        // not just the HTTP header - a client whose body is missing these looks less like the
        // genuine app it's impersonating. Null (omitted) for clients that don't send them.
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
