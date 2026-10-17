/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.awt.EventQueue
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Notices when the window stops responding, and writes down why.
 *
 * Every half second it posts a no-op to the UI thread and notes when it ran. If the UI thread has not
 * run one for [STALL_MS], something is holding it - and at that moment every thread's stack is
 * written to a file in the library folder. A freeze that cannot be reproduced on another machine can
 * then be diagnosed from the one place it happens: the file says which call the UI thread was stuck
 * in and who held what it was waiting for.
 *
 * One report per freeze, not one per tick while it lasts.
 */
object FreezeWatchdog {

    private val lastBeat = AtomicLong(System.currentTimeMillis())

    fun start(directory: File) {
        thread(isDaemon = true, name = "freeze-watchdog") {
            var reported = false
            while (true) {
                EventQueue.invokeLater { lastBeat.set(System.currentTimeMillis()) }
                Thread.sleep(500)
                val stalled = System.currentTimeMillis() - lastBeat.get()
                if (stalled >= STALL_MS && !reported) {
                    reported = true
                    runCatching { write(directory, stalled) }
                } else if (stalled < STALL_MS) {
                    reported = false
                }
            }
        }
    }

    private fun write(directory: File, stalledMs: Long) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(directory, "freeze-$stamp.txt")
        val text = buildString {
            appendLine("OuterTune desktop ${DesktopVersion.NAME}: UI thread unresponsive for ${stalledMs}ms")
            appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
            appendLine()
            Thread.getAllStackTraces().entries
                // The UI thread first: it is the one being asked about.
                .sortedByDescending { it.key.name.startsWith("AWT-EventQueue") }
                .forEach { (t, stack) ->
                    appendLine("\"${t.name}\" ${t.state}")
                    stack.forEach { appendLine("    at $it") }
                    appendLine()
                }
        }
        file.writeText(text)
    }

    private const val STALL_MS = 2_000L
}
