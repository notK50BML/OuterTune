/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * KizzyRPC.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.rpc

import com.my.kizzy.gateway.DiscordWebSocket
import com.my.kizzy.gateway.entities.presence.Activity
import com.my.kizzy.gateway.entities.presence.Assets
import com.my.kizzy.gateway.entities.presence.Metadata
import com.my.kizzy.gateway.entities.presence.Presence
import com.my.kizzy.gateway.entities.presence.Timestamps
import com.my.kizzy.repository.KizzyRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Modified by Zion Huang
 */
open class KizzyRPC(token: String) {
    private val kizzyRepository = KizzyRepository(token)
    private val discordWebSocket = DiscordWebSocket(token)

    fun closeRPC() {
        discordWebSocket.close()
    }

    fun isRpcRunning(): Boolean {
        return discordWebSocket.isWebSocketConnected()
    }

    /** Last thing that happened to the gateway, in words, for display in settings. */
    fun lastStatus(): String = discordWebSocket.lastStatus

    /**
     * Why the last album cover failed to resolve, or null if it did not fail.
     *
     * Separate from [lastStatus] because the two fail independently and look identical from
     * outside: the gateway can be perfectly connected, the card can be on screen with its title
     * and artist, and the art alone can be missing because the thing that registers images is
     * refusing. That combination had no way to report itself at all.
     */
    fun lastImageError(): String? = kizzyRepository.lastError

    /**
     * Opens the gateway and waits for it to finish identifying. Returns the outcome as text so a
     * caller can show it, rather than failing silently the way the rest of this class does.
     */
    suspend fun testConnection(timeoutMs: Long = 20_000L): String {
        return try {
            if (!isRpcRunning()) discordWebSocket.connect()
            if (discordWebSocket.awaitConnected(timeoutMs)) {
                "Connected to Discord as expected"
            } else {
                discordWebSocket.lastStatus
            }
        } catch (e: Exception) {
            "Failed: ${e.message ?: e::class.simpleName}"
        }
    }

    suspend fun stopActivity() {
        if (!isRpcRunning()) {
            discordWebSocket.connect()
        }
        val presence = Presence(
            activities = emptyList()
        )
        discordWebSocket.sendActivity(presence)
    }

    suspend fun setActivity(
        name: String,
        state: String?,
        stateUrl: String? = null,
        details: String?,
        detailsUrl: String? = null,
        largeImage: RpcImage?,
        smallImage: RpcImage?,
        largeText: String? = null,
        smallText: String? = null,
        buttons: List<Pair<String, String>>? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        type: Type = Type.LISTENING,
        statusDisplayType: StatusDisplayType = StatusDisplayType.NAME,
        streamUrl: String? = null,
        applicationId: String? = null,
        status: String? = "online",
        since: Long? = null,
    ) {
        if (!isRpcRunning()) {
            discordWebSocket.connect()
        }
        fun presence(large: String?, small: String?) = Presence(
            activities = listOf(
                Activity(
                    name = name,
                    state = state,
                    stateUrl = stateUrl,
                    details = details,
                    detailsUrl = detailsUrl,
                    type = type.value,
                    statusDisplayType = statusDisplayType.value,
                    timestamps = Timestamps(startTime, endTime),
                    assets = Assets(
                        largeImage = large,
                        smallImage = small,
                        largeText = largeText,
                        smallText = smallText
                    ),
                    buttons = buttons?.map { it.first },
                    metadata = Metadata(buttonUrls = buttons?.map { it.second }),
                    applicationId = applicationId.takeIf { !buttons.isNullOrEmpty() },
                    url = streamUrl
                )
            ),
            afk = true,
            since = since,
            status = status ?: "online"
        )

        // The words first, the pictures when they are ready.
        //
        // Both images have to be registered with Discord before a presence can carry them, and that
        // used to happen in turn, each allowed up to 30 seconds, before anything was sent. All that
        // time the card went on showing the previous song - which on a slow connection, or on a skip
        // straight after a skip, is the presence "stuck on the same song". Now both resolve at
        // once; whatever is ready within a moment goes out with the new title, and if either was
        // still pending the card is sent again with it once it arrives. A newer song cancels this
        // whole call (the caller's collectLatest), so a late picture can never land on the wrong
        // song.
        coroutineScope {
            val large = async { largeImage?.resolveImage(kizzyRepository, applicationId) }
            val small = async { smallImage?.resolveImage(kizzyRepository, applicationId) }
            // Awaiting with a timeout does not cancel the request itself - it carries on below.
            val quickLarge = withTimeoutOrNull(QUICK_IMAGE_WAIT_MS) { large.await() }
            val quickSmall = withTimeoutOrNull(QUICK_IMAGE_WAIT_MS) { small.await() }
            discordWebSocket.sendActivity(presence(quickLarge, quickSmall))

            val largeLate = largeImage != null && quickLarge == null
            val smallLate = smallImage != null && quickSmall == null
            if (largeLate || smallLate) {
                val finalLarge = large.await()
                val finalSmall = small.await()
                if ((largeLate && finalLarge != null) || (smallLate && finalSmall != null)) {
                    discordWebSocket.sendActivity(presence(finalLarge, finalSmall))
                }
            }
        }
    }

    enum class Type(val value: Int) {
        PLAYING(0),
        STREAMING(1),
        LISTENING(2),
        WATCHING(3),
        COMPETING(5)
    }

    enum class StatusDisplayType(val value: Int) {
        NAME(0),
        STATE(1),
        DETAILS(2)
    }

    companion object {
        /** How long a new song's card waits for its pictures before going out without them. */
        private const val QUICK_IMAGE_WAIT_MS = 1_500L

        suspend fun getUserInfo(token: String): Result<UserInfo> = runCatching {
            val client = HttpClient()
            val response = client.get("https://discord.com/api/v9/users/@me") {
                header("Authorization", token)
            }.bodyAsText()
            val json = JSONObject(response)
            val username = json.getString("username")
            val name = json.getString("global_name")
            client.close()

            UserInfo(username, name)
        }
    }
}