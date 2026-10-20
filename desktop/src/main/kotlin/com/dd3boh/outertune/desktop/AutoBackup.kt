/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Backups taken on a schedule, without being asked.
 *
 * The desktop keeps everything - plays, likes, playlists, settings - in one SQLite file, and until
 * now the only copy of it was the one somebody remembered to export. That is the wrong shape for a
 * single file: the moment it matters is the moment it is already gone, and a backup nobody took is
 * indistinguishable from no backup feature at all.
 *
 * Written beside the library rather than into Documents. A backup that lives next to what it backs
 * up is no use against a lost disk, but it is exactly what is wanted against the ordinary failures
 * - a bad import, a corrupted write, a library emptied by mistake - and it needs no folder to have
 * been chosen first, which is what stops this from being another thing to set up before it works.
 *
 * Old ones are pruned by count, oldest first. Unbounded automatic backups of a growing database are
 * a disk-filling bug with a reassuring name.
 */
object AutoBackup {

    private const val PREFIX = "OuterTune_desktop_"

    /** Where automatic backups go: a folder beside the library, made on first use. */
    fun folder(directory: File = defaultDataDirectory()): File =
        File(directory, "backups").apply { mkdirs() }

    /**
     * Takes one if enough time has passed, and prunes the oldest beyond [keep].
     *
     * Returns the backup it wrote, or null when none was due. The decision is made from the files
     * on disk rather than from a stored timestamp: a recorded "last run" and an actual backup can
     * disagree - a crash between the two, a folder cleared by hand - and the files are the thing
     * being relied on, so they are what the question should be asked of.
     */
    suspend fun runIfDue(
        library: LibraryStore,
        intervalHours: Int,
        keep: Int,
        directory: File = defaultDataDirectory(),
    ): File? = withContext(Dispatchers.IO) {
        val folder = folder(directory)
        val existing = existing(folder)
        val newest = existing.maxOfOrNull { it.lastModified() } ?: 0L
        val dueAt = newest + intervalHours.coerceAtLeast(1) * 3_600_000L
        if (newest != 0L && Instant.now().toEpochMilli() < dueAt) return@withContext null

        val written = runCatching { DesktopBackup.export(library, folder) }.getOrElse {
            // Reported by returning nothing. A backup that failed is worth knowing about, but not
            // worth taking the app down for, and the next start will try again.
            return@withContext null
        }
        prune(folder, keep)
        written
    }

    /** Automatic backups, newest first. */
    fun existing(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(".backup") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun prune(folder: File, keep: Int) {
        val all = existing(folder)
        if (all.size <= keep.coerceAtLeast(1)) return
        all.drop(keep.coerceAtLeast(1)).forEach { it.delete() }
    }
}
