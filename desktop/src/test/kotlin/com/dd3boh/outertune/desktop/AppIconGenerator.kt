/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Path
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the phone's launcher icon (drawable/launcher_foreground on its white background) into the
 * Windows icon the packaged app uses, and the PNG the window shows. Run with OT_GEN_APPICON set to
 * the repository root; the results are committed, so ordinary builds never run this.
 */
class AppIconGenerator {

    /** launcher_foreground.xml: (alpha, colour, path), in its 180-unit viewport. */
    private val layers = listOf(
        Triple(0.4f, 0xFFC04450.toInt(), "M76.37,53.25C68.78,48.91 62.63,52.47 62.63,61.19v64.35c0,8.73 6.15,12.28 13.75,7.94l56.43,-32.25c7.59,-4.34 7.59,-11.38 0,-15.72z"),
        Triple(1f, 0xFFED5564.toInt(), "m74.21,49.26c-7.59,-4.34 -13.75,-0.78 -13.75,7.93v64.35c0,8.73 6.15,12.28 13.75,7.94L130.64,97.23c7.59,-4.34 7.59,-11.38 0,-15.72z"),
        Triple(0.9f, -1, "m114.74,86.35l0,6.04a1.51,2.08 90,0 0,4.15 0L118.89,86.35a1.51,2.08 90,0 0,-4.15 0z"),
        Triple(0.9f, -1, "M106.9,77.54L106.9,101.2a1.97,2.08 90,0 0,4.15 0L111.05,77.54a1.97,2.08 90,0 0,-4.15 0z"),
        Triple(0.9f, -1, "m99.06,84.77l0,9.2a2.3,2.08 90,0 0,4.15 0L103.21,84.77a2.3,2.08 90,0 0,-4.15 0z"),
        Triple(0.9f, -1, "m91.22,78.39l0,21.96a1.83,2.08 90,0 0,4.15 0L95.37,78.39a1.83,2.08 90,0 0,-4.15 0z"),
        Triple(0.9f, -1, "m83.38,71.45l0,35.84a1.79,2.08 90,0 0,4.15 0L87.53,71.45a1.79,2.08 90,0 0,-4.15 0z"),
        Triple(0.9f, -1, "m75.54,79.57l0,19.6a1.63,2.08 90,0 0,4.15 0L79.69,79.57a1.63,2.08 90,0 0,-4.15 0z"),
        Triple(0.9f, -1, "m67.7,85.82l0,7.1a1.77,2.08 90,0 0,4.15 0L71.85,85.82a1.77,2.08 90,0 0,-4.15 0z"),
    )

    private fun render(size: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(size, size)
        val canvas = surface.canvas
        canvas.clear(Color.TRANSPARENT)
        val s = size.toFloat()
        // The white tile, rounded like a Windows 11 app icon.
        canvas.drawRRect(RRect.makeXYWH(0f, 0f, s, s, s * 0.22f), Paint().apply { color = Color.WHITE; isAntiAlias = true })
        // An adaptive icon shows the middle 72 of its 108; in the 180 viewport that is 30..150.
        canvas.save()
        canvas.scale(s / 120f, s / 120f)
        canvas.translate(-30f, -30f)
        for ((alpha, colour, data) in layers) {
            val path = Path.makeFromSVGString(data)
            canvas.drawPath(path, Paint().apply {
                color = colour
                this.alpha = (alpha * 255).toInt()
                isAntiAlias = true
            })
        }
        canvas.restore()
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test
    fun generate() {
        val root = System.getenv("OT_GEN_APPICON")?.let(::File)
        assumeTrue(root != null)
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val pngs = sizes.map(::render)

        // ICO with PNG-compressed entries, which Windows has read since Vista.
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(6 + 16 * sizes.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = 6 + 16 * sizes.size
        sizes.forEachIndexed { i, size ->
            header.put((size % 256).toByte()).put((size % 256).toByte()).put(0).put(0)
            header.putShort(1).putShort(32).putInt(pngs[i].size).putInt(offset)
            offset += pngs[i].size
        }
        out.write(header.array())
        pngs.forEach(out::write)

        File(root!!, "desktop/packaging").mkdirs()
        File(root, "desktop/packaging/OuterTune.ico").writeBytes(out.toByteArray())
        File(root, "desktop/src/main/resources/icon.png").writeBytes(pngs.last())
    }
}
