/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The bar along the bottom - the phone's mini player.
 *
 * Laid out as MiniPlayer.kt is: the cover and the song, then plain icon buttons, all at the phone's
 * sizes. Between the two sit whatever extras Settings > Player > Mini player buttons asks for (see
 * [MiniPlayerButtonKind]), so a desktop's spare width can carry shuffle or the volume without the
 * default bar looking any different from the phone's.
 *
 * Present whether or not anything is playing, until it is put away by scrolling down over it - see
 * [MiniPlayerScrollGesture]. Clicking it, or scrolling up over it, opens the full player.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerBar(
    playback: PlaybackState,
    /** A live stream: the progress line full, LIVE in place of the clock. */
    live: Boolean = false,
    queue: QueueState,
    positionMs: () -> Long,
    durationMs: Long,
    liked: Boolean,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onExpand: () -> Unit,
    queueOpen: Boolean = true,
    onToggleQueue: () -> Unit = {},
    /** The extras, left to right - see [MiniPlayerButtonKind]. */
    buttons: List<MiniPlayerButtonKind> = MiniPlayerButtonKind.decode(""),
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    volume: Float = 1f,
    onVolumeChange: (Float) -> Unit = {},
    /** Opens the full player on the lyrics. */
    onLyrics: () -> Unit = {},
    /** Time left on the sleep timer, 0 for "end of this song", or null when none is set. */
    sleepRemainingMs: Long? = null,
    onSleepTimer: () -> Unit = {},
    /** Stops playback and puts the bar away, keeping the queue - the phone's swipe down. */
    onDismiss: () -> Unit = {},
    /** Every scroll in the window, so a scroll that began elsewhere does not act here. */
    scrollStreaks: ScrollStreaks = remember { ScrollStreaks() },
) {
    val song = queue.current?.shown()
    val scroll = remember(scrollStreaks) { MiniPlayerScrollGesture(scrollStreaks) }
    val dismissable by rememberUpdatedState(song != null)
    val expand by rememberUpdatedState(onExpand)
    val dismiss by rememberUpdatedState(onDismiss)
    // A Box rather than a Column, so the progress line can sit on the bottom edge as the phone's
    // does - underlining the bar against whatever it covers instead of drawing a rule between it
    // and the content above, which is what stacking them in a Column amounted to.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MINI_PLAYER_HEIGHT)
            // The rail's colour, so the two frame the content as one surface. The phone leaves this
            // off because its mini player sits on the navigation bar and is already separated from
            // the list; here it floats over scrolling content and would read as part of it.
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
            // The phone's vertical swipes, as the wheel: up for the full player, down to put the
            // music away. Main pass, so the volume button's own wheel handling has already taken
            // its scrolls by the time they bubble up here.
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.first()
                if (change.isConsumed) return@onPointerEvent
                when (scroll.feed(change.uptimeMillis, change.scrollDelta.y)) {
                    MiniPlayerScrollAction.Expand -> expand()
                    // Nothing playing is nothing to put away: the bar is then the only way to the
                    // player, and hiding it would leave the window with no sign there is one.
                    MiniPlayerScrollAction.Dismiss -> if (dismissable) dismiss()
                    null -> return@onPointerEvent
                }
                change.consume()
            }
            .clickable(onClick = onExpand),
    ) {
        LinearProgressIndicator(
            progress = { if (live) 1f else if (durationMs > 0) positionMs().toFloat() / durationMs else 0f },
            drawStopIndicator = { },
            modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        ) {
            // The phone's colour for its mini player buttons, which are plain icons rather than
            // filled discs: the bar is a summary of the player, not a second copy of its controls.
            val tint = MaterialTheme.colorScheme.onSecondaryContainer
            // The phone's treatment: the artwork carries the state. A song that will not load says
            // so on the thing that is not loading, rather than in the artist line - which is where
            // it used to go, and which meant a failure read as the song simply being by somebody
            // called "no internet connection".
            Box(modifier = Modifier.padding(6.dp).size(ARTWORK_SIZE)) {
                Artwork(song?.thumbnail, size = ARTWORK_SIZE, cornerRadius = THUMBNAIL_CORNER)
                // Named in full: inside the Row, the plain name means the Row's own version, which
                // only works on the Row's direct children.
                androidx.compose.animation.AnimatedVisibility(
                    visible = playback is PlaybackState.Loading || playback is PlaybackState.Failed,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(ARTWORK_SIZE)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(THUMBNAIL_CORNER)),
                    ) {
                        if (playback is PlaybackState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                OuterTuneIcons.info,
                                contentDescription = (playback as? PlaybackState.Failed)?.reason,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    text = song?.title ?: "Nothing playing",
                    color = if (song == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // The reason still belongs in words somewhere, since the icon only says that
                    // something went wrong and not what - but it no longer displaces the artist on
                    // the ordinary loading case, which is most of what this line ever shows.
                    text = when (playback) {
                        is PlaybackState.Failed -> playback.reason
                        else -> song?.artists?.joinToString { it.name }?.ifBlank { "Unknown artist" }
                            ?: "Search for something to play"
                    },
                    color = if (playback is PlaybackState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Whatever was chosen in Settings > Player > Mini player buttons, in that order.
            buttons.forEach { button ->
                when (button) {
                    MiniPlayerButtonKind.CLOCK -> if (song != null) {
                        Box(modifier = Modifier.padding(horizontal = 6.dp)) {
                            if (live) LiveLabel() else ClockText(positionMs, durationMs)
                        }
                    }
                    // Nothing to like for a file on this computer - see PlayerButtonKind.forLocalFiles.
                    MiniPlayerButtonKind.LIKE -> if (song != null && !LocalMusic.isLocal(song.id)) {
                        LikeButton(liked = liked, tint = tint, onClick = onToggleLike)
                    }
                    MiniPlayerButtonKind.QUEUE -> IconButton(onClick = onToggleQueue, enabled = queue.songs.isNotEmpty()) {
                        Icon(
                            OuterTuneIcons.queueMusicRounded,
                            contentDescription = if (queueOpen) "Hide queue" else "Show queue",
                            tint = if (queueOpen) MaterialTheme.colorScheme.primary else tint,
                        )
                    }
                    // Fully opaque either way, as on the full player: the arrows themselves say
                    // whether it is on - straight when off, crossed when on.
                    MiniPlayerButtonKind.SHUFFLE -> IconButton(onClick = onToggleShuffle, enabled = queue.songs.isNotEmpty()) {
                        ShuffleIcon(on = queue.shuffled, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    MiniPlayerButtonKind.REPEAT -> IconButton(onClick = onCycleRepeat) {
                        Icon(
                            if (queue.repeat == RepeatMode.ONE) OuterTuneIcons.repeatOne else OuterTuneIcons.repeat,
                            contentDescription = "Repeat",
                            tint = tint.copy(alpha = if (queue.repeat == RepeatMode.OFF) 0.4f else 1f),
                        )
                    }
                    MiniPlayerButtonKind.VOLUME -> VolumeButton(volume = volume, onChange = onVolumeChange, tint = tint)
                    MiniPlayerButtonKind.LYRICS -> IconButton(onClick = onLyrics, enabled = song != null) {
                        Icon(OuterTuneIcons.lyrics, contentDescription = "Lyrics", tint = tint)
                    }
                    // A running timer shows the time left rather than the moon, as the full player's
                    // button does: "how long until the music stops" should not need a click.
                    MiniPlayerButtonKind.SLEEP -> if (sleepRemainingMs != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onSleepTimer)
                                .padding(horizontal = 10.dp),
                        ) {
                            Text(
                                text = if (sleepRemainingMs > 0) SleepTimer.format(sleepRemainingMs) else "—",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    } else {
                        IconButton(onClick = onSleepTimer) {
                            Icon(OuterTuneIcons.bedtime, contentDescription = "Sleep timer", tint = tint)
                        }
                    }
                }
            }

            // Previous and next only while there is somewhere to go, rather than greyed out: a
            // dimmed button at the end of a queue is one more thing to read for nothing. They fold
            // away sideways so play slides over instead of jumping.
            AnimatedVisibility(
                visible = queue.hasPrevious,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(OuterTuneIcons.skipPrevious, "Previous", tint = tint)
                }
            }
            // Pause only while something is actually playing or about to; idle or failed, the button
            // offers what pressing it would do, which is play.
            val active = playback is PlaybackState.Playing || playback is PlaybackState.Loading
            IconButton(onClick = onTogglePause, enabled = song != null) {
                Icon(
                    if (active) OuterTuneIcons.pauseRounded else OuterTuneIcons.playRounded,
                    contentDescription = if (active) "Pause" else "Play",
                    tint = tint.copy(alpha = if (song != null) 1f else 0.5f),
                )
            }
            AnimatedVisibility(
                visible = queue.hasNext,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                IconButton(onClick = onNext) {
                    Icon(OuterTuneIcons.skipNext, "Next", tint = tint)
                }
            }
        }
    }
}

/**
 * The volume, as one icon: hovering and scrolling turns it up or down, clicking opens a slider.
 *
 * The icon says roughly how loud it is - off, quiet or loud - so the level can be read at a glance
 * without opening anything. The slider is for setting it exactly; the wheel is for nudging it, which
 * is most of what anyone does to a volume.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeButton(volume: Float, onChange: (Float) -> Unit, tint: Color) {
    var open by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(volume)
    val change by rememberUpdatedState(onChange)
    // Taken here and consumed, so the bar under it does not also read the scroll as a gesture.
    val wheel = Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
        val scrolled = event.changes.first().scrollDelta.y
        if (scrolled != 0f) {
            // Up is negative, and up should mean louder.
            change((current - scrolled * VOLUME_STEP).coerceIn(0f, 1f))
            event.changes.forEach { it.consume() }
        }
    }
    val percent = (volume * 100).roundToInt()
    Box {
        IconButton(onClick = { open = !open }, modifier = wheel) {
            Icon(volumeIcon(volume), contentDescription = "Volume $percent%", tint = tint)
        }
        if (open) {
            // What it was before muting from the slider's own icon, to go back to.
            var beforeMute by remember { mutableStateOf<Float?>(null) }
            Popup(
                popupPositionProvider = AboveAnchor,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = wheel,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(250.dp).padding(start = 4.dp, end = 14.dp),
                    ) {
                        IconButton(
                            onClick = {
                                val before = beforeMute
                                if (volume > 0f) {
                                    beforeMute = volume
                                    onChange(0f)
                                } else {
                                    beforeMute = null
                                    onChange(before ?: 1f)
                                }
                            },
                        ) {
                            Icon(
                                volumeIcon(volume),
                                contentDescription = if (volume > 0f) "Mute" else "Unmute",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Slider(
                            value = volume,
                            onValueChange = {
                                beforeMute = null
                                onChange(it)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$percent%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(min = 38.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun volumeIcon(volume: Float): ImageVector = when {
    volume <= 0f -> OuterTuneIcons.volumeOff
    volume < 0.5f -> OuterTuneIcons.volumeDown
    else -> OuterTuneIcons.volumeUp
}

/** Centred above whatever opened it, kept inside the window - a popup from a bar along the bottom. */
private object AboveAnchor : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.center.x - popupContentSize.width / 2
        val y = anchorBounds.top - popupContentSize.height - 4
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/** The bar's clock, on its own so a tick redraws these few characters and nothing else. */
@Composable
private fun ClockText(positionMs: () -> Long, durationMs: Long) {
    Text(
        "${formatClock(positionMs())} / ${formatClock(durationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What a deliberate scroll over the mini player does. */
internal enum class MiniPlayerScrollAction { Expand, Dismiss }

/**
 * Every scroll in the window, grouped into streaks - runs of wheel events with no quiet gap between
 * them, which is what one flick of a wheel or a trackpad amounts to.
 *
 * The window notes each scroll on its way down (see Main.kt) before anything inside sees it, so the
 * mini player can tell a streak that began over it from the tail of one that began over the list
 * above and drifted down, or the momentum left over from scrolling the full player shut. And once a
 * streak has been acted on it is [spent]: the window swallows the rest of it, so the full player
 * that a scroll up just opened does not take the remainder as a request for its equaliser.
 *
 * Times are the events' own uptime, in ms, so the window and the bar agree on which event began a
 * streak - each reading a clock of its own would not.
 */
internal class ScrollStreaks(private val quietMs: Long = SCROLL_STREAK_QUIET_MS) {
    /** When the current streak began. */
    var startedMs = Long.MIN_VALUE
        private set

    /** Whether something has acted on the current streak - the rest of it should do nothing. */
    var spent = false
        private set

    private var lastMs = Long.MIN_VALUE

    /** Records a scroll at [timeMs]. The same event noted twice changes nothing. */
    fun note(timeMs: Long) {
        if (lastMs == Long.MIN_VALUE || timeMs - lastMs > quietMs) {
            startedMs = timeMs
            spent = false
        }
        lastMs = timeMs
    }

    fun spend() {
        spent = true
    }
}

/**
 * Turns scrolls over the mini player into the phone's two vertical swipes - or, much more often,
 * into nothing.
 *
 * Both are deliberate on purpose. Down stops the music, and the bar sits right under the list most
 * scrolling happens in, so a stray tick or a flick that overran has to fall short: it takes about
 * two notches in the same direction, within a moment of each other, and the streak has to have
 * begun over the bar - a scroll started on the list above that wanders down does nothing here.
 */
internal class MiniPlayerScrollGesture(private val streaks: ScrollStreaks) {
    /** The streak this gesture is counting, or one it is not part of. */
    private var counting = Long.MIN_VALUE
    private var total = 0f

    /** Feeds one scroll event - [deltaY] is the wheel's, negative for up. Returns what it amounts to, once. */
    fun feed(timeMs: Long, deltaY: Float): MiniPlayerScrollAction? {
        streaks.note(timeMs)
        // This very event began the streak, so it began here.
        if (streaks.startedMs == timeMs) {
            counting = timeMs
            total = 0f
        }
        if (counting != streaks.startedMs || streaks.spent || deltaY == 0f) return null
        // Slower than one gesture: whatever this is, it is not a flick at the bar.
        if (timeMs - counting > MINI_SCROLL_WINDOW_MS) return null
        // Turning back is changing one's mind, so it starts the count again the other way.
        total = if (total != 0f && sign(total) != sign(deltaY)) deltaY else total + deltaY
        val action = when {
            total <= -MINI_SCROLL_THRESHOLD -> MiniPlayerScrollAction.Expand
            total >= MINI_SCROLL_THRESHOLD -> MiniPlayerScrollAction.Dismiss
            else -> return null
        }
        streaks.spend()
        total = 0f
        return action
    }
}

/**
 * How far a scroll over the mini player must go to act, in wheel notches - one notch is 1. Two, so
 * a single tick never stops the music; a trackpad gets there with a short, firm swipe.
 */
internal const val MINI_SCROLL_THRESHOLD = 2f

/** How soon after it starts a scroll over the mini player must reach [MINI_SCROLL_THRESHOLD]. */
internal const val MINI_SCROLL_WINDOW_MS = 600L

/**
 * How quiet the wheel must go before the next scroll is a new streak. The same as the full player's
 * gap for putting itself away: long enough to outlast a trackpad's momentum, short enough that doing
 * it again on purpose still works.
 */
internal const val SCROLL_STREAK_QUIET_MS = 600L

/** How much one wheel notch over the volume button moves it. */
private const val VOLUME_STEP = 0.05f

/** The bar's height, and the artwork and rounding inside it - the phone's Dimensions.kt numbers. */
private val MINI_PLAYER_HEIGHT = 64.dp
private val ARTWORK_SIZE = 48.dp
private val THUMBNAIL_CORNER = 6.dp
