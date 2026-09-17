/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/** Whether this device is sharing, listening along, or doing neither. */
enum class ListenTogetherMode { OFF, HOSTING, FOLLOWING }

/**
 * Owns a listen-together session and everything underneath it.
 *
 * The desktop counterpart to the Android app's `ListenTogetherManager` - same orchestration, same
 * shape, adapted in three places: no Hilt (there is one instance, created in `App()`, not a
 * `@Singleton`), [LanDiscovery] over jmDNS instead of `NsdManager`, and [nowUs] reading
 * `System.nanoTime()` instead of `SystemClock.elapsedRealtimeNanos()`. Everything underneath -
 * [HostSession], [FollowerSession], the transport, the clock and drift math - is the literal same
 * code, because none of it ever depended on Android in the first place.
 *
 * **The scope is dispatched on Swing, and that is a correctness requirement rather than a
 * convention**, for the same reason the phone insists on its main thread: [DesktopPlaybackBridge]
 * reads and writes [PlayerQueue]'s state directly, and that state is read from Compose during
 * recomposition. A session running on a background dispatcher would race Compose's own reads of the
 * same state rather than crash outright the way Media3 does - a quieter failure, not a safer one.
 */
class ListenTogetherManager {

    private val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())
    private val discovery = LanDiscovery()

    private val _mode = MutableStateFlow(ListenTogetherMode.OFF)
    val mode: StateFlow<ListenTogetherMode> = _mode.asStateFlow()

    private val _listeners = MutableStateFlow<List<Listener>>(emptyList())
    val listeners: StateFlow<List<Listener>> = _listeners.asStateFlow()

    private val _followerState = MutableStateFlow(FollowerState())
    val followerState: StateFlow<FollowerState> = _followerState.asStateFlow()

    /** Set when something failed in a way the user should be told about. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Dismisses the current message.
     *
     * Needed because the message otherwise stays on screen until the next attempt to host or join.
     * "Could not reach Sam's PC" sitting above a working session, half an hour later, reads as a
     * current problem rather than as something that happened once.
     */
    fun clearError() {
        _error.value = null
    }

    private var bridge: PlaybackBridge? = null
    private var hostSession: HostSession? = null
    private var followerSession: FollowerSession? = null

    /** Volatile because the socket binds on an IO thread and everything else here runs on Swing. */
    @Volatile
    private var advertisement: LanDiscovery.Advertisement? = null

    /**
     * The listening socket, while hosting.
     *
     * Held rather than left to the accept loop's own lifetime, because that loop cannot end on its
     * own: it is parked in a blocking accept() that no amount of cancellation reaches. Without
     * closing this on the way out, stopping and restarting sharing left the previous port bound and
     * still answering.
     */
    private var listener: LanTransport.HostListener? = null
    private var sessionJobs = mutableListOf<Job>()

    /**
     * Bumped whenever a session ends or a new one starts.
     *
     * A session coroutine that is cancelled still runs its finally block, and it may do so after the
     * next session has already started - so a departing follower would reset the mode of the session
     * that replaced it. Comparing against the generation it was started in makes late cleanup
     * harmless.
     */
    private var generation = 0

    /**
     * Handed the player when there is one, and null when it goes away.
     *
     * Held nullable rather than assumed present: this manager is created once in `App()` and can
     * outlive the specifics of what is currently playing.
     */
    fun attachPlayer(bridge: PlaybackBridge?) {
        this.bridge = bridge
        if (bridge == null && _mode.value != ListenTogetherMode.OFF) {
            stop()
        }
    }

    val canStart: Boolean get() = bridge != null

    /** This device's name as followers will see it. */
    fun deviceName(): String = discovery.deviceName()

    /**
     * How far ahead of the host this device should aim, in milliseconds.
     *
     * Applied live, so a listener can adjust it while a session is running and hear the result -
     * which is the only practical way to set it, since the right value depends on the audio route
     * and nothing can measure it from here.
     */
    var offsetMs: Long = 0L
        set(value) {
            field = value
            followerSession?.offsetMs = value
        }

    /**
     * Hosts on the network so other devices can find and follow.
     *
     * The port is chosen by the OS and then advertised, rather than fixed. A hard-coded port that
     * happens to be taken would fail with nothing useful to say, and there is no reason to insist on
     * one when discovery carries the answer anyway.
     */
    fun startHosting() {
        val bridge = bridge ?: run {
            _error.value = "Start playing something first"
            return
        }
        stop()
        _error.value = null

        val session = HostSession(scope, bridge, ::nowUs, deviceName())
        hostSession = session
        sessionJobs += scope.launch { session.listeners.collect { _listeners.value = it } }

        val started = generation
        val listening = LanTransport.listen(
            scope = scope,
            nowUs = ::nowUs,
            onBound = { port ->
                // Advertised only once the socket is actually listening, and hopped back onto the
                // Swing scope rather than advertised straight from the bind thread - so this cannot
                // interleave with stop(). Otherwise starting and immediately stopping leaves the
                // device advertised forever: stop() ran while advertisement was still null, and this
                // assignment landed afterwards.
                scope.launch {
                    if (generation == started) {
                        advertisement = discovery.advertise(port)
                    }
                }
            },
        )
        sessionJobs += scope.launch {
            try {
                listening.links.collect { session.accept(it) }
            } catch (e: Exception) {
                // The accept loop reports a failure to bind by failing the flow. A SupervisorJob
                // stops that killing its siblings, but the exception would still reach the default
                // handler and take the app down, so it is caught and shown instead.
                if (generation == started) {
                    _error.value = "Could not start sharing"
                    stop()
                }
            }
        }
        session.start()
        _mode.value = ListenTogetherMode.HOSTING
    }

    /**
     * Hosts currently on the network.
     *
     * Cold: browsing keeps the radio busier than idle, so it runs only while a screen is collecting
     * and stops the moment that screen goes away.
     */
    fun discoverHosts(): Flow<List<DiscoveredHost>> {
        if (_mode.value == ListenTogetherMode.HOSTING) {
            // A host browsing would find its own advertisement and offer to follow itself.
            return discovery.discover(excluding = advertisement?.registeredName)
        }
        return discovery.discover()
    }

    /** Joins [host], following whatever it plays until [stop] or the host leaves. */
    fun join(host: DiscoveredHost) {
        val bridge = bridge ?: run {
            _error.value = "Start playing something first"
            return
        }
        stop()
        _error.value = null
        _mode.value = ListenTogetherMode.FOLLOWING

        val started = generation
        sessionJobs += scope.launch {
            val link = try {
                LanTransport.connect(host.address, host.port, scope, ::nowUs)
            } catch (e: CancellationException) {
                // Cancellation is not a failure to reach the host, and swallowing it here would both
                // report a connection error that did not happen and let this coroutine carry on
                // running after it was told to stop. It has to keep propagating.
                throw e
            } catch (e: Exception) {
                if (generation == started) {
                    _error.value = "Could not reach ${host.name}"
                    _mode.value = ListenTogetherMode.OFF
                }
                return@launch
            }

            val session = FollowerSession(scope, bridge, ::nowUs, deviceName())
            session.offsetMs = offsetMs
            followerSession = session
            val mirror = launch {
                session.state.collect { state ->
                    _followerState.value = state
                    // Surfaced rather than swallowed. This is the entire reason BYE carries a reason
                    // at all: without it, a host that stops sharing and a network drop look
                    // identical from here - the session simply vanishes and the user is left
                    // guessing which happened and whether retrying would help.
                    state.endedReason?.let { _error.value = byeMessage(it) }
                }
            }
            try {
                // Suspends for the whole session, so this coroutine's lifetime is the session's and
                // cancelling it is a complete teardown.
                session.run(link)
            } finally {
                mirror.cancel()
                if (generation == started) {
                    followerSession = null
                    _mode.value = ListenTogetherMode.OFF
                }
            }
        }
    }

    /** Ends whatever is running. Safe to call when nothing is. */
    fun stop() {
        generation++
        hostSession?.stop()
        hostSession = null
        followerSession?.leave()
        followerSession = null
        advertisement?.close()
        advertisement = null
        listener?.close()
        listener = null
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        _listeners.value = emptyList()
        _followerState.value = FollowerState()
        _mode.value = ListenTogetherMode.OFF
    }

    /**
     * The monotonic clock, in microseconds.
     *
     * `System.nanoTime()` rather than wall clock, for the same reason the phone reads
     * `SystemClock.elapsedRealtimeNanos()`: a wall clock steps whenever NTP corrects it or the user
     * edits the time, and a step mid-session is indistinguishable from an enormous sync error - the
     * follower would seek somewhere arbitrary in response to nothing having happened. The two clocks
     * have unrelated epochs, which does not matter: [ClockSync] only ever measures the *offset*
     * between two clocks that were never meant to agree in the first place.
     */
    private fun nowUs(): Long = System.nanoTime() / 1_000

    private fun byeMessage(reason: Byte): String = when (reason) {
        Protocol.ByeReason.HOST_STOPPED -> "The host stopped sharing."
        Protocol.ByeReason.VERSION_MISMATCH -> "That device is running a different version of OuterTune."
        Protocol.ByeReason.REJECTED -> "The host declined."
        else -> "The session ended."
    }
}
