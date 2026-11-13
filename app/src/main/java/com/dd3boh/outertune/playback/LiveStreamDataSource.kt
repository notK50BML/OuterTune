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
 *
 * It starts at the newest segment, or - when [LiveRewind] has been asked to rewind - that far back,
 * and tells [LiveRewind] where it started and the newest segment each answer reports, which is how
 * the screens know how far behind live playback is.
 */
@UnstableApi
class LiveStreamDataSource(
    private val upstream: DataSource,
    private val client: OkHttpClient,
) : DataSource {

    class Factory(private val upstream: DataSource.Factory, client: OkHttpClient) : DataSource.Factory {
        /**
         * The same client, more patient. A segment not made yet is held by YouTube until it is -
         * measured at up to 11 seconds - and the player reads ahead to the newest segment, so it
         * asks for unmade ones routinely. OkHttp's 10-second read timeout counted those as failures,
         * and enough of them in a row ended the stream: the radio skipped to the next song.
         */
        private val segmentClient = client.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

        override fun createDataSource(): DataSource = LiveStreamDataSource(upstream.createDataSource(), segmentClient)
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
            LiveRewind.notLive()
            return upstream.open(dataSpec)
        }
        live = true
        openedUri = dataSpec.uri
        val marked = dataSpec.uri.toString()
        base = unmark(marked)
        headers = dataSpec.httpRequestHeaders
        LiveRewind.opened(
            segmentMs = param(marked, SEGMENT) ?: 5_000L,
            rewindMs = param(marked, REWIND),
        )
        // Every open starts the clock again: the first segment read is the player's 0.
        origin = -1
        val (head, newest) = fetch(null) ?: throw IOException("The live stream did not answer")
        // The live point - a few segments behind the newest - or as far behind it as a rewind
        // asked; the newest after all if YouTube no longer has that one.
        val (start, intoMs) = LiveRewind.startingPoint(head)
        val (first, whole) = if (start < head) fetch(start) ?: (head to newest) else head to newest
        // Into the segment as far as the rewind asked, not only to its start: the bar then lands
        // where it was let go, rather than up to a segment earlier.
        val (bytes, skippedMs) = if (first == start && intoMs > 0) trimStart(whole, intoMs) else whole to 0L
        sequence = first
        LiveRewind.started(first, head, skippedMs)
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
            response.header("X-Head-Seqnum")?.toLongOrNull()?.let(LiveRewind::sawHead)
            val number = response.header("X-Sequence-Num")?.toLongOrNull()
                ?: sq ?: response.header("X-Head-Seqnum")?.toLongOrNull() ?: return null
            number to (response.body?.bytes() ?: return null)
        }
    } catch (e: IOException) {
        null
    }

    /**
     * [segment] with its first [skipMs] cut off, and how much was cut - so playback starts inside
     * it. Done on the fragment's sample table: the first samples' entries are dropped from the
     * `trun`, its data offset moved past their bytes (and back by the bytes the table lost), the
     * boxes holding it shortened, and the `tfdt` moved on by their duration so the next segment
     * still follows without a gap. The samples' bytes stay in `mdat`, never read.
     *
     * Only for the shape YouTube serves - one `traf`, one `trun` carrying sample sizes, durations
     * per sample or by default - anything else is returned whole, which only means starting at
     * the segment's beginning.
     */
    private fun trimStart(segment: ByteArray, skipMs: Long): Pair<ByteArray, Long> {
        val whole = segment to 0L
        val segmentMs = LiveRewind.segmentLengthMs
        // The top-level moof, and the one traf inside it.
        var moof = -1
        var at = 0
        while (at + 8 <= segment.size) {
            val size = boxSize(segment, at)
            if (size < 8 || at + size > segment.size) return whole
            if (String(segment, at + 4, 4, Charsets.ISO_8859_1) == "moof") { moof = at; break }
            at += size.toInt()
        }
        if (moof < 0) return whole
        val moofEnd = moof + boxSize(segment, moof).toInt()
        var traf = -1; var tfhd = -1; var tfdt = -1; var trun = -1
        at = moof + 8
        while (at + 8 <= moofEnd) {
            val size = boxSize(segment, at).toInt()
            if (size < 8) return whole
            if (String(segment, at + 4, 4, Charsets.ISO_8859_1) == "traf") {
                if (traf >= 0) return whole
                traf = at
                var inner = at + 8
                while (inner + 8 <= at + size) {
                    val innerSize = boxSize(segment, inner).toInt()
                    if (innerSize < 8) return whole
                    when (String(segment, inner + 4, 4, Charsets.ISO_8859_1)) {
                        "tfhd" -> tfhd = inner
                        "tfdt" -> tfdt = inner
                        "trun" -> if (trun >= 0) return whole else trun = inner
                    }
                    inner += innerSize
                }
            }
            at += size
        }
        if (traf < 0 || tfhd < 0 || tfdt < 0 || trun < 0 || tfdt > trun) return whole

        val trunFlags = readInt(segment, trun + 8) and 0xFFFFFFL
        if ((trunFlags and 0x200L) == 0L || (trunFlags and 0x001L) == 0L) return whole
        val count = readInt(segment, trun + 12).toInt()
        if (count < 2) return whole
        val hasDuration = (trunFlags and 0x100L) != 0L
        val entrySize = 4 * listOf(0x100L, 0x200L, 0x400L, 0x800L).count { (trunFlags and it) != 0L }
        val entries = trun + 20 + if ((trunFlags and 0x004L) != 0L) 4 else 0
        // Durations from the samples themselves, or the tfhd's default.
        val tfhdFlags = readInt(segment, tfhd + 8) and 0xFFFFFFL
        val defaultDuration = if ((tfhdFlags and 0x08L) != 0L) {
            var field = tfhd + 16
            if ((tfhdFlags and 0x01L) != 0L) field += 8
            if ((tfhdFlags and 0x02L) != 0L) field += 4
            readInt(segment, field)
        } else if (hasDuration) 0L else return whole

        val skip = (skipMs * count / segmentMs).toInt().coerceIn(0, count - 1)
        if (skip == 0) return whole
        var skippedBytes = 0L
        var skippedTicks = 0L
        val sizeAt = if (hasDuration) 4 else 0
        for (i in 0 until skip) {
            val entry = entries + i * entrySize
            skippedTicks += if (hasDuration) readInt(segment, entry) else defaultDuration
            skippedBytes += readInt(segment, entry + sizeAt)
        }

        // Patched in place first - every field here sits before the entries being removed.
        val out = segment.copyOf()
        val removed = (skip * entrySize).toLong()
        writeInt(out, moof, boxSize(out, moof) - removed)
        writeInt(out, traf, boxSize(out, traf) - removed)
        writeInt(out, trun, boxSize(out, trun) - removed)
        writeInt(out, trun + 12, (count - skip).toLong())
        writeInt(out, trun + 16, readInt(out, trun + 16) - removed + skippedBytes)
        val version = out[tfdt + 8].toInt()
        if (version == 1) writeLong(out, tfdt + 12, readLong(out, tfdt + 12) + skippedTicks)
        else writeInt(out, tfdt + 12, readInt(out, tfdt + 12) + skippedTicks)

        val cut = java.io.ByteArrayOutputStream(out.size)
        cut.write(out, 0, entries)
        cut.write(out, entries + removed.toInt(), out.size - entries - removed.toInt())
        return cut.toByteArray() to skip * segmentMs / count
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
        private const val SEGMENT = "otseg"
        private const val REWIND = "otdvr"
        // Ten tries 2 seconds apart, each allowed 30 seconds: a minute or more of a stalled network
        // before the stream is given up as ended, where five quick tries skipped a radio on a hiccup.
        private const val RETRIES = 10
        private const val RETRY_MS = 2_000L

        /**
         * [url] marked to be read as a live stream - see the class - with its segment length and
         * how far back YouTube keeps it, both from the stream's format, for [LiveRewind].
         */
        fun mark(url: String, segmentMs: Long, rewindMs: Long?): String =
            "$url&$MARKER=1&$SEGMENT=$segmentMs" + (rewindMs?.let { "&$REWIND=$it" } ?: "")

        fun isMarked(uri: Uri): Boolean = uri.toString().contains("&$MARKER=1")

        private val ownParams = Regex("&(?:$MARKER|$SEGMENT|$REWIND)=[0-9]*")

        private fun unmark(url: String): String = url.replace(ownParams, "")

        private fun param(url: String, name: String): Long? =
            Regex("&$name=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

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
