/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame

/**
 * Gives memory back while the window is minimised.
 *
 * A music player spends most of its life minimised, playing, and nothing it keeps for drawing is
 * any use then: the decoded covers, the heap grown to fit a screenful of them. A few seconds after
 * the window goes down, the covers are dropped (the disk keeps them - they come back in a moment),
 * the heap is collected and shrunk, and Windows is asked to take back whatever pages are idle.
 * Playback is untouched: the song's bytes are in use and stay, and anything paged out comes back
 * as soon as it is touched.
 */
object MemoryTrim {
    private interface Kernel32 : StdCallLibrary {
        fun GetCurrentProcess(): Pointer
        /** -1 for both: "trim what is not in use" - see SetProcessWorkingSetSize. */
        fun SetProcessWorkingSetSize(process: Pointer, minimum: Long, maximum: Long): Boolean
    }

    private val kernel32: Kernel32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    /** Long enough that a quick minimise and restore - checking something - does not pay for it. */
    private const val DELAY_MS = 5_000L

    fun install(window: Frame) {
        window.addWindowStateListener { event ->
            if (event.newState and Frame.ICONIFIED == 0) return@addWindowStateListener
            Thread {
                Thread.sleep(DELAY_MS)
                if (window.extendedState and Frame.ICONIFIED != 0) trim()
            }.apply { isDaemon = true; name = "MemoryTrim" }.start()
        }
    }

    private fun trim() {
        ArtworkCache.dropDecoded()
        // A full collection, which is what lets the heap shrink (MaxHeapFreeRatio in the launcher's
        // options). A pause nobody sees: the window is down.
        System.gc()
        kernel32?.let { k -> runCatching { k.SetProcessWorkingSetSize(k.GetCurrentProcess(), -1L, -1L) } }
    }
}
