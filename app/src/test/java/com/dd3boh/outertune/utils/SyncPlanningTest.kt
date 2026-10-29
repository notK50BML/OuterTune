/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The pure decisions behind the YouTube Music sync: what an overwrite-with-remote pass may remove,
 * and which liked songs need a new liked date.
 */
class SyncPlanningTest {

    private val now = LocalDateTime.of(2026, 1, 10, 12, 0, 0)
    private val earlier = now.minusHours(1)

    // ---- removals ----------------------------------------------------------------------------

    @Test
    fun `items missing from the remote list are removed`() {
        val removed = itemsMissingRemotely(listOf("a", "b", "c"), setOf("a", "c")) { it }
        assertEquals(listOf("b"), removed)
    }

    @Test
    fun `an empty remote list removes nothing`() {
        val removed = itemsMissingRemotely(listOf("a", "b", "c"), emptySet()) { it }
        assertTrue("an empty response is not a user who removed everything", removed.isEmpty())
    }

    // ---- liked dates -------------------------------------------------------------------------

    @Test
    fun `an unchanged list writes nothing`() {
        val local = mapOf(
            "a" to earlier,
            "b" to earlier.minusSeconds(1),
            "c" to earlier.minusSeconds(2),
        )
        assertTrue(planLikedDates(listOf("a", "b", "c"), local, now).isEmpty())
    }

    @Test
    fun `new likes at the top are dated, the rest are left alone`() {
        val local = mapOf("a" to earlier, "b" to earlier.minusSeconds(1))
        val plan = planLikedDates(listOf("x", "y", "a", "b"), local, now)
        assertEquals(mapOf("x" to now, "y" to now.minusSeconds(1)), plan)
    }

    @Test
    fun `a reordered list re-dates every song`() {
        val local = mapOf("a" to earlier, "b" to earlier.minusSeconds(1))
        val plan = planLikedDates(listOf("b", "a"), local, now)
        assertEquals(mapOf("b" to now, "a" to now.minusSeconds(1)), plan)
    }

    @Test
    fun `a new like below an older one re-dates every song`() {
        // "x" dated now would sort above "a", which YouTube lists first.
        val local = mapOf("a" to earlier, "b" to earlier.minusSeconds(1))
        val plan = planLikedDates(listOf("a", "x", "b"), local, now)
        assertEquals(setOf("a", "x", "b"), plan.keys)
        assertEquals(now, plan["a"])
        assertEquals(now.minusSeconds(1), plan["x"])
        assertEquals(now.minusSeconds(2), plan["b"])
    }

    @Test
    fun `a liked song without a date gets one`() {
        val local = mapOf("a" to null, "b" to earlier)
        val plan = planLikedDates(listOf("a", "b"), local, now)
        assertEquals(mapOf("a" to now), plan)
    }

    @Test
    fun `songs liked only here do not affect the plan`() {
        val local = mapOf("a" to earlier, "local-only" to now.plusDays(1))
        assertTrue(planLikedDates(listOf("a"), local, now).isEmpty())
    }
}
