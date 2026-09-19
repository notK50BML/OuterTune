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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.math.abs

/**
 * The transport, over real loopback sockets.
 *
 * Real sockets rather than a mocked stream, because every bug this layer can have is a bug about
 * what TCP actually does - frames coalescing into one segment, a frame split across two, a close
 * arriving while a write is in flight. A fake stream that hands back exactly what was written tests
 * none of that, and would pass while the real thing desynchronised.
 *
 * This is the reason [LanTransport] imports no Android: it runs here, on a desktop JVM, in
 * milliseconds, instead of only on two phones on the same WiFi.
 */
class LanTransportTest {

    private fun nowUs(): Long = System.nanoTime() / 1000

    /**
     * Stands up a genuine connected pair and hands both ends to the test.
     *
     * Port 0 asks the OS for a free one, so tests never collide with each other or with anything
     * else on the machine running them.
     */
    private fun withLinkedPair(block: suspend (host: PeerLink, follower: PeerLink) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val bound = CompletableDeferred<Int>()
                val links = LanTransport.listen(scope, ::nowUs, port = 0) { bound.complete(it) }.links
                val hostSide = scope.async { links.first() }
                val port = withTimeout(TIMEOUT) { bound.await() }
                val follower = LanTransport.connect(
                    InetAddress.getLoopbackAddress(), port, scope, ::nowUs,
                )
                val host = withTimeout(TIMEOUT) { hostSide.await() }
                block(host, follower)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `frames cross the wire in both directions`() = withLinkedPair { host, follower ->
        val toFollower = Protocol.Frame.Track("id", "Title", "Artist", 200_000L, false)
        host.send(toFollower)
        assertEquals(toFollower, withTimeout(TIMEOUT) { follower.incoming.first() }.frame)

        val toHost = Protocol.Frame.Hello(Protocol.VERSION, "Pixel 8")
        follower.send(toHost)
        assertEquals(toHost, withTimeout(TIMEOUT) { host.incoming.first() }.frame)
    }

    @Test
    fun `a burst arrives whole and in order`() = withLinkedPair { host, follower ->
        // Small frames sent back to back will be coalesced by TCP into far fewer segments than
        // frames, and the large ones will be split across segments. Both are the case a bare read()
        // gets wrong: it would return a partial frame, the length prefix would then be read from the
        // middle of a payload, and every frame after that would be garbage - silently, with the
        // stream never recovering. Interleaving the two sizes exercises both in one pass.
        val big = "x".repeat(500)
        val sent = (1..40).map { i ->
            if (i % 4 == 0) Protocol.Frame.Track("id$i", big, big, i.toLong(), false)
            else Protocol.Frame.Tick(i * 1000L, i * 2000L, i % 2 == 0, 1f)
        }
        sent.forEach { host.send(it) }

        val got = withTimeout(TIMEOUT) { follower.incoming.take(sent.size).map { it.frame }.toList() }
        assertEquals(sent, got)
    }

    @Test
    fun `a clock sync exchange over the real transport lands on the truth`() =
        withLinkedPair { host, follower ->
            // Both ends are this machine, so the true offset is zero and any answer far from zero is
            // the timestamp discipline failing somewhere in the chain: t1 stamped when the ping is
            // written, t2 and t3 by the host around its read, t4 when the pong lands.
            //
            // This is the test that justifies sendStamped existing at all. Stamping at queue time
            // instead would fold scheduling delay into what the estimator reads as network delay.
            val sync = ClockSync()
            repeat(8) {
                follower.sendStamped { t1 -> Protocol.Frame.Ping(t1) }

                val ping = withTimeout(TIMEOUT) { host.incoming.first() }
                val t1 = (ping.frame as Protocol.Frame.Ping).t1
                val t2 = ping.atUs
                host.sendStamped { t3 -> Protocol.Frame.Pong(t1, t2, t3) }

                val pong = withTimeout(TIMEOUT) { follower.incoming.first() }
                val reply = pong.frame as Protocol.Frame.Pong
                sync.offer(reply.t1, reply.t2, reply.t3, pong.atUs)
            }

            val offset = sync.offsetUs
            assertTrue("no offset was produced at all", offset != null)
            // Loopback is quick but this runs on a shared CI machine, so a few milliseconds of
            // scheduling noise is expected. What is being ruled out is a sign error or a stamp taken
            // in the wrong place, which would show up as tens of milliseconds or more.
            assertTrue("offset was ${offset}us, expected near zero", abs(offset!!) < 20_000)
        }

    @Test
    fun `incoming frames are timestamped on arrival`() = withLinkedPair { host, follower ->
        val before = nowUs()
        host.send(Protocol.Frame.Tick(1L, 2L, true, 1f))
        val received = withTimeout(TIMEOUT) { follower.incoming.first() }
        val after = nowUs()
        assertTrue(
            "arrival ${received.atUs} outside [$before, $after]",
            received.atUs in before..after,
        )
    }

    @Test
    fun `closing one end completes the other`() = withLinkedPair { host, follower ->
        // The disconnection contract: a collector that simply falls out of its loop has handled the
        // peer going away. If the flow did not complete, every consumer would need a separate
        // liveness check and would hang without one.
        host.close(Protocol.ByeReason.HOST_STOPPED)
        val frames = withTimeout(TIMEOUT) { follower.incoming.map { it.frame }.toList() }
        assertEquals(listOf(Protocol.Frame.Bye(Protocol.ByeReason.HOST_STOPPED)), frames)
    }

    @Test
    fun `a farewell is flushed before the socket goes`() = withLinkedPair { host, follower ->
        host.send(Protocol.Frame.Tick(5L, 6L, true, 1f))
        host.close(Protocol.ByeReason.NORMAL)
        // Closing immediately after a send must not truncate the queue. Anything already written is
        // owed to the peer - the BYE most of all, since it is the difference between "the host left"
        // and "something broke".
        val frames = withTimeout(TIMEOUT) { follower.incoming.map { it.frame }.toList() }
        assertEquals(
            listOf(
                Protocol.Frame.Tick(5L, 6L, true, 1f),
                Protocol.Frame.Bye(Protocol.ByeReason.NORMAL),
            ),
            frames,
        )
    }

    @Test
    fun `an abrupt close still completes the peer`() = withLinkedPair { host, follower ->
        // No BYE - the socket simply dies, which is what a crash or a dropped connection looks like.
        host.close(reason = null)
        withTimeout(TIMEOUT) { follower.incoming.toList() }
        assertFalse(host.isOpen)
    }

    @Test
    fun `sending on a closed link is harmless`() = withLinkedPair { host, _ ->
        host.close()
        // The player keeps ticking for a moment after a session ends. That must not throw into
        // whatever thread the player happens to be on.
        host.send(Protocol.Frame.Tick(1L, 2L, true, 1f))
        host.sendStamped { Protocol.Frame.Ping(it) }
        host.close()
    }

    @Test
    fun `a congested link drops the stalest frames rather than blocking the sender`() =
        withLinkedPair { host, follower ->
            // Documenting a real trade-off rather than testing a feature. send() is called from the
            // player thread and must never block, so a backed-up queue has to lose something. It
            // loses the oldest, because if the link is that far behind, stale positions are worthless
            // and the newest frame is the one worth delivering.
            //
            // Safe only because every frame carries whole state: the next tick repairs whatever a
            // dropped one would have said. It is also why the host repeats the current track
            // periodically instead of sending it once.
            val burst = 4_000
            repeat(burst) { host.send(Protocol.Frame.Tick(it.toLong(), 0L, true, 1f)) }
            host.close()

            val got = withTimeout(TIMEOUT) { follower.incoming.map { it.frame }.toList() }
            assertTrue("nothing arrived at all", got.isNotEmpty())
            assertTrue("expected drops from a burst of $burst, got ${got.size}", got.size < burst)

            val positions = got.filterIsInstance<Protocol.Frame.Tick>().map { it.positionMs }
            assertEquals("what survived must stay in order", positions.sorted(), positions)
        }

    private companion object {
        /** Generous: these are real sockets on a machine that may be busy. */
        const val TIMEOUT = 15_000L
    }
    /**
     * Closing a listener must actually release the port.
     *
     * This is the test that would have caught the desktop half of the socket-release fix being
     * missing, and it is worth stating why it needs a *fixed* port rather than the port-0 the other
     * tests use: asking the OS for any free port cannot tell the difference between a port that was
     * released and one that was never held. Binding the same number twice is the only thing that
     * can.
     *
     * The loop also stands in for the user sequence that was broken - Start sharing, Stop, Start
     * sharing - which silently fell back to a random port and left the first one bound forever.
     */
    @Test
    fun `closing a listener frees the port for the next one`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            // A high port picked to be unlikely to collide with anything on the machine running
            // this. If it is genuinely taken, listen() falls back to port 0 and the assertion below
            // is what reports that rather than the test failing obscurely later.
            val port = 47_931
            repeat(3) { attempt ->
                val bound = CompletableDeferred<Int>()
                val listener = LanTransport.listen(scope, ::nowUs, port = port) { bound.complete(it) }
                val got = withTimeout(TIMEOUT) { bound.await() }
                assertEquals("attempt $attempt did not get the port back", port, got)
                listener.close()
                // close() has to be enough on its own. Cancelling the scope would mask the bug,
                // since accept() is blocking and cancellation cannot interrupt it.
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closing a listener twice is harmless`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val bound = CompletableDeferred<Int>()
            val listener = LanTransport.listen(scope, ::nowUs, port = 0) { bound.complete(it) }
            withTimeout(TIMEOUT) { bound.await() }
            listener.close()
            listener.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closing a listener ends its flow, so a collector does not hang`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val bound = CompletableDeferred<Int>()
            val listener = LanTransport.listen(scope, ::nowUs, port = 0) { bound.complete(it) }
            withTimeout(TIMEOUT) { bound.await() }
            // Collecting to a list completes only when the channel closes. If close() left it open,
            // this would hang rather than fail - hence the timeout.
            val collected = scope.async { listener.links.toList() }
            listener.close()
            val links = withTimeout(TIMEOUT) { collected.await() }
            assertTrue("no connections were made, so none should arrive", links.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    /**
     * Closing while the bind is still in flight must not leave a socket listening.
     *
     * The race the AtomicBoolean in listen() exists for: close() can run before the accept loop has
     * finished binding, finding nothing to close, and the socket would then be created afterwards
     * with nobody holding a reference to it.
     */
    @Test
    fun `closing immediately after starting still releases the port`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val port = 47_932
            // No wait between these two at all - that is the point.
            LanTransport.listen(scope, ::nowUs, port = port).close()

            val bound = CompletableDeferred<Int>()
            val second = LanTransport.listen(scope, ::nowUs, port = port) { bound.complete(it) }
            val got = withTimeout(TIMEOUT) { bound.await() }
            assertEquals("the port was still held by the listener closed mid-bind", port, got)
            second.close()
        } finally {
            scope.cancel()
        }
    }
}
