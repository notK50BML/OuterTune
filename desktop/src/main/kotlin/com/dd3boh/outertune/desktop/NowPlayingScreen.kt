/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.lerp
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.min
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.max
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The full-window player: big cover, and a background taken from the cover's own colours.
 *
 * This is the one piece of the Android app's look that ports as an idea rather than as code. The
 * Android player draws its background with `android.graphics.RuntimeShader`, which does not exist
 * off Android, so nothing here is copied - what carries across is the intent: the player should be
 * tinted by whatever is playing rather than being a fixed grey panel.
 *
 * The gradient animates between tracks instead of cutting, because the colours arrive a moment after
 * the song does - the cover has to be fetched and sampled - and a hard switch lands as a flash of
 * the wrong colour followed by a flash of the right one.
 *
 * Text colour is chosen against the background's brightness rather than fixed. A pale cover produces
 * a pale background, and white-on-white is unreadable; this is the same reasoning as the Android
 * app's auto text contrast, applied here because the same problem exists.
 */
@OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)
@Composable
fun NowPlayingScreen(
    playback: PlaybackState,
    queue: QueueState,
    /** Read only by the parts that show it - see Main for why this is a provider. */
    positionMs: () -> Long,
    durationMs: Long,
    liked: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onClose: () -> Unit,
    /**
     * The spectrum bars, if they are wanted.
     *
     * Off by default now. Sitting between the credits and the seek bar they read as something the
     * player was doing rather than something the song was, and they pushed the controls down the
     * column for it. The tap is still built and still cheap, so this becomes a setting rather than a
     * deletion the moment there is a settings screen to put it in.
     */
    spectrum: VisualizerTap? = null,
    /** The spectrum for a background that moves with the music (Ferrofluid), whatever the visualiser setting. */
    liquidTap: VisualizerTap? = null,
    playedFrames: () -> Long = { 0L },
    /** What the overflow menu offers. Items with no callback do not appear - see [PlayerActions]. */
    actions: PlayerActions = PlayerActions(),
    lyrics: Lyrics? = null,
    lyricsLoading: Boolean = false,
    lyricsOnCoverClick: Boolean = true,
    downloaded: Boolean = false,
    downloading: Boolean = false,
    /** Time left on the sleep timer, or null when none is set. */
    sleepRemainingMs: Long? = null,
    onSleepTimer: (() -> Unit)? = null,
    backgroundStyle: BackgroundStyle = BackgroundStyle.Gradient,
    seekBarStyle: SeekBarStyle = SeekBarStyle.Squiggly,
    ferroReactivity: FerroReactivity = FerroReactivity.Normal,
    /** An imported Windows layout (Settings > Player > Player layout), or null for the built-in one. */
    playerLayout: PlayerLayout? = null,
    /** Queues kept aside, and switching to or removing one - see PlayerQueue.saved. */
    savedQueues: List<SavedQueue> = emptyList(),
    onSwitchQueue: (SavedQueue) -> Unit = {},
    onRemoveSavedQueue: (SavedQueue) -> Unit = {},
    /** A keyboard shortcut's request - see KeyBindings. Handled once, then [onCommandHandled]. */
    command: PlayerCommand? = null,
    onCommandHandled: () -> Unit = {},
    colourByValue: Boolean = true,
    onDownload: (() -> Unit)? = null,
    equalizer: Equalizer? = null,
    /** Tempo and pitch, if the player exposes them. Null hides the dials rather than faking them. */
    timeStretch: TimeStretch? = null,
    compressor: Compressor? = null,
    /** Where the equaliser's curve and saved profiles live. Null keeps nothing. */
    eqStore: EqStore? = null,
    /** Left/right balance. Null hides the dial rather than showing one that does nothing. */
    balance: Balance? = null,
    onJumpToQueueIndex: (Int) -> Unit = {},
    /** Drag-to-reorder and remove, both in play-order positions - see QueueEdit. */
    onMoveInQueue: (Int, Int) -> Unit = { _, _ -> },
    onRemoveFromQueue: (Int) -> Unit = {},
    onOpenArtist: (StoredArtist) -> Unit = {},
    /**
     * Whether the five-second seek buttons appear.
     *
     * Off, as on Android, where the seek increment defaults to OFF and the buttons are simply
     * absent - a seven-button transport row is a lot to read past to find play.
     */
    showSeekButtons: Boolean = false,
    /** Lyrics text size, in percent of the default. */
    lyricsTextScale: Int = 100,
    lyricsAlignment: LyricsAlignment = LyricsAlignment.Centre,
    /** The title-row buttons, in order - see PlayerButtonKind. */
    playerButtons: List<PlayerButtonKind> = PlayerButtonKind.decode(""),
    showQueueTitle: Boolean = true,
    /** Throws away this song's cached lyrics and asks the providers again. */
    onRefetchLyrics: (() -> Unit)? = null,
    lyricsOffsetMs: Int = 0,
    onLyricsOffsetChange: ((Int) -> Unit)? = null,
    /** What a scroll over the player does - see [PlayerScroll]. */
    playerScroll: PlayerScroll = PlayerScroll.Anywhere,
    /** How far the seek buttons move. Was fixed at five seconds, which is now only the default. */
    seekStepMs: Long = SEEK_STEP_DEFAULT_MS,
    /** The analyser drawn behind the equaliser's curve. Null draws the curve alone. */
    eqSpectrum: VisualizerTap? = null,
) {
    // Held here rather than inside the drawer: the handle at the top and the button in the actions
    // row both open the same panel, exactly as they do on Android, and two copies of the state would
    // mean each one only knew about its own taps.
    var equalizerOpen by remember { mutableStateOf(false) }
    val onToggleEqualizer = { equalizerOpen = !equalizerOpen }

    // The player's own height, for the by-position scroll mode - see the Scroll handler below. Zero
    // until the first layout, which is also the only time there is no half for a pointer to be in.
    var playerHeightPx by remember { mutableStateOf(0) }
    // When the last downward scroll arrived, so a trackpad's momentum tail cannot act twice.
    var lastScrollDown by remember { mutableStateOf(0L) }

    val song = queue.current
    val (primary, secondary) = rememberArtworkColours(song?.thumbnail)
    // Ferrofluid is a black mirror and needs a dark room: a light backdrop would turn the text dark,
    // and dark text over black spikes cannot be read. So that style always gets a dark backdrop and
    // light text; every other style picks its text colour from the backdrop as before.
    val ferro = backgroundStyle == BackgroundStyle.Ferrofluid
    val top by animateColorAsState(primary.darken(if (ferro) 0.62f else 0.45f))
    val bottom by animateColorAsState(secondary.darken(if (ferro) 0.85f else 0.75f))
    val onBackground = if (ferro) Color.White else contentColourFor(top)

    // Two states, not three. The queue either is not in the way or has the window; a middle
    // "peeking" step meant the bar took two clicks to get back from, which is one more than a
    // toggle should ever need.
    var queueOpen by remember { mutableStateOf(false) }

    // Reset whenever the song changes. Lyrics left open across a track change would show the
    // previous song's words while the new one plays, which is worse than showing nothing - and the
    // cover of what is now playing is the more useful thing to land on.
    var showLyrics by remember(song?.id) { mutableStateOf(false) }
    LaunchedEffect(command) {
        when (command ?: return@LaunchedEffect) {
            PlayerCommand.ToggleEqualiser -> equalizerOpen = !equalizerOpen
            PlayerCommand.ToggleLyrics -> showLyrics = !showLyrics
            PlayerCommand.ToggleQueue -> queueOpen = !queueOpen
            // The innermost thing open closes first, and the player itself last.
            PlayerCommand.Back -> when {
                equalizerOpen -> equalizerOpen = false
                queueOpen -> queueOpen = false
                showLyrics -> showLyrics = false
                else -> onClose()
            }
        }
        onCommandHandled()
    }

    // The player's own scheme, from the cover and matched to the background's brightness rather
    // than to the window's theme. The background is the cover darkened, so in a light window the
    // light scheme's deep primary put the play button dark-on-dark and it all but disappeared - the
    // phone avoids this the same way, by colouring the player from the artwork.
    val playerScheme = remember(primary, onBackground) {
        DynamicTheme.schemeFor(primary, dark = onBackground == Color.White)
    }

    MaterialTheme(colorScheme = playerScheme) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // Wide enough for both, and the queue is what the width is spent on.
    //
    // A queue that covers the player is the right answer on a phone, where there is no room for
    // anything else, and the wrong one on a desktop window a metre across - the thing being played
    // disappears to look at what is playing next. Past this width it docks beside the player
    // instead, and below it the full-window overlay is still correct.
    val sideBySide = maxWidth >= QUEUE_DOCK_MIN_WIDTH
    val queueDocked = sideBySide && queueOpen

    // Behind everything, docked queue included, so the window reads as one surface tinted by the
    // cover rather than a player with a differently coloured slab bolted to its side.
    PlayerBackground(
        style = backgroundStyle,
        thumbnail = song?.thumbnail,
        top = top,
        bottom = bottom,
        liquidTap = liquidTap,
        playedFrames = playedFrames,
        playing = playback is PlaybackState.Playing,
        ferroReactivity = ferroReactivity.multiplier,
    )

    // The window buttons over the top edge take the player's text colour while it is showing,
    // since the cover decides whether the background is light or dark, not the theme.
    val captionTint = LocalCaptionTint.current
    DisposableEffect(captionTint, onBackground) {
        captionTint.colour = onBackground
        onDispose { captionTint.colour = null }
    }

    // The background above runs to the top edge; the controls keep clear of the window buttons.
    Row(modifier = Modifier.fillMaxSize().padding(top = LocalCaptionInset.current)) {
    // The player's own area. A Box, so the narrow-window queue can cover just this and nothing else.
    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scrolling up at the top of the player pulls the equaliser down, which is the gesture
            // the drawer's own handle implies and the one that was missing - the handle could only
            // be clicked, so "scroll up to see it" simply did nothing. Scrolling down is the other
            // half of the same axis and puts the player away, which is what the phone's swipe down
            // does; there was no pointer gesture for it at all before, only F and Esc.
            .onSizeChanged { playerHeightPx = it.height }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (playerScroll == PlayerScroll.Off) return@onPointerEvent
                val change = event.changes.first()
                // Only a scroll nothing else wanted. This handler sits on the whole player, so
                // without the check, scrolling the lyrics or the queue - both of which are inside it
                // - would also drag the equaliser down over what was being read.
                if (change.isConsumed) return@onPointerEvent
                // Which half the pointer is in, for the by-position mode. Taken from the event, so
                // it is where this very scroll happened rather than wherever the pointer last was.
                // Before the first layout there is no height to halve and so no half to be in.
                val known = playerHeightPx > 0
                val atTop = known && change.position.y <= playerHeightPx / 2f
                val free = playerScroll == PlayerScroll.Anywhere
                val delta = change.scrollDelta.y
                when {
                    // Opening only. Closing by scrolling cannot work and used to be written here as
                    // though it did: once open, the panel covers the window as its own overlay, so
                    // this handler - which lives on the player underneath - never sees another
                    // scroll. It closes by its handle or by a flick upward, the direction it came from.
                    delta < 0f && equalizer != null && !equalizerOpen && (free || atTop) -> {
                        equalizerOpen = true
                    }
                    // Down puts the player away. Not while the equaliser is open: the panel is an
                    // overlay over this, so a scroll arriving here means it is already closed, but
                    // the check keeps that true if the panel ever stops covering the whole window.
                    delta > 0f && !equalizerOpen && (free || (known && !atTop)) -> {
                        // One flick is dozens of events on a trackpad, and all but the first arrive
                        // after the player has already gone. Opening the equaliser shrugs that off
                        // because it is idempotent; closing is not - the tail would land on whatever
                        // the mini player sits over. So the gesture has to spend itself: the clock
                        // is pushed forward by every event, and nothing fires until the wheel stops.
                        val now = System.nanoTime()
                        val quiet = now - lastScrollDown > SCROLL_DISMISS_GAP_NS
                        lastScrollDown = now
                        if (quiet) onClose()
                    }
                }
            },
    ) {
        // Pulled down from the top edge rather than sitting among the controls, which is where the
        // Android player keeps it. An equaliser is something adjusted once and then left alone, so a
        // button for it beside play and pause gives it standing it has not earned - and twelve
        // sliders is the largest thing on this screen.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        // Everything below is sized against the window rather than in fixed dp, and that is the fix
        // for a player that looked wrong at every size. Android runs at a density of two or three,
        // so a 72dp play button is a couple of hundred physical pixels and the cover beside it is
        // maybe five times that. A desktop window runs at density one: the cover, being a fraction
        // of the window, grew to nine hundred pixels while the play button stayed at seventy-two.
        // Same code, same numbers, and a button that had become a speck next to a wall of album art.
        val scale = (maxWidth / 1100.dp).coerceIn(1f, 2.2f)
        val coverMax = 360.dp * scale
        val playSize = 72.dp * scale
        val playIcon = 36.dp * scale
        val transportSize = 52.dp * scale
        val transportIcon = 30.dp * scale
        // The info and controls are slid left so the gap between them and the cover matches the gap
        // between them and the window's edge. The cover hugs the left of its half, so whatever that
        // half has spare ends up on the cover's right; left alone, the block sat a long way from
        // the art and crowded the edge. Worked out from the same numbers the layout uses below.
        val outerPadding = 28.dp
        val artGap = 36.dp
        val artColumn = (maxWidth - outerPadding * 2 - transportSize - 8.dp - artGap) / 2
        // The lyrics take the cover's square, not the whole half, so opening them moves nothing.
        val artSlack =
            (artColumn - min(min(maxHeight - 24.dp, artColumn), coverMax).coerceAtLeast(120.dp)).coerceAtLeast(0.dp)
        val blockShift = ((artSlack + artGap - outerPadding) / 2).coerceAtLeast(0.dp)
        val actionSize = 40.dp * scale
        val actionIcon = 24.dp * scale

        // The transport controls, laid out exactly as Player.kt's controlsBlock - shared by the
        // built-in layout and an imported one, so both draw the same row.
        val transportRow: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(transportSize)) {
                    ShuffleIcon(
                        on = queue.shuffled,
                        // Fully opaque either way: the arrows themselves say whether it is on -
                        // straight when off, crossed when on - as on the phone.
                        tint = onBackground,
                        modifier = Modifier.size(transportIcon),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = onPrevious,
                    enabled = queue.hasPrevious,
                    modifier = Modifier.size(transportSize),
                ) {
                    Icon(
                        OuterTuneIcons.skipPrevious, "Previous",
                        tint = onBackground, modifier = Modifier.size(transportIcon),
                    )
                }
            }
            // Off unless asked for, which is how the Android player ships - its seek
            // increment defaults to OFF and the buttons are simply absent.
            if (showSeekButtons) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { onSeek((positionMs() - seekStepMs).coerceAtLeast(0)) },
                        enabled = durationMs > 0,
                    ) {
                        Icon(OuterTuneIcons.fastRewind, "Back 5 seconds", tint = onBackground)
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // The one filled control, because play/pause is the button being reached for.
            //
            // Its shape carries the state as well as its glyph: a circle while paused, a
            // rounded square while playing. Two things saying the same thing is not
            // redundancy here - the shape is readable from across a room and out of the
            // corner of an eye, where a small triangle and a small pair of bars are not.
            val playCorner by animateDpAsState(
                if (playback.showsPlay) playSize / 2 else playSize * 0.28f
            )
            FilledIconButton(
                onClick = onTogglePause,
                shape = RoundedCornerShape(playCorner),
                modifier = Modifier.size(playSize),
            ) {
                Icon(
                    if (playback.showsPlay) OuterTuneIcons.play else OuterTuneIcons.pause,
                    contentDescription = if (playback.showsPlay) "Play" else "Pause",
                    modifier = Modifier.size(playIcon),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (showSeekButtons) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { onSeek((positionMs() + seekStepMs).coerceAtMost(durationMs)) },
                        enabled = durationMs > 0,
                    ) {
                        Icon(OuterTuneIcons.fastForward, "Forward 5 seconds", tint = onBackground)
                    }
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = onNext,
                    enabled = queue.hasNext,
                    modifier = Modifier.size(transportSize),
                ) {
                    Icon(
                        OuterTuneIcons.skipNext, "Next",
                        tint = onBackground, modifier = Modifier.size(transportIcon),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(transportSize)) {
                    Icon(
                        if (queue.repeat == RepeatMode.ONE) OuterTuneIcons.repeatOne else OuterTuneIcons.repeat,
                        contentDescription = "Repeat",
                        tint = onBackground.copy(alpha = if (queue.repeat == RepeatMode.OFF) 0.4f else 1f),
                        modifier = Modifier.size(transportIcon),
                    )
                }
            }
        }
        }

        // One transport button on its own, for a layout that places them individually.
        val transportMember: @Composable (String) -> Unit = { id ->
            when (id) {
                "shuffle" -> IconButton(onClick = onToggleShuffle, modifier = Modifier.size(transportSize)) {
                    ShuffleIcon(on = queue.shuffled, tint = onBackground, modifier = Modifier.size(transportIcon))
                }
                "skip_previous" -> IconButton(onClick = onPrevious, enabled = queue.hasPrevious, modifier = Modifier.size(transportSize)) {
                    Icon(OuterTuneIcons.skipPrevious, "Previous", tint = onBackground, modifier = Modifier.size(transportIcon))
                }
                "skip_next" -> IconButton(onClick = onNext, enabled = queue.hasNext, modifier = Modifier.size(transportSize)) {
                    Icon(OuterTuneIcons.skipNext, "Next", tint = onBackground, modifier = Modifier.size(transportIcon))
                }
                "seek_backward" -> IconButton(onClick = { onSeek((positionMs() - seekStepMs).coerceAtLeast(0)) }, modifier = Modifier.size(transportSize)) {
                    Icon(OuterTuneIcons.fastRewind, "Back 5 seconds", tint = onBackground, modifier = Modifier.size(transportIcon))
                }
                "seek_forward" -> IconButton(onClick = { onSeek((positionMs() + seekStepMs).coerceAtMost(durationMs)) }, modifier = Modifier.size(transportSize)) {
                    Icon(OuterTuneIcons.fastForward, "Forward 5 seconds", tint = onBackground, modifier = Modifier.size(transportIcon))
                }
                "repeat" -> IconButton(onClick = onCycleRepeat, modifier = Modifier.size(transportSize)) {
                    Icon(
                        if (queue.repeat == RepeatMode.ONE) OuterTuneIcons.repeatOne else OuterTuneIcons.repeat,
                        contentDescription = "Repeat",
                        tint = onBackground.copy(alpha = if (queue.repeat == RepeatMode.OFF) 0.4f else 1f),
                        modifier = Modifier.size(transportIcon),
                    )
                }
                "play_pause" -> {
                    val corner by animateDpAsState(if (playback.showsPlay) playSize / 2 else playSize * 0.28f)
                    FilledIconButton(onClick = onTogglePause, shape = RoundedCornerShape(corner), modifier = Modifier.size(playSize)) {
                        Icon(
                            if (playback.showsPlay) OuterTuneIcons.play else OuterTuneIcons.pause,
                            contentDescription = if (playback.showsPlay) "Play" else "Pause",
                            modifier = Modifier.size(playIcon),
                        )
                    }
                }
            }
        }

        val playerActions = actions.copy(
            onEqualizer = actions.onEqualizer ?: onToggleEqualizer,
            onToggleLyrics = { showLyrics = !showLyrics },
        )
        val actionRow: @Composable (List<PlayerButtonKind>) -> Unit = { kinds ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButtons(
                    liked = liked,
                    actions = playerActions,
                    onToggleLike = onToggleLike,
                    onDownload = actions.onDownload,
                    downloaded = downloaded,
                    downloading = downloading,
                    tint = onBackground,
                    buttonSize = actionSize,
                    iconSize = actionIcon,
                    sleepRemainingMs = sleepRemainingMs,
                    onSleepTimer = onSleepTimer,
                    buttons = kinds,
                )
            }
        }

        val layout = playerLayout
        if (layout != null) {
            // An imported Windows layout: every block at its own place - its centre at (x%, y%) of
            // the player area, w% of its width - which is exactly what the editor saves.
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val areaW = maxWidth
                    val areaH = maxHeight
                    for (block in layout.blocks) {
                        if (!block.visible) continue
                        val w = areaW * (block.w / 100f)
                        PlacedBlock(block, areaW, areaH) {
                            when (block.id) {
                                "artwork" -> {
                                    val side = min(w, areaH)
                                    Box(
                                        modifier = Modifier
                                            .size(side)
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { if (lyricsOnCoverClick && !showLyrics) showLyrics = true },
                                    ) {
                                        if (showLyrics) {
                                            LyricsPane(
                                                lyrics = lyrics,
                                                loading = lyricsLoading,
                                                positionMs = positionMs,
                                                onSeek = onSeek,
                                                onColour = onBackground,
                                                fontSize = 20.sp * scale * (lyricsTextScale / 100f),
                                                alignment = lyricsAlignment,
                                                modifier = Modifier.fillMaxSize().padding(bottom = 76.dp),
                                            )
                                            LyricsHeader(
                                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 20.dp),
                                                tint = onBackground,
                                                onClose = { showLyrics = false },
                                                onRefetch = onRefetchLyrics,
                                                offsetMs = lyricsOffsetMs,
                                                onOffsetChange = onLyricsOffsetChange,
                                            )
                                        } else {
                                            Artwork(song?.thumbnail, size = side, cornerRadius = block.radiusDp.dp * scale)
                                        }
                                    }
                                }
                                "info" -> Column(
                                    horizontalAlignment = if (block.centred) Alignment.CenterHorizontally else Alignment.Start,
                                    modifier = Modifier.width(w),
                                ) {
                                    Text(
                                        text = song?.title ?: "Nothing playing",
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontSize = MaterialTheme.typography.headlineMedium.fontSize * scale,
                                            lineHeight = MaterialTheme.typography.headlineMedium.lineHeight * scale,
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = onBackground,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = if (block.centred) androidx.compose.ui.text.style.TextAlign.Center else null,
                                    )
                                    ArtistNames(
                                        artists = song?.artists.orEmpty().map { artist ->
                                            artist.id?.let { StoredArtist(it, artist.name) } ?: StoredArtist.unlinked(artist.name)
                                        },
                                        fallback = song?.artists?.joinToString { it.name } ?: "",
                                        colour = onBackground.copy(alpha = 0.75f),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = MaterialTheme.typography.titleMedium.fontSize * scale,
                                        ),
                                        onClick = onOpenArtist,
                                    )
                                }
                                "actions" -> actionRow(playerButtons)
                                "sleep_timer" -> actionRow(listOf(PlayerButtonKind.SLEEP))
                                "like" -> actionRow(listOf(PlayerButtonKind.LIKE))
                                "equalizer" -> actionRow(listOf(PlayerButtonKind.EQUALIZER))
                                "menu" -> actionRow(listOf(PlayerButtonKind.MENU))
                                "progress" -> if (durationMs > 0) Column(modifier = Modifier.width(w)) {
                                    SlimSeekBar(
                                        positionMs = positionMs,
                                        durationMs = durationMs,
                                        colour = onBackground,
                                        onSeek = onSeek,
                                        style = seekBarStyle,
                                        playing = playback is PlaybackState.Playing,
                                    )
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        PositionLabel(positionMs, onBackground)
                                        Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall, color = onBackground)
                                    }
                                }
                                "controls" -> Box(modifier = Modifier.width(w)) { transportRow() }
                                "queue" -> Unit // the handle along the bottom, below
                                else -> transportMember(block.id)
                            }
                        }
                    }
                    if (playback is PlaybackState.Failed) {
                        Text(
                            playback.reason,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        )
                    }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(transportSize).align(Alignment.TopStart)) {
                    Icon(OuterTuneIcons.close, contentDescription = "Back", tint = onBackground, modifier = Modifier.size(transportIcon))
                }
            }
        } else {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(transportSize)) {
                Icon(
                    OuterTuneIcons.close,
                    contentDescription = "Back",
                    tint = onBackground,
                    modifier = Modifier.size(transportIcon),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Capped rather than filling whatever it is given. A square as tall as the window is
            // most of the window, and it was taking that room from the controls - which are the part
            // anyone actually reaches for.
            BoxWithConstraints(
                // Toward the left of its column rather than centred in it. Centred, the back button
                // on one side and the wide gap before the controls on the other pushed the art into
                // the middle of the window, which left it reading as belonging to the controls
                // rather than balancing them.
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    // Clicking the cover swaps it for the lyrics, which is how the Android player
                    // does it. No button is spent on a thing the cover itself can be.
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (lyricsOnCoverClick) showLyrics = !showLyrics },
            ) {
                if (showLyrics) {
                    // The controls sit at the bottom right, lifted clear of the edge - where the
                    // phone keeps them - so they are out of the way of the words being read.
                    val side = min(min(maxHeight - 24.dp, maxWidth), coverMax).coerceAtLeast(120.dp)
                    Box(modifier = Modifier.fillMaxHeight().width(side)) {
                    LyricsPane(
                        lyrics = lyrics,
                        loading = lyricsLoading,
                        positionMs = positionMs,
                        onSeek = onSeek,
                        onColour = onBackground,
                        fontSize = 20.sp * scale * (lyricsTextScale / 100f),
                        alignment = lyricsAlignment,
                        // Ends above the lyric controls, so no line is ever read through them.
                        modifier = Modifier.fillMaxHeight().padding(bottom = 76.dp),
                    )
                    LyricsHeader(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 28.dp),
                        tint = onBackground,
                        onClose = { showLyrics = false },
                        onRefetch = onRefetchLyrics,
                        offsetMs = lyricsOffsetMs,
                        onOffsetChange = onLyricsOffsetChange,
                    )
                    }
                } else {
                    val side = min(min(maxHeight - 24.dp, maxWidth), coverMax)
                    Artwork(
                        song?.thumbnail,
                        size = side.coerceAtLeast(120.dp),
                        // Proportional to the art rather than fixed, so it reads the same at any
                        // window size - see the note on the parameter.
                        cornerRadius = side * 0.045f,
                    )
                }
            }

            Spacer(modifier = Modifier.width(36.dp))

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxHeight().offset(x = -blockShift),
            ) {
                // Title, credits and the action buttons share one row, with the text column taking
                // the weight so the buttons land against the right edge. That is Player.kt's
                // infoBlock, and it is where they belong: they act on the song, so they sit with
                // the song's name rather than with the transport controls, which act on playback.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song?.title ?: "Nothing playing",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = MaterialTheme.typography.headlineMedium.fontSize * scale,
                                lineHeight = MaterialTheme.typography.headlineMedium.lineHeight * scale,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = onBackground,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ArtistNames(
                            artists = song?.artists.orEmpty().map { artist ->
                                artist.id?.let { StoredArtist(it, artist.name) } ?: StoredArtist.unlinked(artist.name)
                            },
                            fallback = song?.artists?.joinToString { it.name } ?: "",
                            colour = onBackground.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = MaterialTheme.typography.titleMedium.fontSize * scale,
                            ),
                            onClick = onOpenArtist,
                        )
                    }
                    ActionButtons(
                        liked = liked,
                        actions = actions.copy(
                            onEqualizer = actions.onEqualizer ?: onToggleEqualizer,
                            onToggleLyrics = { showLyrics = !showLyrics },
                        ),
                        onToggleLike = onToggleLike,
                        onDownload = actions.onDownload,
                        downloaded = downloaded,
                        downloading = downloading,
                        tint = onBackground,
                        buttonSize = actionSize,
                        iconSize = actionIcon,
                        sleepRemainingMs = sleepRemainingMs,
                        onSleepTimer = onSleepTimer,
                        buttons = playerButtons,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (durationMs > 0) {
                    // A thin line with a small mark on it, not a Material slider with a lozenge
                    // thumb and a chunky track. This is a readout that happens to be draggable - it
                    // is looked at constantly and touched rarely, so it should be quiet.
                    //
                    // Plain, too. The value gradient is for controls whose setting means something,
                    // and "how far through the song" is not a setting - colouring it implied the
                    // start of a track was a low value of something and the end a high one.
                    SlimSeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        colour = onBackground,
                        onSeek = onSeek,
                        style = seekBarStyle,
                        playing = playback is PlaybackState.Playing,
                    )
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PositionLabel(positionMs, onBackground)
                        Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall, color = onBackground)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Laid out exactly as Player.kt's controlsBlock does it: every button in its own
                // weighted box so they spread evenly across the width, with play/pause sitting
                // outside the weighting at its natural size between two 8dp spacers. Packing them
                // with fixed spacing instead - which is what this used to do - leaves the row
                // bunched to one side and the big button off centre.
                transportRow()

                if (playback is PlaybackState.Failed) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(playback.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        }
        }

        if (playerLayout?.block("queue")?.visible != false) QueueBar(
            queue = queue,
            onColour = onBackground,
            open = queueOpen,
            onToggle = { queueOpen = !queueOpen },
            sideways = sideBySide,
            showTitle = showQueueTitle,
        )
    }

        // Only when it is not docked beside the player - see sideBySide above. Over the player
        // rather than inside the column, so on a narrow window it covers things the way the
        // Android sheet does instead of squeezing the controls into whatever is left.
        if (!sideBySide) {
            QueueOverlay(
                queue = queue,
                savedQueues = savedQueues,
                onSwitchQueue = onSwitchQueue,
                onRemoveSavedQueue = onRemoveSavedQueue,
                playback = playback,
                onColour = onBackground,
                background = bottom,
                open = queueOpen,
                onClose = { queueOpen = false },
                onJump = {
                    onJumpToQueueIndex(it)
                    queueOpen = false
                },
                onMove = onMoveInQueue,
                onRemove = onRemoveFromQueue,
            )
        }
    }

    // Beside the player, in the Row, so the two share the window instead of one hiding the other.
    // Jumping to a track does not close it here: the whole point of having both on screen is that
    // the queue stays readable while the player plays.
    //
    // Its width animates rather than the panel sliding over the edge, so opening it pushes the
    // player aside and the player shrinks to make room - the cover and controls resize as it goes,
    // since everything there is sized from the space it is given - and closing it hands the room
    // back the same way. A spring with no bounce, like the phone's sheets.
    if (sideBySide) {
        val dockWidth by animateDpAsState(
            targetValue = if (queueOpen) QUEUE_DOCK_WIDTH else 0.dp,
            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        )
        if (dockWidth > 0.dp) {
            Box(modifier = Modifier.width(dockWidth).fillMaxHeight().clipToBounds()) {
                QueueOverlay(
                    queue = queue,
                    savedQueues = savedQueues,
                    onSwitchQueue = onSwitchQueue,
                    onRemoveSavedQueue = onRemoveSavedQueue,
                    playback = playback,
                    onColour = onBackground,
                    background = bottom,
                    // Always shown inside the box: the box's width is the animation.
                    open = true,
                    onClose = { queueOpen = false },
                    onJump = onJumpToQueueIndex,
                    onMove = onMoveInQueue,
                    onRemove = onRemoveFromQueue,
                    docked = true,
                    // Laid out at full width and pinned to the left edge, so as the box opens it
                    // reveals the queue rather than squashing its rows.
                    modifier = Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(QUEUE_DOCK_WIDTH)
                        .fillMaxHeight(),
                )
            }
        }
    }
    }

    // Over the whole window, docked queue included, and for the same reason the queue covers the
    // player on a narrow window: an equaliser sharing the window got a strip to fit twelve sliders,
    // five knobs, a graph and a search box into, and squeezed everything else to a sliver.
    if (equalizer != null) {
        EqualizerOverlay(
            equalizer = equalizer,
            timeStretch = timeStretch,
            compressor = compressor,
            colourByValue = colourByValue,
            eqStore = eqStore,
            balance = balance,
            onColour = onBackground,
            background = bottom,
            open = equalizerOpen,
            onClose = { equalizerOpen = false },
            eqSpectrum = eqSpectrum,
            playedFrames = playedFrames,
        )
    }
    }
    }
}

/**
 * The seek bar as Android's slim style draws it: one rounded line, filled to the position, with no
 * thumb. The Material slider's thumb sat a pixel or two off the track's centre line at this
 * thickness, which is the kind of thing that cannot be unseen once noticed.
 *
 * Thickens a little under the pointer and while dragging, so it still reads as something to grab.
 * While dragging, the line follows the pointer and the seek happens on release - seeking on every
 * pixel of a drag would restart the stream dozens of times.
 */
@Composable
private fun SlimSeekBar(
    positionMs: () -> Long,
    durationMs: Long,
    colour: Color,
    onSeek: (Long) -> Unit,
    style: SeekBarStyle = SeekBarStyle.Squiggly,
    playing: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val active = hovered || dragFraction != null
    val thickness by animateDpAsState(
        when (style) {
            SeekBarStyle.Slim -> if (active) 7.dp else 4.dp
            SeekBarStyle.Default -> if (active) 12.dp else 9.dp
            SeekBarStyle.Squiggly -> if (active) 6.dp else 4.dp
        }
    )
    val currentDuration by androidx.compose.runtime.rememberUpdatedState(durationMs)

    // The phone's squiggle: the played part waves while the song plays, and flattens out when it
    // is paused or being dragged - a wave under the pointer would make the target hard to read.
    val waving = style == SeekBarStyle.Squiggly && playing && dragFraction == null
    val amplitude by animateDpAsState(if (waving) 3.dp else 0.dp, animationSpec = tween(300))
    val phase = remember { Animatable(0f) }
    LaunchedEffect(waving) {
        if (waving) {
            phase.snapTo(0f)
            phase.animateTo(
                (2 * PI).toFloat(),
                infiniteRepeatable(tween(2500, easing = LinearEasing), androidx.compose.animation.core.RepeatMode.Restart),
            )
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f).times(currentDuration).toLong())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * currentDuration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        // Position read here, in drawing, so a playback tick redraws the bar and recomposes nothing.
        val played = dragFraction
            ?: if (currentDuration > 0) (positionMs().toFloat() / currentDuration).coerceIn(0f, 1f) else 0f
        val stroke = thickness.toPx()
        val y = size.height / 2
        val left = stroke / 2
        val right = size.width - stroke / 2
        val end = left + (right - left) * played
        val amp = amplitude.toPx()
        if (amp < 0.5f) {
            drawLine(colour.copy(alpha = 0.22f), Offset(end, y), Offset(right, y), stroke, StrokeCap.Round)
            drawLine(colour, Offset(left, y), Offset(end, y), stroke, StrokeCap.Round)
            return@Canvas
        }
        // Waving: the rest of the track starts a little past the wave so its round end does not
        // show beside the wave's off-centre tip.
        val gap = 3.dp.toPx()
        if (end + gap < right) {
            drawLine(colour.copy(alpha = 0.22f), Offset(end + gap, y), Offset(right, y), stroke, StrokeCap.Round)
        }
        val wavelength = 36.dp.toPx()
        val step = wavelength / 12
        val path = Path().apply {
            moveTo(left, y + amp * sin(phase.value))
            var x = step
            while (left + x <= end) {
                lineTo(left + x, y + amp * sin((2 * PI).toFloat() * x / wavelength + phase.value))
                x += step
            }
            lineTo(end, y + amp * sin((2 * PI).toFloat() * (end - left) / wavelength + phase.value))
        }
        drawPath(path, colour, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The elapsed time under the seek bar, alone in its own scope so a tick redraws only this. */
@Composable
private fun PositionLabel(positionMs: () -> Long, colour: Color) {
    Text(formatDuration(positionMs()), style = MaterialTheme.typography.bodySmall, color = colour)
}

/** Below this the queue covers the player; at or above it, the two sit side by side. */
private val QUEUE_DOCK_MIN_WIDTH = 1000.dp

/**
 * How much of the window the docked queue takes.
 *
 * Fixed rather than a fraction. A queue row is a cover, a title and an artist, and that needs about
 * this much whatever the window is doing - given a share of a very wide window it would stretch to
 * a line of text with a great deal of nothing after it.
 */
private val QUEUE_DOCK_WIDTH = 380.dp

/**
 * What sits behind the player.
 *
 * All four styles are driven by the cover, because the player being tinted by whatever is playing is
 * the one thing about the Android player's look that carries across - Android draws it with a
 * `RuntimeShader`, which does not exist off Android, so this is the intent rather than the code.
 *
 * The two blurred styles paint the cover itself rather than colours sampled from it. That is a
 * different effect and worth having both: a gradient is calm and always legible, while the blurred
 * cover keeps the artwork's own shapes and is far more striking on art with strong composition. The
 * scrim over them is not decoration - white text over an unscrimmed blurred cover is unreadable on
 * anything pale, and the whole point of taking colours from the art is that they are unpredictable.
 */
@Composable
private fun PlayerBackground(
    style: BackgroundStyle,
    thumbnail: String?,
    top: Color,
    bottom: Color,
    liquidTap: VisualizerTap? = null,
    playedFrames: () -> Long = { 0L },
    playing: Boolean = false,
    ferroReactivity: Float = 1f,
) {
    when (style) {
        BackgroundStyle.Gradient ->
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))

        BackgroundStyle.Ferrofluid -> Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
            // A pool along the bottom of the player, under the controls; lit by the cover's colours.
            if (liquidTap != null && ferrofluidAvailable) {
                FerrofluidVisualiser(
                    tap = liquidTap,
                    playedFrames = playedFrames,
                    playing = playing,
                    keyColor = lerp(Color.White, MaterialTheme.colorScheme.primaryContainer, 0.12f),
                    rimColor = MaterialTheme.colorScheme.primary,
                    baseColor = top,
                    reactivity = ferroReactivity,
                    // The lower part of the window only, so the crown rises into the space under the
                    // controls rather than through the cover and the buttons.
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f),
                )
            }
        }

        BackgroundStyle.Solid ->
            Box(modifier = Modifier.fillMaxSize().background(top))

        BackgroundStyle.BlurredCover, BackgroundStyle.Frosted -> {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Sized to the longer edge so the square cover covers a landscape window with no
                // gaps, and blurred hard enough that the crop is not readable as a crop.
                val side = max(maxWidth, maxHeight)
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Artwork(
                        thumbnail,
                        size = side,
                        cornerRadius = 0.dp,
                        modifier = Modifier.blur(BLUR_RADIUS),
                    )
                }
                // Frosted adds a pale translucent pane over the blur, which is what makes it read as
                // glass rather than as an out-of-focus photograph; the plain blurred style just gets
                // enough darkening to keep the text legible.
                val scrim = if (style == BackgroundStyle.Frosted) {
                    Brush.verticalGradient(
                        listOf(top.copy(alpha = 0.55f), bottom.copy(alpha = 0.75f)),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.65f)),
                    )
                }
                Box(modifier = Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/** Enough blur that the cover reads as colour and shape rather than as a picture behind the text. */
private val BLUR_RADIUS = 60.dp

/**
 * The equaliser, over the whole window.
 *
 * Full window, not a drawer sharing the screen with the player. The panel holds a response graph,
 * sixteen presets, an AutoEQ search, twelve band sliders, two playback knobs and five compressor
 * knobs; giving it a slice of the window meant it got a scrollbar and the player got a sliver, and
 * neither was usable. It is also the right shape for what it is - the equaliser is opened, adjusted,
 * and closed again, and nothing on the player needs watching while that happens.
 *
 * Slides down from the top, since that is the edge its handle lives on and where scrolling up
 * summons it from.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EqualizerOverlay(
    equalizer: Equalizer,
    timeStretch: TimeStretch?,
    compressor: Compressor?,
    colourByValue: Boolean,
    eqStore: EqStore?,
    balance: Balance?,
    onColour: Color,
    background: Color,
    open: Boolean,
    onClose: () -> Unit,
    /** The analyser drawn behind the curve, and where playback has reached. Both or neither. */
    eqSpectrum: VisualizerTap?,
    playedFrames: () -> Long,
) {
    AnimatedVisibility(
        visible = open,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Opaque. Twelve sliders and a response curve over album art is not readable.
                .background(background)
                // Below the window buttons, or the close handle sits under the drag strip.
                .padding(top = LocalCaptionInset.current)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { },
                    onDragStopped = { velocity -> if (velocity < -300f) onClose() },
                ),
        ) {
            // The handle it was opened from, at the top, dragged the other way to close - the same
            // affordance reversed rather than a separate close button doing the same job.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClose)
                    .padding(vertical = 10.dp),
            ) {
                Icon(
                    OuterTuneIcons.expandLess,
                    contentDescription = "Close equaliser",
                    tint = onColour.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // Scrolling past the bottom closes it, which is the gesture that got it here reversed.
            // Accumulated rather than acted on immediately: a scroll that *ends* at the bottom would
            // otherwise close the panel the moment it arrived there, so reaching the last band would
            // dismiss the thing being reached for. A deliberate push past the end is a different
            // motion from merely landing on it, and the threshold is what tells them apart.
            val panelScroll = rememberScrollState()
            var pastBottom by remember { mutableStateOf(0f) }
            LaunchedEffect(open) { pastBottom = 0f }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val scrolled = event.changes.first().scrollDelta.y
                        val atBottom = panelScroll.value >= panelScroll.maxValue
                        pastBottom = when {
                            atBottom && scrolled > 0f -> pastBottom + scrolled
                            // Any movement the other way is someone reading again, not leaving.
                            else -> 0f
                        }
                        if (pastBottom >= CLOSE_OVERSCROLL) {
                            pastBottom = 0f
                            onClose()
                        }
                    }
                    .verticalScroll(panelScroll),
            ) {
                EqualizerPanel(
                    equalizer = equalizer,
                    accent = onColour,
                    onColour = onColour,
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp),
                    timeStretch = timeStretch,
                    compressor = compressor,
                    colourByValue = colourByValue,
                    store = eqStore,
                    balance = balance,
                    spectrum = eqSpectrum,
                    playedFrames = playedFrames,
                )
            }
        }
    }
}

/**
 * The queue's handle, along the bottom edge.
 *
 * A chevron above the queue's name, centred, which is what the Android player shows there. The name
 * rather than the next song: where the music came from is the thing a single line can usefully say,
 * and what is coming next is about to be visible anyway.
 *
 * Darkens under the pointer. That hover step is doing the work a "Show"/"Hide" label used to do
 * badly - the label sat next to the bar looking like an unpressable button beside a pressable one,
 * while the bar itself gave no sign it could be clicked at all.
 */
@Composable
private fun QueueBar(
    queue: QueueState,
    onColour: Color,
    open: Boolean,
    onToggle: () -> Unit,
    /** Docked beside the player: the queue opens from the right, so the arrow points that way. */
    sideways: Boolean = false,
    showTitle: Boolean = true,
) {
    if (sideways) {
        SidewaysQueueHandle(queue, onColour, open, onToggle, showTitle)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scrim by animateFloatAsState(if (hovered) 0.3f else 0.12f)
    val chevron by animateFloatAsState(if (open) 180f else 0f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = scrim))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            // Flicking up opens it, the same gesture as the sheet on the phone. Any upward throw
            // counts rather than one crossing a distance threshold, because the bar is only a few
            // tens of pixels tall and there is nowhere to drag from within it.
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { },
                onDragStopped = { velocity -> if (velocity < -300f) onToggle() },
            )
            .padding(vertical = 8.dp),
    ) {
        Icon(
            OuterTuneIcons.expandLess,
            contentDescription = "Show queue",
            tint = onColour.copy(alpha = 0.85f),
            modifier = Modifier.size(20.dp).rotate(chevron),
        )
        Text(
            text = queue.title,
            style = MaterialTheme.typography.bodyMedium,
            color = onColour.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The queue's handle when the queue docks beside the player.
 *
 * An arrow pointing the way the queue will move - left to bring it in from the right edge, right to
 * send it back - with the queue's name beside it, at the right-hand end of the bar where the queue
 * appears. The name can be turned off in Settings, as on the phone.
 */
@Composable
private fun SidewaysQueueHandle(
    queue: QueueState,
    onColour: Color,
    open: Boolean,
    onToggle: () -> Unit,
    showTitle: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scrim by animateFloatAsState(if (hovered) 0.3f else 0.12f)
    val turn by animateFloatAsState(if (open) 90f else -90f)
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = scrim))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        if (showTitle) {
            Text(
                text = queue.title,
                style = MaterialTheme.typography.bodyMedium,
                color = onColour.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp),
            )
        }
        Icon(
            OuterTuneIcons.expandLess,
            contentDescription = if (open) "Hide queue" else "Show queue",
            tint = onColour.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp).rotate(turn),
        )
    }
}

/**
 * The lyrics view's own controls: close, back to the cover; and a menu with the timing offset and
 * a way to fetch the lyrics again - the phone's lyrics header and menu.
 */
@Composable
private fun LyricsHeader(
    modifier: Modifier = Modifier,
    tint: Color,
    onClose: () -> Unit,
    onRefetch: (() -> Unit)?,
    offsetMs: Int,
    onOffsetChange: ((Int) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    var offsetDialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(OuterTuneIcons.moreVert, contentDescription = "Lyrics options", tint = tint)
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onOffsetChange != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Timing offset (${if (offsetMs > 0) "+" else ""}$offsetMs ms)") },
                        leadingIcon = { Icon(OuterTuneIcons.timer, null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; offsetDialog = true },
                    )
                }
                if (onRefetch != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Fetch lyrics again") },
                        leadingIcon = { Icon(OuterTuneIcons.restore, null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onRefetch() },
                    )
                }
            }
        }
        IconButton(onClick = onClose) {
            Icon(OuterTuneIcons.close, contentDescription = "Close lyrics", tint = tint)
        }
    }
    if (offsetDialog && onOffsetChange != null) {
        var value by remember { mutableStateOf(offsetMs) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { offsetDialog = false },
            title = { Text("Lyrics timing offset") },
            text = {
                Column {
                    Text(
                        "${if (value > 0) "+" else ""}$value ms - positive shows each line later.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.material3.Slider(
                        value = value.toFloat(),
                        onValueChange = { value = ((it / 10f).toInt() * 10).coerceIn(-3000, 3000) },
                        valueRange = -3000f..3000f,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onOffsetChange(value); offsetDialog = false }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { value = 0 }) { Text("Reset") }
                    TextButton(onClick = { offsetDialog = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/**
 * The queue itself, over the whole window.
 *
 * Full window rather than a panel along the bottom, because that is what the sheet on the phone
 * does and because a queue is read a screenful at a time - a strip showing four entries answers
 * "what is next" and nothing else, which the closed bar already answers in one line.
 *
 * Slides rather than appears. The motion is what says where it came from and, more usefully, where
 * it will go back to; a panel that simply materialises over the player leaves no hint that the
 * player is still underneath.
 */
@Composable
private fun QueueOverlay(
    queue: QueueState,
    savedQueues: List<SavedQueue> = emptyList(),
    onSwitchQueue: (SavedQueue) -> Unit = {},
    onRemoveSavedQueue: (SavedQueue) -> Unit = {},
    onColour: Color,
    playback: PlaybackState = PlaybackState.Idle,
    background: Color,
    open: Boolean,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether this is the panel beside the player rather than the sheet over it.
     *
     * Only the way it arrives differs. A sheet comes up from the bottom edge it is anchored to; a
     * panel comes in from the side it occupies, because a column of list sliding vertically into a
     * space beside something else reads as the wrong thing having moved.
     */
    docked: Boolean = false,
) {
    AnimatedVisibility(
        visible = open,
        enter = if (docked) {
            slideInHorizontally(initialOffsetX = { it }) + fadeIn()
        } else {
            slideInVertically(initialOffsetY = { it }) + fadeIn()
        },
        exit = if (docked) {
            slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        } else {
            slideOutVertically(targetOffsetY = { it }) + fadeOut()
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Opaque as a sheet, unlike the closed bar's scrim: a translucent list over album
                // art is a list that cannot be read. Docked, it sits over the cover's gradient
                // rather than the art, so a light scrim keeps it one surface with the player.
                .background(if (docked) Color.Black.copy(alpha = 0.22f) else background)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { },
                    onDragStopped = { velocity -> if (velocity > 300f) onClose() },
                ),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClose)
                    .padding(vertical = 10.dp),
            ) {
                Icon(
                    OuterTuneIcons.expandLess,
                    contentDescription = "Hide queue",
                    tint = onColour.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp).rotate(180f),
                )
            }

            // The phone's queue header: what the queue is on the left, where in it and how long it
            // runs on the right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 8.dp),
            ) {
                QueueSwitcher(
                    title = queue.title,
                    saved = savedQueues,
                    colour = onColour,
                    style = MaterialTheme.typography.titleMedium,
                    onSwitch = onSwitchQueue,
                    onRemove = onRemoveSavedQueue,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${queue.orderPosition + 1} / ${queue.songs.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onColour.copy(alpha = 0.8f),
                    )
                    Text(
                        text = formatDuration(queue.songs.sumOf { (it.duration ?: 0).toLong() } * 1000L),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onColour.copy(alpha = 0.8f),
                    )
                }
            }

            // Which row is being dragged, as a play-order position, and how far it has travelled
            // since the last time it stepped. Held here rather than per row so a row can hand the
            // drag on to its neighbour as it passes - the gesture belongs to the list, not to
            // whichever composable happened to start it.
            var dragging by remember { mutableStateOf(-1) }
            var dragOffset by remember { mutableStateOf(0f) }
            val rowHeightPx = with(LocalDensity.current) { QUEUE_ROW_HEIGHT.toPx() }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Play order, not the order added: with shuffle on, the list should read as what is
                // coming next, which is the only thing a queue is for.
                //
                // Keyed by song id so a composable follows its song across a reorder. Keyed by
                // position instead, every row would be told it now holds different content the
                // instant anything moved, and the drag in progress would be cancelled by its own
                // first step.
                itemsIndexed(queue.order, key = { _, index -> queue.songs.getOrNull(index)?.id ?: index }) { position, index ->
                    val song = queue.songs.getOrNull(index) ?: return@itemsIndexed
                    val current = position == queue.orderPosition
                    val held = position == dragging

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(QUEUE_ROW_HEIGHT)
                            // Lifted while held, so the row being moved is visibly the one under the
                            // pointer rather than one of several that shifted.
                            .graphicsLayer {
                                translationY = if (held) dragOffset else 0f
                                shadowElevation = if (held) 12f else 0f
                                alpha = if (held) 0.92f else 1f
                            }
                            // The playing row sits in a rounded tint, as on the phone, so it can be
                            // found in a long queue without reading every title.
                            .padding(horizontal = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    held -> onColour.copy(alpha = 0.10f)
                                    current -> onColour.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onJump(index) }
                            .padding(horizontal = 16.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Artwork(song.thumbnail, size = 44.dp)
                            if (current) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                ) {
                                    PlayingBars(
                                        modifier = Modifier.size(20.dp),
                                        animate = playback is PlaybackState.Playing,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = onColour.copy(alpha = if (current) 1f else 0.75f),
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = onColour.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        IconButton(onClick = { onRemove(position) }) {
                            Icon(
                                OuterTuneIcons.close,
                                contentDescription = "Remove from queue",
                                tint = onColour.copy(alpha = 0.55f),
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        // A handle rather than the whole row. The row is already a click target for
                        // jumping to a song, and a list where pressing anything might mean "drag" is
                        // a list that cannot be scrolled with confidence.
                        Icon(
                            OuterTuneIcons.dragHandle,
                            contentDescription = "Reorder",
                            tint = onColour.copy(alpha = 0.45f),
                            modifier = Modifier
                                .size(22.dp)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .pointerInput(song.id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = position
                                            dragOffset = 0f
                                        },
                                        onDragEnd = { dragging = -1; dragOffset = 0f },
                                        onDragCancel = { dragging = -1; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // Stepped a whole row at a time rather than continuously, so
                                        // the list settles between moves instead of thrashing while
                                        // the pointer crosses a boundary.
                                        val steps = (dragOffset / rowHeightPx).toInt()
                                        if (steps != 0 && dragging >= 0) {
                                            val target = (dragging + steps)
                                                .coerceIn(0, queue.order.lastIndex)
                                            if (target != dragging) {
                                                onMove(dragging, target)
                                                dragging = target
                                            }
                                            // Whatever was consumed by the step is taken off, so the
                                            // remainder still counts towards the next one.
                                            dragOffset -= steps * rowHeightPx
                                        }
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * How far past the bottom of the equaliser counts as asking to leave.
 *
 * In scroll units rather than pixels, since that is what the wheel reports. Roughly three notches -
 * enough that arriving at the end does not dismiss the panel, few enough that leaving is one gesture.
 */
private const val CLOSE_OVERSCROLL = 3f

/** Fixed, because the drag arithmetic above counts rows by height and has to be right about it. */
private val QUEUE_ROW_HEIGHT = 60.dp

/** What the seek buttons move by. */
private const val SEEK_STEP_DEFAULT_MS = 5_000L

/**
 * How quiet the wheel must go before another downward scroll can put the player away again.
 *
 * Long enough to outlast a trackpad's momentum, which keeps delivering events well after the
 * fingers have lifted, and short enough that deliberately doing it twice still works.
 */
private const val SCROLL_DISMISS_GAP_NS = 600_000_000L

/**
 * The song's own controls: like, download, equaliser, and the overflow menu.
 *
 * Filled circles rather than bare icons, which is what Player.kt's ActionButtons draws - a disc of
 * the primary colour holding an icon in onPrimary, seven density-independent pixels apart. The fill
 * is doing real work: these sit over album artwork, where a bare tinted glyph competes with whatever
 * happens to be behind it, and a solid disc does not.
 *
 * Sized by the caller rather than fixed at 36dp, for the same reason the transport row is - see the
 * scale note in the player body.
 */
@Composable
private fun ActionButtons(
    liked: Boolean,
    actions: PlayerActions,
    onToggleLike: () -> Unit,
    onDownload: (() -> Unit)?,
    downloaded: Boolean,
    downloading: Boolean,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
    sleepRemainingMs: Long?,
    onSleepTimer: (() -> Unit)?,
    buttons: List<PlayerButtonKind>,
) {
    Spacer(modifier = Modifier.width(10.dp))

    // Whatever was chosen in Settings > Player > Player buttons, left to right in that order.
    buttons.forEachIndexed { index, button ->
        if (index > 0) Spacer(modifier = Modifier.width(7.dp))
        val onClick: (() -> Unit)? = when (button) {
            PlayerButtonKind.LIKE -> onToggleLike
            PlayerButtonKind.MENU -> null // the menu draws its own button below
            PlayerButtonKind.SLEEP -> onSleepTimer
            PlayerButtonKind.EQUALIZER -> actions.onEqualizer
            PlayerButtonKind.DOWNLOAD -> onDownload
            PlayerButtonKind.LYRICS -> actions.onToggleLyrics
            PlayerButtonKind.PLAYLIST -> actions.onAddToPlaylist
            PlayerButtonKind.ARTIST -> actions.onViewArtist
            PlayerButtonKind.ALBUM -> actions.onViewAlbum
            PlayerButtonKind.SHARE -> actions.onShare
            PlayerButtonKind.DETAILS -> actions.onDetails
            PlayerButtonKind.RADIO -> actions.onStartRadio
            PlayerButtonKind.LIBRARY -> actions.onToggleLibrary
            PlayerButtonKind.ADVANCED -> actions.onEqualizer
        }
        when {
            button == PlayerButtonKind.MENU -> if (actions.any) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(buttonSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    PlayerOverflowMenu(
                        actions = actions,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        buttonSize = buttonSize,
                        iconSize = iconSize,
                    )
                }
            }

            // More than one artist: the button asks which, in the same panel the ⋮ uses, rather
            // than always opening whoever happens to be credited first.
            button == PlayerButtonKind.ARTIST && actions.artists.size > 1 && actions.onOpenArtist != null -> Box {
                var picking by remember { mutableStateOf(false) }
                ActionButton(onClick = { picking = true }, size = buttonSize) {
                    Icon(
                        imageVector = button.icon,
                        contentDescription = "Choose artist",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(iconSize),
                    )
                }
                if (picking) {
                    ArtistPickerPanel(
                        artists = actions.artists,
                        onPick = actions.onOpenArtist,
                        onDismiss = { picking = false },
                    )
                }
            }

            // A running timer shows the time left rather than the moon: "how long until the music
            // stops" should be answerable without opening anything.
            button == PlayerButtonKind.SLEEP && sleepRemainingMs != null && onSleepTimer != null -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(buttonSize)
                    .clip(RoundedCornerShape(buttonSize / 2))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onSleepTimer)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = if (sleepRemainingMs > 0) SleepTimer.format(sleepRemainingMs) else "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }

            else -> ActionButton(onClick = onClick, size = buttonSize) {
                val glyphTint = MaterialTheme.colorScheme.onPrimary
                when {
                    button == PlayerButtonKind.DOWNLOAD && downloading -> CircularProgressIndicator(
                        color = glyphTint,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(iconSize * 0.8f),
                    )
                    else -> Icon(
                        imageVector = when {
                            button == PlayerButtonKind.LIKE && liked -> OuterTuneIcons.favorite
                            button == PlayerButtonKind.LIBRARY && actions.inLibrary -> OuterTuneIcons.libraryAddCheck
                            // The phone's downloaded mark; pressing it again removes the file.
                            button == PlayerButtonKind.DOWNLOAD && downloaded -> OuterTuneIcons.offlinePin
                            else -> button.icon
                        },
                        contentDescription = if (button == PlayerButtonKind.DOWNLOAD && downloaded) "Remove download" else button.label,
                        tint = glyphTint,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

/** One filled disc, sized and coloured as Android's action buttons are. */
@Composable
internal fun ActionButton(onClick: (() -> Unit)?, size: Dp, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            // An action this song cannot do - no album to view, say - stays in its place, dimmed,
            // rather than vanishing and shifting the rest of the row along.
            .graphicsLayer { alpha = if (onClick != null) 1f else 0.45f }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        content()
    }
}

/**
 * A heart that reacts when pressed.
 *
 * The glyph swapping between outline and filled is a state change with no motion, so it reads as
 * nothing happening - the button looked broken even though it worked. A brief scale-up on liking,
 * and a colour that animates rather than cuts, is enough to make the press feel like it landed.
 * Only liking springs; unliking just fades, because celebrating a removal is odd.
 */
@Composable
fun LikeButton(liked: Boolean, tint: Color, onClick: () -> Unit, size: Dp = 24.dp) {
    val colour by animateColorAsState(if (liked) Color(0xFFFF4D6D) else tint)
    val scale by animateFloatAsState(
        targetValue = if (liked) 1.25f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (liked) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder,
            contentDescription = if (liked) "Unlike" else "Like",
            tint = colour,
            modifier = Modifier.scale(scale).size(size),
        )
    }
}

/**
 * Black or white, whichever is legible on [background].
 *
 * Perceived brightness rather than a plain average: the eye is far more sensitive to green than to
 * blue, so averaging the channels calls a saturated blue "bright" and puts black text on it.
 */
private fun contentColourFor(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.55f) Color.Black else Color.White
}

/** Pulls a colour towards black, so a cover's own colour can sit behind text without fighting it. */
private fun Color.darken(amount: Float): Color =
    Color(red * (1 - amount), green * (1 - amount), blue * (1 - amount), alpha)

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    // Hours once there are any - a queue's total runs past an hour easily, and "95:00" reads as a
    // typo rather than as an hour and a half.
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
    }
}

/**
 * A layout block at its place: centred on ([PlayerLayout.Block.x]%, [PlayerLayout.Block.y]%) of the
 * player area, at its scale and rotation. Its width is decided by the content - see the caller.
 */
@Composable
private fun PlacedBlock(block: PlayerLayout.Block, areaW: Dp, areaH: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val cx = areaW.toPx() * block.x / 100f
                    val cy = areaH.toPx() * block.y / 100f
                    placeable.place((cx - placeable.width / 2f).roundToInt(), (cy - placeable.height / 2f).roundToInt())
                }
            }
            .graphicsLayer {
                scaleX = block.scale
                scaleY = block.scale
                rotationZ = block.rotation
            },
    ) { content() }
}

/**
 * Whether the play/pause button offers "play": whenever nothing is playing or about to.
 *
 * It used to be only while Paused, so before anything had started - a queue restored from last
 * time, the state Idle - the button showed pause, and pressing it started the song while it went
 * on saying pause. Loading still shows pause: the song has been asked for and is on its way.
 */
internal val PlaybackState.showsPlay: Boolean
    get() = this is PlaybackState.Paused || this is PlaybackState.Idle || this is PlaybackState.Failed
