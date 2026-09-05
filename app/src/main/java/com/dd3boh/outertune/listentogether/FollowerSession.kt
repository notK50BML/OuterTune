/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * What a follower is doing, flattened for the UI.
 *
 * Flat rather than a sealed hierarchy because almost every field is worth showing simultaneously -
 * who is being followed, what is playing, and how well it is keeping up - and a state machine would
 * force the screen to ask which case it is in before it could render any of them.
 */
data class FollowerState(
    val hostName: String? = null,
    val track: SharedTrack? = null,
    val connected: Boolean = false,
    /** False until enough clock samples have landed. Until then no correction is attempted. */
    val synced: Boolean = false,
    /** Current gap from the host, for display. Positive means behind. */
    val driftMs: Long = 0,
    /** The host is playing a local file, which this device has no way to fetch. */
    val unavailable: Boolean = false,
    /** Set when the session ended cleanly, with [Protocol.ByeReason] saying why. */
    val endedReason: Byte? = null,
    /** Set when a song could not be found here at all. */
    val missingTrack: Boolean = false,
)

/**
 * Works out where the host has actually reached.
 *
 * Separated out and made pure because it is the one calculation that decides whether the whole
 * feature works, and it is impossible to check by looking at it.
 */
internal object SyncMath {

    /**
     * Beyond this, a projection is not believable and is discarded.
     *
     * A tick describes a moment that should be a fraction of a second old. A projection claiming the
     * host has played half a minute since then means the clock offset is wrong, not that the host
     * jumped - and acting on it would seek somewhere arbitrary. Better to ignore the tick and let
     * the next one, or a re-measured offset, sort it out.
     */
    private const val MAX_PROJECTION_US = 30_000_000L

    /**
     * The host's position now, in milliseconds, or null if the numbers cannot be trusted.
     *
     * @param offsetUs host clock minus follower clock, from [ClockSync].
     * @param followerNowUs this device's monotonic clock.
     */
    fun hostPositionNowMs(
        tick: Protocol.Frame.Tick,
        offsetUs: Long,
        followerNowUs: Long,
    ): Long? {
        // A paused host is not moving, so its stated position is already current and projecting
        // forward would invent playback that did not happen.
        if (!tick.playing) return tick.positionMs

        // Both sides of this subtraction are on the host's clock: the tick's timestamp directly, and
        // this device's clock shifted into the host's frame. That is the entire purpose of measuring
        // the offset - without it these two numbers are not comparable at all.
        val elapsedUs = (followerNowUs + offsetUs) - tick.hostClockUs
        if (elapsedUs < -MAX_PROJECTION_US || elapsedUs > MAX_PROJECTION_US) return null

        // Rounded rather than truncated, and multiplied by the host's rate, since the host may
        // itself be running slightly off while correcting something.
        return tick.positionMs + Math.round(elapsedUs / 1000.0 * tick.speed)
    }
}

/**
 * The follower end of a session: matches this device to a host.
 *
 * The ordering here is what makes it work. Clock offset is measured first and rapidly; nothing is
 * corrected until it has converged, because a correction computed against an unknown offset is a
 * seek to an arbitrary position - visibly worse than not syncing at all. Only then does the drift
 * ladder start.
 *
 * Android-free, like the rest: a fake [PlaybackBridge] and a scripted [PeerLink] are enough to drive
 * every path through it.
 */
class FollowerSession(
    private val scope: CoroutineScope,
    private val bridge: PlaybackBridge,
    private val nowUs: () -> Long,
    private val deviceName: String,
) {

    private val sync = ClockSync()
    private val anchor = PositionAnchor(nowUs)
    private val corrector = DriftCorrector(nowUs)

    private val _state = MutableStateFlow(FollowerState())
    val state: StateFlow<FollowerState> = _state.asStateFlow()

    private var link: PeerLink? = null
    private var pingJob: Job? = null
    private var pollJob: Job? = null

    /** The track the host says it is on, which may not yet be the one loaded here. */
    private var wanted: SharedTrack? = null
    private var loading = false

    /**
     * Joins a session over an already-connected link. Returns when the session ends.
     *
     * Suspends for the whole session rather than returning immediately, so the caller's scope owns
     * its lifetime and cancelling that scope is a complete teardown.
     */
    suspend fun run(link: PeerLink) {
        this.link = link
        sync.reset()
        corrector.reset()
        _state.value = FollowerState()

        link.send(Protocol.Frame.Hello(Protocol.VERSION, deviceName))
        pingJob = scope.launch { pingLoop(link) }
        pollJob = scope.launch { pollLoop() }

        try {
            link.incoming.collect { (frame, receivedAtUs) ->
                handle(frame, receivedAtUs)
            }
        } finally {
            // The incoming flow completing is the single disconnection signal, however it happened.
            pingJob?.cancel()
            pollJob?.cancel()
            // A rate left applied would outlive the session and quietly play everything slightly
            // fast for the rest of the day.
            bridge.setPlaybackSpeed(1f)
            this.link = null
            _state.update { it.copy(connected = false) }
        }
    }

    fun leave() {
        link?.close(Protocol.ByeReason.NORMAL)
    }

    private fun handle(frame: Protocol.Frame, receivedAtUs: Long) {
        when (frame) {
            is Protocol.Frame.Welcome -> {
                if (frame.protocolVersion != Protocol.VERSION) {
                    link?.close(Protocol.ByeReason.VERSION_MISMATCH)
                    _state.update { it.copy(endedReason = Protocol.ByeReason.VERSION_MISMATCH) }
                    return
                }
                _state.update { it.copy(hostName = frame.hostName, connected = true) }
            }

            is Protocol.Frame.Pong -> {
                // t4 is the arrival stamp taken by the reader, not a clock read here - by this point
                // the frame has been through a channel and a coroutine dispatch, and charging that
                // to the network would inflate the measured delay.
                sync.offer(frame.t1, frame.t2, frame.t3, receivedAtUs)
                _state.update { it.copy(synced = sync.offsetUs != null && sync.sampleCount >= MIN_SAMPLES) }
            }

            is Protocol.Frame.Track -> {
                wanted = SharedTrack(
                    videoId = frame.videoId,
                    title = frame.title,
                    artist = frame.artist,
                    durationMs = frame.durationMs,
                    isLocal = frame.isLocal,
                )
                _state.update { it.copy(track = wanted, unavailable = frame.isLocal, missingTrack = false) }
            }

            is Protocol.Frame.Tick -> onTick(frame)

            is Protocol.Frame.Bye -> {
                _state.update { it.copy(endedReason = frame.reason, connected = false) }
                link?.close(null)
            }

            // HELLO and PING are a host's business. Ignored rather than refused, so a newer host
            // sending something extra does not break an older follower.
            else -> Unit
        }
    }

    private fun onTick(tick: Protocol.Frame.Tick) {
        val target = wanted
        // A local file on the host cannot be fetched here. Shown, and then left alone - retrying
        // every second would be a failure loop with no possible outcome.
        if (target != null && target.isLocal) return

        val offset = sync.offsetUs
        if (offset == null || sync.sampleCount < MIN_SAMPLES) return

        val hostPosition = SyncMath.hostPositionNowMs(tick, offset, nowUs()) ?: return

        if (target != null && bridge.currentTrack?.videoId != target.videoId) {
            startTrack(target, hostPosition)
            return
        }
        if (loading) return

        // Play state is matched before position, because correcting the position of a player that is
        // about to be paused is wasted, and a follower that stays playing while the host is paused
        // is the most obvious failure a listener could notice.
        if (bridge.isPlaying != tick.playing) {
            bridge.setPlayWhenReady(tick.playing)
        }

        if (!tick.playing) {
            // While paused there is no drift to accumulate, but the positions must still agree or
            // resuming would start them apart. A seek here is unheard, since nothing is playing.
            val gap = hostPosition - bridge.positionMs()
            if (abs(gap) > PAUSED_TOLERANCE_MS) {
                bridge.seekTo(hostPosition)
                anchor.reset(hostPosition)
            }
            _state.update { it.copy(driftMs = gap) }
            return
        }

        val error = hostPosition - anchor.positionMs(playing = true, speed = bridge.speed)
        _state.update { it.copy(driftMs = error) }

        when (val correction = corrector.correct(error, playing = true, hostPositionMs = hostPosition)) {
            is Correction.Nudge -> bridge.setPlaybackSpeed(correction.speed)
            is Correction.Seek -> {
                bridge.seekTo(correction.positionMs)
                bridge.setPlaybackSpeed(1f)
                anchor.reset(correction.positionMs)
            }
            Correction.Hold -> Unit
        }
    }

    private fun startTrack(track: SharedTrack, positionMs: Long) {
        if (loading) return
        loading = true
        // Launched rather than awaited inside the frame handler: finding and buffering a song can
        // take seconds, and blocking there would stall every tick and pong behind it - including the
        // clock samples needed to place the song correctly once it does load.
        scope.launch {
            val ok = try {
                bridge.playTrack(track.videoId, positionMs)
            } catch (e: Exception) {
                false
            }
            loading = false
            corrector.reset()
            anchor.reset(bridge.positionMs())
            _state.update { it.copy(missingTrack = !ok) }
        }
    }

    /**
     * Keeps the clock estimate fresh.
     *
     * Fast at first, then sparse. The opening burst is what gets a usable offset within a second or
     * two of joining, which is the difference between the song starting in the right place and the
     * follower audibly hunting for it. After that, drift between two crystal oscillators is slow
     * enough that a sample every couple of seconds is plenty - and it doubles as the traffic that
     * keeps the host's read timeout from expiring.
     */
    private suspend fun pingLoop(link: PeerLink) {
        var sent = 0
        while (scope.isActive && link.isOpen) {
            link.sendStamped { t1 -> Protocol.Frame.Ping(t1) }
            sent++
            delay(if (sent < FAST_PINGS) FAST_PING_INTERVAL_MS else PING_INTERVAL_MS)
        }
    }

    /**
     * Feeds the local anchor from the player.
     *
     * Separate from the tick handler, and much more frequent, because the anchor has to catch the
     * moment the player actually moved. Sampling it only when a tick arrives would mean comparing
     * the host's carefully interpolated position against a raw stale reading here - which would put
     * a staircase back on one end of the comparison and undo the point of having an anchor at all.
     */
    private suspend fun pollLoop() {
        while (scope.isActive) {
            anchor.update(bridge.positionMs())
            delay(POLL_INTERVAL_MS)
        }
    }

    private inline fun MutableStateFlow<FollowerState>.update(block: (FollowerState) -> FollowerState) {
        value = block(value)
    }

    private companion object {
        /** Samples before any correction is attempted. Fewer than this and the offset is a guess. */
        const val MIN_SAMPLES = 4

        const val FAST_PINGS = 8
        const val FAST_PING_INTERVAL_MS = 250L
        const val PING_INTERVAL_MS = 2_000L
        const val POLL_INTERVAL_MS = 250L

        /** While paused, positions may differ by this much before it is worth seeking. */
        const val PAUSED_TOLERANCE_MS = 150
    }
}
