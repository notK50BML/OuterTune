package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeClient

/**
 * One start of [song]. Deliberately not a data class: two starts of the same song are two
 * different values, so play counting keyed on one begins again for the other.
 */
class SongStart(val song: SongItem)

/**
 * How much of one start of a song has actually been heard, from the player's position as it moves.
 *
 * Counted from the position itself, a seek to the end was a play: dragging the bar past the
 * minimum recorded the song, here and on the account. The phone counts time heard, so this does
 * too. Small steps forward are listening; a jump - a seek, the resume of a saved queue part way in,
 * a Listen Together join - or any step back adds nothing.
 */
class HeardTime(private val maxStepMs: Long = MAX_STEP_MS) {
    private var last: Long? = null

    /** Milliseconds heard so far. */
    var heardMs: Long = 0
        private set

    /** The position has moved to [positionMs]. */
    fun advance(positionMs: Long) {
        val previous = last
        last = positionMs
        if (previous == null) return
        val step = positionMs - previous
        if (step in 1..maxStepMs) heardMs += step
    }

    companion object {
        /**
         * Larger than any gap between two position updates the UI sees - they arrive every few
         * hundred milliseconds, more while the window is busy - and smaller than a seek worth the name.
         */
        const val MAX_STEP_MS = 5_000L
    }
}

/**
 * Whether [heardMs] of a song [durationMs] long is a play, at [minPercent] of the song - the phone's
 * rule (see its PlayCounting): a song of no known length, a live stream, never is; and the share is
 * kept between 1% and 99%, so that 0 does not count a song the moment it starts and 100 still
 * counts one heard to its end.
 */
fun countsAsPlay(heardMs: Long, durationMs: Long, minPercent: Int): Boolean {
    if (heardMs <= 0 || durationMs <= 0) return false
    return heardMs * 100 >= durationMs * minPercent.coerceIn(1, 99)
}

/**
 * Puts a play of [videoId] in the signed-in account's YouTube Music history.
 *
 * The metadata call is its own authenticated request rather than the one the stream came from:
 * streams are deliberately fetched signed-out here, so the response that plays the song carries no
 * tracking URL tied to anybody. WEB_REMIX authenticated, as the phone's metadata path is. Failures
 * are let go - the HTTP client has already retried what it could, a history ping that does not
 * land is not worth interrupting anything over, and the local record has already been written.
 */
suspend fun registerPlayOnAccount(videoId: String) {
    val tracking = YouTube.player(
        videoId = videoId,
        client = YouTubeClient.WEB_REMIX,
        authenticated = true,
    ).getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl ?: return
    YouTube.registerPlayback(playlistId = null, playbackTracking = tracking)
}
