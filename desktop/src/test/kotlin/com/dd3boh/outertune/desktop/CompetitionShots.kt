/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Showcase screenshots of the player in very different configurations, with real songs and real
 * covers. Run with OT_SHOTS set to a folder (needs the network). 1920x1080 at 125%.
 *
 * Lyrics shown are Amazing Grace (John Newton, 1779) - public domain - never a copyrighted song's.
 */
class CompetitionShots {

    private val dir = System.getenv("OT_SHOTS")?.let(::File)

    private fun find(query: String): SongItem = runBlocking {
        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            .items.filterIsInstance<SongItem>().first()
    }

    private val amazingGrace = Lyrics(
        lines = listOf(
            "Amazing grace! How sweet the sound" to 0L,
            "That saved a wretch like me!" to 6_000L,
            "I once was lost, but now am found;" to 12_000L,
            "Was blind, but now I see." to 18_000L,
            "" to 24_000L,
            "'Twas grace that taught my heart to fear," to 27_000L,
            "And grace my fears relieved;" to 33_000L,
            "How precious did that grace appear" to 39_000L,
            "The hour I first believed." to 45_000L,
        ).map { (text, at) -> LyricLine(at, text) },
        synced = true,
        source = "Public domain",
    )

    private fun shot(name: String, settleFrames: Int = 50, pauseMs: Long = 80, content: @Composable () -> Unit) {
        val out = dir ?: return
        out.mkdirs()
        if (File(out, "$name.png").exists()) return
        ArtworkCache.substitute = null
        val scene = ImageComposeScene(1920, 1080, Density(1.25f), Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), true)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlaybackActive provides true) { content() }
                }
            }
        }
        var t = 0L
        repeat(settleFrames) {
            scene.render(t)
            t += 50_000_000L
            // Real covers arrive over the network; give them time.
            Thread.sleep(pauseMs)
        }
        File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    private fun spectrumTap(): VisualizerTap = VisualizerTap(VISUALIZER_BANDS).apply {
        submit(0L, FloatArray(VISUALIZER_BANDS) { i ->
            val x = i / (VISUALIZER_BANDS - 1f)
            (0.95f - 0.55f * x + 0.18f * kotlin.math.sin(i * 1.7f)).coerceIn(0.08f, 1f)
        })
    }

    @Composable
    private fun Player(
        songs: List<SongItem>,
        index: Int = 0,
        positionMs: Long = 74_000L,
        background: BackgroundStyle = BackgroundStyle.Gradient,
        buttons: String = "",
        seekBar: SeekBarStyle = SeekBarStyle.Squiggly,
        command: PlayerCommand? = null,
        visualiser: Boolean = false,
        ferro: Boolean = false,
        lyrics: Lyrics? = null,
        lyricsScale: Int = 100,
        eqOn: Boolean = false,
    ) {
        val tap = remember { spectrumTap() }
        val song = songs[index]
        NowPlayingScreen(
            playback = PlaybackState.Playing(song.title),
            queue = QueueState(
                songs = songs, index = index, order = songs.indices.toList(), orderPosition = index,
                title = "Weekend mix",
            ),
            positionMs = { positionMs },
            durationMs = (song.duration ?: 220) * 1000L,
            liked = true,
            onSeek = {}, onTogglePause = {}, onNext = {}, onPrevious = {},
            onToggleLike = {}, onToggleShuffle = {}, onCycleRepeat = {}, onClose = {},
            actions = PlayerActions(onStartRadio = {}, onAddToQueue = {}, onShare = {}, onEqualizer = {}),
            lyrics = lyrics,
            onDownload = {},
            onSleepTimer = {},
            equalizer = remember {
                Equalizer().apply { enabled = eqOn; if (eqOn) EqProfiles.factoryDefault("Rock")?.let(::setBands) }
            },
            compressor = remember { Compressor().apply { enabled = eqOn } },
            balance = Balance(),
            timeStretch = TimeStretch(),
            playerButtons = PlayerButtonKind.decode(buttons),
            backgroundStyle = background,
            seekBarStyle = seekBar,
            spectrum = if (visualiser) tap else null,
            liquidTap = if (ferro) tap else null,
            playedFrames = { 1L },
            command = command,
            lyricsTextScale = lyricsScale,
        )
    }

    @Test
    fun shots() {
        assumeTrue(dir != null)
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        YouTube.visitorData = runBlocking { YouTube.visitorData().getOrThrow() }
        val mix = listOf(
            "Midnight City M83", "Blinding Lights The Weeknd", "Get Lucky Daft Punk",
            "Take On Me a-ha", "Dreams Fleetwood Mac", "Electric Feel MGMT", "Redbone Childish Gambino",
            "Levitating Dua Lipa",
        ).map(::find)
        val grace = find("Amazing Grace Judy Collins")

        // The player as it comes: gradient from the cover, squiggly seek bar, the default buttons.
        shot("1_default") { Player(mix, index = 0) }

        // Everything on: ferrofluid, the visualiser, five buttons and the queue docked beside it.
        shot("2_everything", settleFrames = 34, pauseMs = 200) {
            Player(
                mix, index = 1, background = BackgroundStyle.Ferrofluid, buttons = "SLDEM",
                visualiser = true, ferro = true, command = PlayerCommand.ToggleQueue,
            )
        }

        // The equaliser open over the player, a curve dialled in and the compressor on.
        shot("3_equaliser") {
            Player(mix, index = 2, eqOn = true, command = PlayerCommand.ToggleEqualiser)
        }

        // Minimal: no cover - just the words, a flat colour, a slim line and one button.
        shot("4_minimal") {
            Player(
                listOf(grace) + mix, index = 0, positionMs = 13_000L, background = BackgroundStyle.Solid,
                buttons = "L", seekBar = SeekBarStyle.Slim, lyrics = amazingGrace, lyricsScale = 130,
                command = PlayerCommand.ToggleLyrics,
            )
        }

        // Frosted glass over the cover, with the queue docked.
        shot("5_frosted") {
            Player(
                mix, index = 3, background = BackgroundStyle.Frosted, buttons = "LYM",
                seekBar = SeekBarStyle.Default, command = PlayerCommand.ToggleQueue,
            )
        }
    }
}
