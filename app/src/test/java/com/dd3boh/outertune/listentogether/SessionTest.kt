/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A player that behaves like a real one, including the part that makes syncing hard.
 *
 * The position it reports is accurate but *stale*: it refreshes only every [GRANULARITY_MS], exactly
 * as ExoPlayer does. A fake that returned a perfectly smooth position would make every sync test
 * pass trivially and would hide the single largest source of error in the feature.
 */
private class FakeBridge(private val nowUs: () -> Long) : PlaybackBridge {

    override var currentTrack: SharedTrack? = null
    override var isPlaying = false
    override var speed = 1f

    /** Where playback was at [baseAtUs]. The true position, which the player itself does not expose. */
    private var basePositionMs = 0L
    private var baseAtUs = 0L

    private var reportedMs = 0L
    private var reportedAtUs = 0L
    // A flag rather than a sentinel timestamp. Long.MIN_VALUE here overflows the moment anything
    // subtracts it from a real clock reading, and the comparison then silently goes the wrong way -
    // which showed up as this fake reporting position zero for an entire session.
    private var hasReported = false

    var seeks = 0
        private set
    var playTrackCalls = 0
        private set
    var trackFound = true

    /**
     * Makes the fake report the old song for a while after being handed a new one.
     *
     * Real behaviour, not a contrivance: handing a queue to the service returns long before the
     * player reports the new song, because the queue still has to be built and the item prepared.
     * A fake that switched instantly would hide every bug that lives in that window.
     */
    var reportsNewTrackImmediately = true

    /** The exact position, for a test to check against. A real player has no such method. */
    fun truePositionMs(): Long {
        if (!isPlaying) return basePositionMs
        return basePositionMs + ((nowUs() - baseAtUs) / 1000.0 * speed).roundToLong()
    }

    override fun positionMs(): Long {
        val now = nowUs()
        if (!hasReported || now - reportedAtUs >= GRANULARITY_MS * 1000) {
            reportedMs = truePositionMs()
            reportedAtUs = now
            hasReported = true
        }
        return reportedMs
    }

    private fun rebase() {
        basePositionMs = truePositionMs()
        baseAtUs = nowUs()
    }

    override fun seekTo(positionMs: Long) {
        rebase()
        basePositionMs = positionMs
        seeks++
    }

    override fun setPlayWhenReady(playing: Boolean) {
        rebase()
        isPlaying = playing
    }

    override fun setPlaybackSpeed(speed: Float) {
        rebase()
        this.speed = speed
    }

    override suspend fun playTrack(videoId: String, positionMs: Long): Boolean {
        playTrackCalls++
        if (!trackFound) return false
        // Loading a song is not instant, and pretending it is would skip the window in which the
        // follower must not act on ticks.
        delay(150)
        if (reportsNewTrackImmediately) {
            currentTrack = SharedTrack(videoId, "Title", "Artist", 240_000, false)
        }
        basePositionMs = positionMs
        baseAtUs = nowUs()
        isPlaying = true
        return true
    }

    fun start(track: SharedTrack, positionMs: Long) {
        currentTrack = track
        basePositionMs = positionMs
        baseAtUs = nowUs()
        isPlaying = true
    }

    private companion object {
        /** How often a real player refreshes its reported position. */
        const val GRANULARITY_MS = 300L
    }
}

/** A link with no socket, so a handshake can be driven frame by frame. */
private class FakeLink(private val nowUs: () -> Long) : PeerLink {
    val sent = CopyOnWriteArrayList<Protocol.Frame>()
    private val inbox = Channel<ReceivedFrame>(Channel.UNLIMITED)
    var closedWith: Byte? = null
    var closeCalled = false

    override val remoteAddress = "192.0.2.1"
    override val incoming: Flow<ReceivedFrame> = inbox.receiveAsFlow()
    override val isOpen: Boolean get() = !closeCalled

    override fun send(frame: Protocol.Frame) { sent += frame }
    override fun sendStamped(build: (Long) -> Protocol.Frame) { sent += build(nowUs()) }
    override fun close(reason: Byte?) {
        closeCalled = true
        closedWith = reason
        inbox.close()
    }

    fun deliver(frame: Protocol.Frame, atUs: Long = nowUs()) {
        inbox.trySend(ReceivedFrame(frame, atUs))
    }
}

class SessionTest {

    private fun nowUs(): Long = System.nanoTime() / 1000

    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ---- host handshake -------------------------------------------------------------------

    @Test
    fun `a follower is welcomed and given the current state at once`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs).apply {
                start(SharedTrack("abc", "Song", "Artist", 200_000, false), 45_000)
            }
            val host = HostSession(scope, bridge, ::nowUs, "Living room")
            val link = FakeLink(::nowUs)
            host.accept(link)
            link.deliver(Protocol.Frame.Hello(Protocol.VERSION, "Pixel 8"))

            withTimeout(TIMEOUT) { while (link.sent.size < 3) delay(10) }

            // Welcome, then the track, then a tick - without waiting for the broadcast loop. A
            // follower that joined just after a broadcast would otherwise sit blank for a full
            // second and look broken.
            assertEquals(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"), link.sent[0])
            assertTrue(link.sent[1] is Protocol.Frame.Track)
            assertTrue(link.sent[2] is Protocol.Frame.Tick)
            assertEquals("abc", (link.sent[1] as Protocol.Frame.Track).videoId)

            withTimeout(TIMEOUT) { assertEquals(1, host.listeners.first { it.isNotEmpty() }.size) }
            assertEquals("Pixel 8", host.listeners.value.first().name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a follower on the wrong protocol version is refused with a reason`() = runBlocking {
        val scope = scope()
        try {
            val host = HostSession(scope, FakeBridge(::nowUs), ::nowUs, "Living room")
            val link = FakeLink(::nowUs)
            host.accept(link)
            link.deliver(Protocol.Frame.Hello(Protocol.VERSION + 1, "Old phone"))

            withTimeout(TIMEOUT) { while (!link.closeCalled) delay(10) }
            // Told why, rather than left to misread frames. A version mismatch that half works is
            // far harder to diagnose than one that fails immediately and says so.
            assertEquals(Protocol.ByeReason.VERSION_MISMATCH, link.closedWith)
            assertTrue(host.listeners.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a ping is answered with the timestamps that make it usable`() = runBlocking {
        val scope = scope()
        try {
            val host = HostSession(scope, FakeBridge(::nowUs), ::nowUs, "Living room")
            val link = FakeLink(::nowUs)
            host.accept(link)
            link.deliver(Protocol.Frame.Hello(Protocol.VERSION, "Pixel 8"))
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }
            link.sent.clear()

            link.deliver(Protocol.Frame.Ping(t1 = 555), atUs = 1_000_000)
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            val pong = link.sent.first() as Protocol.Frame.Pong
            // t1 echoed unchanged - it is on the follower's clock and means nothing here. t2 is the
            // arrival stamp taken by the reader. Both are needed for the four-timestamp estimate.
            assertEquals(555, pong.t1)
            assertEquals(1_000_000, pong.t2)
            assertTrue("t3 must be stamped when the reply is written", pong.t3 >= pong.t2)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a departing follower is removed from the list`() = runBlocking {
        val scope = scope()
        try {
            val host = HostSession(scope, FakeBridge(::nowUs), ::nowUs, "Living room")
            val link = FakeLink(::nowUs)
            host.accept(link)
            link.deliver(Protocol.Frame.Hello(Protocol.VERSION, "Pixel 8"))
            withTimeout(TIMEOUT) { host.listeners.first { it.isNotEmpty() } }

            link.close(null)
            withTimeout(TIMEOUT) { host.listeners.first { it.isEmpty() } }
            Unit
        } finally {
            scope.cancel()
        }
    }

    // ---- follower behaviour ---------------------------------------------------------------

    @Test
    fun `nothing is corrected before the clock offset is known`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs).apply {
                start(SharedTrack("abc", "Song", "Artist", 200_000, false), 10_000)
            }
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }

            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }
            link.deliver(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"))
            link.deliver(Protocol.Frame.Track("abc", "Song", "Artist", 200_000, false))
            // A tick claiming the host is a minute further on, with no clock samples to interpret it
            // against. Acting on this would be a seek to a position computed from an unknown offset -
            // which is to say, an arbitrary one.
            link.deliver(Protocol.Frame.Tick(70_000, nowUs(), true, 1f))
            delay(300)

            assertEquals("must not seek on an unmeasured clock", 0, bridge.seeks)
            assertFalse(follower.state.value.synced)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a host playing a local file is shown, not chased`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs)
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            link.deliver(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"))
            link.deliver(Protocol.Frame.Track("file123", "Home recording", "Me", 90_000, true))
            repeat(5) { link.deliver(Protocol.Frame.Tick(1_000, nowUs(), true, 1f)) }
            delay(300)

            // There is nothing to fetch, so retrying every tick would be a failure loop with no
            // possible outcome. Showing what the host is on is the whole of the correct behaviour.
            assertEquals(0, bridge.playTrackCalls)
            assertTrue(follower.state.value.unavailable)
            assertEquals("Home recording", follower.state.value.track?.title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the end of a session is reported with its reason`() = runBlocking {
        val scope = scope()
        try {
            val follower = FollowerSession(scope, FakeBridge(::nowUs), ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            link.deliver(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"))
            link.deliver(Protocol.Frame.Bye(Protocol.ByeReason.HOST_STOPPED))

            withTimeout(TIMEOUT) { follower.state.first { it.endedReason != null } }
            // "The host stopped sharing" and "your WiFi dropped" deserve different messages, and a
            // bare dead socket cannot tell them apart.
            assertEquals(Protocol.ByeReason.HOST_STOPPED, follower.state.value.endedReason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a song missing from this device is reported rather than retried silently`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs).apply { trackFound = false }
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            link.deliver(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"))
            link.deliver(Protocol.Frame.Track("gone", "Missing", "Artist", 200_000, false))
            // Enough pongs for the offset to converge, so the tick is actually acted on.
            repeat(6) {
                val t = nowUs()
                link.deliver(Protocol.Frame.Pong(t, t, t), atUs = t)
            }
            link.deliver(Protocol.Frame.Tick(5_000, nowUs(), true, 1f))

            withTimeout(TIMEOUT) { follower.state.first { it.missingTrack } }
            assertTrue(follower.state.value.missingTrack)
        } finally {
            scope.cancel()
        }
    }

    // ---- both ends, over real sockets ------------------------------------------------------

    @Test
    fun `a follower joins a running session and lands on the host`() = runBlocking {
        // The whole feature, end to end: two coarse players, a real socket between them, and no
        // shortcuts. Everything below is what a listener would actually experience.
        val scope = scope()
        try {
            val hostBridge = FakeBridge(::nowUs).apply {
                start(SharedTrack("abc", "Song", "Artist", 240_000, false), 60_000)
            }
            val host = HostSession(scope, hostBridge, ::nowUs, "Living room")

            val bound = CompletableDeferred<Int>()
            val links = LanTransport.listen(scope, ::nowUs, port = 0) { bound.complete(it) }
            scope.launch { links.collect { host.accept(it) } }
            host.start()

            val port = withTimeout(TIMEOUT) { bound.await() }
            val followerBridge = FakeBridge(::nowUs)
            val follower = FollowerSession(scope, followerBridge, ::nowUs, "Pixel 8")
            val link = LanTransport.connect(InetAddress.getLoopbackAddress(), port, scope, ::nowUs)
            scope.launch { follower.run(link) }

            // Both ends run on this machine, so the true clock offset is zero and any large residual
            // error is a real defect rather than an unlucky network.
            withTimeout(TIMEOUT) { follower.state.first { it.synced && it.track != null } }
            assertEquals("abc", follower.state.value.track?.videoId)
            assertEquals("Living room", follower.state.value.hostName)

            // Long enough for the song to load, the offset to settle, and any correction to run its
            // course.
            delay(6_000)

            val gap = hostBridge.truePositionMs() - followerBridge.truePositionMs()
            assertTrue("still ${gap}ms apart after settling", abs(gap) < 150)
            assertTrue("follower should be playing", followerBridge.isPlaying)
            assertEquals("abc", followerBridge.currentTrack?.videoId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pausing the host pauses the follower`() = runBlocking {
        val scope = scope()
        try {
            val hostBridge = FakeBridge(::nowUs).apply {
                start(SharedTrack("abc", "Song", "Artist", 240_000, false), 30_000)
            }
            val host = HostSession(scope, hostBridge, ::nowUs, "Living room")
            val bound = CompletableDeferred<Int>()
            val links = LanTransport.listen(scope, ::nowUs, port = 0) { bound.complete(it) }
            scope.launch { links.collect { host.accept(it) } }
            host.start()

            val port = withTimeout(TIMEOUT) { bound.await() }
            val followerBridge = FakeBridge(::nowUs)
            val follower = FollowerSession(scope, followerBridge, ::nowUs, "Pixel 8")
            val link = LanTransport.connect(InetAddress.getLoopbackAddress(), port, scope, ::nowUs)
            scope.launch { follower.run(link) }

            withTimeout(TIMEOUT) { follower.state.first { it.synced && it.track != null } }
            withTimeout(TIMEOUT) { while (!followerBridge.isPlaying) delay(50) }

            hostBridge.setPlayWhenReady(false)
            // A follower still playing while the host is paused is the single most obvious way this
            // feature can look broken.
            withTimeout(TIMEOUT) { while (followerBridge.isPlaying) delay(50) }
            assertFalse(followerBridge.isPlaying)

            hostBridge.setPlayWhenReady(true)
            withTimeout(TIMEOUT) { while (!followerBridge.isPlaying) delay(50) }
            assertTrue(followerBridge.isPlaying)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the host keeps playing normally whatever the followers do`() = runBlocking {
        val scope = scope()
        try {
            val hostBridge = FakeBridge(::nowUs).apply {
                start(SharedTrack("abc", "Song", "Artist", 240_000, false), 10_000)
            }
            val host = HostSession(scope, hostBridge, ::nowUs, "Living room")
            val bound = CompletableDeferred<Int>()
            val links = LanTransport.listen(scope, ::nowUs, port = 0) { bound.complete(it) }
            scope.launch { links.collect { host.accept(it) } }
            host.start()

            val port = withTimeout(TIMEOUT) { bound.await() }
            val link = LanTransport.connect(InetAddress.getLoopbackAddress(), port, scope, ::nowUs)
            val follower = FollowerSession(scope, FakeBridge(::nowUs), ::nowUs, "Pixel 8")
            scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { follower.state.first { it.connected } }

            val before = hostBridge.truePositionMs()
            link.close(null)
            delay(1_500)

            // A session is one person listening normally while others follow. The host must never
            // wait for, slow down for, or be seeked by a follower - which also means one bad
            // connection cannot degrade anyone else.
            assertEquals(0, hostBridge.seeks)
            assertEquals(1f, hostBridge.speed)
            assertTrue(hostBridge.isPlaying)
            assertTrue("host should have carried on", hostBridge.truePositionMs() > before)
            withTimeout(TIMEOUT) { host.listeners.first { it.isEmpty() } }
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a song still loading is not requested over and over`() = runBlocking {
        val scope = scope()
        try {
            // The player has been handed the song but has not reported it yet - the ordinary state
            // of affairs for a second or two while it buffers.
            val bridge = FakeBridge(::nowUs).apply { reportsNewTrackImmediately = false }
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            link.deliver(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"))
            link.deliver(Protocol.Frame.Track("abc", "Song", "Artist", 240_000, false))
            repeat(6) {
                val t = nowUs()
                link.deliver(Protocol.Frame.Pong(t, t, t), atUs = t)
            }

            // A tick a second, as the host really sends them.
            repeat(4) {
                link.deliver(Protocol.Frame.Tick(30_000, nowUs(), true, 1f))
                delay(400)
            }

            // Without the settle window each of these would ask for the song again, restarting it
            // from the host's position every second for as long as buffering took - which is exactly
            // while the user is watching and concluding the feature is broken.
            assertEquals("the song must be requested once, not once per tick", 1, bridge.playTrackCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a host skipping to another song is followed at once`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs).apply { reportsNewTrackImmediately = false }
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            link.deliver(Protocol.Frame.Welcome(Protocol.VERSION, "Living room"))
            repeat(6) {
                val t = nowUs()
                link.deliver(Protocol.Frame.Pong(t, t, t), atUs = t)
            }
            link.deliver(Protocol.Frame.Track("abc", "First", "Artist", 240_000, false))
            link.deliver(Protocol.Frame.Tick(30_000, nowUs(), true, 1f))
            withTimeout(TIMEOUT) { while (bridge.playTrackCalls < 1) delay(20) }

            // A different song, well inside the settle window. The guard is keyed on the video id
            // precisely so this is not mistaken for the same request arriving twice.
            link.deliver(Protocol.Frame.Track("xyz", "Second", "Artist", 200_000, false))

            // Ticks keep coming, as a host really sends them. That matters: if the change lands
            // while the previous load is still in flight it is not acted on immediately, and the
            // next tick is what picks it up - the whole-state-every-frame design repairing itself
            // rather than a queue of pending changes that could get out of order.
            withTimeout(5_000) {
                while (bridge.playTrackCalls < 2) {
                    link.deliver(Protocol.Frame.Tick(0, nowUs(), true, 1f))
                    delay(200)
                }
            }
            assertEquals(2, bridge.playTrackCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a connection that never introduces itself is still cleaned up`() = runBlocking {
        val scope = scope()
        try {
            val host = HostSession(scope, FakeBridge(::nowUs), ::nowUs, "Living room")
            val silent = FakeLink(::nowUs)
            host.accept(silent)
            delay(200)

            // Never greeted, so not a listener and not sent anything...
            assertTrue(host.listeners.value.isEmpty())
            assertTrue(silent.sent.isEmpty())

            host.stop()
            // ...but it still holds a socket and a coroutine, so it has to be closed. Tracking only
            // peers that completed a handshake would leak a stalled connect, a port scan, or an app
            // killed halfway through joining.
            assertTrue("a silent connection must not be left open", silent.closeCalled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stopping the session tells every follower why`() = runBlocking {
        val scope = scope()
        try {
            val host = HostSession(scope, FakeBridge(::nowUs), ::nowUs, "Living room")
            val a = FakeLink(::nowUs)
            val b = FakeLink(::nowUs)
            host.accept(a)
            host.accept(b)
            a.deliver(Protocol.Frame.Hello(Protocol.VERSION, "Phone A"))
            b.deliver(Protocol.Frame.Hello(Protocol.VERSION, "Phone B"))
            withTimeout(TIMEOUT) { host.listeners.first { it.size == 2 } }

            host.stop()
            assertEquals(Protocol.ByeReason.HOST_STOPPED, a.closedWith)
            assertEquals(Protocol.ByeReason.HOST_STOPPED, b.closedWith)
            assertTrue(host.listeners.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a follower restores normal playback speed when the session ends`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs)
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            val running = scope.launch { follower.run(link) }
            withTimeout(TIMEOUT) { while (link.sent.isEmpty()) delay(10) }

            bridge.setPlaybackSpeed(1.04f)
            link.close(null)
            withTimeout(TIMEOUT) { running.join() }

            // A rate left applied would outlive the session and quietly play everything slightly
            // fast for the rest of the day - the kind of bug that gets reported as "the app sounds
            // wrong" months later.
            assertEquals(1f, bridge.speed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a follower with no host state does nothing at all`() = runBlocking {
        val scope = scope()
        try {
            val bridge = FakeBridge(::nowUs)
            val follower = FollowerSession(scope, bridge, ::nowUs, "Pixel 8")
            val link = FakeLink(::nowUs)
            scope.launch { follower.run(link) }
            delay(400)

            // Before a welcome, the only frame that should have gone out is the introduction.
            assertEquals(1, link.sent.count { it is Protocol.Frame.Hello })
            assertEquals(0, bridge.seeks)
            assertEquals(0, bridge.playTrackCalls)
            assertNull(follower.state.value.hostName)
            assertNotNull(link.sent.firstOrNull())
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
