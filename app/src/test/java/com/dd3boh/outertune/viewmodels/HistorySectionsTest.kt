/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.HistoryPage.HistorySection
import org.junit.Assert.assertEquals
import org.junit.Test

/** A further page of YouTube history keeps its own dates instead of joining the last one shown. */
class HistorySectionsTest {

    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = "")

    private fun section(title: String, vararg ids: String) = HistorySection(title, ids.map(::song))

    @Test
    fun `an untitled section continues the last one`() {
        val merged = appendHistorySections(
            listOf(section("Today", "a"), section("Yesterday", "b")),
            listOf(section("", "c")),
        )
        assertEquals(listOf(section("Today", "a"), section("Yesterday", "b", "c")), merged)
    }

    @Test
    fun `titled sections of a new page stay separate`() {
        val merged = appendHistorySections(
            listOf(section("Today", "a")),
            listOf(section("Today", "b"), section("Last week", "c"), section("March 2026", "d")),
        )
        assertEquals(
            listOf(section("Today", "a", "b"), section("Last week", "c"), section("March 2026", "d")),
            merged,
        )
    }

    @Test
    fun `a first page with nothing shown yet is taken as it is`() {
        val next = listOf(section("Today", "a"), section("Yesterday", "b"))
        assertEquals(next, appendHistorySections(emptyList(), next))
    }

    @Test
    fun `empty sections add nothing`() {
        val current = listOf(section("Today", "a"))
        assertEquals(current, appendHistorySections(current, listOf(section("Yesterday"))))
    }
}
