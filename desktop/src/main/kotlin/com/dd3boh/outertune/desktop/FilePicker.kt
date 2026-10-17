/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser

/**
 * Choosing files and folders with the system's own picker.
 *
 * On Windows that is the modern Explorer-style picker (IFileOpenDialog) - the one every other app
 * opens, with the sidebar, search and folder picking. Java cannot reach it directly: FileDialog
 * shows the older common dialog and JFileChooser is drawn by Swing. So a few lines of C#
 * (resources/picker/OtPicker.cs) call it through COM, compiled once by PowerShell - which every
 * Windows ships - into the app's data folder, and run from there each time.
 *
 * Elsewhere, or if anything about that fails, the Java dialogs stand in: a picker that looks old is
 * better than one that does not open.
 *
 * Every call is suspending and runs off the UI thread. The picker is modal to the user, not to the
 * app, and blocking the UI thread on it would leave the window unpainted behind it.
 */
object FilePicker {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val assembly by lazy { File(defaultDataDirectory(), "picker/OtPicker-$PICKER_VERSION.dll") }

    /**
     * Compiles the picker ahead of time, on a background thread. The first compile is slow - tens of
     * seconds on some machines - and should never be the thing a click waits for.
     */
    fun warmUp() {
        if (!isWindows) return
        Thread({ runCatching { ensureCompiled() } }, "picker-warmup").apply { isDaemon = true }.start()
    }

    suspend fun pickFolders(title: String): List<File> = withContext(Dispatchers.IO) {
        windowsPick(folders = true, multi = true, title = title, filterName = "", filterSpec = "")
            ?: swingFolders(title)
    }

    suspend fun pickFile(title: String, filterName: String = "", filterSpec: String = ""): File? =
        withContext(Dispatchers.IO) {
            (windowsPick(folders = false, multi = false, title = title, filterName = filterName, filterSpec = filterSpec)
                ?: awtFile(title)).firstOrNull()
        }

    /** The modern picker's choice, an empty list if cancelled, or null if it could not be shown. */
    private fun windowsPick(folders: Boolean, multi: Boolean, title: String, filterName: String, filterSpec: String): List<File>? {
        if (!isWindows) return null
        return runCatching {
            if (!ensureCompiled()) return null
            fun q(s: String) = "'" + s.replace("'", "''") + "'"
            val script = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; " +
                "Add-Type -Path ${q(assembly.absolutePath)}; " +
                "[OtPicker]::Pick(\$$folders, \$$multi, ${q(title)}, ${q(filterName)}, ${q(filterSpec)})"
            val process = ProcessBuilder("powershell", "-NoProfile", "-STA", "-NonInteractive", "-Command", script)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()
            if (process.exitValue() != 0) return null
            output.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::File)
        }.getOrNull()
    }

    @Synchronized
    private fun ensureCompiled(): Boolean {
        if (assembly.isFile) return true
        val source = FilePicker::class.java.getResourceAsStream("/picker/OtPicker.cs")?.readBytes() ?: return false
        assembly.parentFile.mkdirs()
        val cs = File(assembly.parentFile, "OtPicker.cs").apply { writeBytes(source) }
        val partial = File(assembly.parentFile, "OtPicker-building.dll").apply { delete() }
        val script = "Add-Type -Path '${cs.absolutePath}' -OutputAssembly '${partial.absolutePath}' -OutputType Library"
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        if (!process.waitFor(3, TimeUnit.MINUTES)) {
            process.destroy()
            return false
        }
        // Renamed into place only once whole, so an interrupted compile is never mistaken for one.
        return partial.isFile && partial.renameTo(assembly)
    }

    private fun swingFolders(title: String): List<File> {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = true
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }
        } else {
            emptyList()
        }
    }

    private fun awtFile(title: String): List<File> {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return emptyList()
        val name = dialog.file ?: return emptyList()
        return listOf(File(directory, name))
    }

    /** Bumped when OtPicker.cs changes, so a stale compiled copy is not reused. */
    private const val PICKER_VERSION = 1
}
