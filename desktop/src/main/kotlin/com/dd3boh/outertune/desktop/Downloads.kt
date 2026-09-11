/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import java.io.File

/**
 * Songs kept on disk, so they play without the network.
 *
 * The player already fetches a whole MP4 into memory before it decodes anything, so a download is
 * that same fetch written to a file rather than a second, parallel way of getting audio. Playing a
 * downloaded song is then a file read instead of a request, and everything downstream - the
 * demuxer, the decoder, the equaliser - is unchanged and cannot tell the difference.
 *
 * Files are named by video id and nothing else. Titles change, get re-tagged and contain characters
 * a filesystem will not take; the id is stable, unique, and safe as a filename everywhere.
 *
 * The database row is the index and the file is the truth. Every read checks the file still exists,
 * because someone clearing out a folder is a perfectly reasonable thing to do and should leave the
 * app correct rather than convinced it has a song it cannot play.
 */
class Downloads(
    private val directory: File,
    private val database: Database,
) {

    init {
        directory.mkdirs()
    }

    fun fileFor(videoId: String) = File(directory, "$videoId.m4a")

    /** Whether [videoId] is downloaded and its file is actually there. */
    fun has(videoId: String): Boolean {
        if (database.download(videoId) == null) return false
        if (fileFor(videoId).isFile) return true
        // The row outlived its file. Cleared rather than reported, so the next request downloads it
        // again instead of failing forever on a song the app insists it already has.
        database.deleteDownload(videoId)
        return false
    }

    /**
     * Writes [bytes] for [videoId].
     *
     * Written to a temporary file and then moved into place. A download interrupted halfway - the
     * app quitting, the machine sleeping - would otherwise leave a truncated file that looks
     * downloaded and fails to decode, which is far harder to recover from than a missing one.
     */
    fun save(videoId: String, bytes: ByteArray): Result<Unit> = runCatching {
        val target = fileFor(videoId)
        val partial = File(directory, "$videoId.part")
        partial.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            // Rename can fail across some filesystems; copying is slower but always works.
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        database.putDownload(videoId, target.length())
    }

    fun read(videoId: String): ByteArray? =
        if (has(videoId)) runCatching { fileFor(videoId).readBytes() }.getOrNull() else null

    fun delete(videoId: String) {
        runCatching { fileFor(videoId).delete() }
        database.deleteDownload(videoId)
    }

    /** Every downloaded song id, skipping any whose file has gone. */
    fun ids(): List<String> = database.downloadIds().filter { has(it) }

    /** Total bytes on disk, for the settings screen to report. */
    fun totalBytes(): Long = ids().sumOf { fileFor(it).length() }

    fun deleteAll() {
        database.downloadIds().forEach { delete(it) }
    }

    companion object {
        /** Human-readable size, since "1503238553 bytes" is not an answer to "how much space?". */
        fun formatSize(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.0f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}
