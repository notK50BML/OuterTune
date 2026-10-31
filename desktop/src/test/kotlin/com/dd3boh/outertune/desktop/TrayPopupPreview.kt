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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface as SkSurface
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The tray icon's popup in the states worth looking at side by side - `OT_SNAPSHOTS=<folder>` only.
 *
 * The card alone, without its window: a real tray click cannot happen offscreen, but everything the
 * window shows is [TrayPopupSurface] around [TrayPopupCard], drawn here on a stand-in desktop so the
 * shadow and the hairline edge can be judged against something.
 */
class TrayPopupPreview {

    private fun cover(seed: Int): org.jetbrains.skia.Image {
        val surface = SkSurface.makeRasterN32Premul(256, 256)
        val palettes = listOf(
            intArrayOf(0xFF6A3093.toInt(), 0xFFA044FF.toInt(), 0xFFF7797D.toInt()),
            intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF5FC3E4.toInt()),
            intArrayOf(0xFFF4D03F.toInt(), 0xFF16A085.toInt(), 0xFF0B3D2E.toInt()),
        )
        val paint = Paint().apply { shader = Shader.makeLinearGradient(0f, 0f, 256f, 256f, palettes[seed % palettes.size]) }
        surface.canvas.drawRect(Rect.makeWH(256f, 256f), paint)
        surface.canvas.drawCircle(165f, 100f, 60f, Paint().apply { color = 0x55FFFFFF })
        return surface.makeImageSnapshot()
    }

    private val songs = listOf(
        "Midnight City" to "M83",
        "Instant Crush" to "Daft Punk, Julian Casablancas",
        "Strangers In The Night (Remastered 2008)" to "Frank Sinatra",
        "Retrograde" to "James Blake",
    ).mapIndexed { i, (title, artist) ->
        SongItem(
            id = "song$i",
            title = title,
            artists = artist.split(", ").map { Artist(name = it, id = "artist-$it") },
            thumbnail = "snapshot://cover$i",
            duration = 240,
        )
    }

    private fun queueAt(index: Int, shuffled: Boolean = false, repeat: RepeatMode = RepeatMode.OFF) = QueueState(
        songs = songs,
        index = index,
        shuffled = shuffled,
        repeat = repeat,
        order = songs.indices.toList(),
        orderPosition = index,
        title = "Liked songs",
    )

    private class Case(
        val label: String,
        val queue: QueueState,
        val playback: PlaybackState,
        val seed: Color = Color(0xFF7B3FC4),
        val dark: Boolean = true,
        val positionMs: Long = 110_000L,
        val live: Boolean = false,
        val pinned: Boolean = false,
    )

    @Test
    fun render() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        val covers = songs.indices.associate { "cover$it" to cover(it).toComposeImageBitmap() }
        ArtworkCache.substitute = { url -> covers.entries.firstOrNull { url.contains(it.key) }?.value }
        val appIcon = BitmapPainter(useResource("icon.png") { loadImageBitmap(it) })

        val cases = listOf(
            queueAt(1).let { Case("(a) Playing mid-queue", it, PlaybackState.Playing(it.current!!.title)) },
            queueAt(2).let { Case("(b) Paused", it, PlaybackState.Paused(it.current!!.title), positionMs = 40_000L) },
            queueAt(0).let { Case("(c) First song - no previous", it, PlaybackState.Playing(it.current!!.title), positionMs = 200_000L) },
            Case("(d) Nothing playing", QueueState(), PlaybackState.Idle),
            queueAt(1).let { Case("(e) Light theme", it, PlaybackState.Playing(it.current!!.title), dark = false) },
            queueAt(3, shuffled = true, repeat = RepeatMode.ONE).let {
                Case(
                    "(f) Another cover's colours - shuffle, repeat one",
                    it, PlaybackState.Playing(it.current!!.title), seed = Color(0xFF2C8C7A),
                )
            },
            queueAt(2, repeat = RepeatMode.ALL).let {
                Case("(g) Pinned as a widget - repeat all", it, PlaybackState.Playing(it.current!!.title), positionMs = 75_000L, pinned = true)
            },
            queueAt(1).let { Case("(h) A live stream - nothing to seek", it, PlaybackState.Playing(it.current!!.title), live = true) },
        )

        try {
            capture(out!!, "tray_popup", 2250, 1090) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    cases.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { case ->
                                MaterialTheme(colorScheme = DynamicTheme.schemeFor(case.seed, case.dark)) {
                                    Column {
                                        Text(
                                            case.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White.copy(alpha = 0.85f),
                                            modifier = Modifier.padding(start = 16.dp),
                                        )
                                        TrayPopupSurface {
                                            TrayPopupCard(
                                                playback = case.playback,
                                                queue = case.queue,
                                                live = case.live,
                                                positionMs = { case.positionMs },
                                                durationMs = 240_000L,
                                                appIcon = appIcon,
                                                onTogglePause = {},
                                                onNext = {},
                                                onPrevious = {},
                                                onSeek = {},
                                                onToggleShuffle = {},
                                                onCycleRepeat = {},
                                                pinned = case.pinned,
                                                onTogglePin = {},
                                                onShow = {},
                                                onQuit = {},
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            ArtworkCache.substitute = null
        }
    }

    private fun capture(out: File, name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        out.mkdirs()
        val scene = ImageComposeScene(width, height, Density(1.5f), Dispatchers.Unconfined) {
            // A stand-in for a desktop wallpaper, dark and a little busy, so the card's edge and
            // shadow have to hold their own as they would over a real one.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF1B2735), Color(0xFF3A4B5C), Color(0xFF6B5B7B)))),
            ) { content() }
        }
        try {
            // Long enough for the covers to arrive and every animation to come to rest.
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
