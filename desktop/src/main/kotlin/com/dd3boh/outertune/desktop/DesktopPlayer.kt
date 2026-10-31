/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.pow
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** What the UI needs to know about playback, and nothing more. */
/**
 * What is actually being played, for a song's details: the stream YouTube served or the file on disk.
 * Any part that is not known is null.
 */
data class StreamDetails(
    val format: String,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    /** Lossless files only - lossy audio (AAC, MP3, Opus) has no bit depth of its own. */
    val bitsPerSample: Int? = null,
    val sizeBytes: Long? = null,
    val loudnessDb: Double? = null,
    val path: String? = null,
)

/**
 * The details of a song that is not playing, from its file on disk: a file on this computer, or a
 * download. Null for a song with neither - its stream is only known once it plays.
 *
 * Only the file's header is read, never the audio, so it is quick enough for a menu's Details. Blocking:
 * call it off the main thread.
 */
fun storedStreamDetails(id: String, downloads: Downloads?): StreamDetails? {
    if (LocalMusic.isLocal(id)) {
        return LocalMusic.track(id)?.file?.takeIf { it.isFile }?.let { fileStreamDetails(it) }
    }
    val file = downloads?.takeIf { it.has(id) }?.fileFor(id) ?: return null
    // Labelled as the player labels a download it plays.
    return fileStreamDetails(file, format = "AAC (downloaded)")
}

/** [file] described as [DesktopPlayer.playLocal] describes it - from the tags' audio header. */
internal fun fileStreamDetails(file: java.io.File, format: String? = null): StreamDetails {
    val extension = file.extension.lowercase()
    val header = runCatching { org.jaudiotagger.audio.AudioFileIO.read(file).audioHeader }.getOrNull()
    val lengthMs = header?.let { (it.preciseTrackLength * 1000).toLong() } ?: 0L
    return StreamDetails(
        format = format ?: extension.uppercase().ifEmpty { "File" },
        bitrateKbps = lengthMs.takeIf { it > 0 }?.let { (file.length() * 8 / it).toInt() }
            ?: header?.bitRateAsNumber?.toInt()?.takeIf { it > 0 },
        sampleRate = header?.sampleRateAsNumber?.takeIf { it > 0 },
        bitsPerSample = header?.bitsPerSample?.takeIf { it > 0 && extension in LOSSLESS },
        sizeBytes = file.length(),
        path = file.absolutePath,
    )
}

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
/** Bars in the visualiser. Enough to show shape, few enough that each one is readable. */
const val VISUALIZER_BANDS = 28

/** Room for any block a decoder might return, well past the 1024 an AAC-LC frame carries. */
private const val MONO_BUFFER_SAMPLES = 8192

class DesktopPlayer {

    /**
     * Which AAC bitrate to ask for - see the format pick in resolveAndFetchWith.
     *
     * A plain property set from the settings rather than a constructor argument, because the player
     * outlives any one value of it: changing the setting should apply to the next song fetched, and
     * a field read at fetch time does that without rebuilding anything that is currently playing.
     */
    @Volatile
    var quality: AudioQuality = AudioQuality.Auto

    // Not private: ClientProbe reads CLIENTS, so "what does each client say today" is answered by
    // asking the same list the player asks rather than by a second copy that can drift from it.
    internal companion object {
        /** AAC-LC in MP4 at ~130kbps: the format every working client offers with a direct URL. */
        const val ITAG_AAC_MEDIUM = 140

        /** A live segment not out yet: asked for again this many times, this far apart, before the stream ends. */
        const val LIVE_RETRIES = 5

        /** How far the position moves before it is published again behind a minimised window. */
        const val BACKGROUND_POSITION_STEP_MS = 250L
        const val LIVE_RETRY_MS = 1_000L
        const val AAC_FRAME_SAMPLES = 1024L

        /** The same codec at ~48kbps, and at ~256kbps (Premium accounts only). */
        const val ITAG_AAC_LOW = 139
        const val ITAG_AAC_HIGH = 141

        /** What each AAC itag is encoded at, for when YouTube's own figure is not to hand. */
        val NOMINAL_KBPS = mapOf(ITAG_AAC_LOW to 48, ITAG_AAC_MEDIUM to 128, ITAG_AAC_HIGH to 256)

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
        internal val CLIENTS = listOf(
            YouTubeClient.IOS,
            YouTubeClient.ANDROID_VR_NO_AUTH,
            YouTubeClient.VISIONOS,
            YouTubeClient.ANDROID_VR_1_43_32,
        )
    }

    val state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    /** The current song's stream or file - see [StreamDetails]. */
    val stream = MutableStateFlow<StreamDetails?>(null)

    /** The current track is a live stream - shown as LIVE, with no length and nowhere to seek. */
    val live = MutableStateFlow(false)

    private var job: Job? = null

    /**
     * Which play this is. Bumped by every stop, and so by every play.
     *
     * A cancelled decode job does not stop the instant it is cancelled - it can be inside a network
     * read or a blocking write - and anything it touches on the way out lands on the song that has
     * replaced it. The worst of those was closing "the" line in its cleanup: when a song ended
     * by itself, it advanced the queue, the next song opened its line, and then the finished job's
     * cleanup closed that new line. The queue said one song, the speakers said nothing or the
     * wrong thing, and it stayed that way. Every write to shared state now checks it is still the
     * current play first.
     */
    @Volatile
    private var generation = 0L

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
     * Spectrum of what is currently audible, for the visualiser.
     *
     * Fed from the decode loop and read by the UI. It is a tap rather than a flow of arrays because
     * a spectrum arrives about forty times a second and the UI redraws at its own rate; the two do
     * not need to be coupled, and emitting a new array each time would make this the app's largest
     * source of garbage.
     */
    val spectrum = VisualizerTap(VISUALIZER_BANDS)

    /**
     * The equaliser, applied to every block on its way to the output line.
     *
     * Lives here rather than behind the line because there is nowhere else it could go: Java Sound
     * has no effect chain, so the only opportunity to shape the audio is between the decoder and
     * the write. That is also the reason this is a real implementation rather than a binding to
     * something the platform provides, the way it is on Android.
     */
    /**
     * Downloaded audio, consulted before the network.
     *
     * Settable rather than a constructor parameter because the store needs the library directory,
     * which is built after the player. Null simply means nothing is cached.
     */
    var downloads: Downloads? = null

    val equalizer = Equalizer()

    /**
     * Dynamics, between the equaliser and the stretcher.
     *
     * After the equaliser because a compressor should react to the tone that will be heard - boost
     * the bass and the compressor ought to notice. Before the stretcher because its attack and
     * release are in milliseconds of *music*: run it afterwards and a 10ms attack would mean 15ms of
     * the song at 1.5x tempo, so the same settings would behave differently at different speeds.
     */
    val compressor = Compressor()

    /**
     * Tempo and pitch, independently adjustable.
     *
     * Sits after the equaliser and before the line, so the equaliser's band frequencies still mean
     * what they say - filtering after a pitch shift would move every band by the shift.
     */
    val timeStretch = TimeStretch()

    /**
     * Left/right balance, applied last.
     *
     * After the visualiser's measurement point deliberately - see [Balance] for why a mono spectrum
     * should not halve when the music is panned.
     */
    val balance = Balance()

    /**
     * Whether YouTube's loudness figure is used to even out track volumes - the phone's "Audio
     * normalization". Only ever turns a loud track down, never a quiet one up, so it cannot clip.
     */
    @Volatile
    var normalise: Boolean = false

    /**
     * The window is minimised, so nothing shows what the decode loop works out for the screen: the
     * spectrum is not analysed (an FFT a block, some forty a second) and the position is published a
     * few times a second instead of every block, which is what kept the UI redrawing behind a
     * minimised window. The audio itself is processed exactly as before. See MemoryTrim.
     */
    @Volatile
    var inBackground: Boolean = false

    /**
     * Playback volume, 0..1, as a flow as well as a plain value, so the mini player's volume button
     * shows a change made anywhere - the keyboard's volume keys, or the sleep timer's fade-out turning
     * it down - rather than only the ones made through it.
     */
    val volumeLevel = MutableStateFlow(1f)

    /** Playback volume, 0..1 - the sleep timer's fade-out turns this down. See [volumeLevel]. */
    var volume: Float
        get() = volumeLevel.value
        set(value) {
            volumeLevel.value = value
        }

    /** The current track's normalisation gain, linear. 1 when off or unknown. */
    @Volatile
    private var trackGain: Float = 1f

    /** The line's frame position, which is what [VisualizerTap.sample] needs. */
    fun playedFrames(): Long = line?.longFramePosition ?: 0L

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
        // Clamped against the duration only once there is one. durationMs is 0 for the whole of a
        // track's fetch - seconds - and coercing into 0..0 turns every seek in that window into a
        // seek to the beginning. That is not hypothetical: it is exactly how a Listen Together
        // follower joins a song already in progress, and the drift corrector issues a seek on its
        // first tick because the follower is still at zero while the host is minutes in. Both
        // landed back at the start, which looks like the join simply not working.
        val duration = durationMs.value
        val target = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        pendingSeekMs = target
        // Shown at once. The decode loop applies the seek on its next pass - after the write in
        // flight, or, while paused, as soon as it notices - and until then the readout still held
        // the old position: a dragged bar snapped back to where it had been, then jumped to where
        // it was put. Only while a track is actually loaded, so a seek queued for a song that is
        // still being fetched does not show a position nothing is playing at.
        if (line != null) positionMs.value = target
    }

    /**
     * @param startAtMs starts the track already this far in, once decoding has produced enough to
     *   know the sample rate - see the seek-application code in [stream] for why an initial seek has
     *   to be handled differently from a mid-playback one. Used by Listen Together to join a song
     *   already in progress without a seek-from-zero being audible first.
     */
    fun play(scope: CoroutineScope, videoId: String, title: String, startAtMs: Long = 0L) {
        // A song from a folder on this computer plays from its file - see LocalMusic.
        if (LocalMusic.isLocal(videoId)) {
            val track = LocalMusic.track(videoId)
            if (track == null || !track.file.isFile) {
                stop()
                state.value = PlaybackState.Failed(title, "The file for this song is no longer there.")
                return
            }
            playLocal(scope, videoId, title, track.file, track.durationMs)
            if (startAtMs > 0) seekTo(startAtMs)
            return
        }
        // One atomic take, not read-test-clear. play() genuinely runs on two threads - the UI
        // thread for anything the user does, and the decode job's own thread when a track ends and
        // the queue advances - so a read followed by a separate write can interleave with
        // dropPrefetch() and leave a Deferred cancelled underneath a caller that has already
        // decided to use it.
        val taken = prefetched.getAndSet(null)
        val ready = taken?.takeIf { it.videoId == videoId }
        // A prediction that missed - the user jumped somewhere else - is cancelled rather than just
        // dropped. Letting it run finishes downloading a song nobody is going to hear, competing
        // for the connection with the one they just asked for.
        if (ready == null) taken?.bytes?.cancel()
        stop()
        // After stop(), which cancels whatever the *previous* play claimed. Recorded so this one
        // can be cancelled in turn: once claimed, the Deferred is reachable only from the job
        // closure below, so without this a claimed-but-unfinished fetch could not be stopped by
        // anything and ran to completion for a song already skipped past.
        claimed.set(ready?.bytes)
        // Reset explicitly: a new track must never inherit the last one's paused state.
        paused = false
        positionMs.value = 0
        durationMs.value = 0
        pendingSeekMs = startAtMs.takeIf { it > 0 }
        nowPlayingId = videoId
        stream.value = null
        state.value = PlaybackState.Loading(title)
        val gen = generation
        job = scope.launch(Dispatchers.IO) {
            try {
                // A prefetch that failed is discarded rather than reported: it ran minutes ago
                // against whatever the network was doing then, and the right answer to a stale
                // failure is to ask again now, not to refuse to play the song.
                val prepared = ready?.let { awaitPrefetch(it) }
                val outcome = prepared ?: resolveAndFetch(videoId)
                val audio = when (outcome) {
                    is Resolved.Live -> {
                        stream.value = outcome.details
                        live.value = true
                        trackGain = 1f
                        null
                    }
                    is Resolved.Audio -> {
                        stream.value = outcome.details
                        currentAudio = videoId to outcome.bytes
                        // YouTube's figure is how far above its reference the track is mastered;
                        // a positive one is taken off, a negative one left alone.
                        trackGain = outcome.loudnessDb?.takeIf { it > 0 }
                            ?.let { 10.0.pow(-it / 20).toFloat() } ?: 1f
                        outcome.bytes
                    }
                    is Resolved.Failure -> {
                        if (gen == generation) state.value = PlaybackState.Failed(title, outcome.reason)
                        return@launch
                    }
                }
                if (gen != generation) return@launch
                // Paused if pause was pressed while it was being fetched - the decode loop holds on
                // [paused] until play is pressed.
                state.value = if (paused) PlaybackState.Paused(title) else PlaybackState.Playing(title)
                if (audio != null) {
                    stream(audio, gen)
                } else {
                    // Opened here, on the decode job's thread: it reads the newest segment first.
                    play(LiveSource((outcome as Resolved.Live).url), gen)
                }
                // Only settle back to Idle if this job is still the current one; a stop() or a new
                // play() has already set the state it wants. Reaching here with the job still
                // active is the one case that genuinely means "the song ended".
                if (currentCoroutineContext().isActive && gen == generation) {
                    state.value = PlaybackState.Idle
                    onFinished?.invoke()
                }
            } catch (e: Throwable) {
                if (currentCoroutineContext().isActive && gen == generation) {
                    state.value = PlaybackState.Failed(title, "${e::class.simpleName}: ${e.message}")
                }
            } finally {
                // Only its own: by now a newer play may hold the line - see [generation].
                if (gen == generation) closeLine()
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
        when (val now = state.value) {
            is PlaybackState.Loading, is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> {
                paused = false
                // Paused before it had loaded: still fetching, so back to loading, and it plays when
                // it arrives. Loaded: the line carries on.
                val current = line
                if (current == null) {
                    state.value = PlaybackState.Loading(now.title)
                } else {
                    current.start()
                    state.value = PlaybackState.Playing(now.title)
                }
            }
            else -> Unit
        }
    }

    /**
     * Pauses, and does nothing if already paused.
     *
     * Separate from [togglePause] because some callers mean "stop" rather than "the other one". A
     * sleep timer firing against a toggle would resume playback on anything already paused, which
     * is the exact opposite of what it is for.
     */
    fun pause() {
        when (val now = state.value) {
            // Still being fetched: held, so it starts paused when it arrives rather than playing as
            // if pause had never been pressed - which is what happened, since there was no line yet
            // for pause to stop.
            is PlaybackState.Loading -> {
                paused = true
                state.value = PlaybackState.Paused(now.title)
            }
            is PlaybackState.Playing -> {
                val current = line ?: return
                paused = true
                current.stop()
                state.value = PlaybackState.Paused(now.title)
            }
            else -> Unit
        }
    }

    /**
     * One song's bytes, fetched before anything asked to play them.
     *
     * Held as the in-flight fetch rather than as the finished bytes, so a track that starts while
     * its prefetch is still running waits for that one instead of kicking off a second identical
     * download beside it.
     */
    private class Prefetched(val videoId: String, val bytes: Deferred<Resolved>)

    /** Atomic rather than volatile: claiming it is a take, not a read - see [play]. */
    private val prefetched = AtomicReference<Prefetched?>(null)

    /**
     * The prefetch [play] is currently waiting on, so it can still be cancelled.
     *
     * Once claimed, a Deferred is referenced only by the decode job's closure, and that job is a
     * sibling of the async rather than its parent - so cancelling the job does not touch it. This
     * is the handle that makes stopping actually stop the download.
     */
    private val claimed = AtomicReference<Deferred<Resolved>?>(null)

    /** What [play] was last asked for, so a prefetch of the same thing can be declined. */
    @Volatile
    private var nowPlayingId: String? = null

    /**
     * Awaits a claimed prefetch, falling back to a fresh fetch rather than failing the track.
     *
     * The distinction that matters is whose cancellation it was. This coroutine being cancelled has
     * to propagate - the track is being abandoned. The *prefetch* being cancelled underneath it
     * must not: that happens when the queue is cleared at the moment a track starts, and reporting
     * it would show a playback failure reading "DeferredCoroutine was cancelled" for something the
     * user would never connect to what they did.
     */
    private suspend fun awaitPrefetch(ready: Prefetched): Resolved? = try {
        ready.bytes.await().takeIf { it is Resolved.Audio }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        null
    }

    /**
     * Starts fetching [videoId] so that playing it later costs nothing.
     *
     * This is what closes the gap between tracks, and it is worth being precise about why. A song is
     * fetched *in full* before it plays - a deliberate trade, since it makes seeking arithmetic and
     * means no network remains to fail mid-song - so the gap at a track boundary is not the audio
     * line reopening, which is milliseconds. It is the several seconds of download. Doing that
     * download while the previous track is still playing removes all of it.
     *
     * Note this is not sample-accurate gapless playback: the line is still closed and reopened
     * between tracks, and AAC's own encoder delay and padding are not trimmed, so a continuous
     * album will still have a short seam. It is the difference between a few seconds of silence and
     * a barely perceptible one, not between a seam and none.
     */
    fun prefetch(scope: CoroutineScope, videoId: String) {
        // Never the song already playing. Under repeat-one, and in a one-song queue with
        // repeat-all, "what comes next" is this very track - correctly, since that is what next()
        // does - and fetching it would hold a second full copy of a song already in memory,
        // renewed on every loop. Against a 320MB heap and an hour-long mix that is the most
        // plausible way to run out. The cost is that repeat-one keeps the gap the rest of the
        // queue no longer has; reusing the bytes already decoded would fix both, and is the better
        // answer whenever this is worth revisiting.
        if (videoId == nowPlayingId) return
        if (prefetched.get()?.videoId == videoId) return
        // Nothing to fetch for a file that is already on disk.
        if (LocalMusic.isLocal(videoId)) return
        prefetched.getAndSet(
            Prefetched(
                videoId = videoId,
                // runCatching inside, so this Deferred can never complete exceptionally. An async
                // that fails propagates to its parent the moment it does, which would take down the
                // scope driving playback - over a song nobody has asked for yet.
                bytes = scope.async(Dispatchers.IO) {
                    runCatching { resolveAndFetch(videoId) }
                        .getOrElse { Resolved.Failure("prefetch failed - ${it::class.simpleName}: ${it.message}") }
                },
            )
        )?.bytes?.cancel()
    }

    /** Throws away whatever was fetched ahead, for when the queue it was predicting no longer exists. */
    fun dropPrefetch() {
        prefetched.getAndSet(null)?.bytes?.cancel()
    }

    fun stop() {
        // The claimed fetch too, not just the decode job. They are siblings, so cancelling the job
        // leaves a download the user has already moved on from running to completion against the
        // same connection as whatever they asked for instead.
        claimed.getAndSet(null)?.cancel()
        generation++
        nowPlayingId = null
        paused = false
        // Nothing is playing now, so neither outlives it: the last song's bytes (a long mix is tens
        // of MB of a 320MB heap, held for good otherwise), and a seek asked for while a song was
        // still loading - which a local file started next would have begun at.
        currentAudio = null
        pendingSeekMs = null
        live.value = false
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
        data class Audio(val bytes: ByteArray, val loudnessDb: Double? = null, val details: StreamDetails? = null) : Resolved
        /** A live stream: no bytes to have, only a URL to keep reading from - see [LiveSource]. */
        data class Live(val url: String, val details: StreamDetails? = null) : Resolved
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
    /**
     * Fetches [videoId]'s audio without playing it, and keeps it.
     *
     * The same resolve path playback uses, so a download is exactly the bytes that would have been
     * streamed - one format-selection code path rather than two that can disagree about which itag
     * to take.
     */
    /**
     * The audio of the song playing now, kept so downloading it is a file write rather than a second
     * fetch of what was fetched a minute ago - "download" from the player bar used to do exactly
     * that. Only the current song's: one song's bytes, which are in memory while it plays anyway.
     */
    @Volatile
    private var currentAudio: Pair<String, ByteArray>? = null

    suspend fun download(videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        // On IO explicitly, and this is the whole reason the dispatcher is stated. Callers are UI
        // code, so a Compose scope dispatches on the main thread - and everything inside here is
        // blocking: a SQLite query to check, a multi-megabyte file write to save. Without this the
        // window freezes for the length of the write, which does not look like a slow download. It
        // looks like the app has hung.
        val store = downloads
            ?: return@withContext Result.failure(IllegalStateException("downloads are not set up"))
        if (store.has(videoId)) return@withContext Result.success(Unit)
        // Already fetched - playing, or prefetched as the next song: saved without another fetch.
        currentAudio?.takeIf { it.first == videoId }?.let { return@withContext store.save(videoId, it.second) }
        prefetched.get()?.takeIf { it.videoId == videoId && it.bytes.isCompleted && !it.bytes.isCancelled }
            ?.let { (it.bytes.getCompleted() as? Resolved.Audio)?.let { audio -> return@withContext store.save(videoId, audio.bytes) } }
        when (val outcome = resolveAndFetch(videoId)) {
            is Resolved.Audio -> store.save(videoId, outcome.bytes)
            is Resolved.Live -> Result.failure(IllegalStateException("A live stream has no end to download."))
            is Resolved.Failure -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    private suspend fun resolveAndFetch(videoId: String): Resolved {
        // A downloaded song never touches the network, and never asks YouTube whether it is still
        // playable - which is the whole point of having downloaded it.
        downloads?.read(videoId)?.let { bytes ->
            return Resolved.Audio(
                bytes,
                details = StreamDetails("AAC (downloaded)", sizeBytes = bytes.size.toLong(), path = downloads?.fileFor(videoId)?.absolutePath),
            )
        }

        // NewPipe first. Since October 2026 YouTube answers the device clients below with "This video
        // is unavailable", or - iOS - with a URL that serves the first megabyte and then 403s, which
        // is how googlevideo treats a client it wants a proof-of-origin token from. NewPipe resolves
        // the way the web player does, deciphering the signature and the throttling parameter with
        // the player's own JavaScript (Rhino), and its URLs serve the whole song. It is the route
        // Flow takes, and it was already here: the Android app grafts its URLs on as a last resort.
        // The clients stay as the fallback in case YouTube turns either way round again.
        val failures = mutableListOf<String>()
        when (val outcome = resolveAndFetchWithNewPipe(videoId)) {
            is Resolved.Audio, is Resolved.Live -> return outcome
            is Resolved.Failure -> failures += "NewPipe: ${outcome.reason}"
        }
        for (client in CLIENTS) {
            when (val outcome = resolveAndFetchWith(videoId, client)) {
                is Resolved.Audio, is Resolved.Live -> return outcome
                is Resolved.Failure -> failures += "${client.clientName}: ${outcome.reason}"
            }
        }
        // Every client's own reason, not just the last one - "403" from one client and "refused"
        // from another are different problems, and collapsing them hides which is which.
        return Resolved.Failure("no client could serve this track. ${failures.joinToString("; ")}")
    }

    private suspend fun resolveAndFetchWithNewPipe(videoId: String): Resolved = coroutineScope {
        // NewPipe's streams carry no loudness figure, and normalisation needs one. A device client's
        // player response still has it even where its own URLs are refused, so it is asked alongside
        // - it answers well inside the time NewPipe takes, and costs nothing when it does not.
        // Its format list also describes the stream NewPipe hands over - bitrate, sample rate - for
        // the song's details.
        val loudness = async {
            YouTube.player(videoId, client = YouTubeClient.IOS, authenticated = false).getOrNull()
        }
        val found = withContext(Dispatchers.IO) { NewPipeUtils.newPipeStream(videoId) }
        val streams = found.streams.toMap()
        if (streams.isEmpty()) {
            loudness.cancel()
            return@coroutineScope Resolved.Failure(found.error?.let { "extraction failed - $it" } ?: "extraction failed")
        }
        // The same AAC-in-MP4 family the clients are chosen from - see resolveAndFetchWith.
        val preferred = when (quality) {
            AudioQuality.High -> listOf(ITAG_AAC_HIGH, ITAG_AAC_MEDIUM, ITAG_AAC_LOW)
            AudioQuality.Low -> listOf(ITAG_AAC_LOW, ITAG_AAC_MEDIUM)
            AudioQuality.Auto -> listOf(ITAG_AAC_MEDIUM, ITAG_AAC_HIGH, ITAG_AAC_LOW)
        }
        val itag = preferred.firstOrNull { it in streams }
        val url = itag?.let { streams[it] } ?: run {
            loudness.cancel()
            return@coroutineScope Resolved.Failure("no AAC stream; offered itags ${streams.keys.sorted()}")
        }
        // A live stream - a 24/7 radio - has no end to fetch. Asked for whole it answered with its
        // newest few seconds, which played and then counted as the song finishing: five seconds
        // of every station and on to the next. It is read a segment at a time instead.
        if (found.live) {
            loudness.cancel()
            return@coroutineScope Resolved.Live(
                url,
                StreamDetails(format = "AAC · live · itag $itag", bitrateKbps = NOMINAL_KBPS[itag]),
            )
        }
        val length = Regex("""[?&]clen=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        when (val fetched = fetchStream(url, length, YouTubeClient.USER_AGENT_WEB)) {
            is Resolved.Audio -> {
                val response = loudness.await()
                val format = response?.streamingData?.adaptiveFormats?.firstOrNull { it.itag == itag }
                val gain = format?.loudnessDb ?: response?.streamingData?.adaptiveFormats
                    ?.firstOrNull { it.itag == ITAG_AAC_MEDIUM }?.loudnessDb ?: response?.playerConfig?.audioConfig?.loudnessDb
                fetched.copy(
                    loudnessDb = gain,
                    details = StreamDetails(
                        format = "AAC · itag $itag",
                        bitrateKbps = format?.bitrate?.div(1000) ?: NOMINAL_KBPS[itag],
                        sampleRate = format?.audioSampleRate,
                        sizeBytes = fetched.bytes.size.toLong(),
                        loudnessDb = gain,
                    ),
                )
            }
            is Resolved.Live, is Resolved.Failure -> fetched.also { loudness.cancel() }
        }
    }

    private suspend fun resolveAndFetchWith(videoId: String, client: YouTubeClient): Resolved {
        // Anonymously, always. Signing in used to break playback outright: an authenticated device
        // client is expected to carry a proof-of-origin token, and without one YouTube answers "Sign
        // in to confirm you're not a bot" - so the account that was supposed to improve things was
        // what stopped the music. Browsing still authenticates; only the stream fetch does not.
        val response = YouTube.player(videoId, client = client, authenticated = false).getOrElse {
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

        // Within AAC-in-MP4, which is the one container the decode chain is built around: itags 139,
        // 140 and 141 are the same codec at roughly 48, 128 and 256 kbps, so choosing between them
        // changes the bitrate and nothing else downstream. Anything outside that set is still only a
        // last resort, exactly as before - quality is a preference, playing at all is not.
        val aac = audio.filter { it.mimeType.contains("mp4") && !it.url.isNullOrBlank() }
        val format = when (quality) {
            AudioQuality.High -> aac.maxByOrNull { it.bitrate }
            AudioQuality.Low -> aac.minByOrNull { it.bitrate }
            // The format the probes settled on, and only then the best on offer. Auto means "the
            // one known to work here", not "the largest number available".
            AudioQuality.Auto -> aac.firstOrNull { it.itag == ITAG_AAC_MEDIUM } ?: aac.maxByOrNull { it.bitrate }
        }
            ?: audio.firstOrNull { it.mimeType.contains("mp4") && !it.url.isNullOrBlank() }
            ?: return Resolved.Failure(
                "no direct-URL AAC format; offered ${audio.joinToString { "itag ${it.itag}" }}"
            )

        // Live, as NewPipe's path checks too: read a segment at a time - see LiveSource.
        if (response.videoDetails?.isLive == true) {
            return Resolved.Live(format.url!!, StreamDetails(format = "AAC · live · itag ${format.itag}", bitrateKbps = format.bitrate / 1000))
        }
        return when (val fetched = fetchStream(format.url!!, format.contentLength, client.userAgent)) {
            is Resolved.Audio -> {
                val gain = format.loudnessDb ?: response.playerConfig?.audioConfig?.loudnessDb
                fetched.copy(
                    loudnessDb = gain,
                    details = StreamDetails(
                        format = "AAC · itag ${format.itag}",
                        bitrateKbps = format.bitrate / 1000,
                        sampleRate = format.audioSampleRate,
                        sizeBytes = fetched.bytes.size.toLong(),
                        loudnessDb = gain,
                    ),
                )
            }
            is Resolved.Live, is Resolved.Failure -> fetched
        }
    }

    /** One client for every stream fetch, so connections to the CDN are reused rather than reopened. */
    private val streamHttp by lazy { HttpClient(OkHttp) }

    private suspend fun fetchStream(streamUrl: String, length: Long?, userAgent: String): Resolved {
        val url = if (length != null && length > 0) "$streamUrl&range=0-$length" else streamUrl
        val http = streamHttp
        return try {
            val fetched = http.get(url) { header("User-Agent", userAgent) }
            if (fetched.status.value !in 200..299) {
                Resolved.Failure("stream fetch returned HTTP ${fetched.status.value}")
            } else {
                Resolved.Audio(fetched.body<ByteArray>())
            }
        } catch (e: Exception) {
            Resolved.Failure("stream fetch failed - ${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * A live stream, played as it is made: one segment at a time, each a few seconds of fragmented
     * MP4 that YouTube numbers in order (`&sq=`), decoded by one AAC decoder carried across them.
     *
     * It starts at the newest segment - the one a plain request returns, with its number in the
     * response's headers - and fetches the next while the current one plays. A segment that is not
     * out yet is waited for (the URL asks googlevideo to hold the request until it is), and a few
     * failed attempts in a row end the stream as any song ends. There is no length and no seeking:
     * a seek leaves it where it is.
     */
    private inner class LiveSource(private val url: String) : PcmSource {
        private val fetcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiveSegments").apply { isDaemon = true }
        }
        private var seq: Long
        private var samples: List<ByteArray>
        private var index = 0
        private val decoder: Decoder
        private val buffer = SampleBuffer()
        private var pending: ByteArray?
        private var upcoming: java.util.concurrent.Future<ByteArray?>? = null
        private var framesDecoded = 0L

        init {
            val (head, bytes) = fetchSegment(null) ?: error("the live stream did not answer")
            seq = head
            val iso = IsoFile(InMemoryChannel(bytes))
            decoder = Decoder.create(
                audioSpecificConfigIn(bytes) ?: findAudioSpecificConfig(iso) ?: error("no AudioSpecificConfig in the live segment")
            )
            samples = extractSamples(bytes, iso)
            prefetchNext()
            var first = ByteArray(0)
            while (first.isEmpty() && index < samples.size) {
                decoder.decodeFrame(samples[index++], buffer)
                framesDecoded++
                first = buffer.data
            }
            pending = first
        }

        override val sampleRate = buffer.sampleRate
        override val channels = buffer.channels
        override val bitsPerSample = buffer.bitsPerSample
        override val bigEndian = buffer.isBigEndian
        override val durationMs = 0L

        override fun next(): ByteArray? {
            pending?.let {
                pending = null
                return it
            }
            while (index >= samples.size) if (!advance()) return null
            decoder.decodeFrame(samples[index++], buffer)
            framesDecoded++
            return buffer.data
        }

        /** On to the next segment: the one fetched ahead, or asked for again a few times. */
        private fun advance(): Boolean {
            val ahead = runCatching { upcoming?.get() }.getOrNull()
            if (ahead != null) return use(ahead)
            repeat(LIVE_RETRIES) {
                Thread.sleep(LIVE_RETRY_MS)
                fetchSegment(seq + 1)?.let { (_, bytes) -> return use(bytes) }
            }
            return false
        }

        private fun use(bytes: ByteArray): Boolean {
            seq++
            samples = runCatching { extractSamples(bytes, IsoFile(InMemoryChannel(bytes))) }.getOrDefault(emptyList())
            index = 0
            prefetchNext()
            return true
        }

        private fun prefetchNext() {
            val wanted = seq + 1
            upcoming = fetcher.submit<ByteArray?> { fetchSegment(wanted)?.second }
        }

        /** Segment [sq], or the newest when null, with the number it is. Null when there is none to have. */
        private fun fetchSegment(sq: Long?): Pair<Long, ByteArray>? = runCatching {
            val connection = java.net.URI(if (sq == null) url else "$url&sq=$sq").toURL()
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", YouTubeClient.USER_AGENT_WEB)
            try {
                if (connection.responseCode != 200) return@runCatching null
                val number = connection.getHeaderField("X-Sequence-Num")?.toLongOrNull()
                    ?: sq ?: connection.getHeaderField("X-Head-Seqnum")?.toLongOrNull() ?: return@runCatching null
                number to withoutEventMessages(connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        /**
         * The decoder's configuration, read straight from the segment's `esds` box: the
         * DecoderSpecificInfo (tag 5) inside it. A live segment's `esds` carries a few bytes the MP4
         * parser does not expect, and it fails an assertion over them - harmless where assertions
         * are off, as they are in the app, but not something to lean on.
         */
        private fun audioSpecificConfigIn(bytes: ByteArray): ByteArray? {
            val marker = "esds".toByteArray(Charsets.ISO_8859_1)
            val start = (0..bytes.size - 4).firstOrNull { i -> (0 until 4).all { bytes[i + it] == marker[it] } } ?: return null
            // Past the type and the full box's version and flags, then descriptors: tag, length, body.
            val boxSize = ((bytes[start - 4].toInt() and 0xFF) shl 24) or ((bytes[start - 3].toInt() and 0xFF) shl 16) or
                ((bytes[start - 2].toInt() and 0xFF) shl 8) or (bytes[start - 1].toInt() and 0xFF)
            val end = (start - 4 + boxSize).coerceAtMost(bytes.size)
            var at = start + 8
            while (at < end) {
                val tag = bytes[at++].toInt() and 0xFF
                // The length is 7 bits a byte, continued while the top bit is set.
                var length = 0
                do {
                    if (at >= end) return null
                    val b = bytes[at++].toInt() and 0xFF
                    length = (length shl 7) or (b and 0x7F)
                } while (b and 0x80 != 0)
                when (tag) {
                    // The ES descriptor: ES_ID and flags, then its own descriptors.
                    0x03 -> {
                        val flags = bytes.getOrNull(at + 2)?.toInt() ?: return null
                        at += 3
                        if (flags and 0x80 != 0) at += 2
                        if (flags and 0x40 != 0) at += 1 + (bytes.getOrNull(at)?.toInt()?.and(0xFF) ?: return null)
                        if (flags and 0x20 != 0) at += 2
                    }
                    // The decoder config: type, stream type, buffer size and bitrates, then its own.
                    0x04 -> at += 13
                    0x05 -> return if (at + length <= end && length > 0) bytes.copyOfRange(at, at + length) else null
                    else -> at += length
                }
            }
            return null
        }

        /**
         * The segment without its `emsg` boxes - the stream's own timing notes, which nothing here
         * reads and which the MP4 parser trips an assertion on. Top-level boxes only, which is where
         * YouTube puts them, between the header and the fragment.
         */
        private fun withoutEventMessages(bytes: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(bytes.size)
            var at = 0
            while (at + 8 <= bytes.size) {
                val size = ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                    ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
                // A box that runs to the end (0), or a 64-bit size (1): left whole, as everything after it.
                if (size < 8 || at + size > bytes.size) {
                    out.write(bytes, at, bytes.size - at)
                    return out.toByteArray()
                }
                val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
                if (type != "emsg") out.write(bytes, at, size.toInt())
                at += size.toInt()
            }
            if (at < bytes.size) out.write(bytes, at, bytes.size - at)
            return out.toByteArray()
        }

        /** Live has nowhere to seek to; the clock carries on from where it is. */
        override fun seek(ms: Long): Long = framesDecoded * AAC_FRAME_SAMPLES * 1000 / sampleRate.coerceAtLeast(1)

        override fun close() {
            upcoming?.cancel(true)
            fetcher.shutdownNow()
        }
    }

    /** Demuxes YouTube's fragmented MP4 into AAC frames and plays them. */
    private suspend fun stream(mp4: ByteArray, gen: Long) {
        val iso = IsoFile(InMemoryChannel(mp4))
        val config = findAudioSpecificConfig(iso) ?: error("no AudioSpecificConfig in the sample entry")
        val samples = extractSamples(mp4, iso)
        if (samples.isEmpty()) error("no samples could be extracted from the fragments")
        play(AacSource(samples, config), gen)
    }

    /**
     * Plays any [PcmSource] through the processing chain and the output line, stopping cleanly when
     * the job is cancelled. Shared by YouTube audio and local files - see PcmSource.
     */
    private suspend fun play(source: PcmSource, gen: Long) = withContext(Dispatchers.IO) {
        source.use { src ->
        val sampleRate = src.sampleRate
        val bitsPerSample = src.bitsPerSample
        val channels = src.channels
        val bigEndian = src.bigEndian
        val format = AudioFormat(sampleRate.toFloat(), bitsPerSample, channels, true, bigEndian)
        val open = (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine)
            .also { it.open(format); it.start() }
        try {
        if (gen != generation) return@use
        line = open
        durationMs.value = src.durationMs
        val analyzer = SpectrumAnalyzer(sampleRate, bands = VISUALIZER_BANDS)
        // Generous rather than exactly one block, so a decoder handing back more than expected is
        // still analysed whole rather than read as a quieter, duller spectrum.
        val mono = DoubleArray(MONO_BUFFER_SAMPLES)
        /** Frames handed to the line so far, which is when each block becomes audible. */
        var framesWritten = 0L
        spectrum.reset()

        // Where the line's own frame counter stood when the current segment began, and what track
        // time that corresponded to. Position is read from the line rather than from how much has
        // been written, because writing runs ahead of playing by however much the line has buffered
        // - reporting written frames would show a position a second or so into the future.
        var lineFrameBase = 0L
        var trackMsBase = 0L
        // The tempo the current base was measured under. Changing tempo changes how much song one
        // second of output line represents, so the elapsed part has to be rebased at the moment it
        // changes - scaling the whole elapsed span by the new tempo would retroactively rewrite
        // however long has already been played at the old one.
        var baseTempo = 1f

        while (true) {
            if (!currentCoroutineContext().isActive || gen != generation) break
            // Held here rather than by the audio line, which does not reliably block a write. A
            // seek is let through, so dragging the bar while paused moves the song there now rather
            // than when it is next played.
            while (paused && pendingSeekMs == null && currentCoroutineContext().isActive) delay(50)
            if (!currentCoroutineContext().isActive) break

            pendingSeekMs?.let { target ->
                pendingSeekMs = null
                // Drop what is buffered, or the seek would be heard a second late.
                open.flush()
                lineFrameBase = open.longFramePosition
                // Everything written but not yet played has just been discarded, so the running
                // total no longer describes anything real; re-anchored to what the line has played.
                framesWritten = open.longFramePosition
                spectrum.reset()
                analyzer.reset()
                // The filters remember their last samples, which now belong to audio that was
                // thrown away. Carrying that across a jump makes the first moments after a seek ring
                // with the passage before it.
                equalizer.reset()
                compressor.reset()
                timeStretch.reset()
                trackMsBase = src.seek(target)
                baseTempo = timeStretch.rate
                positionMs.value = trackMsBase
            }
            // Sought while paused: back to waiting, without playing anything until resumed.
            if (paused) continue

            val pcm = src.next() ?: break
            if (pcm.isEmpty()) continue

            // Equalised first, so what follows describes the audio that will actually be played -
            // the visualiser in particular measures the signal after this. In place, on the buffer
            // already destined for the line, so there is no extra copy in the hot path.
            equalizer.process(
                bytes = pcm,
                length = pcm.size,
                bitsPerSample = bitsPerSample,
                channels = channels,
                bigEndian = bigEndian,
                sampleRate = sampleRate,
            )

            compressor.process(
                bytes = pcm,
                length = pcm.size,
                bitsPerSample = bitsPerSample,
                channels = channels,
                bigEndian = bigEndian,
                sampleRate = sampleRate,
            )

            // Then tempo and pitch: after the equaliser so the band frequencies keep their meaning,
            // and before the analysis and the write so both describe the same (re-timed) audio.
            val stretched = timeStretch.process(
                bytes = pcm,
                length = pcm.size,
                bitsPerSample = bitsPerSample,
                channels = channels,
                bigEndian = bigEndian,
                sampleRate = sampleRate,
            )
            // The stretcher buffers before it can emit anything, so early blocks come back empty.
            if (stretched.isEmpty()) continue

            // Analysed before the write and queued against the frame at which this block will be
            // heard, so the bars move with the sound rather than a buffer's length ahead of it.
            val frames = if (inBackground) {
                stretched.size / (bitsPerSample / 8 * channels).coerceAtLeast(1)
            } else {
                Pcm.toMono(
                    bytes = stretched,
                    offset = 0,
                    length = stretched.size,
                    bitsPerSample = bitsPerSample,
                    channels = channels,
                    bigEndian = bigEndian,
                    out = mono,
                ).also { if (it > 0) spectrum.submit(framesWritten, analyzer.analyze(mono, it)) }
            }
            if (frames > 0) framesWritten += frames

            // Normalisation and volume, after the analysis so the visualiser shows the music
            // rather than how loud it has been set.
            val gain = (if (normalise) trackGain else 1f) * volume.coerceIn(0f, 1f)
            if (gain < 0.999f) Pcm.scale(stretched, stretched.size, bitsPerSample, bigEndian, gain)

            // Last, and after the analysis: the spectrum is a mono sum, so balance before it would
            // read as the music getting quieter rather than moving across.
            balance.process(
                bytes = stretched,
                length = stretched.size,
                bitsPerSample = bitsPerSample,
                channels = channels,
                bigEndian = bigEndian,
            )

            open.write(stretched, 0, stretched.size)

            val tempo = timeStretch.rate
            if (tempo != baseTempo) {
                trackMsBase += ((open.longFramePosition - lineFrameBase) * 1000.0 * baseTempo / sampleRate).toLong()
                lineFrameBase = open.longFramePosition
                baseTempo = tempo
            }
            // Scaled by tempo: at 1.5x each frame of output carries one and a half frames of song.
            if (gen != generation) break
            // Not over a seek that is waiting to be applied: seekTo() has already shown its
            // target, and writing the old position here flicked the bar back for a moment.
            if (pendingSeekMs == null) {
                val now = trackMsBase + ((open.longFramePosition - lineFrameBase) * 1000.0 * tempo / sampleRate).toLong()
                // Behind a minimised window, a few times a second is plenty - see [inBackground].
                if (!inBackground || now - positionMs.value !in 0 until BACKGROUND_POSITION_STEP_MS) positionMs.value = now
            }
        }
        // Let whatever is buffered finish rather than cutting the last fraction of a second off.
        if (currentCoroutineContext().isActive && gen == generation) open.drain()
        } finally {
            // This play's own line, closed by this play - never whichever line is current by now.
            runCatching { open.stop() }
            runCatching { open.close() }
            if (line === open) line = null
        }
        }
    }

    /**
     * Plays a file from disk. The song id is "local:...", and [localFiles] says where it lives -
     * see LocalMusic. Same loop, same processing, same position and seek behaviour as streaming.
     */
    fun playLocal(scope: CoroutineScope, songId: String, title: String, file: java.io.File, durationMs: Long) {
        stop()
        paused = false
        positionMs.value = 0
        this.durationMs.value = 0
        nowPlayingId = songId
        state.value = PlaybackState.Loading(title)
        trackGain = 1f
        val gen = generation
        job = scope.launch(Dispatchers.IO) {
            try {
                // M4A through the same AAC decoder YouTube audio uses - see M4aReader for why.
                val source = if (M4aReader.isM4a(file)) M4aReader.open(file) else FileSource(file, durationMs)
                if (gen != generation) { source.close(); return@launch }
                val extension = file.extension.lowercase()
                stream.value = StreamDetails(
                    format = extension.uppercase().ifEmpty { "File" },
                    bitrateKbps = source.durationMs.takeIf { it > 0 }?.let { (file.length() * 8 / it).toInt() },
                    sampleRate = source.sampleRate,
                    // Only a lossless file has a bit depth of its own; a decoder's output depth for
                    // MP3 or AAC is not the song's.
                    bitsPerSample = (source as? FileSource)?.sourceBitsPerSample?.takeIf { extension in LOSSLESS },
                    sizeBytes = file.length(),
                    path = file.absolutePath,
                )
                state.value = if (paused) PlaybackState.Paused(title) else PlaybackState.Playing(title)
                play(source, gen)
                if (currentCoroutineContext().isActive && gen == generation) {
                    state.value = PlaybackState.Idle
                    onFinished?.invoke()
                }
            } catch (e: Throwable) {
                if (currentCoroutineContext().isActive && gen == generation) {
                    state.value = PlaybackState.Failed(
                        title,
                        if (e is javax.sound.sampled.UnsupportedAudioFileException) {
                            "This file's format cannot be played here."
                        } else {
                            "${e::class.simpleName}: ${e.message}"
                        },
                    )
                }
            } finally {
                // Only its own: by now a newer play may hold the line - see [generation].
                if (gen == generation) closeLine()
            }
        }
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

/** File types whose bit depth is the music's own. */
private val LOSSLESS = setOf("flac", "wav", "aif", "aiff", "alac")
