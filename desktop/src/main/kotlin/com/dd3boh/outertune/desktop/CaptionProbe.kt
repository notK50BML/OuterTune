package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking

/**
 * Asks whether YouTube still returns a caption track, and what it looks like.
 *
 * Exists to separate two failures that look identical from inside the app: the caption fetch coming
 * back empty, and the cleanup that runs afterwards discarding what it was given. Only the first is
 * visible from here, which is the point - if this prints lines, the fault is downstream of it.
 */
object CaptionProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData = YouTube.visitorData().getOrNull() ?: run {
            println("no visitorData"); return@runBlocking
        }
        for (videoId in (args.takeIf { it.isNotEmpty() } ?: arrayOf("dQw4w9WgXcQ"))) {
            println("=== $videoId ===")
            YouTube.transcript(videoId).fold(
                onSuccess = { lrc ->
                    val lines = lrc.lines().filter { it.isNotBlank() }
                    println("  OK - ${lines.size} line(s)")
                    lines.take(6).forEach { println("    $it") }
                },
                onFailure = { println("  FAILED - ${it::class.simpleName}: ${it.message}") },
            )
        }
    }
}
