/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import com.dd3boh.outertune.listentogether.DiscoveredHost
import com.dd3boh.outertune.listentogether.LanDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Sync over WiFi: while on, this device listens for and looks for the user's other devices with the
 * same sync code on the local network, and swaps plays with each - when one is found, then every
 * [ROUND_MS], or at once on [syncNow]. No account and no server: the code is the pairing.
 *
 * Its own DNS-SD type, so sync devices never appear in Listen together's list. The advertised name
 * starts with the code's tag (SyncCode.tag), so only devices with the same code are connected to;
 * the handshake (PlaySyncSession) is what actually keeps everyone else out.
 *
 * The desktop's copy differs only in its package and imports; keep the two in step.
 */
class PlaySyncManager(
    private val discovery: LanDiscovery,
    private val store: PlaySyncStore,
    private val deviceName: () -> String,
) {
    data class Status(
        /** Listening and looking. */
        val running: Boolean = false,
        /** The user's devices seen on this network, by name. */
        val peers: List<String> = emptyList(),
        val syncing: Boolean = false,
        /** The last exchange that finished, and when. */
        val last: SyncOutcome? = null,
        val lastAt: Long? = null,
        /** Why the last attempt failed, if it did. */
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var code: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lastTried = ConcurrentHashMap<String, Long>()

    /** One outgoing exchange at a time. Incoming ones are not held to it - see [run]. */
    private val outgoing = Mutex()

    @Synchronized
    fun start(code: String) {
        if (this.code == code && job?.isActive == true) return
        stop()
        this.code = code
        job = scope.launch { run(code) }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        code = null
        lastTried.clear()
        _status.update { it.copy(running = false, peers = emptyList(), syncing = false) }
    }

    /** Syncs with every device in reach now, rather than at the next round. */
    fun syncNow() {
        lastTried.clear()
        wake.trySend(Unit)
    }

    private suspend fun run(code: String) = coroutineScope {
        val outer = this
        val tag = SyncCode.tag(code)
        val server = ServerSocket(0)
        val advertisement = discovery.advertise(server.localPort, "$tag ${deviceName()}".take(60))
        _status.update { it.copy(running = true, error = null) }

        // Incoming exchanges run alongside an outgoing one rather than waiting for it: two devices
        // that find each other at the same moment each connect to the other, and with one lock for
        // both directions each would wait on the other until the socket timed out. Merging is
        // idempotent, so two at once costs nothing but the work.
        val accepting = launch {
            while (isActive) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                launch { exchange { PlaySyncSession(store, code, deviceName()).runAsServer(socket) } }
            }
        }

        try {
            while (isActive) {
                withTimeoutOrNull(BROWSE_MS) {
                    discovery.discover(excluding = advertisement.registeredName).collect { hosts ->
                        val own = advertisement.registeredName
                        val peers = hosts.filter { it.name.startsWith("$tag ") && it.name != own && it.port != server.localPort }
                        _status.update { s -> s.copy(peers = peers.map { it.name.removePrefix("$tag ") }) }
                        val now = System.currentTimeMillis()
                        peers.forEach { host ->
                            if (now - (lastTried[host.name] ?: 0L) < ROUND_MS) return@forEach
                            lastTried[host.name] = now
                            // On the outer scope: the browse window closing must not cancel an
                            // exchange that is under way.
                            outer.launch { outgoing.withLock { exchange { connect(host, code) } } }
                        }
                    }
                }
                withTimeoutOrNull(ROUND_MS) { wake.receive() }
            }
        } finally {
            advertisement.close()
            runCatching { server.close() }
            accepting.cancel()
        }
    }

    private fun connect(host: DiscoveredHost, code: String): SyncOutcome {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host.address, host.port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        return PlaySyncSession(store, code, deviceName()).runAsClient(socket)
    }

    private inline fun exchange(block: () -> SyncOutcome) {
        _status.update { it.copy(syncing = true) }
        try {
            val outcome = block()
            _status.update { it.copy(last = outcome, lastAt = System.currentTimeMillis(), error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        } finally {
            _status.update { it.copy(syncing = false) }
        }
    }

    companion object {
        /** Not Listen together's type - see the class doc. At most 15 characters, as DNS-SD requires. */
        const val SERVICE_TYPE = "_otsync._tcp"

        /** How long each look around the network lasts. */
        private const val BROWSE_MS = 30_000L

        /** Between looks, and the least time between two exchanges with one device. */
        private const val ROUND_MS = 10 * 60_000L

        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}

/** The running WiFi sync, for the settings screen. Set while the player service runs. */
object PlaySync {
    @Volatile
    var manager: PlaySyncManager? = null

    val idle: StateFlow<PlaySyncManager.Status> = MutableStateFlow(PlaySyncManager.Status())
}
