/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemFontsTest {

    @Test
    fun `the installed fonts are listed quickly`() {
        val started = System.nanoTime()
        val families = SystemFonts.families
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("no fonts found", families.isNotEmpty())
        // The picker used to freeze the window here; well under a second is the point.
        assertTrue("listing fonts took $ms ms", ms < 2_000)
        assertTrue("not sorted", families == families.sortedBy { it.lowercase() })
    }

    @Test
    fun `a chosen system font renders without hanging`() {
        val name = SystemFonts.families.first()
        val typography = SystemFonts.typography(SystemFonts.family(name))
        val started = System.nanoTime()
        val scene = ImageComposeScene(400, 100, Density(1f)) {
            MaterialTheme(typography = typography) { Text("The quick brown fox") }
        }
        scene.render()
        scene.close()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("rendering in $name took $ms ms", ms < 5_000)
    }
}
