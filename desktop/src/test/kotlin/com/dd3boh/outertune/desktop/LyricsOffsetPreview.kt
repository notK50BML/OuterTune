/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The per-song lyrics timing offset, as the lyrics menu opens it - `OT_SNAPSHOTS=<folder>` only. */
class LyricsOffsetPreview {

    /** The control at a few values side by side, each on a panel the size of the dialog's. */
    @Test
    fun control() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        capture(out!!, "lyrics_offset_control", 1800, 560) {
            Row(modifier = Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(250 to "(a) +250 ms", -100 to "(b) −100 ms", 0 to "(c) none").forEach { (ms, label) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            tonalElevation = 6.dp,
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            LyricsOffsetControl(ms, {}, modifier = Modifier.width(360.dp).padding(16.dp))
                        }
                    }
                }
            }
        }
    }

    /** The whole dialog, over lyrics, as it shows in the player. */
    @Test
    fun dialog() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        capture(out!!, "lyrics_offset_dialog", 1050, 820) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf("We were never meant to wait", "for the night to fall", "Every light along the water", "is a word I left unsaid")
                        .forEachIndexed { i, line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (i == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                }
                LyricsOffsetDialog(valueMs = 250, onChange = {}, onDismiss = {})
            }
        }
    }

    private fun capture(out: File, name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        out.mkdirs()
        val scene = ImageComposeScene(width, height, Density(1.5f), Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), true)) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        try {
            var t = 0L
            repeat(30) {
                scene.render(t)
                t += 50_000_000L
                Thread.sleep(10)
            }
            val image = scene.render(t)
            File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
