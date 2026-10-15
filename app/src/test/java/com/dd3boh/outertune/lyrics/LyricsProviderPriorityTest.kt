package com.dd3boh.outertune.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which provider's lyrics win when more than one has the song.
 *
 * The complaint this exists for: "sometimes the lyrics aren't from the top provider". In AUTO mode
 * the providers are raced, and the aggregator used to keep the first result of each quality class
 * to arrive. Arrival order is network latency, so the quickest provider won every tie and the
 * preference list decided only who was *asked*. It looked intermittent because it was - whoever
 * answered first that day.
 *
 * The outcomes below are deliberately offered out of preference order, because that is the whole
 * point: the list says what the user wants, not what the network delivered first.
 */
class LyricsProviderPriorityTest {

    private val classify: (String) -> FoundKind = { raw ->
        when (raw) {
            "wordsynced" -> FoundKind.WORD_SYNCED
            "synced" -> FoundKind.SYNCED
            "unsynced" -> FoundKind.UNSYNCED
            else -> FoundKind.UNPARSEABLE
        }
    }

    /** Preference order: first is the user's top choice. */
    private val order = listOf("top", "middle", "last")

    private fun aggregate(outcomes: List<Pair<String, LyricsFetchResult>>): RemoteLyricsResult {
        val aggregator = RemoteLyricsAggregator(order.size, order)
        for ((name, result) in outcomes) {
            if (aggregator.offer(name, result, classify)) break
        }
        return aggregator.result()
    }

    private fun found(raw: String) = LyricsFetchResult.Found(raw)

    // ---- preference breaks ties ------------------------------------------------------------------

    @Test
    fun `the preferred provider wins a tie it did not arrive first in`() {
        val result = aggregate(
            listOf(
                "last" to found("synced"),
                "middle" to found("synced"),
                "top" to found("synced"),
            )
        )
        assertEquals("top", (result as RemoteLyricsResult.Found).provider)
    }

    @Test
    fun `arriving first is not enough on its own`() {
        val result = aggregate(listOf("middle" to found("synced"), "top" to found("synced")))
        assertEquals("top", (result as RemoteLyricsResult.Found).provider)
    }

    @Test
    fun `a later provider does not displace a better-ranked one`() {
        val result = aggregate(listOf("top" to found("synced"), "last" to found("synced")))
        assertEquals("top", (result as RemoteLyricsResult.Found).provider)
    }

    // ---- quality still outranks preference --------------------------------------------------------

    @Test
    fun `word timings beat the preferred provider's line timings`() {
        // Preference orders equals. It does not make a worse result better: a line-level renderer
        // ignores word marks, so word-synced is strictly more, and the lower-ranked provider that
        // has them is the right answer.
        val result = aggregate(listOf("top" to found("synced"), "last" to found("wordsynced")))
        assertEquals("last", (result as RemoteLyricsResult.Found).provider)
        assertTrue(result.synced)
    }

    @Test
    fun `synced beats unsynced regardless of order`() {
        val result = aggregate(listOf("top" to found("unsynced"), "last" to found("synced")))
        assertEquals("last", (result as RemoteLyricsResult.Found).provider)
    }

    @Test
    fun `the preferred provider wins among several word-synced results`() {
        val result = aggregate(
            listOf(
                "last" to found("wordsynced"),
                "top" to found("wordsynced"),
            )
        )
        assertEquals("top", (result as RemoteLyricsResult.Found).provider)
    }

    // ---- when the race may end early ---------------------------------------------------------------

    @Test
    fun `the top provider answering word-synced ends the race at once`() {
        // The common case, and it must cost nothing: nothing outstanding can beat the best class
        // from the best rank.
        val aggregator = RemoteLyricsAggregator(order.size, order)
        assertTrue(aggregator.offer("top", found("wordsynced"), classify))
    }

    @Test
    fun `a lower-ranked word-synced result waits for the ones above it`() {
        // "last" has word timings, but "top" and "middle" have not reported - either could return
        // word timings too, and would then win the tie. Stopping here is what made the result
        // depend on latency.
        val aggregator = RemoteLyricsAggregator(order.size, order)
        assertFalse(aggregator.offer("last", found("wordsynced"), classify))
        assertFalse(aggregator.offer("middle", LyricsFetchResult.NotFound, classify))
        // Once the last one above it has reported, nothing better can arrive.
        assertTrue(aggregator.offer("top", LyricsFetchResult.NotFound, classify))
        assertEquals("last", (aggregator.result() as RemoteLyricsResult.Found).provider)
    }

    @Test
    fun `line-synced never ends the race early`() {
        // A slower provider may still return the same song word-timed, which is worth the wait -
        // the behaviour the previous version already had, and which must survive this change.
        val aggregator = RemoteLyricsAggregator(order.size, order)
        assertFalse(aggregator.offer("top", found("synced"), classify))
    }

    // ---- the old guarantees ---------------------------------------------------------------------

    @Test
    fun `an unparseable result is neither adopted nor counted as an absence`() {
        val result = aggregate(
            listOf(
                "top" to found("garbage"),
                "middle" to LyricsFetchResult.NotFound,
                "last" to LyricsFetchResult.NotFound,
            )
        )
        assertEquals(RemoteLyricsResult.Indeterminate, result)
    }

    @Test
    fun `every provider reporting nothing is a definitive absence`() {
        val result = aggregate(
            listOf(
                "top" to LyricsFetchResult.NotFound,
                "middle" to LyricsFetchResult.NotFound,
                "last" to LyricsFetchResult.NotFound,
            )
        )
        assertEquals(RemoteLyricsResult.DefinitiveNotFound, result)
    }

    @Test
    fun `a provider the order does not mention still counts`() {
        // Ranks come from the enabled list, and a newly added provider that the stored order
        // predates goes last rather than being ignored - see orderProviders. It still has to be
        // adoptable when it is the only one with the song.
        val result = aggregate(listOf("newcomer" to found("synced")))
        assertEquals("newcomer", (result as RemoteLyricsResult.Found).provider)
    }
}
