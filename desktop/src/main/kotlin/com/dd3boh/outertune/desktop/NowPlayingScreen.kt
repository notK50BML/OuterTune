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
                Text(
                    text = song?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song?.artists?.joinToString { it.name }?.ifBlank { "Unknown artist" } ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = onBackground.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            OuterTuneIcons.shuffle,
                            contentDescription = "Shuffle",
                            // Dimmed rather than hidden when off: a control that disappears is
                            // harder to find again than one that is plainly inactive.
                            tint = onBackground.copy(alpha = if (queue.shuffled) 1f else 0.4f),
                        )
                    }
                    IconButton(onClick = onPrevious, enabled = queue.hasPrevious) {
                        Icon(OuterTuneIcons.skipPrevious, "Previous", tint = onBackground)
                    }
                    IconButton(
                        onClick = { onSeek((positionMs - SEEK_STEP_MS).coerceAtLeast(0)) },
                        enabled = durationMs > 0,
                    ) {
                        Icon(OuterTuneIcons.fastRewind, "Back 5 seconds", tint = onBackground)
                    }
                    // The one filled control, because play/pause is the button being reached for.
                    FilledIconButton(onClick = onTogglePause, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (playback is PlaybackState.Paused) OuterTuneIcons.play else OuterTuneIcons.pause,
                            contentDescription = if (playback is PlaybackState.Paused) "Play" else "Pause",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(
                        onClick = { onSeek((positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)) },
                        enabled = durationMs > 0,
                    ) {
                        Icon(OuterTuneIcons.fastForward, "Forward 5 seconds", tint = onBackground)
                    }
                    IconButton(onClick = onNext, enabled = queue.hasNext) {
                        Icon(OuterTuneIcons.skipNext, "Next", tint = onBackground)
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            if (queue.repeat == RepeatMode.ONE) OuterTuneIcons.repeatOne else OuterTuneIcons.repeat,
                            contentDescription = "Repeat",
                            tint = onBackground.copy(alpha = if (queue.repeat == RepeatMode.OFF) 0.4f else 1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // A second row, quieter than the first. The Android player draws the same
                // distinction: transport in one row, everything else in another - so the buttons
                // being reached for constantly are not sharing space with the ones touched once a
                // month. Smaller and dimmer for the same reason.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LikeButton(liked = liked, tint = onBackground.copy(alpha = 0.8f), onClick = onToggleLike)
                    if (equalizer != null) {
                        IconButton(onClick = onToggleEqualizer) {
                            Icon(
                                OuterTuneIcons.equalizer,
                                contentDescription = "Equaliser",
                                tint = onBackground.copy(alpha = 0.8f),
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
 * Collapsed it is one row saying what is next, which is the only thing a queue is usually asked.
 * Expanded it lists the lot. Clicking the bar toggles it - the bar is the button, so there is no
 * separate control to find.
 */
@Composable
private fun QueueSheet(
    queue: QueueState,
    onColour: Color,
    onJump: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val upNext = remember(queue) {
        queue.order.getOrNull(queue.orderPosition + 1)?.let { queue.songs.getOrNull(it) }
    }

    Surface(color = Color.Black.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 28.dp, vertical = 10.dp),
            ) {
                Icon(
                    OuterTuneIcons.queueMusic,
                    contentDescription = null,
                    tint = onColour.copy(alpha = 0.8f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (expanded) {
                        "Queue  ${queue.orderPosition + 1}/${queue.songs.size}"
                    } else {
                        upNext?.let { "Up next: ${it.title}" } ?: "Queue  ${queue.songs.size}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColour,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    color = onColour.copy(alpha = 0.7f),
                )
            }

            AnimatedVisibility(visible = expanded) {
                LazyColumn(
                    // Bounded, so a long queue cannot push the player off the top of the window.
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                ) {
                    // Play order, not the order added: with shuffle on, the list should read as what
                    // is coming next, which is the only thing a queue is for.
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
fun LikeButton(liked: Boolean, tint: Color, onClick: () -> Unit) {
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
            modifier = Modifier.scale(scale),
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

/** What the seek buttons move by - the same five seconds the Android player uses. */
private const val SEEK_STEP_MS = 5_000L

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
