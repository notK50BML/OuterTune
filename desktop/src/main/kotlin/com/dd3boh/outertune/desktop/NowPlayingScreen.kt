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
    spectrum: VisualizerTap? = null,
    playedFrames: () -> Long = { 0L },
    equalizer: Equalizer? = null,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        // Pulled down from the top edge rather than sitting among the controls, which is where the
        // Android player keeps it. An equaliser is something adjusted once and then left alone, so a
        // button for it beside play and pause gives it standing it has not earned - and twelve
        // sliders is the largest thing on this screen.
        if (equalizer != null) {
            EqualizerDrawer(
                equalizer = equalizer,
                onColour = onBackground,
                open = equalizerOpen,
                onToggle = { equalizerOpen = !equalizerOpen },
            )
        }

        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(OuterTuneIcons.close, contentDescription = "Back", tint = onBackground)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // The cover takes whatever height the window gives it, rather than a fixed size. A
            // desktop window is resized constantly and a 360dp square looked like a postage stamp
            // the moment anyone maximised it - the art should be as large as the window is tall,
            // which is what the Android landscape player does.
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxHeight().weight(1f),
            ) {
                val side = min(maxHeight - 24.dp, maxWidth)
                Artwork(song?.thumbnail, size = side.coerceAtLeast(120.dp))
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
                            style = MaterialTheme.typography.headlineMedium,
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
                            style = MaterialTheme.typography.titleMedium,
                            onClick = onOpenArtist,
                        )
                    }
                    ActionButtons(
                        liked = liked,
                        showEqualizer = equalizer != null,
                        onToggleLike = onToggleLike,
                        onToggleEqualizer = onToggleEqualizer,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (spectrum != null) {
                    SpectrumBars(
                        tap = spectrum,
                        playedFrames = playedFrames,
                        color = onBackground,
                        active = playback is PlaybackState.Playing,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (durationMs > 0) {
                    Slider(
                        value = positionMs.coerceIn(0, durationMs).toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..durationMs.toFloat(),
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
                        IconButton(onClick = onToggleShuffle) {
                            Icon(
                                OuterTuneIcons.shuffle,
                                contentDescription = "Shuffle",
                                // Dimmed rather than hidden when off: a control that disappears is
                                // harder to find again than one that is plainly inactive.
                                tint = onBackground.copy(alpha = if (queue.shuffled) 1f else 0.4f),
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = onPrevious, enabled = queue.hasPrevious) {
                            Icon(OuterTuneIcons.skipPrevious, "Previous", tint = onBackground)
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
                    FilledIconButton(onClick = onTogglePause, modifier = Modifier.size(72.dp)) {
                        Icon(
                            if (playback is PlaybackState.Paused) OuterTuneIcons.play else OuterTuneIcons.pause,
                            contentDescription = if (playback is PlaybackState.Paused) "Play" else "Pause",
                            modifier = Modifier.size(36.dp),
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
                        IconButton(onClick = onNext, enabled = queue.hasNext) {
                            Icon(OuterTuneIcons.skipNext, "Next", tint = onBackground)
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = onCycleRepeat) {
                            Icon(
                                if (queue.repeat == RepeatMode.ONE) OuterTuneIcons.repeatOne else OuterTuneIcons.repeat,
                                contentDescription = "Repeat",
                                tint = onBackground.copy(alpha = if (queue.repeat == RepeatMode.OFF) 0.4f else 1f),
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

        // Along the bottom edge, opened by the bar itself - the shape the Android player's queue
        // sheet has. Anchored to the window rather than placed in the controls column so it is in
        // the same place whatever the window size, which is what makes it findable without looking.
        QueueSheet(
            queue = queue,
            onColour = onBackground,
            onJump = onJumpToQueueIndex,
        )
    }
}

/**
 * The equaliser, behind a handle at the top edge.
 *
 * Closed it is a thin grab bar; open it slides the panel down over the player. That is the Android
 * arrangement, and it is right for the same reason there: the equaliser is set once and then
 * forgotten, so it should be reachable without being present.
 */
@Composable
private fun ColumnScope.EqualizerDrawer(
    equalizer: Equalizer,
    onColour: Color,
    open: Boolean,
    onToggle: () -> Unit,
) {
    AnimatedVisibility(visible = open) {
        Surface(
            color = Color.Black.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EqualizerPanel(
                equalizer = equalizer,
                accent = onColour,
                onColour = onColour,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clickable(onClick = onToggle),
    ) {
        // A grab bar rather than a labelled button. It reads as "there is more up here" without
        // spending a word on it, and it is the same affordance the Android player uses.
        Box(
            modifier = Modifier
                .width(if (open) 56.dp else 40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(onColour.copy(alpha = if (open) 0.7f else 0.35f)),
        )
    }
}

/**
 * The queue, along the bottom.
 *
 * Three states rather than two, because a queue is asked two different questions. Closed it is a
 * chevron and what is playing next, which answers the common one without taking any room. Peeking -
 * one click - shows a few entries, which answers "what is coming up" without covering the player.
 * Open covers most of the window, for actually working through a long queue.
 *
 * No "Show"/"Hide" label. The bar is the control, and a word saying so is a word explaining an
 * affordance that is already obvious - which reads as an unpressable button sitting next to a
 * pressable bar.
 *
 * Blended rather than panelled: a scrim that deepens with the state, so at rest it is barely there
 * over the artwork and only becomes a surface once it has content to hold.
 */
@Composable
private fun QueueSheet(
    queue: QueueState,
    onColour: Color,
    onJump: (Int) -> Unit,
) {
    var state by remember { mutableStateOf(QueueSheetState.Closed) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Deepens as it opens, and a little on hover. The hover step is what makes the bar read as
    // something that can be clicked without a word saying so.
    val scrim by animateFloatAsState(
        when {
            state == QueueSheetState.Open -> 0.55f
            state == QueueSheetState.Peek -> 0.4f
            hovered -> 0.28f
            else -> 0.12f
        }
    )
    val chevron by animateFloatAsState(if (state == QueueSheetState.Closed) 0f else 180f)

    val listHeight by animateDpAsState(
        when (state) {
            QueueSheetState.Closed -> 0.dp
            QueueSheetState.Peek -> 132.dp
            QueueSheetState.Open -> 360.dp
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = scrim))
            // A flick decides between peeking and opening, so the gesture matches the intent: a
            // small drag asks to see a little, a decisive one asks for the lot.
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { },
                onDragStopped = { velocity ->
                    state = when {
                        velocity < -800f -> QueueSheetState.Open
                        velocity > 800f -> QueueSheetState.Closed
                        else -> state
                    }
                },
            ),
    ) {
        // Centred, chevron above the name, matching the Android player's collapsed handle. The
        // whole strip is the target rather than the chevron alone - a 24dp glyph is a small thing
        // to hit with a mouse, and the row is already reserved for this.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) {
                    // One click steps through rather than toggling, so the same gesture that opens
                    // it a little opens it fully next time.
                    state = when (state) {
                        QueueSheetState.Closed -> QueueSheetState.Peek
                        QueueSheetState.Peek -> QueueSheetState.Open
                        QueueSheetState.Open -> QueueSheetState.Closed
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            Icon(
                OuterTuneIcons.expandLess,
                contentDescription = if (state == QueueSheetState.Closed) "Show queue" else "Hide queue",
                tint = onColour.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp).rotate(chevron),
            )
            Text(
                text = if (state == QueueSheetState.Closed) queue.title
                else "${queue.title}  ${queue.orderPosition + 1}/${queue.songs.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = onColour.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (listHeight > 0.dp) {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(listHeight)) {
                // Play order, not the order added: with shuffle on, the list should read as what is
                // coming next, which is the only thing a queue is for.
                itemsIndexed(queue.order) { position, index ->
                    val song = queue.songs.getOrNull(index) ?: return@itemsIndexed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(index) }
                            .padding(horizontal = 28.dp, vertical = 6.dp),
                    ) {
                        Artwork(song.thumbnail, size = 32.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onColour.copy(alpha = if (position == queue.orderPosition) 1f else 0.7f),
                            fontWeight = if (position == queue.orderPosition) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private enum class QueueSheetState { Closed, Peek, Open }

/** What the seek buttons move by - the same five seconds the Android player uses. */
private const val SEEK_STEP_MS = 5_000L

/**
 * The song's own controls: like, equaliser, and the overflow menu.
 *
 * Filled circles rather than bare icons, which is what Player.kt's ActionButtons draws - a 36dp
 * circle of the primary colour holding a 24dp icon in onPrimary, seven density-independent pixels
 * apart. The fill is doing real work here: these sit over album artwork, where a bare tinted glyph
 * competes with whatever happens to be behind it, and a solid disc does not.
 *
 * No sleep timer. Android leads with one; the desktop player has no sleep timer to offer yet, and a
 * button that opens nothing is worse than an absent one.
 */
@Composable
private fun ActionButtons(
    liked: Boolean,
    showEqualizer: Boolean,
    onToggleLike: () -> Unit,
    onToggleEqualizer: () -> Unit,
) {
    Spacer(modifier = Modifier.width(10.dp))

    ActionButton(onClick = onToggleLike) {
        Icon(
            imageVector = if (liked) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder,
            contentDescription = if (liked) "Unlike" else "Like",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(24.dp),
        )
    }

    if (showEqualizer) {
        Spacer(modifier = Modifier.width(7.dp))
        ActionButton(onClick = onToggleEqualizer) {
            Icon(
                OuterTuneIcons.equalizer,
                contentDescription = "Equaliser",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** One filled disc, sized and coloured as Android's action buttons are. */
@Composable
private fun ActionButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
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
