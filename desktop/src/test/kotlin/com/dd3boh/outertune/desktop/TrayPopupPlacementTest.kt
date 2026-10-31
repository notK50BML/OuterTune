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
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle

/**
 * Where the tray popup opens, and where a pinned one comes back. A real tray click cannot be made
 * from a test, so the arithmetic is checked on its own against the taskbar positions Windows allows.
 */
class TrayPopupPlacementTest {

    private val card = Dimension(TRAY_POPUP_WIDTH.value.toInt(), TRAY_POPUP_HEIGHT.value.toInt())
    private val screen = Rectangle(0, 0, 1920, 1080)
    private val bottomBar = Insets(0, 0, 48, 0)

    private fun assertInside(usable: Rectangle, bounds: Rectangle) =
        assertTrue("$bounds is not inside $usable", usable.contains(bounds))

    @Test
    fun `just above a bottom taskbar, centred on an icon clicked on it`() {
        val bounds = trayPopupCardBounds(Point(1700, 1056), card, screen, bottomBar)
        assertEquals(1032 - 8 - card.height, bounds.y)
        assertEquals(1700 - card.width / 2, bounds.x)
    }

    @Test
    fun `just above a bottom taskbar when the icon was clicked in the overflow flyout`() {
        // The flyout sits above the taskbar and closes once the popup opens; the popup still meets
        // the taskbar rather than hanging where the pointer was in the flyout.
        val bounds = trayPopupCardBounds(Point(1650, 940), card, screen, bottomBar)
        assertEquals(1032 - 8 - card.height, bounds.y)
        assertEquals(1650 - card.width / 2, bounds.x)
    }

    @Test
    fun `beside the tray when the pointer is nowhere near the taskbar`() {
        // Opened from the keyboard, with the pointer left in the middle of the screen.
        val bounds = trayPopupCardBounds(Point(600, 300), card, screen, bottomBar)
        assertEquals(1920 - 8 - card.width, bounds.x)
        assertEquals(1032 - 8 - card.height, bounds.y)
    }

    @Test
    fun `beside the tray with no pointer at all`() {
        val bounds = trayPopupCardBounds(null, card, screen, bottomBar)
        assertEquals(1920 - 8 - card.width, bounds.x)
        assertEquals(1032 - 8 - card.height, bounds.y)
    }

    @Test
    fun `just below a top taskbar`() {
        val bounds = trayPopupCardBounds(Point(1700, 24), card, screen, Insets(48, 0, 0, 0))
        assertEquals(48 + 8, bounds.y)
        assertEquals(1700 - card.width / 2, bounds.x)
    }

    @Test
    fun `beside a taskbar on the right, level with the pointer`() {
        val bounds = trayPopupCardBounds(Point(1880, 700), card, screen, Insets(0, 0, 0, 80))
        assertEquals(1840 - 8 - card.width, bounds.x)
        assertEquals(700 - card.height / 2, bounds.y)
    }

    @Test
    fun `beside a taskbar on the left, at its bottom when the pointer is far off`() {
        val bounds = trayPopupCardBounds(Point(1500, 200), card, screen, Insets(0, 80, 0, 0))
        assertEquals(80 + 8, bounds.x)
        assertEquals(1080 - 8 - card.height, bounds.y)
    }

    @Test
    fun `kept on screen at the corner`() {
        val bounds = trayPopupCardBounds(Point(1915, 1070), card, screen, bottomBar)
        assertEquals(1920 - 8 - card.width, bounds.x)
        assertInside(Rectangle(0, 0, 1920, 1032), bounds)
    }

    @Test
    fun `clear of a taskbar set to hide itself`() {
        // No insets at all: the taskbar is taken to be along the bottom, where it slides up.
        val bounds = trayPopupCardBounds(Point(1700, 1075), card, screen, Insets(0, 0, 0, 0))
        assertEquals(1080 - 48 - 8 - card.height, bounds.y)
    }

    @Test
    fun `on a second screen to the left of the first`() {
        // Negative coordinates, as a screen left of the main one has.
        val left = Rectangle(-2560, 0, 2560, 1440)
        val bounds = trayPopupCardBounds(Point(-300, 1420), card, left, bottomBar)
        assertEquals(-300 - card.width / 2, bounds.x)
        assertEquals(1440 - 48 - 8 - card.height, bounds.y)
    }

    @Test
    fun `a pinned widget's places are kept per screen, the latest first`() {
        var saved = PinnedSpots.with("", "\\Display0", Point(100, 200))
        saved = PinnedSpots.with(saved, "\\Display1", Point(-900, 50))
        saved = PinnedSpots.with(saved, "\\Display0", Point(300, 400))
        assertEquals(
            listOf("\\Display0" to Point(300, 400), "\\Display1" to Point(-900, 50)),
            PinnedSpots.decode(saved),
        )
        // Anything unreadable is skipped rather than taken for a place.
        assertEquals(listOf("a" to Point(1, 2)), PinnedSpots.decode("a|1,2;b|x,3;;c"))
    }

    @Test
    fun `a pinned widget comes back on screen when the screen has shrunk`() {
        val window = Dimension(card.width + 32, card.height + 32)
        // Left at the right of a wider screen; the card itself must end up wholly inside.
        val bounds = pinnedWindowBounds(Point(2400, 900), window, Rectangle(0, 0, 1920, 1032), margin = 16)
        val cardBounds = Rectangle(bounds.x + 16, bounds.y + 16, card.width, card.height)
        assertInside(Rectangle(0, 0, 1920, 1032), cardBounds)
        // And left alone where it already fits.
        assertEquals(Point(500, 300), pinnedWindowBounds(Point(500, 300), window, Rectangle(0, 0, 1920, 1032), 16).location)
    }
}
