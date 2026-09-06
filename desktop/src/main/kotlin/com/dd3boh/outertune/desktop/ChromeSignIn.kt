/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Signs in by driving the browser the user already has, over the DevTools Protocol.
 *
 * This is the approach that costs nothing and works anyway, and it is worth understanding why it is
 * possible at all, because the obvious version of it is not.
 *
 * Reading Chrome's cookie *store* is a fight: since Chrome 127 it is under app-bound encryption
 * whose key is tied to the Chrome process. But there is no need to read the file. **Ask Chrome.**
 * `Network.getAllCookies` is a CDP method, and a browser answering a question about its own cookies
 * is not decrypting anything.
 *
 * So: launch the Chrome that is already installed, pointed at a profile directory of this app's own,
 * let the user sign in - it is real Chrome, so BotGuard, reCAPTCHA, 2FA and passkeys all work,
 * because it is precisely the browser Google expects - and then read the cookies out over CDP.
 *
 * The separate profile is required rather than chosen. Since Chrome 136 the remote-debugging
 * switches are ignored on the default profile, specifically so that malware cannot attach to a real
 * profile and lift its cookies. That restriction is what makes reading the store hard, and the same
 * restriction is what forces this into the shape that works - which is also the more honest shape,
 * since the session is created in a profile belonging to this app rather than taken out of the
 * user's own.
 *
 * Nothing new is bundled. CDP is JSON over a WebSocket, and OkHttp - already here under Ktor - has
 * one. Against embedding Chromium at ~150MB, this is this file.
 */
object ChromeSignIn {

    /** Where the flow has got to, so the screen can say something true while it waits. */
    sealed interface Progress {
        data object Launching : Progress
        data object WaitingForSignIn : Progress
        data object Reading : Progress
    }

    /**
     * True if there is a browser to drive.
     *
     * Checked before the option is offered: an action that cannot work is worse than an absent one,
     * because it reads as the thing that was supposed to work.
     */
    fun isAvailable(): Boolean = findBrowser() != null

    /**
     * Runs the whole flow and returns a cookie string, or null if the user gave up.
     *
     * @param onProgress called on the caller's context as the flow moves.
     */
    suspend fun signIn(
        timeoutMs: Long = 5 * 60 * 1000,
        onProgress: (Progress) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val browser = findBrowser() ?: return@withContext null
        val profile = File(defaultDataDirectory(), "signin-profile")
        profile.mkdirs()
        val port = freePort()

        onProgress(Progress.Launching)
        val process = ProcessBuilder(
            browser.absolutePath,
            "--remote-debugging-port=$port",
            // Required, not optional - see the class doc. Also keeps this entirely separate from
            // whatever the user has open already, so signing in here cannot disturb that.
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run",
            "--no-default-browser-check",
            // A window with one job. Without this Chrome may restore tabs, show what's-new pages, or
            // reuse an existing window and ignore the URL entirely.
            "--new-window",
            SIGN_IN_URL,
        ).redirectErrorStream(true).start()

        try {
            val wsUrl = awaitDebuggerUrl(port) ?: return@withContext null
            onProgress(Progress.WaitingForSignIn)
            awaitSession(wsUrl, timeoutMs, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            process.destroy()
            // Given a moment to close its own files before the directory goes.
            runCatching { process.waitFor(3, TimeUnit.SECONDS) }
            process.destroyForcibly()
            // The profile is a credential store of its own once signed in. Keeping it would mean the
            // app had quietly acquired a second copy of the session it only needed once.
            runCatching { profile.deleteRecursively() }
        }
    }

    /**
     * Waits for Chrome to publish its debugger endpoint.
     *
     * Polled rather than assumed: the port is open only once the browser has started, and how long
     * that takes depends on the machine and on whether Chrome was already running.
     */
    private suspend fun awaitDebuggerUrl(port: Int): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        repeat(60) {
            if (!currentCoroutineContext().isActive) return null
            val url = runCatching {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/json/version").build())
                    .execute()
                    .use { response: Response ->
                        val body = response.body?.string().orEmpty()
                        Json.parseToJsonElement(body).jsonObject["webSocketDebuggerUrl"]
                            ?.jsonPrimitive?.content
                    }
            }.getOrNull()
            if (url != null) return url
            delay(500)
        }
        return null
    }

    /**
     * Polls the browser's cookies until a signed-in session appears.
     *
     * Polling rather than subscribing to network events. The interesting moment - "a session now
     * exists" - is a property of the cookie jar, not of any one response, and watching responses
     * would mean deciding which of them meant success. Asking once a second is a request to a local
     * process and costs nothing.
     */
    private suspend fun awaitSession(
        wsUrl: String,
        timeoutMs: Long,
        onProgress: (Progress) -> Unit,
    ): String? {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val incoming = ArrayBlockingQueue<String>(32)
        var socket: WebSocket? = null
        try {
            socket = client.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Dropped rather than blocking the socket thread if nothing is reading. Only
                        // the reply to the most recent request matters.
                        incoming.offer(text)
                    }
                },
            )

            val deadline = System.currentTimeMillis() + timeoutMs
            var id = 1
            while (System.currentTimeMillis() < deadline) {
                if (!currentCoroutineContext().isActive) return null
                incoming.clear()
                socket.send("""{"id":${id++},"method":"Network.getAllCookies"}""")

                val reply = withContext(Dispatchers.IO) {
                    incoming.poll(5, TimeUnit.SECONDS)
                }
                val cookie = reply?.let { extractSession(it) }
                if (cookie != null) {
                    onProgress(Progress.Reading)
                    return cookie
                }
                delay(1000)
            }
            return null
        } finally {
            runCatching { socket?.close(1000, null) }
            runCatching { client.dispatcher.executorService.shutdown() }
        }
    }

    /**
     * Pulls a YouTube session out of a `Network.getAllCookies` reply, or null if there is not one yet.
     *
     * The same set and the same rule as everywhere else: only YouTube and Google cookies, only the
     * session ones, and nothing counts as signed in without `SAPISID`.
     */
    internal fun extractSession(message: String): String? {
        // Every step is a safe cast rather than a checked accessor. The far end is a browser, not a
        // contract: it sends replies to other commands, events nobody asked for, and error objects
        // down the same socket. kotlinx's `.jsonArray` and friends *throw* on a shape they did not
        // expect, so one unexpected message would end the sign-in instead of costing a single poll.
        val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
        val result = root["result"] as? JsonObject ?: return null
        val cookies = result["cookies"] as? JsonArray ?: return null

        val found = LinkedHashMap<String, String>()
        cookies.forEach { element ->
            val cookie = element as? JsonObject ?: return@forEach
            val domain = (cookie["domain"] as? JsonPrimitive)?.content?.removePrefix(".") ?: return@forEach
            if (!domain.endsWith("youtube.com") && !domain.endsWith("google.com")) return@forEach
            val name = (cookie["name"] as? JsonPrimitive)?.content ?: return@forEach
            val value = (cookie["value"] as? JsonPrimitive)?.content ?: return@forEach
            if (name !in WANTED || value.isBlank()) return@forEach
            // A youtube.com cookie wins over a google.com one of the same name - the request about
            // to be signed is going to music.youtube.com.
            if (name !in found || domain.endsWith("youtube.com")) found[name] = value
        }
        if ("SAPISID" !in found) return null
        return found.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * The first Chromium-based browser that can be found.
     *
     * Edge is included and worth including: it is on every Windows install, so this works even for
     * someone who has never installed Chrome. They are the same engine and the same protocol.
     */
    private fun findBrowser(): File? = candidatePaths().firstOrNull { it.isFile }

    private fun candidatePaths(): List<File> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> {
                val roots = listOfNotNull(
                    System.getenv("PROGRAMFILES"),
                    System.getenv("PROGRAMFILES(X86)"),
                    System.getenv("LOCALAPPDATA"),
                )
                val relative = listOf(
                    "Google/Chrome/Application/chrome.exe",
                    "Microsoft/Edge/Application/msedge.exe",
                    "BraveSoftware/Brave-Browser/Application/brave.exe",
                    "Chromium/Application/chrome.exe",
                )
                roots.flatMap { root -> relative.map { File(root, it) } }
            }

            os.contains("mac") -> listOf(
                File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                File("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"),
                File("/Applications/Chromium.app/Contents/MacOS/Chromium"),
            )

            else -> listOf(
                File("/usr/bin/google-chrome"),
                File("/usr/bin/chromium"),
                File("/usr/bin/chromium-browser"),
                File("/usr/bin/microsoft-edge"),
                File("/snap/bin/chromium"),
            )
        }
    }

    /**
     * A port nobody is using.
     *
     * Bound and released to find out, which leaves a moment in which something else could take it.
     * That race is preferable to a fixed port, which fails whenever anything else happens to hold it
     * - including a previous run of this that did not shut down cleanly.
     *
     * Chrome binds the debugging port to loopback unless told otherwise, and it is not told
     * otherwise: anything that can reach that port can drive the browser.
     */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private const val SIGN_IN_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/"

    /** The same set [CookieImport] keeps, for the same reasons - see there. */
    private val WANTED = setOf(
        "SAPISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "APISID",
        "SID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "HSID",
        "SSID",
        "LOGIN_INFO",
        "PREF",
        "VISITOR_INFO1_LIVE",
    )
}
