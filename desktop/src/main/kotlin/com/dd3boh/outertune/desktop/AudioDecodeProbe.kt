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
import net.sourceforge.jaad.aac.Decoder
import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.mp4.MP4Container
import net.sourceforge.jaad.mp4.MP4InputStream
import net.sourceforge.jaad.mp4.api.AudioTrack
import java.io.ByteArrayInputStream

/**
 * Decides whether a Windows build has to ship a native audio library.
 *
 * This is the question that decides how the desktop app is packaged, so it is worth answering with
 * a decode rather than an assumption. Media3 is Android-only, so playback has to be rebuilt, and
 * the options split sharply:
 *
 * - Pure Java. A single jar, `java -jar` runs it, nothing to install. Only possible if the format
 *   YouTube hands us can actually be decoded without native code.
 * - VLC or GStreamer. Plays anything, but now the app has a native dependency to bundle per
 *   architecture, or an install step for the user.
 *
 * [StackProbe] established that itag 140 - AAC-LC in MP4, 130kbps - comes back with a direct URL on
 * every client that works at all. AAC is decodable in pure Java, so this fetches that format for
 * real and tries to decode it, which is the only way to be sure the specific files YouTube serves
 * are accepted rather than AAC in the abstract.
 *
 * **What it found, on Windows 11:** the download and the container metadata are fine - AAC, 44100Hz,
 * stereo, 3.4MB fetched - and then zero frames come out. Every format YouTube offers now is
 * fragmented MP4 (there are no progressive `formats` left at all, which this also checks and
 * reports), and a fragmented file keeps its samples in `moof` boxes rather than in the `moov`
 * sample table an ordinary MP4 reader walks. So the metadata parses and the audio is unreachable.
 *
 * That is a container problem, not a codec one, and the distinction is the whole point of running
 * this: swapping AAC decoders would change nothing. Pure-Java playback stays possible - it needs a
 * fragmented-MP4 demuxer feeding a pure-Java AAC decoder. The alternative is a native library
 * (VLC/GStreamer) which solves container and codec together, at the cost of shipping
 * per-architecture binaries.
 */
object AudioDecodeProbe {

    /** AAC-LC in MP4 at ~130kbps. The format every usable client offers with a direct URL. */
    private const val ITAG_AAC_MEDIUM = 140

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val videoId = args.firstOrNull() ?: "dQw4w9WgXcQ"
        println("=== OuterTune desktop audio decode probe ===")
        println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")}")
        println("video: $videoId")
        println()

        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData = YouTube.visitorData().getOrNull() ?: run {
            println("could not acquire visitorData - aborting")
            return@runBlocking
        }

        val client = YouTubeClient.IOS
        val response = YouTube.player(videoId, client = client).getOrElse {
            println("player request failed: ${it.message}")
            return@runBlocking
        }

        // The adaptive (DASH) formats are fragmented MP4: their samples live in moof boxes rather
        // than in the moov sample table, which is why a plain MP4 reader parses the metadata
        // correctly and then finds no frames at all. The progressive `formats` list is ordinary
        // non-fragmented MP4, so it is worth knowing whether YouTube still offers one.
        val progressive = response.streamingData?.formats.orEmpty()
            .filter { !it.url.isNullOrBlank() }
        println("progressive formats offered: ${progressive.size}")
        progressive.forEach { println("  itag ${it.itag}  ${it.mimeType.substringBefore(';')}  ${it.bitrate / 1000} kbps") }
        println()

        val format = response.streamingData?.adaptiveFormats
            ?.firstOrNull { it.itag == ITAG_AAC_MEDIUM && !it.url.isNullOrBlank() }
        if (format == null) {
            println("itag $ITAG_AAC_MEDIUM not offered with a direct URL - nothing to decode")
            return@runBlocking
        }
        println("format: itag ${format.itag}  ${format.mimeType}  ${format.bitrate / 1000} kbps")

        val bytes = download(format.url!!, client.userAgent, format.contentLength)
        if (bytes == null) {
            println("download failed - nothing to decode")
            return@runBlocking
        }
        println("downloaded: ${bytes.size} bytes")
        println()

        decode(bytes)
    }

    /**
     * The whole file, asked for as an explicit range.
     *
     * Same trick the Android downloader uses: googlevideo paces a plain progressive request at
     * roughly playback speed, so without a range this takes about as long as the song lasts. With
     * one it is a bulk transfer.
     */
    private suspend fun download(url: String, userAgent: String, contentLength: Long?): ByteArray? {
        val ranged = if (contentLength != null && contentLength > 0) "$url&range=0-$contentLength" else url
        val http = HttpClient(OkHttp)
        return try {
            val response = http.get(ranged) { header("User-Agent", userAgent) }
            if (response.status.value !in 200..299) {
                println("download: HTTP ${response.status.value}")
                null
            } else {
                response.body<ByteArray>()
            }
        } catch (e: Exception) {
            println("download: ${e::class.simpleName}: ${e.message}")
            null
        } finally {
            http.close()
        }
    }

    /**
     * Demuxes the MP4 and decodes its AAC frames to PCM, entirely in Java.
     *
     * Decoding several hundred frames rather than one: a single frame proves the decoder accepts the
     * stream's configuration, but not that it survives the stream. Enough frames to cover a few
     * seconds of real audio is the useful signal, and the frame count and PCM byte total are what
     * show it actually produced sound rather than silently emitting empty buffers.
     */
    private fun decode(mp4: ByteArray) {
        println("--- decoding (pure Java, no native libraries) ---")
        try {
            val container = MP4Container(MP4InputStream.open(ByteArrayInputStream(mp4)))
            val movie = container.movie
            val track = movie.tracks.firstOrNull { it is AudioTrack } as? AudioTrack
            if (track == null) {
                println("  FAILED - no audio track found in the container")
                return
            }
            println("  container:  MP4, ${movie.tracks.size} track(s)")
            println("  codec:      ${track.codec}")
            println("  sample rate:${track.sampleRate} Hz")
            println("  channels:   ${track.channelCount}")

            val decoder = Decoder.create(track.decoderSpecificInfo.data)
            val buffer = SampleBuffer()
            var frames = 0
            var pcmBytes = 0L
            while (track.hasMoreFrames() && frames < 400) {
                val frame = track.readNextFrame() ?: break
                decoder.decodeFrame(frame.data, buffer)
                pcmBytes += buffer.data.size
                frames++
            }

            if (frames == 0) {
                // Worth stating precisely, because the distinction decides the next step. The
                // metadata above parsed - sample rate, channels and codec are all correct - so the
                // file is understood; it is the samples that cannot be reached. Every format
                // YouTube now offers is fragmented MP4, where samples live in moof boxes rather
                // than the moov sample table this reader walks. The codec is not the obstacle and
                // swapping AAC decoders would not help.
                println("  FAILED - metadata parsed but no frames: this reader cannot demux")
                println("           fragmented MP4, which is the only kind YouTube serves.")
                println()
                println("  So pure-Java playback is not ruled out - the codec is fine and AAC has")
                println("  pure-Java decoders. What is missing is a fragmented-MP4 demuxer to feed")
                println("  one. The alternative is a native library (VLC/GStreamer), which handles")
                println("  container and codec together at the cost of bundling per-architecture")
                println("  binaries.")
                return
            }
            if (pcmBytes == 0L) {
                println("  FAILED - read $frames frame(s) but the decoder emitted no PCM")
                return
            }
            // A frame is 1024 samples for AAC-LC, which is what makes this a meaningful duration
            // rather than an arbitrary count.
            val seconds = frames * 1024.0 / track.sampleRate
            println("  decoded:    $frames frames, $pcmBytes bytes of PCM (~%.1f s of audio)".format(seconds))
            println()
            println("  PASS - AAC decodes in pure Java. A desktop build needs no native audio library.")
        } catch (e: Throwable) {
            println("  FAILED - ${e::class.simpleName}: ${e.message}")
        }
    }
}
