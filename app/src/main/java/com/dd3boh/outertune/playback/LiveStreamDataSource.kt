/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

/**
 * A YouTube live stream - a 24/7 radio station - read as one endless fragmented MP4.
 *
 * Handed to the player as an ordinary file, a live stream's URL answers with its newest few seconds
 * and nothing more: playback crept along a fraction of real time and the audio restarted several
 * times a second. YouTube serves a live stream as numbered segments (`&sq=N`), each a small
 * self-contained fragmented MP4. This reads them in order - the newest first, the next fetched while
 * one plays - and hands the player a single stream: the first segment's header once, then every
 * segment's fragments. Their timestamps count from when the broadcast began, often weeks back, so
 * each is moved to count from the moment the stream was joined; left alone the player waited for a
 * start time millions of seconds away.
 *
 * Only a URL marked by [mark] is read this way; anything else passes straight to [upstream]. The
 * desktop's LiveSource reads live streams the same way.
 */
@UnstableApi
class LiveStreamDataSource(
    private val upstream: DataSource,
    private val client: OkHttpClient,
) : DataSource {

    class Factory(private val upstream: DataSource.Factory, private val client: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveStreamDataSource(upstream.createDataSource(), client)
    }

    private var live = false
    private var openedUri: Uri? = null
    private var base = ""
    private var headers: Map<String, String> = emptyMap()
    private var buffer = ByteArray(0)
    private var position = 0
    private var sequence = -1L
    /** The first segment's decode time, which every later one is counted from. */
    private var origin = -1L
    private val fetcher = Executors.newSingleThreadExecutor { task -> Thread(task, "LiveSegments").apply { isDaemon = true } }
    private var ahead: Future<ByteArray?>? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!isMarked(dataSpec.uri)) {
            live = false
            return upstream.open(dataSpec)
        }
        live = true
        openedUri = dataSpec.uri
        base = unmark(dataSpec.uri.toString())
        headers = dataSpec.httpRequestHeaders
        val (head, bytes) = fetch(null) ?: throw IOException("The live stream did not answer")
        sequence = head
        buffer = reshape(bytes, first = true)
        position = 0
        fetchAhead()
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (!live) return upstream.read(target, offset, length)
        if (length == 0) return 0
        while (position >= buffer.size) {
            if (!advance()) return C.RESULT_END_OF_INPUT
        }
        val count = min(length, buffer.size - position)
        System.arraycopy(buffer, position, target, offset, count)
        position += count
        return count
    }

    /** On to the next segment: the one fetched ahead, or asked for again a few times. */
    private fun advance(): Boolean {
        val next = runCatching { ahead?.get() }.getOrNull()
            ?: run {
                var got: ByteArray? = null
                for (attempt in 0 until RETRIES) {
                    if (Thread.interrupted()) throw InterruptedIOException()
                    Thread.sleep(RETRY_MS)
                    got = fetch(sequence + 1)?.second
                    if (got != null) break
                }
                got
            }
            ?: return false
        sequence++
        buffer = reshape(next, first = false)
        position = 0
        fetchAhead()
        return true
    }

    private fun fetchAhead() {
        val wanted = sequence + 1
        ahead = fetcher.submit<ByteArray?> { fetch(wanted)?.second }
    }

    /** Segment [sq], or the newest when null, with its number. Null when there is none to have yet. */
    private fun fetch(sq: Long?): Pair<Long, ByteArray>? = try {
        val request = Request.Builder()
            .url(if (sq == null) base else "$base&sq=$sq")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val number = response.header("X-Sequence-Num")?.toLongOrNull()
                ?: sq ?: response.header("X-Head-Seqnum")?.toLongOrNull() ?: return null
            number to (response.body?.bytes() ?: return null)
        }
    } catch (e: IOException) {
        null
    }

    /**
     * The segment as the stream needs it: the header (ftyp, moov) from the first only, the fragments
     * (moof, mdat) from every one, YouTube's event messages (emsg) from none - and each fragment's
     * decode time moved to count from the first segment's.
     */
    private fun reshape(segment: ByteArray, first: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream(segment.size)
        var at = 0
        while (at + 8 <= segment.size) {
            val size = boxSize(segment, at)
            if (size < 8 || at + size > segment.size) break
            when (String(segment, at + 4, 4, Charsets.ISO_8859_1)) {
                "moof" -> {
                    val box = segment.copyOfRange(at, at + size.toInt())
                    rebaseDecodeTimes(box)
                    out.write(box)
                }
                "mdat" -> out.write(segment, at, size.toInt())
                "ftyp", "moov" -> if (first) out.write(segment, at, size.toInt())
            }
            at += size.toInt()
        }
        return out.toByteArray()
    }

    /** Every tfdt in this moof, rewritten in place relative to [origin] (set by the first seen). */
    private fun rebaseDecodeTimes(moof: ByteArray) {
        var at = 8
        while (at + 8 <= moof.size) {
            val size = boxSize(moof, at)
            if (size < 8 || at + size > moof.size) return
            val type = String(moof, at + 4, 4, Charsets.ISO_8859_1)
            if (type == "traf") {
                var inner = at + 8
                val end = at + size.toInt()
                while (inner + 8 <= end) {
                    val innerSize = boxSize(moof, inner)
                    if (innerSize < 8 || inner + innerSize > end) break
                    if (String(moof, inner + 4, 4, Charsets.ISO_8859_1) == "tfdt") {
                        val version = moof[inner + 8].toInt()
                        val field = inner + 12
                        val time = if (version == 1) readLong(moof, field) else readInt(moof, field)
                        if (origin < 0) origin = time
                        val rebased = (time - origin).coerceAtLeast(0)
                        if (version == 1) writeLong(moof, field, rebased) else writeInt(moof, field, rebased)
                    }
                    inner += innerSize.toInt()
                }
            }
            at += size.toInt()
        }
    }

    override fun getUri(): Uri? = if (live) openedUri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (live) emptyMap() else upstream.responseHeaders

    override fun close() {
        if (live) {
            ahead?.cancel(true)
            ahead = null
            buffer = ByteArray(0)
            live = false
        } else {
            upstream.close()
        }
    }

    companion object {
        private const val MARKER = "otlive"
        private const val RETRIES = 5
        private const val RETRY_MS = 1_000L

        /** [url] marked to be read as a live stream - see the class. */
        fun mark(url: String): String = "$url&$MARKER=1"

        fun isMarked(uri: Uri): Boolean = uri.toString().contains("&$MARKER=1")

        private fun unmark(url: String): String = url.replace("&$MARKER=1", "")

        private fun boxSize(bytes: ByteArray, at: Int): Long = readInt(bytes, at)

        private fun readInt(bytes: ByteArray, at: Int): Long =
            ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)

        private fun readLong(bytes: ByteArray, at: Int): Long = (readInt(bytes, at) shl 32) or readInt(bytes, at + 4)

        private fun writeInt(bytes: ByteArray, at: Int, value: Long) {
            for (i in 0 until 4) bytes[at + i] = (value ushr (24 - 8 * i)).toByte()
        }

        private fun writeLong(bytes: ByteArray, at: Int, value: Long) {
            writeInt(bytes, at, value ushr 32)
            writeInt(bytes, at + 4, value and 0xFFFFFFFFL)
        }
    }
}
