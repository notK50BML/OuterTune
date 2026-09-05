/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.dd3boh.outertune.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Whether this device is sharing, listening along, or doing neither. */
enum class ListenTogetherMode { OFF, HOSTING, FOLLOWING }

/**
 * Owns a listen-together session and everything underneath it.
 *
 * Single entry point for the UI: the screen asks for a list of hosts, or to start one, and watches
 * the state flows. Nothing above this layer knows about sockets, mDNS or clock offsets.
 *
 * **The scope is main-dispatched, and that is a correctness requirement rather than a convention.**
 * Both sessions read and write the player directly, Media3 permits that only from the main thread,
 * and the transport already does its blocking IO on [Dispatchers.IO] behind a channel. Moving this
 * to a background dispatcher would produce crashes that only appear once a follower actually
 * connects.
 */
@Singleton
class ListenTogetherManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val discovery = LanDiscovery(context)

    private val _mode = MutableStateFlow(ListenTogetherMode.OFF)
    val mode: StateFlow<ListenTogetherMode> = _mode.asStateFlow()

    private val _listeners = MutableStateFlow<List<Listener>>(emptyList())
    val listeners: StateFlow<List<Listener>> = _listeners.asStateFlow()

    private val _followerState = MutableStateFlow(FollowerState())
    val followerState: StateFlow<FollowerState> = _followerState.asStateFlow()

    /** Set when something failed in a way the user should be told about. Cleared on the next start. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var bridge: PlaybackBridge? = null
    private var hostSession: HostSession? = null
    private var followerSession: FollowerSession? = null
    /** Volatile because the socket binds on an IO thread and everything else here runs on Main. */
    @Volatile
    private var advertisement: LanDiscovery.Advertisement? = null
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
     * Handed the player when the service has one, and null when it goes away.
     *
     * The manager outlives any particular player instance - it is a singleton and the service is
     * not - so it holds a nullable bridge rather than a player it might be using after the service
     * has been destroyed.
     */
    fun attachPlayer(bridge: PlaybackBridge?) {
        this.bridge = bridge
        if (bridge == null && _mode.value != ListenTogetherMode.OFF) {
            // Playback stopped underneath a running session. Ending it is the honest response;
            // leaving followers connected to a device that can no longer play anything would show
            // them a session that has silently stopped meaning anything.
            stop()
        }
    }

    val canStart: Boolean get() = bridge != null

    /** This device's name as followers will see it. */
    fun deviceName(): String = discovery.deviceName()

    /**
     * Hosts on the network so other devices can find and follow.
     *
     * The port is chosen by the OS and then advertised, rather than fixed. A hard-coded port that
     * happens to be taken would fail with nothing useful to say, and there is no reason to insist on
     * one when discovery carries the answer anyway.
     */
    fun startHosting() {
        val bridge = bridge ?: run {
            _error.value = context.getString(R.string.lt_needs_playback)
            return
        }
        stop()
        _error.value = null

        val session = HostSession(scope, bridge, ::nowUs, deviceName())
        hostSession = session
        sessionJobs += scope.launch { session.listeners.collect { _listeners.value = it } }

        val started = generation
        val links = LanTransport.listen(
            scope = scope,
            nowUs = ::nowUs,
            onBound = { port ->
                // Advertised only once the socket is actually listening. Announcing first would
                // publish a port that briefly refuses connections, and a follower that tried in that
                // window would see a failure with no explanation.
                //
                // Hopped back onto the main scope rather than advertised straight from the bind
                // thread, so this cannot interleave with stop(). Otherwise a user who taps start and
                // then immediately stop leaves the device advertised on the network forever: stop
                // ran while advertisement was still null, and the assignment landed afterwards.
                scope.launch {
                    if (generation == started) {
                        advertisement = discovery.advertise(port)
                        Log.i(TAG, "hosting on port $port")
                    }
                }
            },
        )
        sessionJobs += scope.launch {
            try {
                links.collect { session.accept(it) }
            } catch (e: Exception) {
                // The accept loop reports a failure to bind by failing the flow. A SupervisorJob
                // stops that killing its siblings, but the exception would still reach the default
                // handler and take the app down, so it is caught and shown instead.
                Log.e(TAG, "hosting stopped", e)
                if (generation == started) {
                    _error.value = context.getString(R.string.lt_host_failed)
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
            _error.value = context.getString(R.string.lt_needs_playback)
            return
        }
        stop()
        _error.value = null
        _mode.value = ListenTogetherMode.FOLLOWING

        val started = generation
        sessionJobs += scope.launch {
            val link = try {
                LanTransport.connect(host.address, host.port, scope, ::nowUs)
            } catch (e: Exception) {
                Log.e(TAG, "could not reach ${host.name}", e)
                if (generation == started) {
                    _error.value = context.getString(R.string.lt_unreachable, host.name)
                    _mode.value = ListenTogetherMode.OFF
                }
                return@launch
            }

            val session = FollowerSession(scope, bridge, ::nowUs, deviceName())
            followerSession = session
            val mirror = launch {
                session.state.collect { state ->
                    _followerState.value = state
                    // Surfaced rather than swallowed. This is the entire reason BYE carries a reason
                    // at all: without it, a host that stops sharing and a WiFi drop look identical
                    // from here - the session simply vanishes and the user is left guessing which
                    // happened and whether retrying would help.
                    state.endedReason?.let { _error.value = context.getString(byeMessage(it)) }
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
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        _listeners.value = emptyList()
        _followerState.value = FollowerState()
        _mode.value = ListenTogetherMode.OFF
    }

    /**
     * The monotonic clock, in microseconds.
     *
     * [SystemClock.elapsedRealtimeNanos] rather than wall clock, and this is load-bearing: a wall
     * clock steps whenever NTP corrects it or the user edits the time, and a step mid-session is
     * indistinguishable from an enormous sync error - the follower would seek somewhere arbitrary in
     * response to nothing having happened. This one also keeps counting through deep sleep, which
     * uptime-based clocks do not.
     */
    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000

    @StringRes
    private fun byeMessage(reason: Byte): Int = when (reason) {
        Protocol.ByeReason.HOST_STOPPED -> R.string.lt_ended_host_stopped
        Protocol.ByeReason.VERSION_MISMATCH -> R.string.lt_ended_version
        Protocol.ByeReason.REJECTED -> R.string.lt_ended_rejected
        else -> R.string.lt_ended
    }

    private companion object {
        const val TAG = "ListenTogether"
    }
}
