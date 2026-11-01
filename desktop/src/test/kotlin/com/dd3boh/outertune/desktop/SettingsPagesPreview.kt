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
 * The category list and the busiest settings pages, after the reorganisation - `OT_SNAPSHOTS=<folder>`
 * only. Tall enough that each page shows whole, and each category clicked in the list the way
 * someone would, as SettingsContentPreview does.
 */
class SettingsPagesPreview {

    @Test
    fun render() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        val library = LibraryStore(java.nio.file.Files.createTempDirectory("ot-settings-pages").toFile())
        val settings = Settings(library.settings)
        val scene = ImageComposeScene(1180, 2100, Density(1f), Dispatchers.Unconfined) {
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
            fun save(name: String) =
                File(out, name).writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            settle()
            // Opens on Appearance, beside the whole category list.
            save("settings_appearance.png")
            listOf(
                SettingsCategory.Player to "settings_player_and_audio.png",
                SettingsCategory.Lyrics to "settings_lyrics.png",
                SettingsCategory.Content to "settings_library_and_content.png",
                SettingsCategory.System to "settings_system.png",
                SettingsCategory.Backup to "settings_backup.png",
            ).forEach { (category, name) ->
                // The category list has a row every 64px from the first.
                val at = Offset(140f, 172f + 64f * category.ordinal)
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
                settle()
                save(name)
            }
        } finally {
            scene.close()
        }
    }
}
