/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
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
 * Renders the desktop screens offscreen to PNGs, for looking at rather than asserting on.
 *
 * Skipped unless OT_SNAPSHOTS names a directory, so it costs nothing in an ordinary test run. There
 * is no window and no network: covers are drawn here and handed to the artwork cache directly.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class UiSnapshots {

    private val outDir = System.getenv("OT_SNAPSHOTS")?.let(::File)

    private fun cover(seed: Int): org.jetbrains.skia.Image {
        val surface = SkSurface.makeRasterN32Premul(512, 512)
        val palettes = listOf(
            intArrayOf(0xFF6A3093.toInt(), 0xFFA044FF.toInt(), 0xFFF7797D.toInt()),
            intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF5FC3E4.toInt()),
            intArrayOf(0xFFF4D03F.toInt(), 0xFF16A085.toInt(), 0xFF0B3D2E.toInt()),
        )
        val paint = Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 512f, 512f, palettes[seed % palettes.size])
        }
        surface.canvas.drawRect(Rect.makeWH(512f, 512f), paint)
        surface.canvas.drawCircle(330f, 200f, 120f, Paint().apply { color = 0x55FFFFFF })
        return surface.makeImageSnapshot()
    }

    private val songs = listOf(
        "Midnight City" to "M83",
        "Strangers In The Night (Remastered 2008)" to "Frank Sinatra",
        "Instant Crush" to "Daft Punk, Julian Casablancas",
        "Retrograde" to "James Blake",
        "Holocene" to "Bon Iver",
        "Motion Sickness" to "Phoebe Bridgers",
        "Nights" to "Frank Ocean",
        "Dissolve" to "Absofacto",
    ).mapIndexed { i, (title, artist) ->
        SongItem(
            id = "song$i",
            title = title,
            artists = artist.split(", ").map { Artist(name = it, id = "artist-$it") },
            thumbnail = "snapshot://cover$i",
            duration = 240,
        )
    }

    private val lyrics = Lyrics(
        lines = listOf(
            "Waiting in the car" to 0L,
            "Waiting for a ride in the dark" to 4_000L,
            "The night city grows" to 8_000L,
            "Look and see her eyes, they glow" to 12_000L,
            "Waiting in the car" to 16_000L,
            "Waiting for the right time" to 20_000L,
            "♪" to 24_000L,
            "The city is my church" to 28_000L,
            "It wraps me in the sparkling twilight" to 32_000L,
        ).map { (text, at) -> LyricLine(at, text) },
        synced = true,
        source = "Snapshot",
    )

    private fun queue(index: Int = 0) = QueueState(
        songs = songs,
        index = index,
        order = songs.indices.toList(),
        orderPosition = index,
        title = "Liked songs",
    )

    private fun capture(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = true,
        liveArt: Boolean = false,
        settleFrames: Int = 40,
        interact: (ImageComposeScene) -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        val dir = outDir ?: return
        dir.mkdirs()
        val covers = (0 until songs.size).associate { "cover$it" to cover(it).toComposeImageBitmap() }
        ArtworkCache.substitute = if (liveArt) null else { url -> covers.entries.firstOrNull { url.contains(it.key) }?.value }
        val scene = ImageComposeScene(width, height, Density(1f), Dispatchers.Unconfined) {
            val scheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), dark)
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        var t = 0L
        fun settle() = repeat(settleFrames) {
            scene.render(t)
            t += 50_000_000L
            Thread.sleep(15)
        }
        settle()
        interact(scene)
        settle()
        val image = scene.render(t)
        File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }

    private fun ImageComposeScene.click(x: Float, y: Float) {
        sendPointerEvent(PointerEventType.Move, Offset(x, y))
        render(1_000_000_000L)
        sendPointerEvent(PointerEventType.Press, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        render(1_050_000_000L)
        sendPointerEvent(PointerEventType.Release, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        render(1_100_000_000L)
    }

    @Composable
    private fun Player(
        width: Int,
        eqOn: Boolean = false,
        buttons: String = "",
        background: BackgroundStyle = BackgroundStyle.Gradient,
        liquidTap: VisualizerTap? = null,
        layout: PlayerLayout? = null,
        highlight: LyricsHighlight = LyricsHighlight.Fade,
        kind: LyricsKind? = null,
        shownLyrics: Lyrics = lyrics,
    ) {
        NowPlayingScreen(
            playback = PlaybackState.Playing(songs[0].title),
            queue = queue(),
            positionMs = { 13_500L },
            durationMs = 240_000,
            liked = true,
            onSeek = {}, onTogglePause = {}, onNext = {}, onPrevious = {},
            onToggleLike = {}, onToggleShuffle = {}, onCycleRepeat = {}, onClose = {},
            actions = PlayerActions(onStartRadio = {}, onAddToQueue = {}, onShare = {}),
            lyrics = shownLyrics,
            lyricsHighlight = highlight,
            lyricsKind = kind,
            onDownload = {},
            onSleepTimer = {},
            equalizer = remember { Equalizer().apply { enabled = eqOn; if (eqOn) EqProfiles.factoryDefault("Rock")?.let(::setBands) } },
            compressor = remember { Compressor().apply { enabled = eqOn } },
            balance = Balance(),
            timeStretch = TimeStretch(),
            playerButtons = PlayerButtonKind.decode(buttons),
            backgroundStyle = background,
            liquidTap = liquidTap,
            playedFrames = { 1L },
            playerLayout = layout,
        )
    }

    @Test
    fun songMenu() {
        assumeTrue(outDir != null)
        val actions = SongActions({}, {}, {}, {}, {}, { it == "song1" }, {}, { false }, {}, {}, {})
        capture("song_menu", 900, 640, interact = { scene -> scene.click(862f, 46f) }) {
            CompositionLocalProvider(LocalSongActions provides actions, LocalRowButtons provides RowButtons(listOf(RowButton.LIKE))) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    songs.take(5).forEachIndexed { i, song -> SongRow(song, playing = i == 1) {} }
                }
            }
        }
    }

    @Test
    fun artistPicker() {
        assumeTrue(outDir != null)
        val artists = listOf(
            StoredArtist("UCdp", "Daft Punk"),
            StoredArtist("UCjc", "Julian Casablancas"),
            StoredArtist.unlinked("The Voidz"),
        )
        // On its own, as the player's artist button opens it.
        capture("artist_picker", 560, 420) {
            Box(modifier = Modifier.padding(start = 380.dp, top = 24.dp).size(40.dp)) {
                ArtistPickerPanel(artists, onPick = {}, onDismiss = {})
            }
        }
        // From a song's ⋮, clicked through: "Go to artist" on a two-artist song turns the panel
        // into the list, and "← Back" returns to the tiles.
        val actions = SongActions({}, {}, {}, {}, {}, { false }, {}, { false }, {}, {}, {})
        val rows: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSongActions provides actions, LocalRowButtons provides RowButtons(listOf(RowButton.LIKE))) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    listOf(songs[2], songs[0], songs[3]).forEach { SongRow(it, playing = false) {} }
                }
            }
        }
        // Each click on a clock that keeps moving, with time for the panel to finish animating in
        // between - a click mid-animation lands on a tile that is still scaled up from its corner.
        // capture() settles 120 frames (6s) before and after: start where the first settle ended,
        // and finish well inside the second, so the clock only ever moves forward on the last frames.
        fun ImageComposeScene.clicks(vararg points: Pair<Float, Float>) {
            var at = 6_000_000_000L
            for ((x, y) in points) {
                sendPointerEvent(PointerEventType.Move, Offset(x, y))
                render(at); at += 20_000_000L
                sendPointerEvent(PointerEventType.Press, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                render(at); at += 20_000_000L
                sendPointerEvent(PointerEventType.Release, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                repeat(20) { render(at); at += 50_000_000L; Thread.sleep(10) }
            }
        }
        capture("artist_picker_menu", 900, 640, settleFrames = 120, interact = { it.clicks(862f to 46f, 602f to 350f) }, content = rows)
        capture("artist_picker_back", 900, 640, settleFrames = 120, interact = { it.clicks(862f to 46f, 602f to 350f, 578f to 101f) }, content = rows)
    }

    @Test
    fun editorLayout() {
        assumeTrue(outDir != null)
        val json = javaClass.getResource("/editor-windows-default.json")!!.readText()
        val layout = PlayerLayout.parse(json).getOrThrow()
        capture("player_layout_editor", 1280, 800) { Player(1280, layout = layout) }
        capture("player_builtin_1280", 1280, 800) { Player(1280) }
    }

    @Test
    fun ferrofluidPlayer() {
        assumeTrue(outDir != null)
        val tap = VisualizerTap(VISUALIZER_BANDS).apply {
            submit(0L, FloatArray(VISUALIZER_BANDS) { if (it < 5) 0.9f else 0.35f })
        }
        capture("player_ferro", 1000, 640, settleFrames = 24) { Player(1000, background = BackgroundStyle.Ferrofluid, liquidTap = tap) }
    }

    @Test
    fun snapshots() {
        assumeTrue(outDir != null)
        capture("player_1280", 1280, 800) { Player(1280) }
        capture("player_1000", 1000, 700) { Player(1000) }
        capture("player_light", 1280, 800, dark = false) { Player(1280) }
        capture("queue_wide", 1400, 860, interact = { it.click(500f, 20f) }) { Player(1400) }
        capture("queue_narrow", 900, 700, interact = { it.click(450f, 680f) }) { Player(900) }
        capture("lyrics", 1280, 800, interact = { it.click(300f, 400f) }) { Player(1280) }
        // Words estimated and lit both ways, with the kind tag: the current line is 13.5s into a
        // line that starts at 12s.
        capture("lyrics_sweep", 1280, 800, interact = { it.click(300f, 400f) }) {
            Player(1280, highlight = LyricsHighlight.Sweep, kind = LyricsKind.Estimated, shownLyrics = lyrics.withEstimatedWords())
        }
        capture("lyrics_fade", 1280, 800, interact = { it.click(300f, 400f) }) {
            Player(1280, highlight = LyricsHighlight.Fade, kind = LyricsKind.Estimated, shownLyrics = lyrics.withEstimatedWords())
        }
        capture("eq", 1280, 900, interact = {
            it.sendPointerEvent(PointerEventType.Scroll, Offset(640f, 300f), scrollDelta = Offset(0f, -1f))
        }) { Player(1280) }
        capture("eq_on", 1280, 1500, interact = {
            it.sendPointerEvent(PointerEventType.Scroll, Offset(640f, 300f), scrollDelta = Offset(0f, -1f))
        }) { Player(1280, eqOn = true) }
    }

    @Test
    fun shell() {
        assumeTrue(outDir != null)
        val dir = java.nio.file.Files.createTempDirectory("ot-snap").toFile()
        val library = LibraryStore(dir)
        songs.take(6).forEach { library.toggleLiked(it.toStored()) }
        songs.drop(2).forEach { library.recordPlay(it.toStored(), 240_000) }
        songs.take(3).forEach { library.recordPlay(it.toStored(), 240_000) }
        val mix = library.createPlaylist("Late night mix")
        songs.take(4).forEach { library.addToPlaylist(mix, it.toStored()) }
        val settings = Settings(library.settings)
        val player = DesktopPlayer()
        val shellContent: @Composable () -> Unit = { App(player, library, settings, onSeedChanged = {}) }
        capture("shell_home", 1280, 800, content = shellContent)
        capture("shell_songs", 1280, 800, interact = { it.click(40f, 300f) }, content = shellContent)
        capture("shell_playlists", 1280, 800, interact = { it.click(40f, 360f) }, content = shellContent)
        capture("shell_playlist_open", 1280, 800, interact = { it.click(40f, 360f); it.click(500f, 150f) }, content = shellContent)
        capture("shell_stats", 1280, 800, interact = { it.click(40f, 420f) }, content = shellContent)
        capture("shell_song_menu", 1280, 800, interact = {
            it.click(40f, 300f)
            it.sendPointerEvent(
                PointerEventType.Press, Offset(400f, 160f),
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isSecondaryPressed = true),
                button = androidx.compose.ui.input.pointer.PointerButton.Secondary,
            )
        }, content = shellContent)
        capture("shell_settings", 1280, 800, interact = { it.click(40f, 690f) }, content = shellContent)
        capture("shell_settings_lyrics", 1280, 800, interact = { it.click(40f, 690f); it.click(200f, 260f) }, content = shellContent)
        player.stop()
    }

    @Test
    fun playerButtons() {
        assumeTrue(outDir != null)
        capture("player_buttons", 1280, 800) { Player(1280, buttons = "DLHUM") }
        capture("player_buttons_dialog", 700, 820) {
            PlayerButtonsDialog(current = PlayerButtonKind.decode("DLHUM"), onSave = {}, onDismiss = {})
        }
    }

    @Test
    fun icons() {
        assumeTrue(outDir != null)
        capture("icons", 900, 300) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                OuterTuneIcons.all.values.forEach {
                    androidx.compose.material3.Icon(it, null, modifier = Modifier.padding(6.dp).size(40.dp))
                }
            }
        }
    }

    @Test
    fun liveArtist() = kotlinx.coroutines.runBlocking {
        assumeTrue(outDir != null && System.getenv("OT_PROBE") != null)
        com.zionhuang.innertube.YouTube.locale = com.zionhuang.innertube.models.YouTubeLocale(gl = "US", hl = "en")
        com.zionhuang.innertube.YouTube.visitorData = com.zionhuang.innertube.YouTube.visitorData().getOrThrow()
        val hit = com.zionhuang.innertube.YouTube.search("Daft Punk", com.zionhuang.innertube.YouTube.SearchFilter.FILTER_ARTIST)
            .getOrThrow().items.filterIsInstance<com.zionhuang.innertube.models.ArtistItem>().first()
        val library = LibraryStore(java.nio.file.Files.createTempDirectory("ot-artist").toFile())
        capture("artist_live", 1280, 1400, liveArt = true, settleFrames = 120) {
            ArtistPane(
                requested = StoredArtist(hit.id, hit.title),
                library = library,
                currentId = null,
                onBack = {}, onPlay = { _, _ -> }, onAddToPlaylist = {},
            )
        }
    }

    @Test
    fun unlinkedArtists() = kotlinx.coroutines.runBlocking {
        assumeTrue(outDir != null)
        val library = LibraryStore(java.nio.file.Files.createTempDirectory("ot-artist").toFile())
        for ((name, artist) in listOf(
            "artist_unlinked" to StoredArtist.unlinked("Nobody Known"),
            "artist_bogus" to StoredArtist("UCnotarealchannel000000", "Ghost"),
            "artist_blank" to StoredArtist("", "Blank"),
        )) {
            capture(name, 1000, 800, settleFrames = 80) {
                ArtistPane(requested = artist, library = library, currentId = null,
                    onBack = {}, onPlay = { _, _ -> }, onAddToPlaylist = {})
            }
        }
    }

    @Test
    fun liveSearch() = kotlinx.coroutines.runBlocking {
        assumeTrue(outDir != null && System.getenv("OT_PROBE") != null)
        com.zionhuang.innertube.YouTube.locale = com.zionhuang.innertube.models.YouTubeLocale(gl = "US", hl = "en")
        com.zionhuang.innertube.YouTube.visitorData = com.zionhuang.innertube.YouTube.visitorData().getOrThrow()
        capture("search_live", 1280, 1100, liveArt = true, settleFrames = 120) {
            SearchPane(
                query = "daft punk",
                currentId = null,
                hideExplicit = false,
                onPlay = { _, _ -> },
                onOpenAlbum = {},
                onOpenArtist = {},
                onOpenPlaylist = {},
            )
        }
        capture("search_live_albums", 1280, 900, liveArt = true, settleFrames = 120, interact = { it.click(230f, 18f) }) {
            SearchPane(
                query = "daft punk",
                currentId = null,
                hideExplicit = false,
                onPlay = { _, _ -> },
                onOpenAlbum = {},
                onOpenArtist = {},
                onOpenPlaylist = {},
            )
        }
    }

    @Test
    fun settingsResponsiveness() {
        assumeTrue(outDir != null)
        val library = LibraryStore(java.nio.file.Files.createTempDirectory("ot-resp").toFile())
        val settings = Settings(library.settings)
        val player = DesktopPlayer()
        val scene = ImageComposeScene(1280, 800, Density(1f)) {
            MaterialTheme(colorScheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), true)) {
                Surface(modifier = Modifier.fillMaxSize()) { App(player, library, settings, onSeedChanged = {}) }
            }
        }
        var t = 0L
        fun frames(label: String, n: Int = 10) {
            val times = LongArray(n) {
                val start = System.nanoTime()
                scene.render(t)
                t += 16_000_000L
                (System.nanoTime() - start) / 1_000_000
            }
            println("FRAMES $label: max=${times.max()}ms total=${times.sum()}ms ${times.joinToString(",")}")
        }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        }
        frames("start", 30)
        // Playback ticking, as it does ~50 times a second while a song plays.
        run {
            val times = LongArray(60) {
                player.positionMs.value = it * 20L
                val start = System.nanoTime()
                scene.render(t)
                t += 16_000_000L
                (System.nanoTime() - start) / 1_000_000
            }
            println("FRAMES while playing (home): max=${times.max()}ms total=${times.sum()}ms")
        }
        click(40f, 690f); frames("open settings")
        click(200f, 300f); frames("open lyrics")
        File(outDir, "resp_lyrics.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        click(520f, 585f); frames("open alignment menu", 20)
        File(outDir, "resp_menu.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        click(560f, 660f); frames("pick from menu", 20)
        println("FRAMES alignment now ${settings.lyricsAlignment}")
        settings.lyricsAlignment = LyricsAlignment.Left; frames("set alignment")
        settings.textScale = 110; frames("set text size")
        settings.pureBlack = true; frames("set pure black")
        File(outDir, "resp_after.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        player.stop()
    }

    /** Frame cost with a real-sized library while playback ticks: OT_PERF_BACKUP names a phone backup. */
    @Test
    fun performance() = kotlinx.coroutines.runBlocking {
        val backup = System.getenv("OT_PERF_BACKUP")
        assumeTrue(backup != null)
        val library = LibraryStore(java.nio.file.Files.createTempDirectory("ot-perf").toFile())
        BackupImport.importFrom(File(backup!!), library)
        val settings = Settings(library.settings)
        val player = DesktopPlayer()
        val scene = ImageComposeScene(1280, 800, Density(1f)) {
            MaterialTheme(colorScheme = DynamicTheme.schemeFor(Color(0xFF7B3FC4), true)) {
                Surface(modifier = Modifier.fillMaxSize()) { App(player, library, settings, onSeedChanged = {}) }
            }
        }
        var t = 0L
        repeat(60) { scene.render(t); t += 16_000_000L }
        fun measure(label: String, tick: Boolean = true) {
            val times = LongArray(120) {
                if (tick) player.positionMs.value = 60_000L + it * 25L
                player.durationMs.value = 240_000L
                val start = System.nanoTime()
                scene.render(t)
                t += 16_000_000L
                (System.nanoTime() - start) / 1000
            }
            val sorted = times.sorted()
            println("PERF $label: mean=${times.average().toInt()}us p95=${sorted[(sorted.size * 0.95).toInt()]}us max=${sorted.last()}us")
        }
        fun click(x: Float, y: Float) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            repeat(20) { scene.render(t); t += 16_000_000L }
        }
        measure("home idle", tick = false)
        measure("home")
        click(40f, 300f); measure("library")
        click(40f, 420f); measure("stats")
        click(600f, 770f); measure("full player idle", tick = false); measure("full player")
        scene.close()
        player.stop()
    }
}
