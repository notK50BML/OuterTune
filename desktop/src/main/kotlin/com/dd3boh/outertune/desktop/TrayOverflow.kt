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
        fun GetCursorPos(point: CursorPoint): Boolean
        fun WindowFromPoint(point: CursorPoint.ByValue): Pointer?
        fun GetAncestor(hwnd: Pointer, flags: Int): Pointer?
        fun GetClassNameW(hwnd: Pointer, name: CharArray, max: Int): Int
    }

    @Structure.FieldOrder("x", "y")
    open class CursorPoint : Structure() {
        @JvmField var x = 0
        @JvmField var y = 0

        class ByValue : CursorPoint(), Structure.ByValue
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
    private const val GA_ROOT = 2

    /** The taskbar itself, on the main monitor and on the others: an icon clicked there has no flyout. */
    private val TASKBARS = setOf("Shell_TrayWnd", "Shell_SecondaryTrayWnd")

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
    fun find(pointer: Point, configuration: GraphicsConfiguration): Rectangle? {
        val log = StringBuilder("pointer=$pointer screen=${configuration.bounds}\n")
        val found = runCatching {
            val user32 = user32 ?: return@runCatching null
            // First, the window the click actually landed in. An icon right-clicked in the flyout is
            // under the pointer inside the flyout, so this finds it on any Windows version, whatever
            // its class is called - the class names below were never more than a guess at that.
            underPointer(user32, configuration, log)
                // Then the known classes, for a release that arrives after the pointer has left.
                ?: CLASSES.firstNotNullOfOrNull { name ->
                    val hwnd = user32.FindWindowW(WString(name), null) ?: return@firstNotNullOfOrNull null
                    val visible = user32.IsWindowVisible(hwnd)
                    val bounds = awtBounds(user32, hwnd, configuration)
                    log.append("class $name: visible=$visible bounds=$bounds\n")
                    if (!visible) return@firstNotNullOfOrNull null
                    bounds?.takeIf { Rectangle(it.x - NEAR, it.y - NEAR, it.width + 2 * NEAR, it.height + 2 * NEAR).contains(pointer) }
                }
        }.onFailure { log.append("failed: $it\n") }.getOrNull()
        log.append("chosen=$found\n")
        TrayDebugLog.write(log.toString())
        return found
    }

    /**
     * The top-level window under the pointer, unless it is the taskbar (no flyout then) or too big to
     * be one - a full-screen window behind a click that missed. In AWT's units.
     */
    private fun underPointer(user32: User32, configuration: GraphicsConfiguration, log: StringBuilder): Rectangle? {
        val cursor = CursorPoint()
        if (!user32.GetCursorPos(cursor)) return null
        val at = CursorPoint.ByValue().apply { x = cursor.x; y = cursor.y }
        val hwnd = user32.WindowFromPoint(at) ?: return null
        val root = user32.GetAncestor(hwnd, GA_ROOT) ?: hwnd
        val name = CharArray(256)
        val className = String(name, 0, user32.GetClassNameW(root, name, name.size))
        val bounds = awtBounds(user32, root, configuration)
        log.append("under pointer (${cursor.x},${cursor.y} px): class=$className bounds=$bounds\n")
        if (className in TASKBARS || bounds == null) return null
        val screen = configuration.bounds
        if (bounds.width > screen.width / 2 || bounds.height > screen.height / 2) return null
        return bounds
    }

    /** A window's rectangle in AWT's units, or null if it has none. */
    private fun awtBounds(user32: User32, hwnd: Pointer, configuration: GraphicsConfiguration): Rectangle? {
        val rect = Rect()
        if (!user32.GetWindowRect(hwnd, rect)) return null
        if (rect.right <= rect.left || rect.bottom <= rect.top) return null
        val monitor = user32.MonitorFromRect(rect, MONITOR_DEFAULTTONEAREST) ?: return null
        val info = MonitorInfo()
        if (!user32.GetMonitorInfoW(monitor, info)) return null
        val transform = configuration.defaultTransform
        return physicalToAwt(rect.toRectangle(), info.rcMonitor.toRectangle(), configuration.bounds, transform.scaleX, transform.scaleY)
    }
}

/**
 * What the tray popup saw on its last few right-clicks - the pointer, the window under it and where
 * the flyout was taken to be - in tray-debug.txt beside the library, so a popup in the wrong place
 * can be explained from one try rather than guessed at. Kept to the last few entries.
 */
internal object TrayDebugLog {
    private const val KEEP = 20

    fun write(entry: String) {
        runCatching {
            val file = java.io.File(defaultDataDirectory(), "tray-debug.txt")
            val old = if (file.exists()) file.readText().split("\n---\n").takeLast(KEEP - 1) else emptyList()
            file.writeText((old + "${java.time.LocalDateTime.now()}\n$entry").joinToString("\n---\n"))
        }
    }
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
