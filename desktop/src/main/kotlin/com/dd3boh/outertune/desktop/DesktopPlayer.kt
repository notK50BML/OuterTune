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
import kotlinx.coroutines.delay
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
    data class Paused(val title: String) : PlaybackState
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

    private companion object {
        /** AAC-LC in MP4 at ~130kbps: the format every working client offers with a direct URL. */
        const val ITAG_AAC_MEDIUM = 140

        /** Samples per AAC-LC frame - fixed by the codec, which is what makes seeking arithmetic. */
        const val FRAME_SAMPLES = 1024L

        /**
         * Clients to try, in order, until one yields a stream that actually fetches.
         *
         * More than one because a single client is a single point of failure, and the way it fails
         * is not at resolution time. googlevideo hands over a perfectly well-formed URL and then
         * answers 403 when it is fetched - the URL being issued says nothing about whether it will
         * be served. Which client a given track will serve varies by track, so the only way to know
         * is to try, and the Android app re-resolves through other clients for the same reason.
         */
        val CLIENTS = listOf(
            YouTubeClient.IOS,
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.VISIONOS,
            YouTubeClient.ANDROID_VR_1_43_32,
        )
    }

    val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    private var job: Job? = null

    /** The line currently being written to, kept only so [stop] can interrupt a blocking write. */
    @Volatile
    private var line: SourceDataLine? = null

    /** Whether the decode loop is being held - see [togglePause] for why this is not just the line. */
    @Volatile
    private var paused = false

    /**
     * Called when a track reaches its end on its own.
     *
     * Deliberately not fired when playback is stopped or replaced, because those are the user
     * choosing to leave the track and advancing the queue on them would be wrong. "The song ended"
     * and "the song is no longer playing" are different events, and only the first should move a
     * queue along.
     */
    var onFinished: (() -> Unit)? = null

    /** How far through the track playback has actually reached, and how long it is, in ms. */
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)

    /**
     * A seek the decode loop has not acted on yet, in ms.
     *
     * Seeking is close to free here, and that is a direct consequence of fetching the whole song
     * before playing it: every AAC frame is already in memory and each one is a fixed 1024 samples,
     * so a target time is just an index. Streaming playback would remove the wait before a track
     * starts and take this with it - the frames past the current point would no longer exist yet.
     * Worth stating because it makes the two a trade rather than an obvious upgrade.
     */
    @Volatile
    private var pendingSeekMs: Long? = null

    fun seekTo(ms: Long) {
        pendingSeekMs = ms.coerceIn(0, durationMs.value)
    }

    fun play(scope: CoroutineScope, videoId: String, title: String) {
        stop()
        // Reset explicitly: a new track must never inherit the last one's paused state.
        paused = false
        positionMs.value = 0
        durationMs.value = 0
        pendingSeekMs = null
        state.value = PlaybackState.Loading(title)
        job = scope.launch(Dispatchers.IO) {
            try {
                val audio = when (val outcome = resolveAndFetch(videoId)) {
                    is Resolved.Audio -> outcome.bytes
                    is Resolved.Failure -> {
                        state.value = PlaybackState.Failed(title, outcome.reason)
                        return@launch
                    }
                }
                state.value = PlaybackState.Playing(title)
                stream(audio)
                // Only settle back to Idle if this job is still the current one; a stop() or a new
                // play() has already set the state it wants. Reaching here with the job still
                // active is the one case that genuinely means "the song ended".
                if (currentCoroutineContext().isActive) {
                    state.value = PlaybackState.Idle
                    onFinished?.invoke()
                }
            } catch (e: Throwable) {
                if (currentCoroutineContext().isActive) {
                    state.value = PlaybackState.Failed(title, "${e::class.simpleName}: ${e.message}")
                }
            } finally {
                closeLine()
            }
        }
    }

    /**
     * Pauses or resumes: the decode loop is held, and the line is stopped so it falls silent.
     *
     * Both halves are needed. Stopping the line alone was the first attempt, on the assumption that
     * a write would block once the stopped line's buffer filled and the decoder would throttle
     * itself. It does not block on Windows - the loop ran through every remaining sample in
     * silence, reached the end and ended the track, so pausing appeared to delete the song. The
     * decoder is held by [paused] now, and the line is only stopped so what is already buffered
     * goes quiet immediately instead of playing on for a second.
     */
    fun togglePause() {
        val current = line ?: return
        when (val playing = state.value) {
            is PlaybackState.Playing -> {
                paused = true
                current.stop()
                state.value = PlaybackState.Paused(playing.title)
            }
            is PlaybackState.Paused -> {
                paused = false
                current.start()
                state.value = PlaybackState.Playing(playing.title)
            }
            else -> Unit
        }
    }

    fun stop() {
        paused = false
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

    /** Either the song's bytes, or a reason specific enough to act on. */
    private sealed interface Resolved {
        data class Audio(val bytes: ByteArray) : Resolved
        data class Failure(val reason: String) : Resolved
    }

    /**
     * The song's bytes, or why not.
     *
     * The reasons are kept distinct on purpose. "No audio format" previously covered the player
     * request failing, YouTube refusing the client, the response carrying only ciphered formats, and
     * itag 140 simply being absent - four different problems with four different fixes, reported
     * identically. The most likely of them is not even about formats: without visitorData YouTube
     * refuses players with "Video unavailable", which is indistinguishable from a missing format
     * unless the status is actually read.
     *
     * Asked for as an explicit range: googlevideo paces a plain progressive request at roughly
     * playback speed, so without one this takes about as long as the song lasts.
     */
    private suspend fun resolveAndFetch(videoId: String): Resolved {
        val failures = mutableListOf<String>()
        for (client in CLIENTS) {
            when (val outcome = resolveAndFetchWith(videoId, client)) {
                is Resolved.Audio -> return outcome
                is Resolved.Failure -> failures += "${client.clientName}: ${outcome.reason}"
            }
        }
        // Every client's own reason, not just the last one - "403" from one client and "refused"
        // from another are different problems, and collapsing them hides which is which.
        return Resolved.Failure("no client could serve this track. ${failures.joinToString("; ")}")
    }

    private suspend fun resolveAndFetchWith(videoId: String, client: YouTubeClient): Resolved {
        val response = YouTube.player(videoId, client = client).getOrElse {
            return Resolved.Failure("player request failed - ${it::class.simpleName}: ${it.message}")
        }

        val status = response.playabilityStatus.status
        if (status != "OK") {
            val reason = response.playabilityStatus.reason?.let { ": $it" }.orEmpty()
            return Resolved.Failure("YouTube refused this client ($status$reason)")
        }

        val audio = response.streamingData?.adaptiveFormats.orEmpty()
            .filter { it.mimeType.startsWith("audio") }
        if (audio.isEmpty()) {
            return Resolved.Failure("the response carried no audio formats at all")
        }

        val format = audio.firstOrNull { it.itag == ITAG_AAC_MEDIUM && !it.url.isNullOrBlank() }
            ?: audio.firstOrNull { it.mimeType.contains("mp4") && !it.url.isNullOrBlank() }
            ?: return Resolved.Failure(
                "no direct-URL AAC format; offered ${audio.joinToString { "itag ${it.itag}" }}"
            )

        val length = format.contentLength
        val url = if (length != null && length > 0) "${format.url}&range=0-$length" else format.url!!
        val http = HttpClient(OkHttp)
        return try {
            val fetched = http.get(url) { header("User-Agent", client.userAgent) }
            if (fetched.status.value !in 200..299) {
                Resolved.Failure("stream fetch returned HTTP ${fetched.status.value}")
            } else {
                Resolved.Audio(fetched.body<ByteArray>())
            }
        } catch (e: Exception) {
            Resolved.Failure("stream fetch failed - ${e::class.simpleName}: ${e.message}")
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
        var sampleRate = 0

        // Where the line's own frame counter stood when the current segment began, and what track
        // time that corresponded to. Position is read from the line rather than from how much has
        // been written, because writing runs ahead of playing by however much the line has buffered
        // - reporting written frames would show a position a second or so into the future.
        var lineFrameBase = 0L
        var trackMsBase = 0L

        var index = 0
        while (index < samples.size) {
            if (!currentCoroutineContext().isActive) break
            // Held here rather than by the audio line, which does not reliably block a write.
            while (paused && currentCoroutineContext().isActive) delay(50)
            if (!currentCoroutineContext().isActive) break

            pendingSeekMs?.let { target ->
                pendingSeekMs = null
                if (sampleRate > 0) {
                    // Every AAC-LC frame is exactly 1024 samples, so a time is an index.
                    index = ((target * sampleRate / 1000) / FRAME_SAMPLES).toInt().coerceIn(0, samples.lastIndex)
                    open?.let {
                        // Drop what is buffered, or the seek would be heard a second late.
                        it.flush()
                        lineFrameBase = it.longFramePosition
                    }
                    trackMsBase = index.toLong() * FRAME_SAMPLES * 1000 / sampleRate
                    positionMs.value = trackMsBase
                }
            }

            decoder.decodeFrame(samples[index], buffer)
            index++
            val pcm = buffer.data
            if (pcm.isEmpty()) continue

            if (open == null) {
                // Everything about the format comes from the decoder itself - see the class doc.
                sampleRate = buffer.sampleRate
                val format = AudioFormat(
                    sampleRate.toFloat(),
                    buffer.bitsPerSample,
                    buffer.channels,
                    true,
                    buffer.isBigEndian,
                )
                open = (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine)
                    .also { it.open(format); it.start() }
                line = open
                durationMs.value = samples.size.toLong() * FRAME_SAMPLES * 1000 / sampleRate
            }
            open.write(pcm, 0, pcm.size)

            open.let {
                positionMs.value = trackMsBase + (it.longFramePosition - lineFrameBase) * 1000 / sampleRate
            }
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
