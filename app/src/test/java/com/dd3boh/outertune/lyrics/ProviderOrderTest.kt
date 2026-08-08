package com.dd3boh.outertune.lyrics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [orderProviders] has to survive the provider list changing under a stored preference: a user who
 * ordered five providers two releases ago must not lose the sixth, and must not lose the fifth if it
 * was later removed.
 */
class ProviderOrderTest {

    private class FakeProvider(override val id: String) : LyricsProvider {
        override val name = id
        override fun isEnabled(context: Context) = true
        override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int) =
            LyricsFetchResult.NotFound
    }

    private val a = FakeProvider("a")
    private val b = FakeProvider("b")
    private val c = FakeProvider("c")
    private val all = listOf(a, b, c)

    private fun ids(providers: List<LyricsProvider>) = providers.map { it.id }

    @Test
    fun noStoredOrder_keepsTheBuiltInOrder() {
        assertEquals(listOf("a", "b", "c"), ids(orderProviders(all, emptyList())))
    }

    @Test
    fun aFullOrder_isUsedAsGiven() {
        assertEquals(listOf("c", "a", "b"), ids(orderProviders(all, listOf("c", "a", "b"))))
    }

    /**
     * The case that matters when a provider is added: a stored order written before it existed
     * cannot mention it, and it has to end up somewhere rather than disappearing.
     */
    @Test
    fun aProviderMissingFromTheOrder_isKeptAndGoesLast() {
        assertEquals(listOf("c", "a", "b"), ids(orderProviders(all, listOf("c", "a"))))
    }

    /** And when one is removed or renamed, its id in the stored order is simply ignored. */
    @Test
    fun anUnknownIdInTheOrder_isIgnored() {
        assertEquals(listOf("b", "a", "c"), ids(orderProviders(all, listOf("b", "gone", "a"))))
    }

    @Test
    fun aDuplicatedIdDoesNotDuplicateTheProvider() {
        assertEquals(listOf("b", "a", "c"), ids(orderProviders(all, listOf("b", "b", "a"))))
    }

    @Test
    fun anOrderOfOnlyUnknownIds_fallsBackToTheBuiltInOrder() {
        assertEquals(listOf("a", "b", "c"), ids(orderProviders(all, listOf("x", "y"))))
    }

    /** Every provider appears exactly once, whatever the stored order says. */
    @Test
    fun theResultIsAlwaysAPermutationOfTheInput() {
        for (order in listOf(emptyList(), listOf("c"), listOf("c", "b", "a"), listOf("z", "a", "a"))) {
            assertEquals(all.size, orderProviders(all, order).size)
            assertEquals(all.toSet(), orderProviders(all, order).toSet())
        }
    }
}
