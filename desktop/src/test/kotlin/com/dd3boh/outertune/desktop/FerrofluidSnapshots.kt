/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Stills of the ferrofluid at a few field strengths. Written only with OT_SNAPSHOTS set. */
class FerrofluidSnapshots {

    @Test
    fun shaderCompiles() {
        assertTrue("the SkSL should compile", ferrofluidAvailable)
    }

    @Test
    fun stills() {
        val dir = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(dir != null)
        dir!!.mkdirs()
        for ((name, frame) in listOf(
            "ferro_calm" to FerrofluidFrame(time = 3f, field = 0.05f),
            "ferro_rising" to FerrofluidFrame(time = 5f, field = 0.45f, shimmer = 0.2f, leanX = 0.02f),
            "ferro_peak" to FerrofluidFrame(time = 7f, field = 1.1f, shimmer = 0.5f, leanX = -0.03f, leanZ = 0.02f),
        )) {
            val scene = ImageComposeScene(1000, 520, Density(1f), Dispatchers.Unconfined) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A2A5C), Color(0xFF120C1E))))) {
                    FerrofluidScene(
                        frame,
                        keyColor = Color(0xFFF4F1FF),
                        rimColor = Color(0xFFB98CFF),
                        baseColor = Color(0xFF3A2A5C),
                        renderScale = 1f,
                    )
                }
            }
            File(dir, "$name.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
