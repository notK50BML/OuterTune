/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The pair of tokens a player request needs. Mirrors the app's `PoTokenResult`. */
data class PoTokens(val playerRequest: String, val streamingData: String)

/**
 * Mints proof-of-origin tokens, which YouTube now requires before it will hand over a stream.
 *
 * Every client this app can speak as is currently answered with "Sign in to confirm you're not a
 * bot" - anonymously as well as signed in, which is what `ClientProbe` established. A proof-of-origin
 * token is what settles that, and producing one means running BotGuard: obfuscated JavaScript that
 * will not run outside a browser.
 *
 * The phone does this in a WebView. There is no WebView here, but there is something better placed -
 * the Chrome this app already drives for signing in. So [CdpSession] opens a headless page on
 * youtube.com and the same BotGuard interop script runs there, with the same two service calls made
 * from Kotlin exactly as the Android side makes them.
 *
 * The flow, which is not obvious and is worth stating once:
 *
 *  1. Ask `/api/jnn/v1/Create` for a challenge.
 *  2. Run it through BotGuard in the page, which yields a response and a signal output.
 *  3. Exchange the response at `/api/jnn/v1/GenerateIT` for an integrity token.
 *  4. Build a minter in the page from the signal output and that token.
 *  5. Mint one token per identifier - the session id for the player request, the video id for the
 *     stream itself. Two different tokens, and they are not interchangeable.
 *
 * Steps one to four are the expensive part and are done once per session; step five is cheap and
 * happens per track.
 */
class PoTokenMinter(private val dataDirectory: java.io.File = defaultDataDirectory()) {

    private val lock = Mutex()
    private var session: CdpSession? = null
    private var sessionToken: String? = null
    private var boundTo: String? = null
    private var expiresAtMs = 0L

    private val http = OkHttpClient()

    /**
     * Tokens for [videoId] under [sessionId], or null if none can be produced.
     *
     * Null rather than an exception because a machine with no Chrome is not a broken machine - the
     * caller tries without a token and reports what YouTube says, which is more useful than a stack
     * trace about a browser nobody promised.
     */
    suspend fun tokensFor(videoId: String, sessionId: String): PoTokens? = lock.withLock {
        runCatching {
            val page = ensureMinter(sessionId) ?: return null
            val streaming = mint(page, videoId)
            PoTokens(playerRequest = sessionToken!!, streamingData = streaming)
        }.getOrElse {
            // One retry from scratch, then give up. The page can be lost - Chrome tidying a
            // backgrounded renderer, the integrity token ageing out mid-use - and rebuilding costs a
            // couple of seconds against a track that will not play at all otherwise.
            discard()
            runCatching {
                val page = ensureMinter(sessionId) ?: return null
                PoTokens(playerRequest = sessionToken!!, streamingData = mint(page, videoId))
            }.getOrNull()
        }
    }

    /** Throws the page away, so the next request builds a fresh one. */
    fun discard() {
        runCatching { session?.close() }
        session = null
        sessionToken = null
        boundTo = null
        expiresAtMs = 0L
    }

    private suspend fun ensureMinter(sessionId: String): CdpSession? {
        val existing = session
        if (existing != null && boundTo == sessionId && System.currentTimeMillis() < expiresAtMs) {
            return existing
        }
        discard()

        val page = CdpSession.open("https://www.youtube.com/") ?: return null
        session = page

        // The interop script, the same file the phone loads. Evaluated into the page rather than
        // served as a document, because the page is already on youtube.com - which is a real origin
        // rather than one asserted by a base URL, and BotGuard cares where it is running.
        val script = javaClass.getResourceAsStream("/po_token.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("po_token.html missing from the jar")
        page.evaluate(extractScript(script))

        val challenge = serviceRequest(CREATE_URL, "[ \"$REQUEST_KEY\" ]")
        val parsedChallenge = parseChallengeData(challenge)

        // The signal output is kept on the page because the minter needs it later and it is not
        // something that can be carried out to Kotlin and back - it holds live JavaScript objects.
        val botguardResponse = page.evaluateString(
            """
            runBotGuard($parsedChallenge).then(function (result) {
                window.__otWebPoSignalOutput = result.webPoSignalOutput
                return result.botguardResponse
            })
            """.trimIndent(),
            timeoutMs = 30_000,
        ) ?: error("BotGuard returned no response")

        val integrity = serviceRequest(
            GENERATE_IT_URL,
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        )
        val (integrityToken, expirySeconds) = parseIntegrityTokenData(integrity)

        page.evaluate(
            """
            createPoTokenMinter(window.__otWebPoSignalOutput, $integrityToken).then(function () {
                return "ready"
            })
            """.trimIndent(),
            timeoutMs = 30_000,
        )

        sessionToken = mint(page, sessionId)
        boundTo = sessionId
        // A minute short of the stated expiry, so a token is never minted from a minter that expires
        // between being used and being answered.
        expiresAtMs = System.currentTimeMillis() + (expirySeconds * 1000) - 60_000
        return page
    }

    private suspend fun mint(page: CdpSession, identifier: String): String {
        val bytes = page.evaluateString(
            "obtainPoToken(${stringToU8(identifier)}).then(function (t) { return t.join(',') })"
        ) ?: error("no token for $identifier")
        return u8ToBase64(bytes)
    }

    /**
     * One call to the BotGuard service.
     *
     * Headers copied exactly from the Android side, including the grpc-web user agent - this is a
     * gRPC-web endpoint wearing JSON, and it refuses requests that do not look the part.
     */
    private suspend fun serviceRequest(url: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody())
            .headers(
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json+protobuf",
                    "x-goog-api-key" to GOOGLE_API_KEY,
                    "x-user-agent" to "grpc-web-javascript/0.1",
                ).toHeaders()
            )
            .build()
        http.newCall(request).execute().use { response ->
            val text = if (response.isSuccessful) response.body.string() else null
            // An empty 200 counts as a failure: passing "" on would fail later in a parser, with a
            // message about JSON rather than about the request that actually went wrong.
            if (text.isNullOrEmpty()) error("BotGuard service returned ${response.code} with no body")
            text
        }
    }

    /**
     * The JavaScript out of the interop HTML.
     *
     * The file is a document with one script in it, and only the script is wanted - evaluating the
     * markup would be a syntax error.
     */
    internal fun extractScript(html: String): String {
        val open = html.indexOf("<script")
        val start = html.indexOf('>', open) + 1
        val end = html.lastIndexOf("</script>")
        require(open >= 0 && end > start) { "po_token.html has no script to run" }
        return html.substring(start, end)
    }

    private companion object {
        const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    }
}
