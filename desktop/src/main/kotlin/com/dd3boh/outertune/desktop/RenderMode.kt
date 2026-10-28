/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File

/**
 * Whether the window is drawn by the graphics card (the default) or by the processor.
 *
 * Measured on this app idling on its home page: drawn by the card it holds about 380MB, by the
 * processor about 245MB - the difference is the graphics driver's own allocations, nothing of the
 * app's. The cost is processor time on screens that move: scrolling, the player's animated
 * backgrounds. So it is offered, not chosen.
 *
 * Kept as a file beside the library rather than as a setting, because it has to be decided before
 * the window - and so before the library and its settings - exists.
 */
object RenderMode {
    private val marker get() = File(defaultDataDirectory(), "render-on-processor")

    /** Whether the processor draws. Read once at start; a change applies next time. */
    val onProcessor: Boolean = runCatching { marker.isFile }.getOrDefault(false)

    /** What the switch shows - which may not be what this run uses, until it restarts. */
    fun chosen(): Boolean = runCatching { marker.isFile }.getOrDefault(false)

    fun choose(processor: Boolean) {
        runCatching {
            if (processor) marker.apply { parentFile?.mkdirs() }.writeText("")
            else marker.delete()
        }
    }

    /** Before anything is drawn: tells the renderer which way to draw. */
    fun apply() {
        if (onProcessor) System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
    }
}
