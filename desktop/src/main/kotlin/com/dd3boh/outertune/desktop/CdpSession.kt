/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A page in a headless Chrome, driven over the DevTools Protocol.
 *
 * The desktop already drives Chrome for signing in, and this is the same idea put to a second use:
 * there are things only a browser can do, and the machine running this has one. Here it is running
 * BotGuard - see [PoTokenMinter] - which is JavaScript that will not run anywhere else.
 *
 * Headless and on a profile of this app's own, because nothing here needs a window or a person. That
 * is the difference from the sign-in flow, which needs both.
 *
 * Unlike the Android WebView this replaces, CDP can await a promise for you: `Runtime.evaluate` with
 * `awaitPromise` returns the resolved value. The WebView version had to post results back through a
 * JavaScript interface and match them to continuations by hand, which is most of that file's length
 * and all of its concurrency risk. Here an evaluation is a suspending function that returns a value.
 */
class CdpSession private constructor(
    private val process: Process,
    private val profile: File,
    private val client: OkHttpClient,
    private val socket: WebSocket,
    private val incoming: ArrayBlockingQueue<String>,
    private val sessionId: String,
) : AutoCloseable {

    private var nextId = 1
    private val pending = ConcurrentHashMap<Int, String>()

    /**
     * Evaluates [expression] in the page and returns its result as JSON.
     *
     * Awaits promises, so `fetch(...).then(...)` can be written as an expression rather than as a
     * callback that has to find its way home. Throws on a JavaScript exception rather than returning
     * null, because every caller here treats a failed evaluation as fatal to the whole attempt and
     * swallowing it would only move the failure somewhere less informative.
     */
    suspend fun evaluate(expression: String, timeoutMs: Long = 20_000): JsonObject {
        val id = nextId++
        val command = buildString {
            append("""{"id":$id,"sessionId":"$sessionId","method":"Runtime.evaluate","params":{""")
            append(""""expression":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(expression))},""")
            append(""""awaitPromise":true,"returnByValue":true,"userGesture":true}}""")
        }
        socket.send(command)

        val reply = await(id, timeoutMs) ?: error("Chrome did not answer in ${timeoutMs}ms")
        val root = Json.parseToJsonElement(reply) as? JsonObject ?: error("unparseable reply")
        (root["error"] as? JsonObject)?.let { error("CDP error: ${it["message"]}") }
        val result = root["result"] as? JsonObject ?: error("reply had no result")
        (result["exceptionDetails"] as? JsonObject)?.let {
            error("JavaScript threw: ${describe(it)}")
        }
        return result["result"] as? JsonObject ?: error("evaluation returned nothing")
    }

    /** The `value` of an evaluation, as a string, or null when it was not one. */
    suspend fun evaluateString(expression: String, timeoutMs: Long = 20_000): String? =
        (evaluate(expression, timeoutMs)["value"] as? JsonPrimitive)?.content

    private suspend fun await(id: Int, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        pending[id]?.let { return@withContext pending.remove(id) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val message = incoming.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: continue
            val replyId = (root["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
            if (replyId == id) return@withContext message
            // Another command's reply arriving first. Kept rather than dropped: commands are sent
            // one at a time here, but an out-of-order reply that is discarded becomes a hang, and
            // that is a far worse failure than a few bytes held.
            pending[replyId] = message
        }
        null
    }

    private fun describe(exceptionDetails: JsonObject): String {
        val exception = exceptionDetails["exception"] as? JsonObject
        return (exception?.get("description") as? JsonPrimitive)?.content
            ?: (exceptionDetails["text"] as? JsonPrimitive)?.content
            ?: "unknown error"
    }

    override fun close() {
        runCatching { socket.close(1000, null) }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { process.destroy() }
        runCatching { process.waitFor(3, TimeUnit.SECONDS) }
        runCatching { process.destroyForcibly() }
        runCatching { profile.deleteRecursively() }
    }

    companion object {

        /**
         * Launches Chrome and attaches to a page at [url].
         *
         * Returns null rather than throwing when there is no browser, because that is a fact about
         * the machine rather than a fault - the caller falls back to whatever it can do without one.
         */
        suspend fun open(url: String, headless: Boolean = true): CdpSession? = withContext(Dispatchers.IO) {
            val browser = findBrowser() ?: return@withContext null
            val profile = File(defaultDataDirectory(), "cdp-profile-${System.nanoTime()}")
            profile.mkdirs()
            val port = ServerSocket(0).use { it.localPort }

            val process = ProcessBuilder(
                buildList {
                    add(browser.absolutePath)
                    add("--remote-debugging-port=$port")
                    add("--user-data-dir=${profile.absolutePath}")
                    add("--no-first-run")
                    add("--no-default-browser-check")
                    // Nothing here needs a window, and one appearing while music is meant to start
                    // would be alarming rather than informative.
                    if (headless) add("--headless=new")
                    add("--disable-gpu")
                    add(url)
                }
            ).redirectErrorStream(true).start()

            val http = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            val browserWs = awaitJson(http, "http://127.0.0.1:$port/json/version")
                ?.let { (it["webSocketDebuggerUrl"] as? JsonPrimitive)?.content }
            if (browserWs == null) {
                process.destroyForcibly()
                profile.deleteRecursively()
                return@withContext null
            }

            // The page target, not the browser one. Runtime.evaluate needs a page to evaluate in -
            // the browser endpoint has no document, which is the same distinction that made
            // Network.getAllCookies fail there.
            val targetId = awaitTargetId(http, "http://127.0.0.1:$port/json/list")
            if (targetId == null) {
                process.destroyForcibly()
                profile.deleteRecursively()
                return@withContext null
            }

            val socketClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            val incoming = ArrayBlockingQueue<String>(256)
            val socket = socketClient.newWebSocket(
                Request.Builder().url(browserWs).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Dropped rather than blocking the socket thread if the queue backs up. The
                        // replies that matter are read promptly; events nobody asked for are not.
                        incoming.offer(text)
                    }
                },
            )

            // Attach, so later commands can name a session rather than a target.
            socket.send(
                """{"id":0,"method":"Target.attachToTarget","params":{"targetId":"$targetId","flatten":true}}"""
            )
            val sessionId = awaitSessionId(incoming)
            if (sessionId == null) {
                runCatching { socket.close(1000, null) }
                process.destroyForcibly()
                profile.deleteRecursively()
                return@withContext null
            }

            CdpSession(process, profile, socketClient, socket, incoming, sessionId)
        }

        private suspend fun awaitJson(client: OkHttpClient, url: String): JsonObject? {
            repeat(60) {
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response: Response ->
                        Json.parseToJsonElement(response.body.string()) as? JsonObject
                    }
                }.getOrNull()?.let { return it }
                delay(500)
            }
            return null
        }

        private suspend fun awaitTargetId(client: OkHttpClient, url: String): String? {
            repeat(40) {
                val targets = runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { response: Response ->
                        Json.parseToJsonElement(response.body.string()) as? JsonArray
                    }
                }.getOrNull()
                targets?.firstNotNullOfOrNull { element ->
                    val target = element as? JsonObject ?: return@firstNotNullOfOrNull null
                    if ((target["type"] as? JsonPrimitive)?.content != "page") return@firstNotNullOfOrNull null
                    (target["id"] as? JsonPrimitive)?.content
                }?.let { return it }
                delay(250)
            }
            return null
        }

        private suspend fun awaitSessionId(incoming: ArrayBlockingQueue<String>): String? =
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    val message = incoming.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject
                        ?: continue
                    val result = root["result"] as? JsonObject ?: continue
                    (result["sessionId"] as? JsonPrimitive)?.content?.let { return@withContext it }
                }
                null
            }

        /** The same search the sign-in flow uses; Edge counts, being the same engine. */
        private fun findBrowser(): File? {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val candidates = when {
                os.contains("win") -> listOfNotNull(
                    System.getenv("PROGRAMFILES"),
                    System.getenv("PROGRAMFILES(X86)"),
                    System.getenv("LOCALAPPDATA"),
                ).flatMap { root ->
                    listOf(
                        "Google/Chrome/Application/chrome.exe",
                        "Microsoft/Edge/Application/msedge.exe",
                        "BraveSoftware/Brave-Browser/Application/brave.exe",
                        "Chromium/Application/chrome.exe",
                    ).map { File(root, it) }
                }

                os.contains("mac") -> listOf(
                    File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
                    File("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"),
                    File("/Applications/Chromium.app/Contents/MacOS/Chromium"),
                )

                else -> listOf(
                    "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
                    "/usr/bin/microsoft-edge", "/snap/bin/chromium",
                ).map(::File)
            }
            return candidates.firstOrNull { it.isFile }
        }
    }
}
