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
) {
    val song = queue.current
    val (primary, secondary) = rememberArtworkColours(song?.thumbnail)
    val top by animateColorAsState(primary.darken(0.45f))
    val bottom by animateColorAsState(secondary.darken(0.75f))
    val onBackground = contentColourFor(top)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(28.dp),
    ) {
        IconButton(onClick = onClose) {
        Icon(OuterTuneIcons.close, contentDescription = "Back", tint = onBackground)
    }

        // Cover beside the controls rather than above them. A desktop window is wide and short, so
        // stacking wastes the width and squeezes the art into whatever height is left; side by side
        // lets the cover be as large as the window is tall.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
        ) {
            Artwork(song?.thumbnail, size = 360.dp)

            Column(modifier = Modifier.weight(1f)) {
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

                // Above the progress bar rather than behind the cover. It is a readout of the
                // sound, and it belongs with the other readouts of the sound - and behind the art it
                // would have to be faded so far to keep the cover legible that there would be
                // nothing left to see.
                if (spectrum != null) {
                    SpectrumBars(
                        tap = spectrum,
                        playedFrames = playedFrames,
                        color = onBackground,
                        active = playback is PlaybackState.Playing,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Folded away by default. Twelve sliders is the largest thing on this screen, and
                // most of the time nobody is adjusting them - a player that opens on its equaliser
                // has its priorities the wrong way round.
                if (equalizer != null) {
                    var showEq by remember { mutableStateOf(false) }
                    TextButton(onClick = { showEq = !showEq }) {
                        Text(
                            text = if (showEq) "Hide equaliser" else "Equaliser",
                            color = onBackground,
                        )
                    }
                    if (showEq) {
                        EqualizerPanel(
                            equalizer = equalizer,
                            accent = onBackground,
                            onColour = onBackground,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
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
                    // The one filled control, because play/pause is the button being reached for.
                    FilledIconButton(onClick = onTogglePause, modifier = Modifier.size(64.dp)) {
                        Icon(
                            if (playback is PlaybackState.Paused) OuterTuneIcons.play else OuterTuneIcons.pause,
                            contentDescription = if (playback is PlaybackState.Paused) "Play" else "Pause",
                            modifier = Modifier.size(32.dp),
                        )
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
                    LikeButton(liked = liked, tint = onBackground, onClick = onToggleLike)
                }

                if (playback is PlaybackState.Failed) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(playback.reason, color = MaterialTheme.colorScheme.error)
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

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
