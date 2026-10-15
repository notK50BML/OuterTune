/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * ApiService.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */
package com.my.kizzy.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Modified by Zion Huang
 */
class ApiService(private val token: String) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /**
     * Turns a public image URL into the `mp:` asset key a presence can carry.
     *
     * Discord will not display an arbitrary URL on a presence card. The image has to be registered
     * with it first, which hands back a path under its own media proxy, and that path - prefixed
     * `mp:` - is what the `assets` object actually holds.
     *
     * This used to go through a third party: a Cloudflare Worker belonging to another project,
     * which did this exact call and returned the result. It stopped existing, and because every
     * failure here is swallowed into a null asset, the only symptom was album art silently not
     * appearing - no error, no log, nothing to connect it to a server somebody else maintains.
     *
     * So it talks to Discord directly now, with the token the gateway connection is already using.
     * It is one fewer machine in the path, one fewer thing to go down, and it stops every cover of
     * everything anyone plays being announced to a stranger's worker on the way past.
     */
    suspend fun externalAsset(applicationId: String, url: String): Result<String> = runCatching {
        val response = client.post {
            url("$DISCORD_API/applications/$applicationId/external-assets")
            // A user token, unprefixed - this is the same credential the gateway identifies with,
            // and Discord expects it bare here rather than as "Bearer ...".
            header(HttpHeaders.Authorization, token)
            contentType(ContentType.Application.Json)
            setBody(ExternalAssetRequest(urls = listOf(url)))
        }
        if (response.status != HttpStatusCode.OK) {
            // The body rather than just the code. Discord explains itself here - an expired token,
            // an image host it could not reach - and that message is the whole difference between
            // "the cover is missing" and knowing why.
            error("Discord answered ${response.status.value}: ${response.bodyAsText().take(200)}")
        }
        val path = response.body<List<ExternalAsset>>().firstOrNull()?.path
        require(!path.isNullOrBlank()) { "Discord accepted the image but named no asset for it" }
        "mp:$path"
    }

    companion object {
        private const val DISCORD_API = "https://discord.com/api/v9"
    }
}
