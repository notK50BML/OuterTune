/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.max
import androidx.compose.material3.CircularProgressIndicator
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
    positionMs: Long,
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
    playedFrames: () -> Long = { 0L },
    /** What the overflow menu offers. Items with no callback do not appear - see [PlayerActions]. */
    actions: PlayerActions = PlayerActions(),
    lyrics: Lyrics? = null,
    lyricsLoading: Boolean = false,
    lyricsOnCoverClick: Boolean = true,
    downloaded: Boolean = false,
    downloading: Boolean = false,
    backgroundStyle: BackgroundStyle = BackgroundStyle.Gradient,
    colourByValue: Boolean = true,
    onDownload: (() -> Unit)? = null,
    equalizer: Equalizer? = null,
    /** Tempo and pitch, if the player exposes them. Null hides the dials rather than faking them. */
    timeStretch: TimeStretch? = null,
    compressor: Compressor? = null,
    onJumpToQueueIndex: (Int) -> Unit = {},
    onOpenArtist: (StoredArtist) -> Unit = {},
    /**
     * Whether the five-second seek buttons appear.
     *
     * Off, as on Android, where the seek increment defaults to OFF and the buttons are simply
     * absent - a seven-button transport row is a lot to read past to find play.
     */
    showSeekButtons: Boolean = false,
) {
    // Held here rather than inside the drawer: the handle at the top and the button in the actions
    // row both open the same panel, exactly as they do on Android, and two copies of the state would
    // mean each one only knew about its own taps.
    var equalizerOpen by remember { mutableStateOf(false) }
    val onToggleEqualizer = { equalizerOpen = !equalizerOpen }

    val song = queue.current
    val (primary, secondary) = rememberArtworkColours(song?.thumbnail)
    val top by animateColorAsState(primary.darken(0.45f))
    val bottom by animateColorAsState(secondary.darken(0.75f))
    val onBackground = contentColourFor(top)

    // Two states, not three. The queue either is not in the way or has the window; a middle
    // "peeking" step meant the bar took two clicks to get back from, which is one more than a
    // toggle should ever need.
    var queueOpen by remember { mutableStateOf(false) }

    // Reset whenever the song changes. Lyrics left open across a track change would show the
    // previous song's words while the new one plays, which is worse than showing nothing - and the
    // cover of what is now playing is the more useful thing to land on.
    var showLyrics by remember(song?.id) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
    PlayerBackground(
        style = backgroundStyle,
        thumbnail = song?.thumbnail,
        top = top,
        bottom = bottom,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scrolling up at the top of the player pulls the equaliser down, which is the gesture
            // the drawer's own handle implies and the one that was missing - the handle could only
            // be clicked, so "scroll up to see it" simply did nothing.
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (equalizer == null) return@onPointerEvent
                val change = event.changes.first()
                // Only a scroll nothing else wanted. This handler sits on the whole player, so
                // without the check, scrolling the lyrics or the queue - both of which are inside it
                // - would also drag the equaliser down over what was being read.
                if (change.isConsumed) return@onPointerEvent
                val scrolled = change.scrollDelta.y
                if (scrolled < 0f && !equalizerOpen) equalizerOpen = true
                else if (scrolled > 0f && equalizerOpen) equalizerOpen = false
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
        val actionSize = 40.dp * scale
        val actionIcon = 24.dp * scale

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
                    LyricsPane(
                        lyrics = lyrics,
                        loading = lyricsLoading,
                        positionMs = positionMs,
                        onSeek = onSeek,
                        onColour = onBackground,
                        fontSize = 20.sp * scale,
                        modifier = Modifier.fillMaxHeight(),
                    )
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
                modifier = Modifier.weight(1f).fillMaxHeight(),
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
                    Slider(
                        value = positionMs.coerceIn(0, durationMs).toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..durationMs.toFloat(),
                        track = { state ->
                            val fraction = if (durationMs > 0) {
                                (state.value / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(onBackground.copy(alpha = 0.22f)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(3.dp)
                                        .clip(CircleShape)
                                        .background(onBackground),
                                )
                            }
                        },
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(onBackground),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall, color = onBackground)
                        Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall, color = onBackground)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Laid out exactly as Player.kt's controlsBlock does it: every button in its own
                // weighted box so they spread evenly across the width, with play/pause sitting
                // outside the weighting at its natural size between two 8dp spacers. Packing them
                // with fixed spacing instead - which is what this used to do - leaves the row
                // bunched to one side and the big button off centre.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = onToggleShuffle, modifier = Modifier.size(transportSize)) {
                            Icon(
                                OuterTuneIcons.shuffle,
                                contentDescription = "Shuffle",
                                // Dimmed rather than hidden when off: a control that disappears is
                                // harder to find again than one that is plainly inactive.
                                tint = onBackground.copy(alpha = if (queue.shuffled) 1f else 0.4f),
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
                                onClick = { onSeek((positionMs - SEEK_STEP_MS).coerceAtLeast(0)) },
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
                        if (playback is PlaybackState.Paused) playSize / 2 else playSize * 0.28f
                    )
                    FilledIconButton(
                        onClick = onTogglePause,
                        shape = RoundedCornerShape(playCorner),
                        modifier = Modifier.size(playSize),
                    ) {
                        Icon(
                            if (playback is PlaybackState.Paused) OuterTuneIcons.play else OuterTuneIcons.pause,
                            contentDescription = if (playback is PlaybackState.Paused) "Play" else "Pause",
                            modifier = Modifier.size(playIcon),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (showSeekButtons) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            IconButton(
                                onClick = { onSeek((positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)) },
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

                if (playback is PlaybackState.Failed) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(playback.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        }

        QueueBar(
            queue = queue,
            onColour = onBackground,
            open = queueOpen,
            onToggle = { queueOpen = !queueOpen },
        )
    }

        // Over the player rather than inside the column, and for the same reason as the queue: an
        // equaliser sharing the window with the player got a strip to fit twelve sliders, five
        // knobs, a graph and a search box into, and squeezed the player to a sliver to do it.
        if (equalizer != null) {
            EqualizerOverlay(
                equalizer = equalizer,
                timeStretch = timeStretch,
                compressor = compressor,
                colourByValue = colourByValue,
                onColour = onBackground,
                background = bottom,
                open = equalizerOpen,
                onClose = { equalizerOpen = false },
            )
        }

        // Over the player rather than inside the column, so opening it covers the window the way the
        // Android sheet does instead of squeezing the controls into whatever is left.
        QueueOverlay(
            queue = queue,
            onColour = onBackground,
            background = bottom,
            open = queueOpen,
            onClose = { queueOpen = false },
            onJump = {
                onJumpToQueueIndex(it)
                queueOpen = false
            },
        )
    }
}

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
) {
    when (style) {
        BackgroundStyle.Gradient ->
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))

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
@Composable
private fun EqualizerOverlay(
    equalizer: Equalizer,
    timeStretch: TimeStretch?,
    compressor: Compressor?,
    colourByValue: Boolean,
    onColour: Color,
    background: Color,
    open: Boolean,
    onClose: () -> Unit,
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

            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EqualizerPanel(
                    equalizer = equalizer,
                    accent = onColour,
                    onColour = onColour,
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp),
                    timeStretch = timeStretch,
                    compressor = compressor,
                    colourByValue = colourByValue,
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
) {
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
    onColour: Color,
    background: Color,
    open: Boolean,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Opaque, unlike the closed bar's scrim. A translucent list over album art is a
                // list that cannot be read.
                .background(background)
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
                Text(
                    text = "${queue.title}  ${queue.orderPosition + 1}/${queue.songs.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = onColour,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Play order, not the order added: with shuffle on, the list should read as what is
                // coming next, which is the only thing a queue is for.
                itemsIndexed(queue.order) { position, index ->
                    val song = queue.songs.getOrNull(index) ?: return@itemsIndexed
                    val current = position == queue.orderPosition
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(index) }
                            .padding(horizontal = 36.dp, vertical = 8.dp),
                    ) {
                        Artwork(song.thumbnail, size = 44.dp)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
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
                    }
                }
            }
        }
    }
}

/** What the seek buttons move by - the same five seconds the Android player uses. */
private const val SEEK_STEP_MS = 5_000L

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
) {
    Spacer(modifier = Modifier.width(10.dp))

    ActionButton(onClick = onToggleLike, size = buttonSize) {
        Icon(
            imageVector = if (liked) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder,
            contentDescription = if (liked) "Unlike" else "Like",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(iconSize),
        )
    }

    // Beside the like button rather than only in the menu. Liking and saving a song are the two
    // things done to a track while it plays, and one of them being two clicks deeper than the other
    // is an accident of where each happened to be added.
    if (onDownload != null) {
        Spacer(modifier = Modifier.width(7.dp))
        ActionButton(onClick = onDownload, size = buttonSize) {
            when {
                // A spinner in place of the glyph rather than beside it, so the button does not
                // change size mid-download and shift everything after it along the row.
                downloading -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(iconSize * 0.8f),
                )
                else -> Icon(
                    OuterTuneIcons.download,
                    contentDescription = if (downloaded) "Remove download" else "Download",
                    // Dimmed once it is downloaded, which reads as "done" while keeping the button
                    // where it was - swapping in a tick would make the row jump and would hide the
                    // fact that pressing it again removes the file.
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (downloaded) 0.45f else 1f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }

    if (actions.onEqualizer != null) {
        Spacer(modifier = Modifier.width(7.dp))
        ActionButton(onClick = actions.onEqualizer, size = buttonSize) {
            Icon(
                OuterTuneIcons.equalizer,
                contentDescription = "Equaliser",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(iconSize),
            )
        }
    }

    if (actions.any) {
        Spacer(modifier = Modifier.width(7.dp))
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
}

/** One filled disc, sized and coloured as Android's action buttons are. */
@Composable
private fun ActionButton(onClick: () -> Unit, size: Dp, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
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
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
