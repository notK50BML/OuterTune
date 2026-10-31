/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.util.UUID

/**
 * One OuterTune at a time: starting it again while it is already running - most often hidden in the
 * tray, where it is easy to forget - brings the running one forward instead of opening a second.
 *
 * A second copy would be worse than a duplicate window. Both would play, both would write the same
 * library and settings files, and whichever closed last would overwrite what the other had saved.
 *
 * The first copy holds a lock on a file in the data folder for as long as it runs, and listens on a
 * loopback port for one word. A later copy finds the lock taken, sends that word to the port the
 * first one wrote down, and exits. The lock goes with the process however it ends, so a crash never
 * leaves OuterTune refusing to start. The port file carries a random token as well, so nothing else
 * on the machine that happens to find the port can raise the window.
 */
object SingleInstance {
    /** What the running copy does when another start hands over to it - see Main. */
    @Volatile var onActivate: () -> Unit = {}

    // Held for the life of the process: released, the lock would let a second copy start.
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * True when this is the only copy and should carry on starting. False when another is already
     * running: it has been asked to show itself, and this one should exit without opening anything.
     */
    fun claim(dataDirectory: File): Boolean {
        dataDirectory.mkdirs()
        val lockFile = File(dataDirectory, "instance.lock")
        val portFile = File(dataDirectory, "instance.port")
        val channel = runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull()
            // Nowhere to put the lock - a read-only folder, say. Running twice is the lesser harm
            // than not running at all.
            ?: return true
        val held = runCatching { channel.tryLock() }.getOrNull()
        if (held == null) {
            handOver(portFile)
            return false
        }
        lock = held
        listen(portFile)
        return true
    }

    private fun listen(portFile: File) {
        val token = UUID.randomUUID().toString()
        val socket = runCatching {
            ServerSocket().apply { bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4) }
        }.getOrNull() ?: return
        server = socket
        runCatching { portFile.writeText("${socket.localPort}\n$token") }
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                client.use {
                    it.soTimeout = 2_000
                    val said = runCatching { it.getInputStream().bufferedReader().readLine() }.getOrNull()
                    if (said == "show $token") onActivate()
                }
            }
        }.apply { isDaemon = true; name = "SingleInstance" }.start()
    }

    private fun handOver(portFile: File) {
        // Windows only lets the program the user just started bring a window forward, and that is
        // this one, not the copy in the tray. Passing the right on lets the running copy come to the
        // front rather than only flash its taskbar button.
        user32?.AllowSetForegroundWindow(ASFW_ANY)
        // The running copy writes its port as it starts, so a start straight after another can
        // arrive a moment before the file does: worth a few tries before giving up.
        repeat(10) {
            val (port, token) = runCatching {
                portFile.readLines().let { it[0].trim().toInt() to it[1].trim() }
            }.getOrNull() ?: run { Thread.sleep(200); return@repeat }
            val sent = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                    socket.getOutputStream().apply { write("show $token\n".toByteArray()); flush() }
                }
            }.isSuccess
            if (sent) return
            Thread.sleep(200)
        }
    }

    private interface User32 : StdCallLibrary {
        fun AllowSetForegroundWindow(processId: Int): Boolean
    }

    /** "Any process" - the running copy's id is not known here, only its port. */
    private const val ASFW_ANY = -1

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }
}
