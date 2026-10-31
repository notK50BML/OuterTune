/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.GraphicsConfiguration
import java.awt.Point
import java.awt.Rectangle

/**
 * Where Windows' hidden-icons flyout is - the box of icons the ^ beside the clock opens - while it is
 * open, so the tray popup can sit above it rather than over it (see trayPopupCardBounds).
 *
 * Found by its window class, which is the only handle Windows offers on it: one class on Windows 11,
 * where the flyout is a XAML island, and another on Windows 10. Nothing anywhere else, where there
 * is no such flyout, and nothing on any failure - the popup then goes against the taskbar as it
 * would for an icon clicked there.
 */
internal object OverflowFlyout {
    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun FindWindowW(className: WString, windowName: WString?): Pointer?
        fun IsWindowVisible(hwnd: Pointer): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun MonitorFromRect(rect: Rect, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0

        fun toRectangle() = Rectangle(left, top, right - left, bottom - top)
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = 0
        @JvmField var rcMonitor = Rect()
        @JvmField var rcWork = Rect()
        @JvmField var dwFlags = 0

        init {
            cbSize = size()
        }
    }

    /** Windows 11's flyout, then Windows 10's. */
    private val CLASSES = listOf("TopLevelWindowForOverflowXamlIsland", "NotifyIconOverflowWindow")
    private const val MONITOR_DEFAULTTONEAREST = 2

    /**
     * How far outside the flyout the pointer can be and it still counts as the box the icon was
     * clicked in: a click lands on an icon inside it, but the pointer may have drifted a little by
     * the time the release is handled.
     */
    private const val NEAR = 48

    private val user32 by lazy {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            runCatching { Native.load("user32", User32::class.java) }.getOrNull()
        } else {
            null
        }
    }

    /**
     * The flyout's bounds in AWT's units, if it is open on [configuration]'s screen with [pointer]
     * in or beside it; null otherwise - closed, somewhere else, or not there at all.
     */
    fun find(pointer: Point, configuration: GraphicsConfiguration): Rectangle? = runCatching {
        val user32 = user32 ?: return null
        CLASSES.firstNotNullOfOrNull { name ->
            val hwnd = user32.FindWindowW(WString(name), null) ?: return@firstNotNullOfOrNull null
            if (!user32.IsWindowVisible(hwnd)) return@firstNotNullOfOrNull null
            val rect = Rect()
            if (!user32.GetWindowRect(hwnd, rect)) return@firstNotNullOfOrNull null
            if (rect.right <= rect.left || rect.bottom <= rect.top) return@firstNotNullOfOrNull null
            val monitor = user32.MonitorFromRect(rect, MONITOR_DEFAULTTONEAREST) ?: return@firstNotNullOfOrNull null
            val info = MonitorInfo()
            if (!user32.GetMonitorInfoW(monitor, info)) return@firstNotNullOfOrNull null
            val transform = configuration.defaultTransform
            val bounds = physicalToAwt(
                rect.toRectangle(),
                info.rcMonitor.toRectangle(),
                configuration.bounds,
                transform.scaleX,
                transform.scaleY,
            )
            bounds.takeIf { Rectangle(it.x - NEAR, it.y - NEAR, it.width + 2 * NEAR, it.height + 2 * NEAR).contains(pointer) }
        }
    }.getOrNull()
}

/**
 * A window's rectangle as Windows reports it - in the screen's real pixels, since the app is told
 * the true scale of each monitor - in AWT's units instead, which are those pixels divided by the
 * screen's scale.
 *
 * Measured from the corner of the monitor, which both describe, rather than from the desktop's: with
 * monitors at different scales there is no one scale that turns every pixel on the desktop into
 * AWT's units, so dividing the absolute coordinates would put anything on a second monitor
 * somewhere else entirely.
 */
internal fun physicalToAwt(rect: Rectangle, monitor: Rectangle, screen: Rectangle, scaleX: Double, scaleY: Double): Rectangle {
    fun x(px: Int) = screen.x + Math.round((px - monitor.x) / scaleX).toInt()
    fun y(px: Int) = screen.y + Math.round((px - monitor.y) / scaleY).toInt()
    val left = x(rect.x)
    val top = y(rect.y)
    return Rectangle(left, top, x(rect.x + rect.width) - left, y(rect.y + rect.height) - top)
}
