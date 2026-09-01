/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeLocale
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking

/**
 * Establishes how far a desktop build gets before it needs a browser.
 *
 * This exists because the interesting question about a Windows port is not "does Kotlin run on
 * Windows" - obviously it does - but which specific parts of the playback path are actually tied to
 * Android, and that is a question about YouTube's behaviour rather than about our code. The answer
 * cannot be reasoned out; it has to be asked.
 *
 * What this establishes:
 *
 * - Whether :innertube works unmodified off Android. It is already `kotlin("jvm")` with no Android
 *   imports, so search and browse are expected to work, and that covers the whole API layer.
 * - Which player clients still return a usable stream without a PoToken. This is the real question.
 *   A client that does is a client a desktop build could ship on immediately; if none do, then
 *   BotGuard has to run somewhere, and that means embedding a browser engine.
 *
 * Run with: `gradlew :desktop:run --args="<videoId>"` after adding the application plugin, or
 * invoke [main] directly from an IDE. Deliberately kept as a probe rather than an app: nothing here
 * should grow into the desktop client, it only informs what that client has to be built out of.
 */
object StackProbe {

    /** Clients worth asking, in the order a desktop build would prefer them. */
    private val CANDIDATES = listOf(
        "WEB_REMIX" to YouTubeClient.WEB_REMIX,
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        "IOS" to YouTubeClient.IOS,
        "ANDROID_VR_NO_AUTH" to YouTubeClient.ANDROID_VR_NO_AUTH,
        "ANDROID_VR_1_43_32" to YouTubeClient.ANDROID_VR_1_43_32,
        "VISIONOS" to YouTubeClient.VISIONOS,
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val videoId = args.firstOrNull() ?: "dQw4w9WgXcQ"
        println("=== OuterTune desktop stack probe ===")
        println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")}")
        println("video: $videoId")

        // Both of these are set by App.onCreate on Android, and without them YouTube rejects
        // requests with a bare 400 INVALID_ARGUMENT that looks like a platform problem and is not
        // one. Worth doing first and reporting, so a failure here is never mistaken for a desktop
        // limitation.
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        val visitorData = YouTube.visitorData().getOrNull()
        if (visitorData != null) {
            YouTube.visitorData = visitorData
            println("visitorData: acquired")
        } else {
            println("visitorData: FAILED to acquire - everything below is suspect")
        }
        println()

        probeApiLayer()
        println()
        probePlayerClients(videoId)
    }

    /**
     * The API layer, which is the part expected to be free. If this fails, the port is in far more
     * trouble than a missing browser and everything below is moot.
     */
    private suspend fun probeApiLayer() {
        println("--- API layer (no browser needed) ---")
        runCatching { YouTube.search("aphex twin", YouTube.SearchFilter.FILTER_SONG) }
            .onSuccess { result ->
                result.onSuccess { println("  search:      OK (${it.items.size} items)") }
                    .onFailure { println("  search:      FAILED - ${it::class.simpleName}: ${it.message}") }
            }
            .onFailure { println("  search:      THREW - ${it::class.simpleName}: ${it.message}") }
    }

    /**
     * The actual question. Each client is asked for a stream with no PoToken and no signature
     * timestamp - which is exactly the position a desktop build starts from, having no WebView to
     * produce either.
     *
     * Three outcomes worth telling apart, because they mean very different things:
     *
     * - a URL: this client works today, and a desktop build could ship on it.
     * - formats but every one of them cipher-protected: the stream is there but needs the signature
     *   deobfuscated, which needs YouTube's player JS executed.
     * - a playability status other than OK: the client was refused outright, usually for a missing
     *   PoToken, and no amount of local work fixes that.
     */
    private suspend fun probePlayerClients(videoId: String) {
        println("--- player clients (no PoToken, no signatureTimestamp) ---")
        // The first client that produced a real URL, kept so it can actually be fetched below.
        var firstUsable: Pair<String, YouTubeClient>? = null
        var firstUsableUrl: String? = null
        for ((name, client) in CANDIDATES) {
            val label = name.padEnd(32)
            val result = runCatching { YouTube.player(videoId, client = client) }.getOrElse {
                println("  $label THREW - ${it::class.simpleName}: ${it.message}")
                continue
            }
            result.onSuccess { response ->
                val status = response.playabilityStatus.status
                val formats = response.streamingData?.adaptiveFormats.orEmpty()
                val audio = formats.filter { it.mimeType.startsWith("audio") }
                val plain = audio.count { !it.url.isNullOrBlank() }
                val ciphered = audio.count { it.url.isNullOrBlank() && !it.signatureCipher.isNullOrBlank() }

                val verdict = when {
                    status != "OK" -> "REFUSED ($status${response.playabilityStatus.reason?.let { r -> ": $r" } ?: ""})"
                    plain > 0 -> "USABLE - $plain audio format(s) with a direct URL"
                    ciphered > 0 -> "NEEDS CIPHER - $ciphered audio format(s), all signature-protected"
                    else -> "NO AUDIO FORMATS (status OK, streamingData empty)"
                }
                println("  $label $verdict")

                if (firstUsable == null && plain > 0) {
                    firstUsable = name to client
                    firstUsableUrl = audio.first { !it.url.isNullOrBlank() }.url
                }
            }.onFailure {
                println("  $label FAILED - ${it::class.simpleName}: ${it.message}")
            }
        }

        println()
        println("A USABLE line means a desktop build can stream on that client with no browser.")
        println("NEEDS CIPHER means YouTube's player JS has to be executed somewhere.")
        println("REFUSED usually means a PoToken, which means BotGuard, which means a browser engine.")

        val (name, client) = firstUsable ?: run {
            println()
            println("No client produced a URL, so there is nothing to fetch.")
            return
        }
        println()
        // The client's own headers travel with the URL: googlevideo has been observed refusing a
        // fetch whose headers do not match the client the URL was issued to.
        probeStreamFetch(firstUsableUrl!!, mapOf("User-Agent" to client.userAgent))
        println("  (via $name)")
    }

    /**
     * Fetches the first few bytes of a stream, because a URL is not the same thing as a stream.
     *
     * googlevideo hands out URLs that then answer 403 - that is exactly the failure this fork spent
     * weeks on, and it is invisible at the point the URL is produced. So "USABLE" above is only a
     * claim about the player response; this is the part that checks the claim, and until it passes
     * nothing should be said about desktop playback working.
     *
     * A ranged request rather than a full one: enough to prove the CDN serves this client from this
     * machine, without pulling a whole song to find out.
     */
    private suspend fun probeStreamFetch(url: String, headers: Map<String, String>) {
        println("--- fetching actual audio bytes ---")
        val client = HttpClient(OkHttp)
        try {
            val response = client.get(url) {
                headers.forEach { (name, value) -> header(name, value) }
                header("Range", "bytes=0-65535")
            }
            val bytes = response.body<ByteArray>()
            val status = response.status.value
            when {
                bytes.isEmpty() -> println("  FAILED - HTTP $status but no body")
                status in 200..299 -> println("  OK - HTTP $status, ${bytes.size} bytes of audio received")
                else -> println("  FAILED - HTTP $status after ${bytes.size} bytes")
            }
        } catch (e: Exception) {
            println("  FAILED - ${e::class.simpleName}: ${e.message}")
        } finally {
            client.close()
        }
    }
}
