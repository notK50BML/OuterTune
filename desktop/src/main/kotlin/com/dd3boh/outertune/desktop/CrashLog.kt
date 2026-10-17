/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes crash-<time>.txt beside the library when something goes uncaught, then lets the crash
 * carry on as it would have. A window that vanishes leaves nothing behind otherwise - this is the
 * crash counterpart of [FreezeWatchdog].
 */
object CrashLog {
    fun install(directory: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                directory.mkdirs()
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(directory, "crash-$stamp.txt").writeText(
                    "OuterTune desktop ${DesktopVersion.NAME}: uncaught on \"${thread.name}\"\n" +
                        "${System.getProperty("os.name")} ${System.getProperty("os.version")}, " +
                        "Java ${System.getProperty("java.version")}\n\n$trace"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
