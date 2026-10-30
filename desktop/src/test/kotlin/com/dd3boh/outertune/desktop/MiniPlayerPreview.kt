/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
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

/** The mini player in the states worth looking at side by side - `OT_SNAPSHOTS=<folder>` only. */
class MiniPlayerPreview {

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
        "Holocene" to "Bon Iver",
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

    private data class Case(
        val label: String,
        val queue: QueueState,
        val playback: PlaybackState,
        val buttons: List<MiniPlayerButtonKind>,
        val liked: Boolean = false,
        val volume: Float = 0.8f,
        val sleepRemainingMs: Long? = null,
    )

    @Test
    fun render() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        val covers = songs.indices.associate { "cover$it" to cover(it).toComposeImageBitmap() }
        ArtworkCache.substitute = { url -> covers.entries.firstOrNull { url.contains(it.key) }?.value }

        val all = MiniPlayerButtonKind.entries.toList()
        val playing = { q: QueueState -> PlaybackState.Playing(q.current!!.title) }
        val cases = listOf(
            queueAt(2).let { Case("(a) Playing mid-queue - default extras (queue only)", it, playing(it), MiniPlayerButtonKind.decode("")) },
            queueAt(1).let { Case("(b) All extras on", it, playing(it), all) },
            queueAt(1, shuffled = true, repeat = RepeatMode.ONE).let {
                Case(
                    "(b2) All extras - liked, shuffle and repeat one on, quiet, sleep timer running",
                    it, playing(it), all, liked = true, volume = 0.3f, sleepRemainingMs = 24 * 60_000L + 13_000L,
                )
            },
            queueAt(0).let { Case("(c) First song - no previous", it, playing(it), MiniPlayerButtonKind.decode("")) },
            queueAt(4).let { Case("(d) Last song - no next", it, playing(it), MiniPlayerButtonKind.decode("")) },
            queueAt(3).let { Case("(e) Loading", it, PlaybackState.Loading(it.current!!.title), MiniPlayerButtonKind.decode("")) },
            Case("(f) Paused, nothing chosen", queueAt(3), PlaybackState.Paused(songs[3].title), emptyList()),
        )

        try {
            capture(out!!, "mini_player", 1350, 1180) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    cases.forEach { case ->
                        Text(
                            case.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PlayerBar(
                            playback = case.playback,
                            queue = case.queue,
                            positionMs = { 110_000L },
                            durationMs = 240_000L,
                            liked = case.liked,
                            onTogglePause = {},
                            onNext = {},
                            onPrevious = {},
                            onToggleLike = {},
                            onExpand = {},
                            queueOpen = true,
                            buttons = case.buttons,
                            volume = case.volume,
                            sleepRemainingMs = case.sleepRemainingMs,
                        )
                    }
                }
            }
        } finally {
            ArtworkCache.substitute = null
        }
    }

    /** Its editor in Settings, with a few extras chosen. */
    @Test
    fun dialog() {
        val out = System.getenv("OT_SNAPSHOTS")?.let(::File)
        assumeTrue(out != null)
        capture(out!!, "mini_player_buttons_dialog", 1050, 1230) {
            MiniPlayerButtonsDialog(current = MiniPlayerButtonKind.decode("CVQ"), onSave = {}, onDismiss = {})
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
