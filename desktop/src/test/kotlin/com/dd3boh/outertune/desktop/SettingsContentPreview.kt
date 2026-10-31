/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Library and content, now that local media lives in it - `OT_SNAPSHOTS=<folder>` only.
 *
 * The pane opens on Appearance, so this clicks the category in the list the way someone would,
 * rather than reaching past the pane for the page underneath.
 */
class SettingsContentPreview {

    @Test
    fun render() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        val library = LibraryStore(java.nio.file.Files.createTempDirectory("ot-settings-content").toFile())
        val settings = Settings(library.settings)
        // A couple of folders, so the page shows what it looks like in use rather than empty.
        settings.localFolders = listOf("C:\\Users\\you\\Music", "D:\\FLAC rips").joinToString("\n")
        val scene = ImageComposeScene(1180, 1250, Density(1f), Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        SettingsPane(
                            settings = settings,
                            account = Account(library.database),
                            listenTogether = ListenTogetherManager(),
                            libraryPath = "C:\\Users\\you\\AppData\\Local\\OuterTune\\library.db",
                            onBack = {},
                            onClearLyricsCache = {},
                            downloads = library.downloads,
                            onDownloadsChanged = {},
                            library = library,
                        )
                    }
                }
            }
        }
        try {
            var t = 0L
            fun settle() = repeat(30) {
                scene.render(t)
                t += 50_000_000L
                Thread.sleep(10)
            }
            settle()
            // The category list has a row every 64px from the first; Library and content is the fifth.
            val at = Offset(140f, 172f + 64f * SettingsCategory.Content.ordinal)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            settle()
            val image = scene.render(t)
            File(out, "settings_library_and_content.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
