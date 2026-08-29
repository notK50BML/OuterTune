package com.dd3boh.outertune.utils

/**
 * How the media request for a resolved stream url has to be shaped, which depends on the client the
 * url was signed for and is not a free choice.
 *
 * The values mirror innertubex's own rules (requiresBoundedMediaRange / usesChunkedMediaRanges /
 * mediaRangeChunkSize), deliberately rather than being invented here, so both stream engines in this
 * app agree about a given client.
 *
 * This matters more than it looks. Requesting a WEB_REMIX url as a series of small bounded ranges -
 * which this app did unconditionally, at 256KB a time, for every client - gets a few dozen of those
 * requests served and then a 403 from googlevideo. The symptom is playback that dies partway into
 * the first minute, at a point that moves with the track's bitrate rather than the clock: 47 seconds
 * on one song, 60 on another, because what is being exhausted is a count of range requests, not a
 * timer. A fixed timeout would land in the same place every time, and this never did.
 *
 * The bounding was originally added here on the opposite theory - that one long-lived connection was
 * being cut off by the CDN. That was wrong, and the giveaway was already in the note left behind
 * with it: raising the chunk size did not move the failure point, which is exactly what one expects
 * when the limit is on requests rather than on bytes or seconds.
 */
object StreamRangePolicy {
    private const val CHUNKED_RANGE_BYTES = 512L * 1024L
    private const val BOUNDED_RANGE_BYTES = 1024L * 1024L

    /** Clients whose urls are only served for an explicitly bounded range. */
    fun requiresBoundedRange(clientName: String?): Boolean =
        clientName == "ANDROID_VR" || clientName == "IOS" || clientName == "TVHTML5_SIMPLY"

    /** Clients that expect the media to be walked in fixed-size chunks rather than read straight through. */
    fun usesChunkedRanges(clientName: String?): Boolean =
        clientName == "ANDROID_VR" || clientName == "TVHTML5_SIMPLY"

    fun chunkSizeBytes(clientName: String?): Long =
        if (usesChunkedRanges(clientName)) CHUNKED_RANGE_BYTES else BOUNDED_RANGE_BYTES
}
