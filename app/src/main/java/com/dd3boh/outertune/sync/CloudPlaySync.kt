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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Cloud play sync - the core both apps share, like PlaySyncCore (the desktop's copy differs only in
 * its package; keep the two in step).
 *
 * The cloud is a Firebase Realtime Database the project owns, used as a plain key-value store over
 * its REST API. Nobody using the app signs in to anything: each user's data lives under a path made
 * from their sync code - 64 hex characters nobody can guess - and every value there is encrypted
 * with a key also made from the code, which never leaves their devices. The database's rules allow
 * reads and writes only at paths that long, and nothing can list what is stored, so without the code
 * the data can neither be found nor read.
 *
 *   /sync/<bucket>/plays/<id>  = sealed(songId, at, playTimeMs)   one per play
 *   /sync/<bucket>/songs/<id>  = sealed(song)                     one per song played
 *   /sync/<bucket>/rev         = random token, changed on every upload
 *
 * A play's id is a keyed hash of its song and exact instant, so the same play always lands on the
 * same id: uploading it twice, from any device, overwrites it with itself and never adds a copy.
 */

/** Where the cloud is. Empty until the project's database exists - then cloud sync is offered. */
object CloudSyncConfig {
    const val DATABASE_URL = "https://outertune-sync-database-default-rtdb.asia-southeast1.firebasedatabase.app"

    val configured: Boolean get() = DATABASE_URL.isNotBlank()
}

/** What cloud sync remembers between runs, so an unchanged cloud and library cost one tiny request. */
interface CloudSyncMemory {
    var lastRev: String?
    var lastFingerprint: String?
}

data class CloudOutcome(val received: Int, val sent: Int)

/** One sync against the cloud. Blocking - run it on an IO thread. */
class CloudPlaySync(
    private val store: PlaySyncStore,
    code: String,
    private val memory: CloudSyncMemory,
    private val baseUrl: String = CloudSyncConfig.DATABASE_URL,
) {
    private val bucket = hex(SyncCode.sha256("outertune-cloud-bucket-v1:$code"))
    private val sealKey = SecretKeySpec(SyncCode.sha256("outertune-cloud-seal-v1:$code"), "AES")
    private val idKey = SyncCode.sha256("outertune-cloud-id-v1:$code")
    private val random = SecureRandom()

    fun sync(): CloudOutcome {
        val local = store.plays()
        val fingerprint = playsFingerprint(local).let { "${it.first}:${it.second}" }
        val remoteRev = get("rev")?.trim('"')?.takeIf { it != "null" }
        if (remoteRev == memory.lastRev && fingerprint == memory.lastFingerprint) return CloudOutcome(0, 0)

        // What the cloud holds, by id.
        val remote = HashMap<String, SyncPlay>()
        entries(get("plays")).forEach { (id, sealed) -> open(sealed)?.let(::readPlay)?.let { remote[id] = it } }

        // Up: every play here the cloud lacks, with its song.
        val mine = local.associateBy(::playId)
        val missingThere = mine.filterKeys { it !in remote }.values.toList()
        var rev = remoteRev
        if (missingThere.isNotEmpty()) {
            store.songs(missingThere.mapTo(HashSet()) { it.songId }).chunked(BATCH).forEach { songs ->
                patch("songs", songs.associate { songId(it.id) to seal(writeSong(it)) })
            }
            missingThere.chunked(BATCH).forEach { plays ->
                patch("plays", plays.associate { playId(it) to seal(writePlay(it)) })
            }
            rev = token()
            put("rev", "\"$rev\"")
        }

        // Down: every play there this device lacks, with the songs it does not know yet.
        val missingHere = remote.filterKeys { it !in mine }.values.toList()
        var received = 0
        if (missingHere.isNotEmpty()) {
            val wanted = missingHere.mapTo(HashSet()) { songId(it.songId) }
            val songs = entries(get("songs")).mapNotNull { (id, sealed) ->
                if (id in wanted) open(sealed)?.let(::readSong) else null
            }
            received = store.merge(songs, missingHere)
        }

        memory.lastRev = rev
        memory.lastFingerprint = playsFingerprint(store.plays()).let { "${it.first}:${it.second}" }
        return CloudOutcome(received = received, sent = missingThere.size)
    }

    /** Removes everything this code has in the cloud. This device's own plays are not touched. */
    fun deleteAll() {
        request("DELETE", "", null)
        memory.lastRev = null
        memory.lastFingerprint = null
    }

    // ---- ids and sealing ----

    private fun playId(play: SyncPlay) = keyedId("play|${play.songId}|${play.at}")
    private fun songId(id: String) = keyedId("song|$id")

    /** 20 hex characters of an HMAC - unique for a user's plays, and saying nothing about them. */
    private fun keyedId(text: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(idKey, "HmacSHA256"))
        hex(doFinal(text.toByteArray(Charsets.UTF_8))).take(20)
    }

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

    // ---- payloads ----

    private fun writePlay(play: SyncPlay): ByteArray = bytes {
        writeUTF(play.songId)
        writeLong(play.at)
        writeLong(play.playTimeMs)
    }

    private fun readPlay(bytes: ByteArray): SyncPlay? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).run { SyncPlay(readUTF(), readLong(), readLong()) }
    }.getOrNull()

    private fun writeSong(song: SyncSong): ByteArray = bytes {
        writeUTF(song.id)
        writeUTF(song.title.take(2_000))
        writeUTF(song.thumbnail.take(2_000))
        writeInt(song.durationSec)
        writeInt(song.artists.size)
        song.artists.forEach { writeUTF(it.id.orEmpty()); writeUTF(it.name.take(2_000)) }
    }

    private fun readSong(bytes: ByteArray): SyncSong? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).run {
            SyncSong(
                id = readUTF(),
                title = readUTF(),
                thumbnail = readUTF(),
                durationSec = readInt(),
                artists = List(readInt().coerceIn(0, 64)) { SyncArtist(readUTF().ifEmpty { null }, readUTF()) },
            )
        }
    }.getOrNull()

    private inline fun bytes(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply(block).flush() }.toByteArray()

    // ---- the REST API ----

    private fun get(path: String): String? = request("GET", path, null)
    private fun put(path: String, json: String) { request("PUT", path, json) }

    /** Adds or replaces children of [path], leaving the others alone. */
    private fun patch(path: String, values: Map<String, String>) {
        if (values.isEmpty()) return
        // Ids are hex and values base64, so neither needs escaping.
        request("PATCH", path, values.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" })
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

    /** The children of a flat JSON object of "id": "value" strings - all this store ever holds. */
    private fun entries(json: String?): List<Pair<String, String>> =
        if (json == null || json == "null") emptyList()
        else ENTRY.findAll(json).map { it.groupValues[1] to it.groupValues[2] }.toList()

    private companion object {
        val ENTRY = Regex(""""([0-9a-f]{20})"\s*:\s*"([A-Za-z0-9+/=]+)"""")
        const val BATCH = 1_000

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
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
 * Keeps cloud sync running: a sync when started, then every [ROUND_MS], or at once on [syncNow].
 * Each sync that finds nothing changed costs one tiny request (see CloudPlaySync.sync).
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
            _status.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        } finally {
            _status.update { it.copy(syncing = false) }
        }
    }

    companion object {
        /** Between syncs while running. */
        private const val ROUND_MS = 30 * 60_000L
    }
}

/** The running cloud sync, for the settings screen. */
object CloudSync {
    @Volatile
    var runner: CloudSyncRunner? = null

    val idle: StateFlow<CloudSyncRunner.Status> = MutableStateFlow(CloudSyncRunner.Status())
}
