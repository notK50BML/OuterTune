/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP transport for a listen-together session, over the local network.
 *
 * TCP rather than UDP, which is the opposite of what a real-time protocol usually reaches for. The
 * reason is that these frames are rare and small - one tick a second - so head-of-line blocking has
 * almost nothing to block, while everything TCP gives away for free is something this would
 * otherwise have to build: ordering, connection state that can be observed, and a disconnection that
 * is actually detectable. Over a single WiFi hop, the retransmission UDP would avoid is a case that
 * essentially does not arise.
 *
 * Nothing here imports Android. That is deliberate, and it costs a clock passed in as a parameter
 * rather than read directly - but it means the framing, the fragmentation handling and the timestamp
 * discipline can be tested against real sockets on a desktop JVM in milliseconds, instead of only on
 * two physical phones.
 */
object LanTransport {

    /** The port the host prefers. Falls back to any free port if this one is taken. */
    const val DEFAULT_PORT = 47_814

    /**
     * Refuse a frame larger than this rather than allocating whatever the length prefix asks for.
     *
     * The largest legitimate frame is a TRACK carrying two 512-byte strings, so a few kilobytes is
     * generous. Without the check, a peer - or some entirely unrelated service whose bytes happen to
     * arrive on this port - could name any size up to 64KB and have it allocated on demand.
     */
    private const val MAX_FRAME = 8_192

    /**
     * How long a link may hear nothing at all before it is presumed dead.
     *
     * Both directions carry continuous traffic by design: the host ticks every second even while
     * paused, and the follower pings for clock sync. So silence is not a quiet session, it is a
     * broken one. This is what catches the disconnection TCP will not report for minutes on its own
     * - walking out of WiFi range produces no FIN, just nothing.
     */
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Connects to a host. Returns a live link, or throws if the host cannot be reached.
     *
     * @param timeoutMs kept well below the read timeout, because a device that has left the network
     *   should fail fast - the user is watching a spinner while it does.
     */
    suspend fun connect(
        address: InetAddress,
        port: Int,
        scope: CoroutineScope,
        nowUs: () -> Long,
        timeoutMs: Int = 5_000,
    ): PeerLink = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), timeoutMs)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        SocketPeerLink(socket, nowUs, scope, READ_TIMEOUT_MS, MAX_FRAME)
    }

    /**
     * Listens for followers.
     *
     * Emits one link per connection and keeps accepting until the scope is cancelled - including
     * after a follower disconnects, since a session that ended because one listener took a phone
     * call would be useless.
     *
     * @param onBound reports the port actually bound, which may not be the one requested.
     */
    fun listen(
        scope: CoroutineScope,
        nowUs: () -> Long,
        port: Int = DEFAULT_PORT,
        onBound: (Int) -> Unit = {},
    ): Flow<PeerLink> {
        val links = Channel<PeerLink>(Channel.BUFFERED)
        scope.launch(Dispatchers.IO) {
            // The well-known port first, so a follower could in principle connect without discovery
            // at all. But port 0 - any free port - rather than failing if something already holds
            // it, because discovery advertises whichever port was actually granted.
            val server = try {
                ServerSocket(port)
            } catch (e: IOException) {
                try {
                    ServerSocket(0)
                } catch (e2: IOException) {
                    links.close(e2)
                    return@launch
                }
            }
            onBound(server.localPort)
            try {
                while (true) {
                    val socket = server.accept()
                    links.send(SocketPeerLink(socket, nowUs, scope, READ_TIMEOUT_MS, MAX_FRAME))
                }
            } catch (e: Exception) {
                // accept() throws when the socket is closed on cancellation. Not an error.
            } finally {
                runCatching { server.close() }
                links.close()
            }
        }
        return links.receiveAsFlow()
    }
}

/**
 * One socket, framed into [Protocol.Frame]s.
 *
 * Blocking IO on [Dispatchers.IO] rather than NIO. A session holds a handful of connections at most,
 * so the thread cost is irrelevant, and blocking reads give the one thing that genuinely matters
 * here for free: a timestamp taken on the very thread that read the bytes, with nothing scheduled in
 * between the read completing and the clock being sampled.
 */
private class SocketPeerLink(
    private val socket: Socket,
    private val nowUs: () -> Long,
    scope: CoroutineScope,
    readTimeoutMs: Int,
    private val maxFrame: Int,
) : PeerLink {

    /**
     * Frames waiting to be written, held as builders rather than as frames.
     *
     * Builders because clock-sync frames have to be stamped at the moment they are written rather
     * than when they were queued - see [PeerLink.sendStamped].
     *
     * [BufferOverflow.DROP_OLDEST] because if this backs up the link is congested, and then the
     * stalest frames are precisely the ones worth losing. Dropping a tick costs nothing, since the
     * next one carries whole state.
     */
    private val outgoing = Channel<(Long) -> Protocol.Frame>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val received = Channel<ReceivedFrame>(Channel.BUFFERED)
    private val closed = AtomicBoolean(false)

    override val remoteAddress: String = socket.inetAddress?.hostAddress ?: "unknown"
    override val incoming: Flow<ReceivedFrame> = received.receiveAsFlow()
    override val isOpen: Boolean get() = !closed.get()

    init {
        runCatching { socket.soTimeout = readTimeoutMs }
        // Without this, Nagle's algorithm holds a small write back waiting for more data to fill a
        // packet - up to a couple of hundred milliseconds. Every frame here is small, and the one
        // whose timing is the entire point of the feature is the smallest of them all, so the
        // optimisation is exactly inverted for this traffic.
        runCatching { socket.tcpNoDelay = true }
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
    }

    private suspend fun readLoop() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (true) {
                // readUnsignedShort and readFully, never a bare read(). A plain read() may return
                // fewer bytes than asked for whenever a frame straddles two TCP segments, and
                // treating a short read as a complete frame would desynchronise the stream from that
                // point onward - every later frame misparsed, with no error to point at.
                val length = input.readUnsignedShort()
                if (length == 0 || length > maxFrame) throw IOException("bad frame length $length")
                val payload = ByteArray(length)
                input.readFully(payload)
                val at = nowUs()
                // Stamped before decoding, because decoding takes time that would otherwise be
                // charged to the network and inflate the measured round trip.
                val frame = Protocol.decode(payload)
                if (frame != null) received.send(ReceivedFrame(frame, at))
                // A frame that will not decode is dropped and the link kept. The version handshake
                // already refuses a peer whose frames this build cannot read, so anything arriving
                // here is corruption worth surviving rather than a mismatch worth disconnecting on.
            }
        } catch (e: Exception) {
            // Every disconnection arrives here: EOF on a clean close, a reset, a timeout on a peer
            // that vanished, or the socket being closed by close() below.
        } finally {
            shutdown()
        }
    }

    private suspend fun writeLoop() {
        try {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            // Buffered, then flushed once per frame. Writing the two-byte header and the payload
            // straight to the socket would put them in separate TCP segments now that Nagle is off -
            // two packets and an extra round of latency for every single frame.
            for (build in outgoing) {
                val bytes = Protocol.encode(build(nowUs()))
                output.writeShort(bytes.size)
                output.write(bytes)
                output.flush()
            }
            // The channel is closed only by close(), after any BYE has been queued. So reaching here
            // normally means the farewell has been written and the socket can go.
        } catch (e: Exception) {
            // Broken pipe, or cancellation. Either way the link is finished.
        } finally {
            shutdown()
        }
    }

    override fun send(frame: Protocol.Frame) {
        outgoing.trySend { frame }
    }

    override fun sendStamped(build: (nowUs: Long) -> Protocol.Frame) {
        outgoing.trySend(build)
    }

    override fun close(reason: Byte?) {
        if (closed.getAndSet(true)) return
        if (reason != null) {
            // Queue the farewell, then close the channel. The writer drains what is already buffered
            // before its loop ends, so the BYE reaches the wire and only then does the socket close.
            outgoing.trySend { Protocol.Frame.Bye(reason) }
            outgoing.close()
        } else {
            shutdown()
        }
    }

    /**
     * Tears everything down, from whichever loop noticed first.
     *
     * Closing the socket is what unblocks the other loop's blocking call, so this is both the
     * cleanup and the wake-up. Idempotent, because both loops will reach it.
     */
    private fun shutdown() {
        closed.set(true)
        outgoing.close()
        received.close()
        runCatching { socket.close() }
    }
}
