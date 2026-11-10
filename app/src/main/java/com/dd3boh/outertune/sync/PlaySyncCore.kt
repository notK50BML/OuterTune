/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Play sync between this user's own devices - the core both apps share. Written without Android so
 * the phone and OuterTune for Windows run the same file (the desktop's copy differs only in its
 * package); keep the two in step, as with Listen together's Protocol.
 */

/** A song, as much as another device needs to show it and count its listening time. */
data class SyncSong(
    val id: String,
    val title: String,
    val thumbnail: String,
    /** Seconds; 0 or less when unknown. */
    val durationSec: Int,
    val artists: List<SyncArtist>,
)

/** A credit: [id] is the artist's YouTube channel, or null for a credit with none. */
data class SyncArtist(val id: String?, val name: String)

/**
 * One play: its song and the exact instant it was recorded, as the device that recorded it stored
 * it. That pair is the play's identity everywhere - it is never re-dated, so the same play arriving
 * twice, by any route, is recognised and added once.
 */
data class SyncPlay(val songId: String, val at: Long, val playTimeMs: Long)

/** What an app's library gives the sync and takes from it. Called off the main thread. */
interface PlaySyncStore {
    /** Every play worth syncing - not those of local files, whose ids mean nothing elsewhere. */
    fun plays(): List<SyncPlay>

    /** The songs with these ids, with their credits. */
    fun songs(ids: Collection<String>): List<SyncSong>

    /** Adds the plays not already here (and the songs for them it lacks); returns how many were new. */
    fun merge(songs: List<SyncSong>, plays: List<SyncPlay>): Int
}

/**
 * The sync code: twelve characters, e.g. K7QD-M2XP-9RFA, made on one device and typed on the
 * others. It is the whole of the pairing - devices with the same code sync, others are refused - so
 * it is random (60 bits) rather than chosen, and from an alphabet without 0/O or 1/I to misread.
 */
object SyncCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 12

    fun generate(): String {
        val random = SecureRandom()
        return format(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))
    }

    /** [input] as a code, however it was typed (case, spaces, dashes), or null if it is not one. */
    fun normalize(input: String): String? {
        val chars = input.uppercase().filter { it.isLetterOrDigit() }
        if (chars.length != LENGTH || chars.any { it !in ALPHABET }) return null
        return format(chars)
    }

    private fun format(chars: String) = chars.chunked(4).joinToString("-")

    /** What has been typed so far, as a code is written: capitals, dashes put in every four. */
    fun asTyped(input: String): String = format(input.uppercase().filter { it.isLetterOrDigit() }.take(LENGTH))

    /** The secret both ends prove they hold. Never sent. */
    fun key(code: String): ByteArray = sha256("outertune-sync-key-v1:$code")

    /**
     * A short tag in the device's advertised name, so devices only connect to others with the same
     * code. Derived separately from [key], and too short to recover the code from.
     */
    fun tag(code: String): String = sha256("outertune-sync-tag-v1:$code").joinToString("") { "%02x".format(it) }.take(8)

    internal fun sha256(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}

/**
 * A fingerprint of a set of plays: how many, and the XOR of each play's 64-bit hash. Order does
 * not matter, so two devices holding the same plays agree without sending them - and a sync between
 * devices already in step costs two numbers.
 */
internal fun playsFingerprint(plays: List<SyncPlay>): Pair<Int, Long> {
    var hash = 0L
    plays.forEach { play ->
        // FNV-1a over "songId|at" - the play's identity, nothing else.
        var h = -0x340d631b7bdddcdbL
        "${play.songId}|${play.at}".forEach { c ->
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        hash = hash xor h
    }
    return plays.size to hash
}

/** The outcome of one exchange, for the status line. */
data class SyncOutcome(val peer: String, val received: Int, val sent: Int)

/**
 * One exchange over a connected socket. Blocking - run it on an IO thread.
 *
 * The connecting side opens with HELLO (its tag, a random nonce, its name); the other answers with
 * its own nonce and an HMAC over both nonces, and the first replies with its own HMAC. Each proves
 * it holds the code's key without sending it, so a device with a different code - or no code - gets
 * nothing. Then each sends its fingerprint; if they agree there is nothing to do. Otherwise each
 * sends every play it has, compressed, with the songs behind them, and adds what it lacks.
 *
 * Both sides read their own plays before taking in the other's, so nothing is sent back to where it
 * came from in the same exchange.
 */
class PlaySyncSession(
    private val store: PlaySyncStore,
    code: String,
    private val deviceName: String,
) {
    private val key = SyncCode.key(code)
    private val tag = SyncCode.tag(code)

    fun runAsClient(socket: Socket): SyncOutcome = socket.use {
        it.soTimeout = TIMEOUT_MS
        val input = DataInputStream(it.getInputStream().buffered())
        val output = DataOutputStream(it.getOutputStream().buffered())

        val myNonce = nonce()
        output.frame(HELLO) { writeUTF(tag); write(myNonce); writeUTF(deviceName) }
        val reply = input.readFrame(HELLO_REPLY)
        val theirNonce = ByteArray(NONCE).also(reply::readFully)
        val peer = reply.readUTF()
        val proof = ByteArray(MAC).also(reply::readFully)
        if (!MessageDigest.isEqual(proof, mac("server", myNonce, theirNonce))) throw IOException("peer does not hold this sync code")
        output.frame(AUTH) { write(mac("client", theirNonce, myNonce)) }

        exchange(input, output, peer, sendFirst = true)
    }

    fun runAsServer(socket: Socket): SyncOutcome = socket.use {
        it.soTimeout = TIMEOUT_MS
        val input = DataInputStream(it.getInputStream().buffered())
        val output = DataOutputStream(it.getOutputStream().buffered())

        val hello = input.readFrame(HELLO)
        if (hello.readUTF() != tag) throw IOException("different sync code")
        val theirNonce = ByteArray(NONCE).also(hello::readFully)
        val peer = hello.readUTF()
        val myNonce = nonce()
        output.frame(HELLO_REPLY) { write(myNonce); writeUTF(deviceName); write(mac("server", theirNonce, myNonce)) }
        val auth = input.readFrame(AUTH)
        val proof = ByteArray(MAC).also(auth::readFully)
        if (!MessageDigest.isEqual(proof, mac("client", myNonce, theirNonce))) throw IOException("peer does not hold this sync code")

        exchange(input, output, peer, sendFirst = false)
    }

    private fun exchange(input: DataInputStream, output: DataOutputStream, peer: String, sendFirst: Boolean): SyncOutcome {
        // Read before anything arrives - see the class doc.
        val mine = store.plays()
        val (count, hash) = playsFingerprint(mine)
        val theirs: Pair<Int, Long>
        if (sendFirst) {
            output.frame(SUMMARY) { writeInt(count); writeLong(hash) }
            theirs = input.readFrame(SUMMARY).let { it.readInt() to it.readLong() }
        } else {
            theirs = input.readFrame(SUMMARY).let { it.readInt() to it.readLong() }
            output.frame(SUMMARY) { writeInt(count); writeLong(hash) }
        }
        if (theirs == count to hash) return SyncOutcome(peer, received = 0, sent = 0)

        val songs = store.songs(mine.mapTo(HashSet()) { it.songId })
        val received: Pair<List<SyncSong>, List<SyncPlay>>
        if (sendFirst) {
            output.writeData(songs, mine)
            received = input.readData()
        } else {
            received = input.readData()
            output.writeData(songs, mine)
        }
        val added = store.merge(received.first, received.second)
        return SyncOutcome(peer, received = added, sent = mine.size)
    }

    private fun mac(role: String, first: ByteArray, second: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(role.toByteArray(Charsets.UTF_8))
            update(first)
            update(second)
            doFinal()
        }

    private fun nonce() = ByteArray(NONCE).also { SecureRandom().nextBytes(it) }

    private fun DataOutputStream.frame(type: Int, body: DataOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).apply(body).flush() }.toByteArray()
        writeByte(type)
        writeInt(bytes.size)
        write(bytes)
        flush()
    }

    private fun DataInputStream.readFrame(expected: Int): DataInputStream {
        val type = readUnsignedByte()
        val length = readInt()
        if (type != expected) throw IOException("expected frame $expected, got $type")
        if (length !in 0..MAX_FRAME) throw IOException("bad frame length $length")
        val bytes = ByteArray(length).also(::readFully)
        return DataInputStream(ByteArrayInputStream(bytes))
    }

    private fun DataOutputStream.writeData(songs: List<SyncSong>, plays: List<SyncPlay>) {
        val packed = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(packed)).use { out ->
            out.writeInt(songs.size)
            songs.forEach { song ->
                out.writeUTF(song.id)
                out.writeUTF(song.title.take(MAX_TEXT))
                out.writeUTF(song.thumbnail.take(MAX_TEXT))
                out.writeInt(song.durationSec)
                out.writeInt(song.artists.size)
                song.artists.forEach { artist ->
                    out.writeUTF(artist.id.orEmpty())
                    out.writeUTF(artist.name.take(MAX_TEXT))
                }
            }
            out.writeInt(plays.size)
            plays.forEach { play ->
                out.writeUTF(play.songId)
                out.writeLong(play.at)
                out.writeLong(play.playTimeMs)
            }
        }
        frame(DATA) { write(packed.toByteArray()) }
    }

    private fun DataInputStream.readData(): Pair<List<SyncSong>, List<SyncPlay>> {
        val frame = readFrame(DATA)
        DataInputStream(InflaterInputStream(frame)).use { input ->
            val songs = List(input.readInt().coerceIn(0, MAX_ITEMS)) {
                SyncSong(
                    id = input.readUTF(),
                    title = input.readUTF(),
                    thumbnail = input.readUTF(),
                    durationSec = input.readInt(),
                    artists = List(input.readInt().coerceIn(0, 64)) {
                        SyncArtist(input.readUTF().ifEmpty { null }, input.readUTF())
                    },
                )
            }
            val plays = List(input.readInt().coerceIn(0, MAX_ITEMS)) {
                SyncPlay(input.readUTF(), input.readLong(), input.readLong())
            }
            return songs to plays
        }
    }

    private companion object {
        const val HELLO = 1
        const val HELLO_REPLY = 2
        const val AUTH = 3
        const val SUMMARY = 4
        const val DATA = 5

        const val NONCE = 16
        const val MAC = 32
        const val TIMEOUT_MS = 30_000
        const val MAX_FRAME = 64 * 1024 * 1024
        const val MAX_ITEMS = 5_000_000
        const val MAX_TEXT = 2_000
    }
}
