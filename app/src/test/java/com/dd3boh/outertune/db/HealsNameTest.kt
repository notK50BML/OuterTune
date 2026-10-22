/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.healsName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an ordinary write may replace a stored artist name - see ArtistsDao.healsName.
 *
 * The refusals come first: "the names differ" used to be enough, and a sync carrying a renamed
 * channel's old name then undid every repair the artist page made.
 */
class HealsNameTest {

    @Test
    fun `a differing name from a credit does not overwrite a good one`() {
        assertFalse(healsName(stored = "i-dle", incoming = "(G)I-DLE"))
        assertFalse(healsName(stored = "(G)I-DLE", incoming = "i-dle"))
    }

    @Test
    fun `a name never gains a Topic suffix, and a blank never wins`() {
        assertFalse(healsName(stored = "Taylor Swift", incoming = "Taylor Swift - Topic"))
        assertFalse(healsName(stored = "Taylor Swift", incoming = ""))
        assertFalse(healsName(stored = "", incoming = "  "))
    }

    @Test
    fun `a blank or Topic-suffixed stored name is healed`() {
        assertTrue(healsName(stored = "", incoming = "Taylor Swift"))
        assertTrue(healsName(stored = "Taylor Swift - Topic", incoming = "Taylor Swift"))
    }
}
