/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.awt.Rectangle

/**
 * A real Windows window with the title bar taken away - so the app runs to the top edge with its own
 * buttons, and still has everything a window has: the shadow, the rounded corners, resizing from
 * every edge, Aero snap when dragged, the minimise and maximise animations, and the Windows 11 snap
 * layouts flyout when the maximise button is hovered.
 *
 * The undecorated window this replaces had none of that, because to Windows it was not a window with
 * a frame at all. Here the frame stays; only its caption is removed, by answering two of the
 * messages Windows asks every window:
 *
 * - WM_NCCALCSIZE, "how much of you is frame": the usual left, right and bottom borders (which on
 *   Windows 10 and 11 are invisible resize handles outside the visible window), and nothing at the
 *   top. The client area - where Compose draws - then reaches the top edge.
 * - WM_NCHITTEST, "what is at this point": the top few pixels resize, the strip along the top is the
 *   caption (so dragging and double-clicking are Windows' own), the maximise button is the maximise
 *   button (which is what makes snap layouts appear), and everything else is the app.
 *
 * Done by subclassing the window procedure through JNA - the same thing a native app does, without
 * shipping native code. If anything about it fails, [install] returns false and the window keeps
 * the borderless fallback.
 */
object NativeFrame {

    /** Whether the hook is in. When it is, the caption strip and maximise button are Windows'. */
    var active by mutableStateOf(false)
        private set

    /** Whether the pointer is over the maximise button - Windows reports it, Compose cannot see it. */
    var maximiseHovered by mutableStateOf(false)
        private set

    /** Height of the caption strip, in window pixels. */
    @Volatile var captionHeightPx = 0

    /** Where the window buttons are, in window pixels - kept up to date by the caption bar. */
    @Volatile var maximiseButton: Rectangle? = null
    @Volatile var otherButtons: List<Rectangle> = emptyList()

    /** Called when the maximise button is clicked, after Windows has done it. */
    @Volatile var onMaximiseToggled: (() -> Unit)? = null

    private interface User32 : StdCallLibrary {
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, proc: WndProc): Pointer?
        fun CallWindowProcW(prev: Pointer, hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
        fun ScreenToClient(hwnd: Pointer, point: POINT): Boolean
        fun GetDpiForWindow(hwnd: Pointer): Int
        fun GetSystemMetricsForDpi(index: Int, dpi: Int): Int
    }

    @Suppress("unused")
    @Structure.FieldOrder("x", "y")
    class POINT : Structure() {
        @JvmField var x = 0
        @JvmField var y = 0
    }

    private interface WndProc : StdCallLibrary.StdCallCallback {
        fun callback(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    }

    private const val GWLP_WNDPROC = -4
    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCMOUSEMOVE = 0x00A0
    private const val WM_NCLBUTTONDOWN = 0x00A1
    private const val WM_NCLBUTTONUP = 0x00A2
    private const val WM_NCLBUTTONDBLCLK = 0x00A3
    private const val WM_MOUSEMOVE = 0x0200
    private const val WM_NCMOUSELEAVE = 0x02A2
    private const val HTCLIENT = 1L
    private const val HTCAPTION = 2L
    private const val HTMAXBUTTON = 9L
    private const val HTTOP = 12L
    private const val HTTOPLEFT = 13L
    private const val HTTOPRIGHT = 14L
    private const val SM_CYFRAME = 33
    private const val SM_CXPADDEDBORDER = 92
    private const val SWP_FLAGS = 0x0020 or 0x0002 or 0x0001 or 0x0004 or 0x0010 // FRAMECHANGED|NOMOVE|NOSIZE|NOZORDER|NOACTIVATE
    private const val SW_MAXIMIZE = 3
    private const val SW_RESTORE = 9

    private var user32: User32? = null
    private var previous: Pointer? = null

    // Held for the life of the window: a callback JNA has handed to Windows must not be collected.
    private var proc: WndProc? = null

    /** Hooks [window]. Returns false, changing nothing, wherever it cannot. */
    fun install(window: java.awt.Window): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        return runCatching {
            val u = Native.load("user32", User32::class.java)
            val hwnd = Pointer(Native.getWindowID(window))
            val hook = object : WndProc {
                override fun callback(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long =
                    runCatching { handle(u, hwnd, msg, wParam, lParam) }
                        .getOrElse { u.CallWindowProcW(previous!!, hwnd, msg, wParam, lParam) }
            }
            proc = hook
            user32 = u
            previous = u.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, hook) ?: return false
            active = true
            // Asks Windows to recompute the frame now, with the caption gone.
            u.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_FLAGS)
            true
        }.getOrElse { false }
    }

    private fun frameThickness(u: User32, hwnd: Pointer): Int {
        val dpi = runCatching { u.GetDpiForWindow(hwnd) }.getOrDefault(96).takeIf { it > 0 } ?: 96
        return u.GetSystemMetricsForDpi(SM_CYFRAME, dpi) + u.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)
    }

    private fun handle(u: User32, hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long {
        val prev = previous!!
        when (msg) {
            WM_NCCALCSIZE -> {
                if (wParam == 0L) return u.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
                // NCCALCSIZE_PARAMS: the first RECT is the proposed window rect, rewritten to the
                // client rect. Let Windows lay out the usual borders, then put the top back.
                val params = Pointer(lParam)
                val top = params.getInt(4)
                val result = u.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
                // A maximised window hangs its frame off the screen; without this inset the top of
                // the app would be cut off by that much.
                params.setInt(4, if (u.IsZoomed(hwnd)) top + frameThickness(u, hwnd) else top)
                return result
            }

            WM_NCHITTEST -> {
                val result = u.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
                if (result != HTCLIENT) {
                    maximiseHovered = false
                    return result
                }
                val p = POINT().apply {
                    x = (lParam and 0xFFFF).toShort().toInt()
                    y = ((lParam shr 16) and 0xFFFF).toShort().toInt()
                }
                u.ScreenToClient(hwnd, p)
                val zoomed = u.IsZoomed(hwnd)
                val edge = frameThickness(u, hwnd)
                val hit = when {
                    !zoomed && p.y < edge && p.x < edge * 2 -> HTTOPLEFT
                    !zoomed && p.y < edge && otherButtons.none { it.contains(p.x, p.y) } &&
                        maximiseButton?.contains(p.x, p.y) != true -> HTTOP
                    maximiseButton?.contains(p.x, p.y) == true -> HTMAXBUTTON
                    otherButtons.any { it.contains(p.x, p.y) } -> HTCLIENT
                    p.y < captionHeightPx -> HTCAPTION
                    else -> HTCLIENT
                }
                maximiseHovered = hit == HTMAXBUTTON
                return hit
            }

            // The maximise button is non-client now, so its clicks come here rather than to Compose.
            // Left to Windows, they would draw the old grey caption button over the app's.
            WM_NCLBUTTONDOWN, WM_NCLBUTTONDBLCLK -> if (wParam == HTMAXBUTTON) return 0
            WM_NCLBUTTONUP -> if (wParam == HTMAXBUTTON) {
                u.ShowWindow(hwnd, if (u.IsZoomed(hwnd)) SW_RESTORE else SW_MAXIMIZE)
                onMaximiseToggled?.invoke()
                return 0
            }
            WM_NCMOUSEMOVE -> maximiseHovered = wParam == HTMAXBUTTON
            WM_NCMOUSELEAVE, WM_MOUSEMOVE -> maximiseHovered = false
        }
        return u.CallWindowProcW(prev, hwnd, msg, wParam, lParam)
    }
}
