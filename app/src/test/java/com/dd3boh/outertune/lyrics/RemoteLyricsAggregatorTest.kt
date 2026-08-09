package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aggregation branches of [RemoteLyricsAggregator]. The classifier is faked so the decision logic is
 * exercised without a real lyrics parser: a raw string maps to a [FoundKind] by its own value.
 */
class RemoteLyricsAggregatorTest {

    private val classify: (String) -> FoundKind = { raw ->
        when (raw) {
            "wordsynced" -> FoundKind.WORD_SYNCED
            "synced" -> FoundKind.SYNCED
            "unsynced" -> FoundKind.UNSYNCED
            else -> FoundKind.UNPARSEABLE
        }
    }

    private fun aggregate(enabledCount: Int, outcomes: List<Pair<String, LyricsFetchResult>>): RemoteLyricsResult {
        val aggregator = RemoteLyricsAggregator(enabledCount)
        for ((name, result) in outcomes) {
            if (aggregator.offer(name, result, classify)) break
        }
        return aggregator.result()
    }

    @Test
    fun allNotFound_isDefinitiveNotFound() {
        val result = aggregate(
            enabledCount = 3,
            outcomes = listOf(
                "a" to LyricsFetchResult.NotFound,
                "b" to LyricsFetchResult.NotFound,
                "c" to LyricsFetchResult.NotFound,
            ),
        )
        assertEquals(RemoteLyricsResult.DefinitiveNotFound, result)
    }

    @Test
    fun notFoundPlusFailed_isIndeterminate() {
        val result = aggregate(
            enabledCount = 2,
            outcomes = listOf(
                "a" to LyricsFetchResult.NotFound,
                "b" to LyricsFetchResult.Failed(),
            ),
        )
        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }

    @Test
    fun foundPlusFailed_isFound() {
        val result = aggregate(
            enabledCount = 2,
            outcomes = listOf(
                "a" to LyricsFetchResult.Failed(),
                "b" to LyricsFetchResult.Found("synced"),
            ),
        )
        assertTrue(result is RemoteLyricsResult.Found)
        result as RemoteLyricsResult.Found
        assertEquals("b", result.provider)
        assertTrue(result.synced)
    }

    @Test
    fun noEnabledProviders_isDefinitiveNotFoundOnlyWhenZeroExpectedAndNoOutcomes() {
        // Skipped is produced by the caller before aggregation; with zero enabled and zero outcomes the
        // aggregator itself reports DefinitiveNotFound (0 == 0). The production path returns Skipped first.
        assertEquals(RemoteLyricsResult.DefinitiveNotFound, aggregate(0, emptyList()))
    }

    /**
     * A line-level synced result is adopted, but deliberately does *not* stop the race any more:
     * a slower provider may still answer with the same song in word-timed form, and that is the
     * only form the word-by-word renderer can use. Only WORD_SYNCED ends it early now.
     */
    @Test
    fun syncedWins_butDoesNotStopEarly() {
        val aggregator = RemoteLyricsAggregator(3)
        assertFalse(aggregator.offer("a", LyricsFetchResult.Found("synced"), classify))
        val result = aggregator.result()
        assertTrue(result is RemoteLyricsResult.Found)
        assertTrue((result as RemoteLyricsResult.Found).synced)
        assertEquals("a", result.provider)
    }

    @Test
    fun unsyncedHeldAsFallback_whenNoSynced() {
        val result = aggregate(
            enabledCount = 2,
            outcomes = listOf(
                "a" to LyricsFetchResult.Found("unsynced"),
                "b" to LyricsFetchResult.NotFound,
            ),
        )
        assertTrue(result is RemoteLyricsResult.Found)
        result as RemoteLyricsResult.Found
        assertEquals("a", result.provider)
        assertTrue(!result.synced)
    }

    @Test
    fun syncedPreferredOverEarlierUnsynced() {
        val result = aggregate(
            enabledCount = 2,
            outcomes = listOf(
                "a" to LyricsFetchResult.Found("unsynced"),
                "b" to LyricsFetchResult.Found("synced"),
            ),
        )
        assertTrue(result is RemoteLyricsResult.Found)
        result as RemoteLyricsResult.Found
        assertEquals("b", result.provider)
        assertTrue(result.synced)
    }

    @Test
    fun unparseableFound_blocksDefinitiveNotFound() {
        val result = aggregate(
            enabledCount = 2,
            outcomes = listOf(
                "a" to LyricsFetchResult.NotFound,
                "b" to LyricsFetchResult.Found("garbage"), // classified UNPARSEABLE
            ),
        )
        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }

    @Test
    fun missingReports_blockDefinitiveNotFound() {
        // Two providers enabled but only one reported NotFound (the other timed out and never reported).
        val result = aggregate(
            enabledCount = 2,
            outcomes = listOf("a" to LyricsFetchResult.NotFound),
        )
        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }

    /**
     * The reason word timings are a kind of their own: the providers are raced, so without this a
     * line-level answer from a quick provider beat a word-timed one from a slower provider every
     * time, and the word-by-word renderer had nothing to render.
     */
    @Test
    fun wordSyncedWins_andStopsEarly() {
        val agg = RemoteLyricsAggregator(3)
        assertFalse(agg.offer("A", LyricsFetchResult.Found("synced"), classify))
        assertTrue(agg.offer("B", LyricsFetchResult.Found("wordsynced"), classify))
        val r = agg.result() as RemoteLyricsResult.Found
        assertEquals("B", r.provider)
    }

    /** A line-level result is still adopted when nothing better ever arrives. */
    @Test
    fun syncedAdopted_whenNoWordSyncedArrives() {
        val agg = RemoteLyricsAggregator(2)
        assertFalse(agg.offer("A", LyricsFetchResult.Found("synced"), classify))
        assertFalse(agg.offer("B", LyricsFetchResult.NotFound, classify))
        val r = agg.result() as RemoteLyricsResult.Found
        assertEquals("A", r.provider)
        assertTrue(r.synced)
    }

    /** And it does not stop the race, so a later word-timed answer can still overtake it. */
    @Test
    fun syncedDoesNotStopTheRace() {
        val agg = RemoteLyricsAggregator(3)
        assertFalse(agg.offer("A", LyricsFetchResult.Found("synced"), classify))
        assertFalse(agg.offer("B", LyricsFetchResult.Found("synced"), classify))
        assertTrue(agg.offer("C", LyricsFetchResult.Found("wordsynced"), classify))
        assertEquals("C", (agg.result() as RemoteLyricsResult.Found).provider)
    }

    @Test
    fun wordSyncedStillBeatsUnsynced() {
        val agg = RemoteLyricsAggregator(2)
        assertFalse(agg.offer("A", LyricsFetchResult.Found("unsynced"), classify))
        assertTrue(agg.offer("B", LyricsFetchResult.Found("wordsynced"), classify))
        assertEquals("B", (agg.result() as RemoteLyricsResult.Found).provider)
    }
}
