/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.utils

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.utils.lmScannerCoroutine
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The .m3u a playlist exports as. Lifted out of PlaylistMenu unchanged so that Liked songs and
 * Downloaded songs - which are not playlists in the database, and so never had the menu - write
 * exactly the same file as every other playlist: a local song by its id and path, anything else by
 * its YouTube link.
 */
fun buildM3u(songs: List<Song>): String {
    val result = StringBuilder("#EXTM3U\n")
    songs.forEach { s ->
        val se = s.song
        result.append("#EXTINF:${se.duration},${s.artists.joinToString(";") { it.name }} - ${s.title}\n")
        result.append(if (se.isLocal) "${se.id}, ${se.localPath}" else "https://youtube.com/watch?v=${se.id}")
        result.append("\n")
    }
    return result.toString()
}

/**
 * A launcher that asks where to save the .m3u and writes [songs] there. Launch it with the file
 * name to suggest, e.g. `launcher.launch("playlist.m3u")`.
 *
 * [songs] is read when the user has picked the file, not when the launcher is made, so a list that
 * was still loading when the menu opened is exported in full.
 */
@Composable
fun rememberM3uExportLauncher(songs: () -> List<Song>): ManagedActivityResultLauncher<String, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val toExport = songs()
        CoroutineScope(lmScannerCoroutine).launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(buildM3u(toExport).toByteArray(Charsets.UTF_8))
                }
            } catch (e: IOException) {
                reportException(e)
            }
        }
    }
}

/** A file name for [name]'s export: the characters a file system refuses swapped for "_". */
fun m3uFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "playlist" } + ".m3u"
