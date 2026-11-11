/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Cloud relay - plays passed between the user's devices through the cloud, which keeps nothing once
 * every device has them. The core both apps share, like PlaySyncCore (the desktop's copy differs
 * only in its package; keep the two in step).
 *
 * The cloud is a Firebase Realtime Database the project owns, used over its REST API. Nobody signs
 * in: each user's data lives under a path made from their sync code (64 hex characters nobody can
 * guess), and every bundle is encrypted with a key also made from the code, which never leaves
 * their devices. The database allows reads and writes only at paths that long.
 *
 *   /sync/<bucket>/devices/<device>        {seen: ms, full: true?, fullFrom: {<device>: true}}
 *   /sync/<bucket>/meta/<bundle>           {f: from device, t: ms, to: device or ""}
 *   /sync/<bucket>/data/<bundle>           sealed, compressed songs + plays
 *   /sync/<bucket>/got/<bundle>/<device>   true once that device has taken the bundle
 *
 * Each sync a device sends one bundle with the plays it has added since it last sent, takes every
 * bundle it has not seen, and marks them taken. A bundle every active device has taken is deleted,
 * and so is any bundle after [EXPIRY_MS]. A device that is new - or asks to start again - is marked
 * "full", and each of the others sends it everything it has, once. Plays keep their identity (song
 * and exact time) through all of it, so whatever arrives twice is still added once.
 */

/** Where the cloud is. */
object CloudSyncConfig {
    const val DATABASE_URL = "https://outertune-sync-database-default-rtdb.asia-southeast1.firebasedatabase.app"

    val configured: Boolean get() = DATABASE_URL.isNotBlank()
}

/** What the relay remembers on this device between syncs. */
interface CloudSyncMemory {
    /** This device's id in the relay, made on its first sync. */
    var deviceId: String?

    /** The newest local play row already sent. */
    var sentUpTo: Long

    /** Bundles already taken, so they are not taken again while they wait for other devices. */
    var taken: Set<String>
}

data class CloudOutcome(val received: Int, val sent: Int)

/** One sync with the relay. Blocking - run it on an IO thread. */
class CloudPlaySync(
    private val store: PlaySyncStore,
    code: String,
    private val memory: CloudSyncMemory,
    private val baseUrl: String = CloudSyncConfig.DATABASE_URL,
) {
    private val bucket = hex(SyncCode.sha256("outertune-cloud-bucket-v1:$code"))
    private val sealKey = SecretKeySpec(SyncCode.sha256("outertune-cloud-seal-v1:$code"), "AES")
    private val random = SecureRandom()

    fun sync(): CloudOutcome {
        val now = System.currentTimeMillis()
        val firstRun = memory.deviceId == null
        val me = memory.deviceId ?: token().also {
            memory.deviceId = it
            memory.sentUpTo = 0L
            memory.taken = emptySet()
        }
        val updates = LinkedHashMap<String, String>()
        if (firstRun) {
            // The first cloud sync's store, which kept every play: replaced by the relay.
            listOf("plays", "songs", "rev").forEach { updates[it] = "null" }
            // And this device starts with everything the others have.
            updates["devices/$me/full"] = "true"
        }
        updates["devices/$me/seen"] = "$now"
        patch(updates)
        updates.clear()

        val devices = (Json.parse(get("devices")) as? Map<*, *>).orEmpty()
        fun seen(id: String) = ((devices[id] as? Map<*, *>)?.get("seen") as? Number)?.toLong() ?: 0L
        val active = devices.keys.map { it.toString() }.filter { now - seen(it) < EXPIRY_MS }.toSet()
        val meta = (Json.parse(get("meta")) as? Map<*, *>).orEmpty()
            .mapNotNull { (id, value) -> (value as? Map<*, *>)?.let { id.toString() to Meta(it["f"].toString(), (it["t"] as? Number)?.toLong() ?: 0L, it["to"]?.toString().orEmpty()) } }
            .toMap()
        val got = (Json.parse(get("got")) as? Map<*, *>).orEmpty()
            .mapValues { (_, v) -> (v as? Map<*, *>)?.keys?.map { it.toString() }?.toSet().orEmpty() }
            .mapKeys { it.key.toString() }

        // In: every bundle meant for this device that it has not taken yet, oldest first.
        val taken = memory.taken.filter { it in meta }.toMutableSet()
        val receivedIds = HashSet<String>()
        var received = 0
        meta.toSortedMap().forEach { (id, m) ->
            if (m.from == me || id in taken || (m.to.isNotEmpty() && m.to != me)) return@forEach
            val sealed = Json.parse(get("data/$id")) as? String
            val bundle = sealed?.let(::open)?.let(::readBundle)
            if (bundle != null) {
                received += store.merge(bundle.first, bundle.second)
                bundle.second.forEach { receivedIds += key(it) }
            }
            taken += id
        }
        val takenNow = taken - memory.taken

        // Out: the plays added here since the last send - not the ones that just arrived, which would
        // only travel straight back.
        val (fresh, newest) = store.playsSince(memory.sentUpTo)
        val outgoing = fresh.filter { key(it) !in receivedIds }
        if (outgoing.isNotEmpty()) addBundle(updates, me, "", outgoing, now)

        // Everything, once, to each device that asked for it and has not had it from this one.
        devices.forEach { (idAny, value) ->
            val id = idAny.toString()
            val info = value as? Map<*, *> ?: return@forEach
            if (id == me || id !in active || info["full"] != true) return@forEach
            if ((info["fullFrom"] as? Map<*, *>)?.containsKey(me) == true) return@forEach
            val all = store.plays()
            if (all.isNotEmpty()) addBundle(updates, me, id, all, now)
            updates["devices/$id/fullFrom/$me"] = "true"
        }

        // This device's own request for everything is met once every other active device answered.
        val mine = devices[me] as? Map<*, *>
        if (mine?.get("full") == true) {
            val answered = (mine["fullFrom"] as? Map<*, *>)?.keys?.map { it.toString() }?.toSet().orEmpty()
            if ((active - me).all { it in answered }) {
                updates["devices/$me/full"] = "null"
                updates["devices/$me/fullFrom"] = "null"
            }
        }

        // Clean-up: a bundle every device it was for has taken goes, as does any past its expiry.
        val gone = HashSet<String>()
        meta.forEach { (id, m) ->
            val holders = got[id].orEmpty() + (if (id in takenNow) setOf(me) else emptySet())
            val delivered = if (m.to.isNotEmpty()) m.to in holders else (active - m.from).all { it in holders }
            if (delivered || now - m.time > EXPIRY_MS) gone += id
        }
        gone.forEach { id -> listOf("data", "meta", "got").forEach { updates["$it/$id"] = "null" } }
        takenNow.filter { it !in gone }.forEach { updates["got/$it/$me"] = "true" }
        // Devices not seen for twice the expiry are forgotten.
        devices.keys.map { it.toString() }.filter { now - seen(it) > 2 * EXPIRY_MS }.forEach { updates["devices/$it"] = "null" }

        patch(updates)
        memory.sentUpTo = newest
        memory.taken = taken - gone
        return CloudOutcome(received = received, sent = outgoing.size)
    }

    /**
     * Marks this device as wanting everything again, and sends everything it has - for a device that
     * lost its plays, or when the user wants a clean resend.
     */
    fun resendAll() {
        memory.sentUpTo = 0L
        val me = memory.deviceId ?: return
        patch(linkedMapOf("devices/$me/full" to "true", "devices/$me/fullFrom" to "null"))
    }

    /** Removes everything this code has in the cloud. This device's own plays are not touched. */
    fun deleteAll() {
        request("DELETE", "", null)
        memory.deviceId = null
        memory.sentUpTo = 0L
        memory.taken = emptySet()
    }

    private class Meta(val from: String, val time: Long, val to: String)

    private fun addBundle(updates: MutableMap<String, String>, me: String, to: String, plays: List<SyncPlay>, now: Long) {
        val id = "%011x".format(now) + "-" + token().take(6)
        val songs = store.songs(plays.mapTo(HashSet()) { it.songId })
        updates["data/$id"] = "\"${seal(writeBundle(songs, plays))}\""
        updates["meta/$id"] = "{\"f\":\"$me\",\"t\":$now,\"to\":\"$to\"}"
        updates["got/$id/$me"] = "true"
    }

    private fun key(play: SyncPlay) = "${play.songId}|${play.at}"

    // ---- sealing ----

    private fun seal(plain: ByteArray): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sealKey, GCMParameterSpec(128, nonce))
        return Base64Lite.encode(nonce + cipher.doFinal(plain))
    }

    /** The plain bytes, or null for anything not sealed with this code's key. */
    private fun open(sealed: String): ByteArray? = runCatching {
        val bytes = Base64Lite.decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sealKey, GCMParameterSpec(128, bytes, 0, 12))
        cipher.doFinal(bytes, 12, bytes.size - 12)
    }.getOrNull()

    private fun token(): String = hex(ByteArray(8).also(random::nextBytes))

    // ---- bundles ----

    private fun writeBundle(songs: List<SyncSong>, plays: List<SyncPlay>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(out)).use { data ->
            data.writeInt(songs.size)
            songs.forEach { song ->
                data.writeUTF(song.id)
                data.writeUTF(song.title.take(2_000))
                data.writeUTF(song.thumbnail.take(2_000))
                data.writeInt(song.durationSec)
                data.writeInt(song.artists.size)
                song.artists.forEach { data.writeUTF(it.id.orEmpty()); data.writeUTF(it.name.take(2_000)) }
            }
            data.writeInt(plays.size)
            plays.forEach { data.writeUTF(it.songId); data.writeLong(it.at); data.writeLong(it.playTimeMs) }
        }
        return out.toByteArray()
    }

    private fun readBundle(bytes: ByteArray): Pair<List<SyncSong>, List<SyncPlay>>? = runCatching {
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { data ->
            val songs = List(data.readInt().coerceIn(0, 5_000_000)) {
                SyncSong(
                    id = data.readUTF(),
                    title = data.readUTF(),
                    thumbnail = data.readUTF(),
                    durationSec = data.readInt(),
                    artists = List(data.readInt().coerceIn(0, 64)) { SyncArtist(data.readUTF().ifEmpty { null }, data.readUTF()) },
                )
            }
            val plays = List(data.readInt().coerceIn(0, 5_000_000)) { SyncPlay(data.readUTF(), data.readLong(), data.readLong()) }
            songs to plays
        }
    }.getOrNull()

    // ---- the REST API ----

    private fun get(path: String): String? = request("GET", path, null)

    /** Several paths under this code's node at once, as raw JSON values ("null" deletes). */
    private fun patch(values: Map<String, String>) {
        if (values.isEmpty()) return
        request("PATCH", "", values.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" })
    }

    private fun request(method: String, path: String, body: String?): String? {
        val node = if (path.isEmpty()) "sync/$bucket" else "sync/$bucket/$path"
        // HttpURLConnection has no PATCH; Firebase takes it as a POST with an override.
        val override = if (method == "PATCH") "?x-http-method-override=PATCH" else ""
        val connection = URL("${baseUrl.trimEnd('/')}/$node.json$override").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (method == "PATCH") "POST" else method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("the cloud answered $status")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** How long a bundle waits for a device that never comes back, and how long a device counts as active. */
        const val EXPIRY_MS = 30L * 24 * 60 * 60 * 1000

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Just enough JSON to read what the relay stores - objects, strings, numbers, true/false/null.
 * The two apps share no JSON library, and this is all the relay needs.
 */
internal object Json {
    fun parse(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        return runCatching { Reader(text).run { value().also { skip() } } }.getOrNull()
    }

    private class Reader(val s: String) {
        var i = 0

        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skip()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip()
                val k = str()
                skip()
                i++ // ':'
                out[k] = value()
                skip()
                if (s[i++] == '}') return out
            }
        }

        fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skip()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skip()
                if (s[i++] == ']') return out
            }
        }

        fun str(): String {
            val out = StringBuilder()
            i++ // opening quote
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { out.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> out.append(e)
                }
            }
            i++
            return out.toString()
        }

        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }
    }
}

/** Standard Base64, since the phone's oldest supported Android has none in the JDK. */
internal object Base64Lite {
    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String = buildString((data.size + 2) / 3 * 4) {
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            append(CHARS[b0 shr 2])
            append(CHARS[((b0 and 3) shl 4) or (b1 shr 4)])
            append(if (i + 1 < data.size) CHARS[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            append(if (i + 2 < data.size) CHARS[b2 and 63] else '=')
            i += 3
        }
    }

    fun decode(text: String): ByteArray {
        val clean = text.trimEnd('=')
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        clean.forEach { c ->
            val v = CHARS.indexOf(c)
            require(v >= 0) { "not base64" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

/**
 * Keeps the relay running: a sync when started, then every [ROUND_MS], or at once on [syncNow].
 * A sync with nothing new costs a heartbeat and three small reads.
 */
class CloudSyncRunner(
    private val store: PlaySyncStore,
    private val memory: CloudSyncMemory,
) {
    data class Status(
        val running: Boolean = false,
        val syncing: Boolean = false,
        val last: CloudOutcome? = null,
        val lastAt: Long? = null,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var code: String? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(code: String) {
        if (!CloudSyncConfig.configured) return
        if (this.code == code && job?.isActive == true) return
        stop()
        this.code = code
        job = scope.launch {
            _status.update { it.copy(running = true, error = null) }
            while (isActive) {
                runOnce(code)
                withTimeoutOrNull(ROUND_MS) { wake.receive() }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        code = null
        _status.update { it.copy(running = false, syncing = false) }
    }

    fun syncNow() {
        wake.trySend(Unit)
    }

    /** Sends everything again and asks the other devices for everything - see CloudPlaySync.resendAll. */
    fun resendAll(code: String) {
        scope.launch {
            runCatching { CloudPlaySync(store, code, memory).resendAll() }
                .onFailure { e -> _status.update { it.copy(error = describe(e)) } }
            syncNow()
        }
    }

    /** Deletes this code's cloud data, then stops. */
    fun deleteCloudData(code: String, onDone: (Boolean) -> Unit) {
        stop()
        scope.launch {
            val ok = runCatching { CloudPlaySync(store, code, memory).deleteAll() }.isSuccess
            onDone(ok)
        }
    }

    private fun runOnce(code: String) {
        _status.update { it.copy(syncing = true) }
        try {
            val outcome = CloudPlaySync(store, code, memory).sync()
            _status.update { it.copy(last = outcome, lastAt = System.currentTimeMillis(), error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.update { it.copy(error = describe(e)) }
        } finally {
            _status.update { it.copy(syncing = false) }
        }
    }

    companion object {
        /** Between syncs while running. */
        private const val ROUND_MS = 30 * 60_000L

        /**
         * What went wrong, in words. The exceptions' own messages are often just the server's
         * address - a failed lookup reports the host name and nothing else - which says nothing.
         */
        fun describe(e: Throwable): String = when (e) {
            is java.net.UnknownHostException ->
                "can't find the sync server - no internet, or a DNS or ad blocker is blocking firebasedatabase.app"
            is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                "couldn't reach the sync server - check the connection"
            is javax.net.ssl.SSLException -> "secure connection to the sync server failed (${e.message})"
            else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trimEnd(':', ' ')
        }
    }
}

/** The running relay, for the settings screen. */
object CloudSync {
    @Volatile
    var runner: CloudSyncRunner? = null

    val idle: StateFlow<CloudSyncRunner.Status> = MutableStateFlow(CloudSyncRunner.Status())
}
