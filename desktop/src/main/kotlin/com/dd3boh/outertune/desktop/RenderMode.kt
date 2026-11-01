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
 * Kept as a file beside the library, because it has to be decided before the window - and so
 * before the library and its settings - exists. Mirrored into the settings as well ([SETTING_KEY]),
 * because a backup is the library alone: without the mirror this was the one setting a backup lost.
 * [restoreFrom] puts the file back from the setting, so a restored library brings it with it.
 */
object RenderMode {
    private val marker get() = File(defaultDataDirectory(), "render-on-processor")

    /** The copy in the library's settings, which backups carry. */
    const val SETTING_KEY = "window.renderOnProcessor"

    /**
     * The file brought into line with the setting a restored backup carried, once the library is
     * open. Applies at the next start, as any change here does. A library from before the mirror
     * has no setting, and leaves the file alone; one without a choice yet is given this machine's.
     */
    fun restoreFrom(settings: SettingsStore) {
        val stored = settings.get(SETTING_KEY)?.toBooleanStrictOrNull()
        if (stored == null) {
            settings.put(SETTING_KEY, chosen().toString())
        } else if (stored != chosen()) {
            choose(stored)
        }
    }

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
