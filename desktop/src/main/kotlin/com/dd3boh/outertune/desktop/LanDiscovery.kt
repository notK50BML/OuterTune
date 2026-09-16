/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** One change to the set of visible hosts, so a single coroutine can own the collected result. */
private sealed interface DiscoveryEvent {
    data class Found(val type: String, val name: String) : DiscoveryEvent
    data class Lost(val name: String) : DiscoveryEvent
}

/** A host found on the network, ready to be joined. */
data class DiscoveredHost(
    val name: String,
    val address: InetAddress,
    val port: Int,
)

/**
 * Finds OuterTune hosts on the local network, and advertises this device as one.
 *
 * The Android app's `listentogether/LanDiscovery.kt` does the same thing through `NsdManager`; this
 * is the plain-JVM equivalent using jmDNS, since there is no `NsdManager` off Android. Both speak the
 * same DNS-SD: a host publishes `_outertune._tcp.local.` and a follower browses for it, so a phone
 * and a desktop find each other exactly as two phones would - nothing about the wire protocol
 * differs, only which library drives it.
 *
 * As on the phone, **the list is of hosts, not of devices**. A follower only ever sees a device that
 * is both running OuterTune and actively sharing, rather than every device on the LAN probed to find
 * out. Requires both ends on the same network with multicast permitted - guest and enterprise WiFi
 * frequently isolate clients, in which case nothing will ever be found and no retrying helps, which
 * is why [discover] surfaces an empty list rather than a spinner that never resolves.
 */
class LanDiscovery {

    private var instance: JmDNS? = null

    /** Created once, lazily, and reused - starting jmDNS opens a multicast socket, not something
     * worth doing more than once per process. */
    private fun jmdns(): JmDNS? {
        instance?.let { return it }
        return runCatching { JmDNS.create() }.getOrNull()?.also { instance = it }
    }

    /** A live advertisement. Closing it takes this device off the network. */
    interface Advertisement {
        /**
         * The name actually registered.
         *
         * Not necessarily the name requested: mDNS resolves collisions by renaming, so two machines
         * both called "Desktop" become "Desktop" and "Desktop (2)". Worth having because it is what
         * followers will see, and what this device must exclude when browsing.
         */
        val registeredName: String?
        fun close()
    }

    /**
     * Publishes this device as a host on [port].
     *
     * @param displayName what followers will see. Defaults to the machine's own hostname.
     */
    fun advertise(port: Int, displayName: String = deviceName()): Advertisement {
        val jmdns = jmdns() ?: return NoopAdvertisement
        return try {
            val info = ServiceInfo.create(SERVICE_TYPE, displayName, port, "")
            jmdns.registerService(info)
            object : Advertisement {
                override val registeredName: String? get() = info.name
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    runCatching { jmdns.unregisterService(info) }
                }
            }
        } catch (e: Exception) {
            NoopAdvertisement
        }
    }

    /**
     * Browses for hosts, emitting the current list whenever it changes.
     *
     * Emits an empty list immediately, so the UI has something to render and can say "looking" and
     * then "nothing found" rather than sitting on an indefinite spinner.
     *
     * @param excluding a service name to leave out - normally this device's own advertisement, so a
     *   host that also browses does not offer to join itself.
     */
    fun discover(excluding: String? = null): Flow<List<DiscoveredHost>> = callbackFlow {
        val jmdns = jmdns()
        if (jmdns == null) {
            send(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        send(emptyList())

        // Both kinds of change go through one queue, and one coroutine owns the map - the same
        // reasoning as the Android version: resolution happens off the callback thread and
        // completes asynchronously, so the map has to be owned by whichever thread reads the
        // events, not mutated from both a jmDNS thread and a coroutine at once.
        val events = Channel<DiscoveryEvent>(Channel.UNLIMITED)

        val worker = launch {
            val found = LinkedHashMap<String, DiscoveredHost>()
            for (event in events) {
                when (event) {
                    is DiscoveryEvent.Found -> {
                        // Bounded, because a service can vanish between being found and being
                        // resolved - ordinary on a network where a machine just went to sleep - and
                        // one that never resolves must not stall every host found after it.
                        val info = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) {
                                jmdns.getServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
                            }
                        } ?: continue
                        val host = info.toDiscoveredHost() ?: continue
                        if (host.name == excluding) continue
                        found[event.name] = host
                    }
                    // Keyed by service name because that is all a "lost" event carries - there is
                    // no address on it to match against.
                    is DiscoveryEvent.Lost -> if (found.remove(event.name) == null) continue
                }
                trySend(found.values.toList())
            }
        }

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                if (event.name == excluding) return
                events.trySend(DiscoveryEvent.Found(event.type, event.name))
            }
            override fun serviceRemoved(event: ServiceEvent) {
                events.trySend(DiscoveryEvent.Lost(event.name))
            }
            // jmdns.getServiceInfo(...) above already blocks until resolved, so this callback -
            // which fires for a resolution jmDNS itself initiated - has nothing left to do.
            override fun serviceResolved(event: ServiceEvent) = Unit
        }

        try {
            jmdns.addServiceListener(SERVICE_TYPE, listener)
        } catch (e: Exception) {
            close()
        }

        awaitClose {
            worker.cancel()
            events.close()
            runCatching { jmdns.removeServiceListener(SERVICE_TYPE, listener) }
        }
    }

    private fun ServiceInfo.toDiscoveredHost(): DiscoveredHost? {
        // A host can answer on several addresses - IPv4 and IPv6, or two interfaces. The first is
        // what jmDNS itself preferred when resolving, and trying to be cleverer than that would mean
        // guessing at routing.
        val address = inetAddresses.firstOrNull() ?: return null
        if (port <= 0) return null
        return DiscoveredHost(name.ifBlank { address.hostAddress }, address, port)
    }

    /** This machine's own hostname, for display to whoever discovers it. */
    fun deviceName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Desktop"

    private object NoopAdvertisement : Advertisement {
        override val registeredName: String? = null
        override fun close() = Unit
    }

    private companion object {
        /**
         * The DNS-SD type, `.local.` domain included as jmDNS expects it spelled. The same type the
         * phone advertises and browses for, minus the trailing domain NsdManager appends for you -
         * both put the same string on the wire.
         */
        const val SERVICE_TYPE = "_outertune._tcp.local."

        /** Longest a single resolve may take before the queue moves on without it. */
        const val RESOLVE_TIMEOUT_MS = 6_000L
    }
}
