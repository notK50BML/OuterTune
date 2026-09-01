/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.sourceforge.jaad.SampleBuffer
import net.sourceforge.jaad.aac.Decoder
import org.mp4parser.IsoFile
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.AudioSpecificConfig
import org.mp4parser.boxes.iso14496.part12.MovieFragmentBox
import org.mp4parser.boxes.iso14496.part12.SampleTableBox
import org.mp4parser.boxes.iso14496.part12.TrackBox
import org.mp4parser.boxes.iso14496.part12.TrackRunBox
import org.mp4parser.boxes.iso14496.part14.ESDescriptorBox
import org.mp4parser.boxes.sampleentry.AudioSampleEntry
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** What the UI needs to know about playback, and nothing more. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Loading(val title: String) : PlaybackState
    data class Playing(val title: String) : PlaybackState
    data class Failed(val title: String, val reason: String) : PlaybackState
}

/**
 * Plays a YouTube song on the desktop, with nothing native involved.
 *
 * The chain is the one the probes established: ask for itag 140 (AAC-LC in MP4), fetch it as an
 * explicit byte range, demux the fragmented MP4 into individual AAC frames, decode those to PCM,
 * and write the PCM to a javax.sound line.
 *
 * Two things here are deliberate rather than incidental.
 *
 * The audio format is taken from the decoder's first frame rather than assumed. Sample rate,
 * channel count and *byte order* all come from what was actually decoded - an earlier version of
 * this work wrote a WAV declaring little-endian over jaad's big-endian output, which is correct
 * data described incorrectly, and it played as static. Nothing about the output format is guessed
 * here for that reason.
 *
 * Decoding happens frame by frame as the line consumes them, rather than decoding the whole song
 * up front. A four minute track is about 42MB of PCM, and there is no reason to hold it.
 */
class DesktopPlayer {

    /** AAC-LC in MP4 at ~130kbps: the one format every working client offers with a direct URL. */
    private companion object {
        const val ITAG_AAC_MEDIUM = 140
    }

    val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    private var job: Job? = null

    /** The line currently being written to, kept only so [stop] can interrupt a blocking write. */
    @Volatile
    private var line: SourceDataLine? = null

    fun play(scope: CoroutineScope, videoId: String, title: String) {
        stop()
        state.value = PlaybackState.Loading(title)
        job = scope.launch(Dispatchers.IO) {
            try {
                val audio = resolveAndFetch(videoId)
                if (audio == null) {
                    state.value = PlaybackState.Failed(title, "no playable audio format was offered")
                    return@launch
                }
                state.value = PlaybackState.Playing(title)
                stream(audio)
                // Only settle back to Idle if this job is still the current one; a stop() or a new
                // play() has already set the state it wants.
                if (currentCoroutineContext().isActive) state.value = PlaybackState.Idle
            } catch (e: Throwable) {
                if (currentCoroutineContext().isActive) {
                    state.value = PlaybackState.Failed(title, "${e::class.simpleName}: ${e.message}")
                }
            } finally {
                closeLine()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeLine()
        state.value = PlaybackState.Idle
    }

    private fun closeLine() {
        // stop() before close() so a write blocked on a full buffer returns rather than hanging.
        line?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        line = null
    }

    /**
     * The song's bytes, or null if YouTube offered nothing usable.
     *
     * Asked for as an explicit range: googlevideo paces a plain progressive request at roughly
     * playback speed, so without one this takes about as long as the song lasts.
     */
    private suspend fun resolveAndFetch(videoId: String): ByteArray? {
        val client = YouTubeClient.IOS
        val format = YouTube.player(videoId, client = client).getOrNull()
            ?.streamingData?.adaptiveFormats
            ?.firstOrNull { it.itag == ITAG_AAC_MEDIUM && !it.url.isNullOrBlank() }
            ?: return null

        val length = format.contentLength
        val url = if (length != null && length > 0) "${format.url}&range=0-$length" else format.url!!
        val http = HttpClient(OkHttp)
        return try {
            val response = http.get(url) { header("User-Agent", client.userAgent) }
            if (response.status.value !in 200..299) null else response.body<ByteArray>()
        } finally {
            http.close()
        }
    }

    /** Demuxes, decodes and writes to the audio line, stopping cleanly when the job is cancelled. */
    private suspend fun stream(mp4: ByteArray) = withContext(Dispatchers.IO) {
        val iso = IsoFile(InMemoryChannel(mp4))
        val config = findAudioSpecificConfig(iso) ?: error("no AudioSpecificConfig in the sample entry")
        val samples = extractSamples(mp4, iso)
        if (samples.isEmpty()) error("no samples could be extracted from the fragments")

        val decoder = Decoder.create(config)
        val buffer = SampleBuffer()
        var open: SourceDataLine? = null

        for (sample in samples) {
            if (!currentCoroutineContext().isActive) break
            decoder.decodeFrame(sample, buffer)
            val pcm = buffer.data
            if (pcm.isEmpty()) continue

            if (open == null) {
                // Everything about the format comes from the decoder itself - see the class doc.
                val format = AudioFormat(
                    buffer.sampleRate.toFloat(),
                    buffer.bitsPerSample,
                    buffer.channels,
                    true,
                    buffer.isBigEndian,
                )
                open = (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine)
                    .also { it.open(format); it.start() }
                line = open
            }
            open.write(pcm, 0, pcm.size)
        }
        // Let whatever is buffered finish rather than cutting the last fraction of a second off.
        if (currentCoroutineContext().isActive) open?.drain()
    }

    private fun findAudioSpecificConfig(iso: IsoFile): ByteArray? {
        for (track in iso.movieBox.getBoxes(TrackBox::class.java)) {
            val stbl = track.mediaBox?.mediaInformationBox?.getBoxes(SampleTableBox::class.java)?.firstOrNull()
                ?: continue
            for (entry in stbl.sampleDescriptionBox.boxes) {
                if (entry !is AudioSampleEntry) continue
                val esds = entry.getBoxes(ESDescriptorBox::class.java).firstOrNull() ?: continue
                val config: AudioSpecificConfig =
                    esds.esDescriptor?.decoderConfigDescriptor?.audioSpecificInfo ?: continue
                return config.configBytes
            }
        }
        return null
    }

    /**
     * The file's AAC frames, cut out of the mdat payloads using the sample sizes each trun declares.
     *
     * A fragmented file interleaves moof (which says how long each sample is) with mdat (which holds
     * the bytes), so neither box is enough on its own.
     */
    private fun extractSamples(raw: ByteArray, iso: IsoFile): List<ByteArray> {
        val samples = mutableListOf<ByteArray>()
        val sizesPerFragment = iso.getBoxes(MovieFragmentBox::class.java).map { moof ->
            moof.getBoxes(TrackRunBox::class.java, true)
                .flatMap { trun -> trun.entries.map { it.sampleSize.toInt() } }
        }
        val mdatRanges = findMdatPayloadRanges(raw)

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

    private fun findMdatPayloadRanges(raw: ByteArray): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var pos = 0
        while (pos + 8 <= raw.size) {
            val size = ((raw[pos].toInt() and 0xFF) shl 24) or
                ((raw[pos + 1].toInt() and 0xFF) shl 16) or
                ((raw[pos + 2].toInt() and 0xFF) shl 8) or
                (raw[pos + 3].toInt() and 0xFF)
            if (size < 8) break
            if (String(raw, pos + 4, 4, Charsets.US_ASCII) == "mdat") {
                ranges += (pos + 8) to minOf(pos + size, raw.size)
            }
            pos += size
        }
        return ranges
    }
}

/** mp4parser wants a seekable channel; the song is already in memory, so give it one over the array. */
private class InMemoryChannel(private val data: ByteArray) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (position >= data.size) return -1
        val count = minOf(dst.remaining(), (data.size - position).toInt())
        dst.put(data, position.toInt(), count)
        position += count
        return count
    }

    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("read-only")
    override fun position(): Long = position
    override fun position(newPosition: Long): SeekableByteChannel = apply { position = newPosition }
    override fun size(): Long = data.size.toLong()
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("read-only")
    override fun isOpen(): Boolean = open
    override fun close() { open = false }
}
