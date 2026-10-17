/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Backing up the desktop library, and restoring it - the phone's Backup and restore.
 *
 * A backup is a zip holding one file, library.db: songs, likes, playlists, history, settings,
 * cached lyrics, all of it, as SQLite's VACUUM INTO writes it - consistent even while the app is
 * running. Downloads are not included; they are files that can be fetched again.
 *
 * Restoring replaces the library, so it cannot happen under the open database. The backup's
 * library is put aside and swapped in at the next start, and the library it replaces is kept
 * beside it as library-before-restore.db, so a restore can itself be undone by hand.
 */
object DesktopBackup {

    private const val ENTRY = "library.db"
    private const val PENDING = "restore-pending.db"

    /** Writes a backup into [folder]; returns the file written. */
    suspend fun export(library: LibraryStore, folder: File): File = withContext(Dispatchers.IO) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val zip = File(folder, "OuterTune_desktop_$stamp.backup")
        val copy = File.createTempFile("outertune-export", ".db")
        try {
            library.database.exportTo(copy)
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry(ENTRY))
                copy.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        } finally {
            copy.delete()
        }
        zip
    }

    /** Whether [file] is a desktop backup rather than a phone one. */
    fun isDesktopBackup(file: File): Boolean =
        runCatching { ZipFile(file).use { it.getEntry(ENTRY) != null } }.getOrDefault(false)

    /** Puts [file]'s library aside to replace the current one at the next start. */
    suspend fun stageRestore(file: File, directory: File = defaultDataDirectory()) = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(ENTRY) ?: error("That backup holds no library.")
            File(directory, PENDING).outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
        }
    }

    /**
     * Called before the database opens: swaps in a staged restore if there is one, and returns
     * the library file to open either way.
     */
    fun applyPendingRestore(directory: File): File {
        val library = File(directory, ENTRY)
        val pending = File(directory, PENDING)
        if (!pending.isFile) return library
        directory.mkdirs()
        val kept = File(directory, "library-before-restore.db")
        kept.delete()
        if (library.isFile) library.renameTo(kept)
        // The old write-ahead log belongs to the library just put aside, not to the restored one.
        File(directory, "$ENTRY-wal").delete()
        File(directory, "$ENTRY-shm").delete()
        pending.renameTo(library)
        return library
    }
}
