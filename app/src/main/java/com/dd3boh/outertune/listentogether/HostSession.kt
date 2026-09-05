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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A follower currently in the session, for display. */
data class Listener(val name: String, val address: String)

/**
 * The host end of a session: broadcasts what this device is playing, and answers clock probes.
 *
 * The host is deliberately passive about the followers. It never waits for one, never slows down for
 * one, and never changes its own playback because of one - a session is one person listening
 * normally while others follow along. That is what keeps the host's experience identical whether
 * anyone is listening or not, and it removes every failure mode where one follower's bad connection
 * degrades everyone else.
 *
 * No Android here either: it takes a [PlaybackBridge] and [PeerLink]s, so the broadcast cadence and
 * the handshake can be tested against a fake player and a scripted peer.
 */
class HostSession(
    private val scope: CoroutineScope,
    private val bridge: PlaybackBridge,
    private val nowUs: () -> Long,
    private val hostName: String,
) {

    /**
     * Every accepted connection, greeted or not.
     *
     * Separate from [greeted] because a peer that connects and then says nothing - a stalled
     * handshake, a port scanner, an app killed mid-connect - still holds a socket and a coroutine.
     * Tracking only the ones that completed a handshake would leak those on stop.
     */
    private val links = CopyOnWriteArrayList<PeerLink>()

    /** Peers that have introduced themselves, mapped to their name. Only these receive broadcasts. */
    private val greeted = ConcurrentHashMap<PeerLink, String>()
    private val anchor = PositionAnchor(nowUs)
    private var broadcastJob: Job? = null

    private val _listeners = MutableStateFlow<List<Listener>>(emptyList())
    val listeners: StateFlow<List<Listener>> = _listeners.asStateFlow()

    /** Starts broadcasting. Safe to call before any follower has connected. */
    fun start() {
        if (broadcastJob != null) return
        anchor.reset(bridge.positionMs())
        broadcastJob = scope.launch { broadcastLoop() }
    }

    /**
     * Ends the session and tells everyone why.
     *
     * The BYE matters: a follower that simply loses the socket cannot tell "the host stopped sharing"
     * from "the WiFi dropped", and those deserve different messages and different retry behaviour.
     */
    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        links.forEach { it.close(Protocol.ByeReason.HOST_STOPPED) }
        links.clear()
        greeted.clear()
        _listeners.value = emptyList()
    }

    /** Takes on a newly accepted connection. The handshake happens here, not at the transport. */
    fun accept(link: PeerLink) {
        links.addIfAbsent(link)
        scope.launch {
            try {
                link.incoming.collect { (frame, receivedAtUs) ->
                    handle(link, frame, receivedAtUs)
                }
            } finally {
                // Reached on any disconnection, since the incoming flow completes when the link
                // dies. One cleanup path for every way a follower can leave.
                drop(link)
            }
        }
    }

    private fun handle(link: PeerLink, frame: Protocol.Frame, receivedAtUs: Long) {
        when (frame) {
            is Protocol.Frame.Hello -> {
                if (frame.protocolVersion != Protocol.VERSION) {
                    // Refused with a reason rather than left to misread frames. A protocol mismatch
                    // that half works is far worse to diagnose than one that fails immediately.
                    link.close(Protocol.ByeReason.VERSION_MISMATCH)
                    return
                }
                link.send(Protocol.Frame.Welcome(Protocol.VERSION, hostName))
                greeted[link] = frame.deviceName
                publishListeners()
                // Immediately, rather than at the next tick. A follower that joins just after a
                // broadcast would otherwise sit silent for a full second wondering if it worked.
                sendState(link)
            }

            is Protocol.Frame.Ping -> {
                // t2 is when the frame arrived, stamped by the reader; t3 is stamped by the writer
                // as the reply goes out. Anything between them is time this device spent, and
                // charging that to the network would bias the follower's offset estimate.
                link.sendStamped { t3 -> Protocol.Frame.Pong(frame.t1, receivedAtUs, t3) }
            }

            is Protocol.Frame.Bye -> link.close(null)

            // A host has no use for the rest. Ignored rather than treated as an error, so a future
            // follower that sends something extra does not get disconnected by an older host.
            else -> Unit
        }
    }

    private fun drop(link: PeerLink) {
        links.remove(link)
        greeted.remove(link)
        publishListeners()
    }

    private fun publishListeners() {
        // Only greeted peers. A half-open connection is not a listener, and showing one would be a
        // phantom entry nobody could account for.
        _listeners.value = greeted.entries.map { Listener(it.value, it.key.remoteAddress) }
    }

    /** Counts down to the next repeat of an unchanged track. Starts at zero so one goes out first. */
    private var ticksUntilTrackRepeat = 0

    private suspend fun broadcastLoop() {
        var lastTrackId: String? = null
        // Starts due, so a follower connecting to an already-running session is served immediately
        // rather than after a poll interval.
        var pollsSinceTick = POLLS_PER_TICK

        while (scope.isActive) {
            // Polled far more often than it is broadcast. The anchor needs readings at least as
            // often as the player moves - roughly every 300ms - to catch the moment it actually
            // advanced; a reading once a second would only ever see a stale value and the
            // interpolation would be anchored to the wrong instant.
            anchor.update(bridge.positionMs())

            if (pollsSinceTick >= POLLS_PER_TICK) {
                val track = bridge.currentTrack
                if (track != null) {
                    val changed = track.videoId != lastTrackId
                    lastTrackId = track.videoId
                    // Repeated periodically even when unchanged. A TRACK frame can be dropped by a
                    // congested link, and without repetition a follower would stay on the wrong song
                    // until the next time the host happened to change it.
                    if (changed || ticksUntilTrackRepeat <= 0) {
                        broadcast(
                            Protocol.Frame.Track(
                                videoId = track.videoId,
                                title = track.title,
                                artist = track.artist,
                                durationMs = track.durationMs,
                                isLocal = track.isLocal,
                            )
                        )
                        ticksUntilTrackRepeat = TRACK_REPEAT_TICKS
                    } else {
                        ticksUntilTrackRepeat--
                    }
                }
                broadcast(currentTick())
                pollsSinceTick = 0
            }
            pollsSinceTick++
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun currentTick(): Protocol.Frame.Tick {
        // The latched pair, not an interpolated position. The follower needs to know where the
        // player was at a stated instant so it can work out where it has reached by the time the
        // frame arrives - an already-interpolated value would bake this device's guess into the
        // answer and hide the transit delay rather than exposing it.
        val (positionMs, atUs) = anchor.latched()
        return Protocol.Frame.Tick(
            positionMs = positionMs,
            hostClockUs = atUs,
            playing = bridge.isPlaying,
            speed = bridge.speed,
        )
    }

    private fun sendState(link: PeerLink) {
        bridge.currentTrack?.let {
            link.send(
                Protocol.Frame.Track(it.videoId, it.title, it.artist, it.durationMs, it.isLocal)
            )
        }
        link.send(currentTick())
    }

    private fun broadcast(frame: Protocol.Frame) {
        greeted.keys.forEach { it.send(frame) }
    }

    private companion object {
        /** How often the player is read. Must be finer than the player's own update granularity. */
        const val POLL_INTERVAL_MS = 250L

        /** Polls per broadcast, so a tick goes out once a second. */
        const val POLLS_PER_TICK = 4

        /** Ticks between repeats of an unchanged track - about half a minute. */
        const val TRACK_REPEAT_TICKS = 30
    }
}
