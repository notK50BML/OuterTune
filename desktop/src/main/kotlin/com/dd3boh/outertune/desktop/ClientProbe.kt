/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking

/**
 * Asks every client in [DesktopPlayer.CLIENTS] for one video and reports what each says.
 *
 * Exists because "no client worked" is the least useful error a player can give, and working out
 * which of four clients failed for which of several reasons by reading code is guesswork. YouTube
 * changes what it accepts without notice; this answers "what does it accept *today*" in one run.
 *
 * `gradlew :desktop:clientProbe --args="<videoId>"`
 */
object ClientProbe {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val videoId = args.firstOrNull() ?: DEFAULT_VIDEO
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData = YouTube.visitorData().getOrNull() ?: run {
            println("could not acquire visitorData - every client will fail for that reason alone")
            return@runBlocking
        }
        println("visitorData: ${YouTube.visitorData?.take(24)}…")
        println("video: $videoId")
        println()

        // Every client the module defines, not just the four the player tries. The point of this is
        // to find out whether *anything* still works, and limiting it to the current list could only
        // ever confirm what the player already reported.
        val all = listOf(
            "WEB" to YouTubeClient.WEB,
            "WEB_REMIX" to YouTubeClient.WEB_REMIX,
            "WEB_CREATOR" to YouTubeClient.WEB_CREATOR,
            "TVHTML5" to YouTubeClient.TVHTML5,
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            "IOS" to YouTubeClient.IOS,
            "ANDROID" to YouTubeClient.ANDROID,
            "ANDROID_VR_NO_AUTH" to YouTubeClient.ANDROID_VR_NO_AUTH,
            "VISIONOS" to YouTubeClient.VISIONOS,
            "VISIONOS_0_1" to YouTubeClient.VISIONOS_0_1,
            "ANDROID_VR_1_65_10" to YouTubeClient.ANDROID_VR_1_65_10,
            "ANDROID_VR_1_43_32" to YouTubeClient.ANDROID_VR_1_43_32,
        )
        // Mint a proof-of-origin token first, and report whether that was even possible. Every
        // client was refused without one, so a probe that does not try with one only re-establishes
        // what is already known.
        val minter = PoTokenMinter()
        val tokens = minter.tokensFor(videoId, sessionId = YouTube.visitorData!!)
        if (tokens == null) {
            println("!! could not mint a proof-of-origin token - is Chrome installed?")
        } else {
            println("minted: player=${tokens.playerRequest.take(24)}… streaming=${tokens.streamingData.take(24)}…")
        }
        println()

        all.forEach { (label, client) ->
            val result = YouTube.player(
                videoId,
                client = client,
                webPlayerPot = tokens?.playerRequest.takeIf { client.useWebPoTokens },
                authenticated = false,
            )
            result.fold(
                onSuccess = { response ->
                    val status = response.playabilityStatus.status
                    val reason = response.playabilityStatus.reason.orEmpty()
                    val audio = response.streamingData?.adaptiveFormats.orEmpty()
                        .filter { it.mimeType.startsWith("audio") }
                    val withUrls = audio.count { !it.url.isNullOrBlank() }
                    println("$label: $status ${if (reason.isBlank()) "" else "- $reason"}")
                    println("    ${audio.size} audio formats, $withUrls with direct urls"
                        + if (client.useWebPoTokens) " [sent a pot]" else "")
                    if (audio.isNotEmpty()) {
                        println("    itags: ${audio.joinToString { "${it.itag}${if (it.url.isNullOrBlank()) "(no url)" else ""}" }}")
                    }
                },
                onFailure = { println("$label: request failed - ${it::class.simpleName}: ${it.message}") },
            )
            println()
        }
    }

    /** YouTube's own "Me at the zoo" - the oldest and least likely video on the site to go away. */
    private const val DEFAULT_VIDEO = "jNQXAC9IVRw"
}
