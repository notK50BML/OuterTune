/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

/**
 * Where the tray popup opens. A real tray click cannot be made from a test, so the arithmetic is
 * checked on its own against the taskbar positions Windows allows.
 */
class TrayPopupPlacementTest {

    private val card = Dimension(TRAY_POPUP_WIDTH.value.toInt(), TRAY_POPUP_HEIGHT.value.toInt())

    private fun assertInside(usable: Rectangle, bounds: Rectangle) =
        assertTrue("$bounds is not inside $usable", usable.contains(bounds))

    @Test
    fun `above the pointer with the taskbar along the bottom`() {
        // 1920x1080 with a 48-high taskbar; the click on an icon near the right of it.
        val usable = Rectangle(0, 0, 1920, 1032)
        val bounds = trayPopupCardBounds(Point(1700, 1056), card, usable)
        assertEquals(1032 - 8 - card.height, bounds.y)
        assertEquals(1700 - card.width / 2, bounds.x)
        assertInside(usable, bounds)
    }

    @Test
    fun `below the pointer with the taskbar along the top`() {
        val usable = Rectangle(0, 48, 1920, 1032)
        val bounds = trayPopupCardBounds(Point(1700, 24), card, usable)
        assertEquals(48 + 8, bounds.y)
        assertInside(usable, bounds)
    }

    @Test
    fun `kept on screen at the corner`() {
        val usable = Rectangle(0, 0, 1920, 1032)
        val bounds = trayPopupCardBounds(Point(1915, 1070), card, usable)
        assertEquals(1920 - 8 - card.width, bounds.x)
        assertInside(usable, bounds)
    }

    @Test
    fun `beside a taskbar docked on the right`() {
        val usable = Rectangle(0, 0, 1840, 1080)
        val bounds = trayPopupCardBounds(Point(1880, 1000), card, usable)
        assertTrue("overlaps the taskbar: $bounds", bounds.x + bounds.width <= 1840)
        assertInside(usable, bounds)
    }

    @Test
    fun `on a second screen to the left of the first`() {
        // Negative coordinates, as a screen left of the main one has.
        val usable = Rectangle(-2560, 0, 2560, 1392)
        val bounds = trayPopupCardBounds(Point(-300, 1420), card, usable)
        assertInside(usable, bounds)
        assertEquals(-300 - card.width / 2, bounds.x)
    }
}
