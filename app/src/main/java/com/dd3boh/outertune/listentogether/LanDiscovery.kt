/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress

/** One change to the set of visible hosts, so a single coroutine can own the collected result. */
private sealed interface DiscoveryEvent {
    data class Found(val service: NsdServiceInfo) : DiscoveryEvent
    data class Lost(val serviceName: String) : DiscoveryEvent
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
 * Uses DNS-SD over multicast - the same mechanism as AirPlay and Chromecast - through Android's
 * [NsdManager]. A host publishes a `_outertune._tcp` service; a follower browses for that type.
 *
 * This is worth stating because it differs from the obvious design: **the list is of hosts, not of
 * devices.** Scanning the subnet for every device and then probing each one to see whether OuterTune
 * is installed would be slow, would look like a port scan to anything watching, and on modern
 * Android is largely blocked anyway. Browsing for a service inverts it - only devices actually
 * running OuterTune and actually hosting ever answer, so "is OuterTune installed" is answered by the
 * device appearing in the list at all. Nothing that appears can be stale in that sense, and nothing
 * that cannot be joined is shown.
 *
 * The cost is that a device must be hosting before it can be seen, so a follower cannot browse for
 * "people who might want to listen". That matches how the feature is used: someone starts a session,
 * others join it.
 *
 * Requires both devices on the same network with multicast permitted. Guest and enterprise WiFi
 * frequently isolate clients, in which case nothing will ever be discovered and no amount of
 * retrying helps - which is why [discover] surfaces an empty list rather than a spinner that never
 * resolves.
 */
class LanDiscovery(private val context: Context) {

    private val nsd: NsdManager?
        get() = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** A live advertisement. Closing it takes this device off the network. */
    interface Advertisement {
        /**
         * The name actually registered.
         *
         * Not necessarily the name requested: mDNS resolves collisions by renaming, so two phones
         * both called "Pixel 8" become "Pixel 8" and "Pixel 8 (2)". Worth having because it is what
         * followers will see, and what this device must exclude when browsing.
         */
        val registeredName: String?
        fun close()
    }

    /**
     * Publishes this device as a host on [port].
     *
     * @param displayName what followers will see. Defaults to the device's own name.
     */
    fun advertise(port: Int, displayName: String = deviceName()): Advertisement {
        val manager = nsd ?: return NoopAdvertisement
        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            var name: String? = null
            override fun onServiceRegistered(info: NsdServiceInfo) {
                name = info.serviceName
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "advertise failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        return try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            object : Advertisement {
                override val registeredName: String? get() = listener.name
                private var closed = false
                override fun close() {
                    if (closed) return
                    closed = true
                    // Throws if registration never succeeded. Nothing useful to do about it, and it
                    // must not take the caller down on the way out of a session.
                    runCatching { manager.unregisterService(listener) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "advertise threw", e)
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
        val manager = nsd
        if (manager == null) {
            send(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        send(emptyList())

        // Both kinds of change go through one queue, and one coroutine owns the map.
        //
        // Two reasons, and they are independent. Resolution has to be serialised because before API
        // 34 the platform allows exactly one resolve in flight, and a second one started while the
        // first is pending fails with "listener already in use" - easy to hit, since services are
        // found in a burst and the natural code resolves each as it arrives. The symptom is that the
        // first host appears and the rest silently never do.
        //
        // The map has to be owned by one thread because NSD delivers its callbacks on a binder
        // thread while resolution completes on a coroutine. Mutating a LinkedHashMap from both, and
        // iterating it to publish, is a ConcurrentModificationException waiting for the moment a
        // device leaves the network just as another is being resolved.
        val events = Channel<DiscoveryEvent>(Channel.UNLIMITED)

        val worker = launch {
            val found = LinkedHashMap<String, DiscoveredHost>()
            for (event in events) {
                when (event) {
                    is DiscoveryEvent.Found -> {
                        val resolved = resolveOne(manager, event.service) ?: continue
                        val host = resolved.toDiscoveredHost() ?: continue
                        if (host.name == excluding) continue
                        found[resolved.serviceName] = host
                    }
                    // Keyed by service name because that is all a "lost" event carries - there is
                    // no address on it to match against.
                    is DiscoveryEvent.Lost -> if (found.remove(event.serviceName) == null) continue
                }
                trySend(found.values.toList())
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType?.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                if (service.serviceName == excluding) return
                events.trySend(DiscoveryEvent.Found(service))
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                events.trySend(DiscoveryEvent.Lost(service.serviceName ?: return))
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "discovery failed to start: $errorCode")
                runCatching { manager.stopServiceDiscovery(this) }
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }
        }

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices threw", e)
            close()
        }

        awaitClose {
            worker.cancel()
            events.close()
            // Multicast browsing is not free - it keeps the radio busier than idle - so stopping
            // promptly when the screen goes away matters for battery, not just tidiness.
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    /**
     * One resolve, as a suspending call.
     *
     * Returns null rather than throwing on failure: a service can disappear between being found and
     * being resolved, which is ordinary on a network where a phone just went to sleep, and the right
     * response is to move on to the next one.
     */
    @Suppress("DEPRECATION")
    private suspend fun resolveOne(
        manager: NsdManager,
        service: NsdServiceInfo,
    ): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        // The deprecated resolveService rather than API 34's registerServiceInfoCallback. One code
        // path across every supported version is worth more here than shedding a deprecation
        // warning, given the queue above already handles the limitation that the newer API exists to
        // remove. Worth revisiting if resolveService is ever actually removed.
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                cont.resumeOnce(null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                cont.resumeOnce(info)
            }
        }
        try {
            manager.resolveService(service, listener)
        } catch (e: Exception) {
            cont.resumeOnce(null)
        }
    }

    private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
        // resumeWith rather than resume: CancellableContinuation has its own resume overload that
        // wants a cancellation handler, and the plain one is easy to bind to by accident.
        if (isActive) resumeWith(Result.success(value))
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.toDiscoveredHost(): DiscoveredHost? {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // A host can answer on several addresses - IPv4 and IPv6, or two interfaces. The first
            // is what the platform considers most usable, and trying to be cleverer than that would
            // mean guessing at routing.
            hostAddresses.firstOrNull()
        } else {
            host
        } ?: return null
        if (port <= 0) return null
        return DiscoveredHost(serviceName ?: address.hostAddress ?: "Unknown", address, port)
    }

    /**
     * The name the user has given this device, falling back to its model.
     *
     * "Sam's Pixel" is far more useful in a list than "Pixel 8", and on most devices the former is
     * set. Not a documented public setting, hence the lint suppression and the fallback.
     */
    @SuppressLint("HardwareIds")
    fun deviceName(): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return configured?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android device"
    }

    private object NoopAdvertisement : Advertisement {
        override val registeredName: String? = null
        override fun close() = Unit
    }

    private companion object {
        /**
         * The DNS-SD type. Changing this breaks discovery between versions, which is a legitimate
         * way to retire an incompatible protocol - but [Protocol.VERSION] is the cheaper lever, and
         * refusing a peer with a message beats being invisible to it.
         */
        const val SERVICE_TYPE = "_outertune._tcp"
        const val TAG = "LanDiscovery"
    }
}
