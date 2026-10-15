package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-checking lyrics a song already has.
 *
 * Nothing used to re-check a *successful* result, so a song that only had a plain text sheet when
 * it was first played kept it forever, even after a provider gained a word-timed version. This
 * turns that into an interval.
 *
 * The risk it introduces is the one these mostly cover: a re-check that comes back with *less*
 * than is already stored. Providers go down, races go differently, and replacing word-timed lyrics
 * with plain text because of either would make the feature worse than not having it.
 */
class LyricsRefreshTest {

    private val day = 24L * 60 * 60 * 1000
    private val signature = "AUTO:a,b"

    private fun stored(
        lyrics: String,
        lastCheckedAt: Long? = 0L,
        providerSignature: String? = signature,
    ) = LyricsEntity(
        id = "song",
        lyrics = lyrics,
        provider = "a",
        lastCheckedAt = lastCheckedAt,
        providerSignature = providerSignature,
    )

    private val wordSynced = "[00:01.00]<00:01.00>Never <00:01.50>gonna"
    private val lineSynced = "[00:01.00]Never gonna"
    private val plain = "Never gonna"

    // ---- when a stored result is re-checked --------------------------------------------------------

    @Test
    fun `a found result is never re-checked when the interval is off`() {
        // The default, and the behaviour that was there before: a result that was found is final
        // until something forces a refresh.
        assertFalse(
            shouldFetchLyrics(
                stored(lineSynced), signature, now = 999 * day, forceRefresh = false, positiveTtlMs = 0,
            )
        )
    }

    @Test
    fun `a found result is re-checked once it is older than the interval`() {
        assertFalse(
            shouldFetchLyrics(
                stored(lineSynced, lastCheckedAt = 0L), signature,
                now = 6 * day, forceRefresh = false, positiveTtlMs = 7 * day,
            )
        )
        assertTrue(
            shouldFetchLyrics(
                stored(lineSynced, lastCheckedAt = 0L), signature,
                now = 7 * day, forceRefresh = false, positiveTtlMs = 7 * day,
            )
        )
    }

    @Test
    fun `enabling a provider does not throw away lyrics already in hand`() {
        // Deliberately not signature-sensitive on the positive side. A preference change is not a
        // reason to discard a good result - the next scheduled re-check is where a newly enabled
        // provider gets its chance.
        assertFalse(
            shouldFetchLyrics(
                stored(lineSynced), signature = "AUTO:a,b,c",
                now = day, forceRefresh = false, positiveTtlMs = 7 * day,
            )
        )
    }

    @Test
    fun `a clock that went backwards re-checks rather than waiting forever`() {
        assertTrue(
            shouldFetchLyrics(
                stored(lineSynced, lastCheckedAt = 100 * day), signature,
                now = day, forceRefresh = false, positiveTtlMs = 7 * day,
            )
        )
    }

    @Test
    fun `a negative cache is unaffected by the positive interval`() {
        // It has its own, much shorter, TTL and its own signature rule, and both must survive.
        assertTrue(
            shouldFetchLyrics(
                stored(LYRICS_NOT_FOUND, lastCheckedAt = 0L), signature = "AUTO:different",
                now = day, forceRefresh = false, positiveTtlMs = 0,
            )
        )
    }

    // ---- what a re-check is allowed to replace -------------------------------------------------------

    @Test
    fun `word timings are not replaced by line timings`() {
        assertTrue(isDowngrade(stored(wordSynced), RemoteLyricsResult.Found("b", lineSynced, synced = true)))
    }

    @Test
    fun `word timings are not replaced by plain text`() {
        assertTrue(isDowngrade(stored(wordSynced), RemoteLyricsResult.Found("b", plain, synced = false)))
    }

    @Test
    fun `line timings are not replaced by plain text`() {
        assertTrue(isDowngrade(stored(lineSynced), RemoteLyricsResult.Found("b", plain, synced = false)))
    }

    @Test
    fun `an improvement is taken`() {
        assertFalse(isDowngrade(stored(lineSynced), RemoteLyricsResult.Found("b", wordSynced, synced = true)))
        assertFalse(isDowngrade(stored(plain), RemoteLyricsResult.Found("b", lineSynced, synced = true)))
    }

    @Test
    fun `an equal result is taken, so a reordering eventually takes effect`() {
        // Two results at the same sync level are treated as equal rather than compared on length.
        // The fresh one came from the current preference order and the stored one may not have, so
        // preferring it is how moving a provider up the list actually changes anything.
        assertFalse(isDowngrade(stored(lineSynced), RemoteLyricsResult.Found("b", "[00:02.00]Other", synced = true)))
    }

    @Test
    fun `there is nothing to downgrade from when nothing is stored`() {
        assertFalse(isDowngrade(null, RemoteLyricsResult.Found("b", plain, synced = false)))
        assertFalse(isDowngrade(stored(LYRICS_NOT_FOUND), RemoteLyricsResult.Found("b", plain, synced = false)))
        assertFalse(isDowngrade(stored("   "), RemoteLyricsResult.Found("b", plain, synced = false)))
    }
}
