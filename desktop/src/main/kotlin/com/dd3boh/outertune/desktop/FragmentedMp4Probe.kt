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
import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.aac.Decoder
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part12.MovieFragmentBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part12.TrackRunBox
import org.mp4parser.boxes.sampleentry.AudioSampleEntry
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.AudioSpecificConfig
import org.mp4parser.boxes.iso14496.part14.ESDescriptorBox
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

/**
 * Settles whether desktop playback can be done without a native library.
 *
 * [AudioDecodeProbe] established that the obstacle is the container, not the codec: everything
 * YouTube serves is fragmented MP4, whose samples live in `moof`/`trun` boxes rather than the
 * `moov` sample table an ordinary MP4 reader walks, so a plain reader parses the metadata and then
 * finds nothing to decode. That left one question worth answering before anyone chooses how to ship
 * the app: is a fragmented-MP4 demuxer available in pure Java, and does it actually feed a
 * pure-Java AAC decoder on the specific files YouTube hands out?
 *
 * This answers it end to end - fetch, demux, decode, count PCM - so the packaging decision can be
 * made on evidence:
 *
 * - If this passes, both options are real, and the choice between one self-contained jar and a
 *   native library is about preference rather than possibility.
 * - If it fails, a native library (VLC/GStreamer) is the only route, and the per-architecture
 *   bundling that comes with it is unavoidable rather than a choice.
 *
 * The AudioSpecificConfig is read from the sample entry's ESDS rather than guessed, because the
 * decoder has to be configured with the same profile, sample rate and channel layout the stream was
 * encoded with - and a guess that happens to work on one file is not an answer.
 */
object FragmentedMp4Probe {

    private const val ITAG_AAC_MEDIUM = 140

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val videoId = args.firstOrNull() ?: "dQw4w9WgXcQ"
        println("=== OuterTune fragmented-MP4 demux probe ===")
        println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")}")
        println("video: $videoId")
        println()

        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData = YouTube.visitorData().getOrNull() ?: run {
            println("could not acquire visitorData - aborting")
            return@runBlocking
        }

        val client = YouTubeClient.IOS
        val format = YouTube.player(videoId, client = client).getOrNull()
            ?.streamingData?.adaptiveFormats
            ?.firstOrNull { it.itag == ITAG_AAC_MEDIUM && !it.url.isNullOrBlank() }
            ?: run {
                println("itag $ITAG_AAC_MEDIUM unavailable - nothing to demux")
                return@runBlocking
            }

        val bytes = download(format.url!!, client.userAgent, format.contentLength) ?: run {
            println("download failed")
            return@runBlocking
        }
        println("downloaded: ${bytes.size} bytes  (itag ${format.itag}, ${format.bitrate / 1000} kbps)")
        println()

        demuxAndDecode(bytes)
    }

    private suspend fun download(url: String, userAgent: String, contentLength: Long?): ByteArray? {
        val ranged = if (contentLength != null && contentLength > 0) "$url&range=0-$contentLength" else url
        val http = HttpClient(OkHttp)
        return try {
            val response = http.get(ranged) { header("User-Agent", userAgent) }
            if (response.status.value !in 200..299) null else response.body<ByteArray>()
        } catch (e: Exception) {
            println("download: ${e::class.simpleName}: ${e.message}")
            null
        } finally {
            http.close()
        }
    }

    private fun demuxAndDecode(mp4: ByteArray) {
        println("--- demux (mp4parser) + decode (jaad), both pure Java ---")
        try {
            val channel: SeekableByteChannel = SeekableInMemoryByteChannel(mp4)
            val iso = IsoFile(channel)

            val fragments = iso.getBoxes(MovieFragmentBox::class.java)
            println("  movie fragments: ${fragments.size}")
            if (fragments.isEmpty()) {
                println("  FAILED - no moof boxes; this file is not fragmented after all")
                return
            }

            val config = findAudioSpecificConfig(iso)
            if (config == null) {
                println("  FAILED - no AudioSpecificConfig in the sample entry, cannot configure the decoder")
                return
            }
            println("  decoder config:  ${config.size} bytes from esds")

            val samples = extractSamples(mp4, iso)
            println("  samples found:   ${samples.size}")
            if (samples.isEmpty()) {
                println("  FAILED - fragments present but no sample data extracted")
                return
            }

            val decoder = Decoder.create(config)
            val buffer = SampleBuffer()
            var frames = 0
            var pcmBytes = 0L
            var sampleRate = 0
            for (sample in samples.take(400)) {
                decoder.decodeFrame(sample, buffer)
                if (buffer.data.isNotEmpty()) {
                    pcmBytes += buffer.data.size
                    sampleRate = buffer.sampleRate
                    frames++
                }
            }

            if (frames == 0 || pcmBytes == 0L) {
                println("  FAILED - samples extracted but the decoder produced no PCM")
                return
            }
            val seconds = frames * 1024.0 / (if (sampleRate > 0) sampleRate else 44100)
            println("  decoded:         $frames frames, $pcmBytes bytes of PCM")
            println("  audio:           ${sampleRate}Hz, ${buffer.channels}ch, ~%.1f s".format(seconds))
            println()
            println("  PASS - fragmented MP4 demuxes and decodes in pure Java.")
            println("  A desktop build can ship as one jar with no native audio library.")
        } catch (e: Throwable) {
            println("  FAILED - ${e::class.simpleName}: ${e.message}")
            e.stackTrace.take(3).forEach { println("           at $it") }
        }
    }

    /**
     * The encoder's own configuration, taken from the audio sample entry's `esds`.
     *
     * Read rather than assumed: the decoder has to be set up with the profile, sample rate and
     * channel configuration the stream was actually encoded with, and a default that happens to
     * match one file tells you nothing about the next one.
     */
    private fun findAudioSpecificConfig(iso: IsoFile): ByteArray? {
        for (track in iso.movieBox.getBoxes(TrackBox::class.java)) {
            val stbl = track.mediaBox?.mediaInformationBox?.getBoxes(SampleTableBox::class.java)?.firstOrNull()
                ?: continue
            for (entry in stbl.sampleDescriptionBox.boxes) {
                if (entry !is AudioSampleEntry) continue
                val esds = entry.getBoxes(ESDescriptorBox::class.java).firstOrNull() ?: continue
                val audioConfig: AudioSpecificConfig =
                    esds.esDescriptor?.decoderConfigDescriptor?.audioSpecificInfo ?: continue
                return audioConfig.configBytes
            }
        }
        return null
    }

    /**
     * Every sample in the file, as individual AAC frames.
     *
     * A fragmented file interleaves `moof` (which says how long each sample is) with `mdat` (which
     * holds the bytes). The sizes in each `trun` are walked in order against the matching `mdat`,
     * which is what turns one opaque blob into the discrete frames a decoder wants.
     */
    private fun extractSamples(raw: ByteArray, iso: IsoFile): List<ByteArray> {
        val samples = mutableListOf<ByteArray>()
        // Sample sizes, fragment by fragment, in file order.
        val sizesPerFragment = iso.getBoxes(MovieFragmentBox::class.java).map { moof ->
            moof.getBoxes(TrackRunBox::class.java, true)
                .flatMap { trun -> trun.entries.map { it.sampleSize.toInt() } }
        }
        // mdat payload offsets, in the same order.
        val mdatRanges = findMdatPayloadRanges(raw)
        if (mdatRanges.size < sizesPerFragment.size) {
            println("  note: ${sizesPerFragment.size} fragment(s) but ${mdatRanges.size} mdat(s)")
        }

        sizesPerFragment.forEachIndexed { index, sizes ->
            val range = mdatRanges.getOrNull(index) ?: return@forEachIndexed
            var offset = range.first
            for (size in sizes) {
                if (offset + size > range.second) break
                samples += raw.copyOfRange(offset, offset + size)
                offset += size
            }
        }
        return samples
    }

    /** Start (inclusive) and end (exclusive) of each mdat payload, found by walking top-level boxes. */
    private fun findMdatPayloadRanges(raw: ByteArray): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var pos = 0
        while (pos + 8 <= raw.size) {
            val size = ((raw[pos].toInt() and 0xFF) shl 24) or
                ((raw[pos + 1].toInt() and 0xFF) shl 16) or
                ((raw[pos + 2].toInt() and 0xFF) shl 8) or
                (raw[pos + 3].toInt() and 0xFF)
            val type = String(raw, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8) break
            if (type == "mdat") ranges += (pos + 8) to minOf(pos + size, raw.size)
            pos += size
        }
        return ranges
    }
}

/** mp4parser wants a seekable channel; the file is already in memory, so give it one over the array. */
private class SeekableInMemoryByteChannel(private val data: ByteArray) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun read(dst: java.nio.ByteBuffer): Int {
        if (position >= data.size) return -1
        val count = minOf(dst.remaining(), (data.size - position).toInt())
        dst.put(data, position.toInt(), count)
        position += count
        return count
    }

    override fun write(src: java.nio.ByteBuffer): Int = throw UnsupportedOperationException("read-only")
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }
    override fun size(): Long = data.size.toLong()
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("read-only")
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}
