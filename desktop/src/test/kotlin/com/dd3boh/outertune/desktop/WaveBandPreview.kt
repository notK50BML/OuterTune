/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The Waves and Wave lines seek bars, a few moments of each - `OT_SNAPSHOTS=<folder>` only. */
class WaveBandPreview {
    @Test
    fun render() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        ImageComposeScene(760, 620).use { scene ->
            scene.setContent {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color(0xFF5B2A86), Color(0xFF2A1640)))),
                ) {
                    Column(modifier = Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        data class Row(val label: String, val filled: Boolean, val thin: Boolean, val progress: Float, val t: Float)
                        listOf(
                            Row("Waves", true, false, 0.46f, 0f),
                            Row("Waves", true, false, 0.46f, 0.6f),
                            Row("Waves, thinner", true, true, 0.46f, 0.3f),
                            Row("Waves, 2 seconds into 4:00", true, false, 2f / 240f, 0.3f),
                            Row("Wave lines", false, false, 0.46f, 0.5f),
                            Row("Wave lines, thinner", false, true, 0.46f, 0.5f),
                        ).forEach { row ->
                            val filled = row.filled
                            val t = row.t
                            Column {
                                Text(
                                    row.label,
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Box(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                                    // The unplayed rest of the bar, thin and flat, as in the player.
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(3.dp).align(androidx.compose.ui.Alignment.BottomCenter)
                                            .padding(bottom = 1.5.dp).background(Color.White.copy(alpha = 0.22f)),
                                    )
                                    AccentWaves(
                                        progress = row.progress,
                                        colour = Color(0xFFE7C6FF),
                                        timeSeconds = t,
                                        energy = 0.85f,
                                        filled = filled,
                                        thin = row.thin,
                                        modifier = Modifier.fillMaxWidth().height(30.dp),
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                    Text(if (row.progress < 0.1f) "0:02" else "1:50", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                    Text("4:00", color = Color.White, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            val image = scene.render()
            File(out, "wave_band.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
