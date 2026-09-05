/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.listentogether

import kotlinx.coroutines.flow.Flow

/**
 * A frame together with the moment the reader saw it.
 *
 * The timestamp is taken by the read loop the instant the bytes are complete, before the frame is
 * even decoded. That matters for exactly one frame - a PONG, whose t4 is the follower's receive time
 * - but it has to be done for every frame because the reader does not know what it has until it has
 * decoded it, and decoding costs time that would otherwise be charged to the network.
 */
data class ReceivedFrame(val frame: Protocol.Frame, val atUs: Long)

/**
 * One open connection to one peer, in whichever direction it was established.
 *
 * Deliberately ignorant of who is host and who is follower. Both ends read frames and write frames;
 * the difference between them is entirely in which frames they choose to send, and that belongs a
 * layer up. Keeping it out of here means the socket code is written and tested once.
 *
 * [incoming] completes when the link dies, for any reason - a clean BYE, a dropped WiFi connection,
 * a read timeout. A collector that simply falls out of its loop has correctly handled every
 * disconnection case there is, which is the reason for expressing it as a stream rather than as a
 * callback plus a separate error path.
 */
interface PeerLink {

    /** For display - "who am I following" - and for keying peers in a list. */
    val remoteAddress: String

    /** Frames as they arrive. Completes on disconnection. */
    val incoming: Flow<ReceivedFrame>

    val isOpen: Boolean

    /**
     * Queues a frame. Never blocks the caller, which is usually the player's thread.
     *
     * A frame may be dropped if the link is congested. That is safe by construction: every frame
     * carries whole state rather than a change to it, so the next one repairs whatever the lost one
     * would have said.
     */
    fun send(frame: Protocol.Frame)

    /**
     * Queues a frame whose contents depend on the moment it is actually written.
     *
     * This exists for clock sync and nothing else. A PING's t1 and a PONG's t3 are meant to bracket
     * the network delay, so any gap between building the frame and putting it on the wire - time
     * spent queued behind another frame, most obviously - would be counted as network delay and bias
     * the offset estimate. Stamping inside the writer removes that gap from the measurement.
     *
     * Note that a TICK must *not* use this: its clock reading is a genuine historical fact about
     * when the player moved, not a description of when the frame was sent.
     */
    fun sendStamped(build: (nowUs: Long) -> Protocol.Frame)

    /**
     * Closes the link, optionally saying why first.
     *
     * A [reason] sends a BYE and lets it flush before the socket goes; null closes immediately. The
     * distinction is worth having because a peer that is told why it was disconnected can say so,
     * and one that just sees a dead socket can only guess.
     */
    fun close(reason: Byte? = Protocol.ByeReason.NORMAL)
}
