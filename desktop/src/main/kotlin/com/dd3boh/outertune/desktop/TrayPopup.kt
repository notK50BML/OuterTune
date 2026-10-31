/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.delay
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The icon in the notification area, and the small player that opens from it.
 *
 * Compose's own `Tray` can only hang a native AWT menu off the icon, which on Windows is a plain
 * grey list of words in the system font - no cover, no colour, no sign of which song it would
 * pause. So the [TrayIcon] is managed here directly, with no native menu at all, and a right-click
 * opens [TrayPopupCard] in a window of its own instead: the mini player's look in the theme the app
 * is showing, the way Windows 11's own media flyout sits over the taskbar.
 *
 * A single left-click opens the app, as most tray icons on Windows do. AWT's action event - which is
 * all Compose's `Tray` offered - only fires on a double-click there, and is kept only for the
 * keyboard: Win+B, the arrows to the icon, then Enter. Showing is not a toggle, so the double-click
 * that also sends it does no more than the single click before it already did.
 *
 * The popup can be pinned, which turns it into a small widget: it stays open whatever else is
 * clicked, stays above other windows, can be dragged anywhere, and comes back where it was left the
 * next time OuterTune starts. Unpinning it closes it; a right-click on the icon opens it again.
 */
@Composable
internal fun OuterTuneTray(
    icon: Painter,
    tooltip: String,
    player: DesktopPlayer,
    library: LibraryStore,
    settings: Settings,
    onShow: () -> Unit,
    onQuit: () -> Unit,
) {
    // Checked once: a desktop session either has a notification area or it does not.
    val supported = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
    if (!supported) return
    val show by rememberUpdatedState(onShow)
    // Where the popup is open, or null while it is closed. A fresh window each time it opens rather
    // than one kept hidden: it is open for seconds at a time, and a hidden window still holds its
    // drawing surface - the same memory MemoryTrim goes to the trouble of giving back for the
    // main one. Pinned when the app last closed, it opens straight away where it was left.
    var placement by remember {
        mutableStateOf(if (settings.trayPinned) TrayPopupPlacement.pinned(settings.trayPinnedAt) else null)
    }
    // Bumped by a right-click while the widget is pinned: rather than a second popup, the one that
    // is already open comes to the front.
    var frontRequests by remember { mutableStateOf(0) }

    // The same size Compose's Tray draws the icon at, at the screen's scale, so it is as sharp as the
    // notification area's other icons rather than a large image squeezed down by the shell.
    val awtIcon = remember(icon) {
        val scale = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0).toFloat()
        val tray = runCatching { SystemTray.getSystemTray().trayIconSize }.getOrNull() ?: Dimension(16, 16)
        icon.toAwtImage(Density(scale), LayoutDirection.Ltr, Size(tray.width.toFloat(), tray.height.toFloat()))
    }
    val tray = remember { TrayIcon(awtIcon).apply { isImageAutoSize = true } }
    SideEffect {
        if (tray.image !== awtIcon) tray.image = awtIcon
        if (tray.toolTip != tooltip) tray.toolTip = tooltip
    }
    DisposableEffect(tray) {
        val mouse = object : MouseAdapter() {
            // On release, which is when Windows reports a right-click as the popup trigger, and so
            // when its own tray menus open too.
            override fun mouseReleased(e: MouseEvent) {
                when {
                    e.isPopupTrigger || e.button == MouseEvent.BUTTON3 -> {
                        if (settings.trayPinned && placement != null) frontRequests++
                        else placement = TrayPopupPlacement.atPointer()
                    }
                    // Only the first click of a double-click: the second has a count of two.
                    e.button == MouseEvent.BUTTON1 && e.clickCount == 1 -> {
                        if (!settings.trayPinned) placement = null
                        show()
                    }
                }
            }
        }
        val action = ActionListener {
            if (!settings.trayPinned) placement = null
            show()
        }
        tray.addMouseListener(mouse)
        tray.addActionListener(action)
        runCatching { SystemTray.getSystemTray().add(tray) }
        onDispose {
            tray.removeMouseListener(mouse)
            tray.removeActionListener(action)
            runCatching { SystemTray.getSystemTray().remove(tray) }
        }
    }

    placement?.let { at ->
        // Keyed, so a second right-click while it is open starts a new window where the pointer now
        // is, rather than moving the old one.
        key(at) {
            TrayPopupWindow(
                at = at,
                player = player,
                library = library,
                pinned = settings.trayPinned,
                frontRequests = frontRequests,
                // Only this window's own closing: a replaced window losing focus as it goes must not
                // take its replacement with it.
                onDismiss = { if (placement === at) placement = null },
                onTogglePin = { window ->
                    if (settings.trayPinned) {
                        settings.trayPinned = false
                        placement = null
                    } else {
                        settings.trayPinned = true
                        settings.trayPinnedAt = PinnedSpots.with(settings.trayPinnedAt, window)
                    }
                },
                onMoved = { window -> settings.trayPinnedAt = PinnedSpots.with(settings.trayPinnedAt, window) },
                onShow = {
                    // A pinned widget stays where it is; it was put there to stay.
                    if (!settings.trayPinned) placement = null
                    show()
                },
                onQuit = onQuit,
                icon = icon,
            )
        }
    }
}

/** The popup's window: undecorated, see-through around the card's shadow, above everything. */
@Composable
private fun TrayPopupWindow(
    at: TrayPopupPlacement,
    player: DesktopPlayer,
    library: LibraryStore,
    pinned: Boolean,
    frontRequests: Int,
    onDismiss: () -> Unit,
    onTogglePin: (java.awt.Window) -> Unit,
    onMoved: (java.awt.Window) -> Unit,
    onShow: () -> Unit,
    onQuit: () -> Unit,
    icon: Painter,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    val pinnedNow by rememberUpdatedState(pinned)
    Window(
        onPreviewKeyEvent = {
            // Escape closes the popup, not the widget: pinned, only the pin does that.
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && !pinnedNow) {
                dismiss()
                true
            } else {
                false
            }
        },
        create = {
            ComposeWindow(at.configuration).apply {
                // A tool window: no taskbar button and no place in Alt+Tab, whether it is a popup
                // that is gone again a moment later or a widget pinned to one corner of the screen.
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                isResizable = false
                isAlwaysOnTop = true
                title = "OuterTune"
                bounds = at.bounds
                // Gone as soon as anything else is clicked - another window, the desktop, the
                // taskbar - as a menu would be. Pinned, it stays, and only makes sure that whatever
                // took the focus has not also taken its place on top.
                addWindowFocusListener(object : WindowAdapter() {
                    override fun windowLostFocus(e: WindowEvent) {
                        if (pinnedNow) NativeWindows.keepOnTop(this@apply) else dismiss()
                    }
                })
            }
        },
        dispose = { it.dispose() },
    ) {
        // Activated, so that clicking elsewhere takes the focus away and closes it - a window that
        // never had the focus never loses it. Windows lets a program bring itself forward just after
        // its tray icon is clicked; toFront asks, and the direct call covers a refusal. Not for a
        // widget brought back at start-up, which would take the focus from whatever was being used.
        val opened = remember { frontRequests }
        LaunchedEffect(frontRequests) {
            if (at.restored && frontRequests == opened) return@LaunchedEffect
            window.toFront()
            window.requestFocus()
            NativeWindows.bring(window)
        }
        // Always on top is a request Windows can quietly stop honouring - another always-on-top
        // window shown later goes above it, and a full-screen one can push it down - so a pinned
        // widget asks again now and then. Cheap: it moves nothing and takes no focus.
        LaunchedEffect(pinned) {
            while (pinned) {
                delay(2_000)
                NativeWindows.keepOnTop(window)
            }
        }
        val playback by player.state.collectAsState()
        val queue by TrayControls.queue.collectAsState()
        val live by player.live.collectAsState()
        // The theme the main window is showing, cover colours and font included; until it has drawn
        // once, the app's default for the system's light or dark.
        MaterialTheme(
            colorScheme = TrayControls.colors ?: DynamicTheme.schemeFor(null, isSystemInDarkTheme()),
            typography = TrayControls.typography ?: Typography(),
        ) {
            CompositionLocalProvider(LocalCredits provides library.credits.collectAsState().value) {
                TrayPopupSurface(
                    modifier = if (pinned) Modifier.dragsWindow(window) { onMoved(window) } else Modifier,
                ) {
                    TrayPopupCard(
                        playback = playback,
                        queue = queue,
                        live = live,
                        positionMs = { TrayControls.positionMs() },
                        durationMs = TrayControls.durationMs,
                        appIcon = icon,
                        onTogglePause = { TrayControls.togglePlay() },
                        onNext = { TrayControls.next() },
                        onPrevious = { TrayControls.previous() },
                        onSeek = { TrayControls.seekTo(it) },
                        onToggleShuffle = { TrayControls.toggleShuffle() },
                        onCycleRepeat = { TrayControls.cycleRepeat() },
                        pinned = pinned,
                        onTogglePin = { onTogglePin(window) },
                        onShow = onShow,
                        onQuit = onQuit,
                    )
                }
            }
        }
    }
}

/**
 * Moves [window] with the pointer while its body is dragged.
 *
 * Only presses nothing inside has taken: the buttons and the seek bar keep their own, since they see
 * a press first and claim it. Moved by where the pointer is on the screen rather than within the
 * window, which would itself move under the pointer with every step and feed back into the next.
 */
private fun Modifier.dragsWindow(window: java.awt.Window, onMoved: () -> Unit) = pointerInput(window) {
    awaitEachGesture {
        awaitFirstDown()
        val start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        val origin = window.location
        var moved = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull() ?: break
            if (!change.pressed) break
            val now = MouseInfo.getPointerInfo()?.location ?: continue
            if (now != start || moved) {
                window.setLocation(origin.x + now.x - start.x, origin.y + now.y - start.y)
                moved = true
            }
            change.consume()
        }
        // Remembered once it is put down, rather than at every step on the way.
        if (moved) onMoved()
    }
}

/**
 * The card with room around it for its shadow, which is drawn into the window's transparent margin -
 * the window itself has no frame to cast one.
 */
@Composable
internal fun TrayPopupSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(TRAY_POPUP_MARGIN)) {
        Surface(
            shape = RoundedCornerShape(TRAY_POPUP_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainer,
            // A hairline as well as the shadow: on a dark taskbar a dark card's shadow is all but
            // invisible, and it is the edge that says where the card stops.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .width(TRAY_POPUP_WIDTH)
                .height(TRAY_POPUP_HEIGHT)
                .shadow(10.dp, RoundedCornerShape(TRAY_POPUP_CORNER), clip = false)
                .then(modifier),
        ) {
            content()
        }
    }
}

/**
 * What the tray popup shows: the song, a slim seek bar with the time either side, the transport
 * buttons with shuffle and repeat at either end, and the two things the old menu could do besides -
 * open the app, or quit it. A pin in the corner keeps it open as a widget.
 *
 * Every part is a fixed height, so the window can be sized and placed before it is drawn - see
 * [TRAY_POPUP_HEIGHT] - and does not jump as songs with and without an artist line come and go.
 */
@Composable
internal fun TrayPopupCard(
    playback: PlaybackState,
    queue: QueueState,
    live: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    appIcon: Painter,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onShow: () -> Unit,
    onQuit: () -> Unit,
) {
    val song = queue.current?.shown()
    // The mini player's colour for its buttons - see PlayerBar.
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = SECTION_PADDING)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(COVER_SIZE).padding(horizontal = SECTION_PADDING),
            ) {
                Box(modifier = Modifier.size(COVER_SIZE)) {
                    Artwork(song?.thumbnail, size = COVER_SIZE, cornerRadius = 10.dp)
                    // An empty tile on its own reads as a cover still loading; the note says there is
                    // simply nothing there.
                    if (song == null) {
                        Icon(
                            OuterTuneIcons.musicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).size(28.dp),
                        )
                    }
                }
                // Clear of the pin in the corner, so a long title stops short of it.
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 20.dp)) {
                    Text(
                        text = song?.title ?: "Nothing playing",
                        color = if (song == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // As the mini player words it, a failure's reason included - see PlayerBar.
                        text = when (playback) {
                            is PlaybackState.Failed -> playback.reason
                            else -> song?.artists?.joinToString { it.name }?.ifBlank { "Unknown artist" }
                                ?: "Pick a song in OuterTune"
                        },
                        color = if (playback is PlaybackState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            TraySeek(
                positionMs = positionMs,
                durationMs = durationMs,
                live = live,
                hasSong = song != null,
                onSeek = onSeek,
            )
            Row(
                // Shuffle and repeat the same width as previous and next, so play stays in the middle.
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(CONTROLS_HEIGHT),
            ) {
                val hasQueue = queue.songs.isNotEmpty()
                // As the mini player draws it: fully opaque either way, the arrows themselves saying
                // whether it is on - straight when off, crossed when on. Dimmed only with nothing to
                // shuffle.
                IconButton(onClick = onToggleShuffle, enabled = hasQueue) {
                    ShuffleIcon(
                        on = queue.shuffled,
                        tint = tint.copy(alpha = if (hasQueue) 1f else 0.38f),
                        modifier = Modifier.size(22.dp),
                    )
                }
                // Dimmed rather than hidden, as the mini player hides them: there they fold away beside
                // play, here play sits in the middle of the card and would be pushed off it.
                IconButton(onClick = onPrevious, enabled = queue.hasPrevious) {
                    Icon(
                        OuterTuneIcons.skipPrevious,
                        contentDescription = "Previous",
                        tint = tint.copy(alpha = if (queue.hasPrevious) 1f else 0.38f),
                        modifier = Modifier.size(26.dp),
                    )
                }
                // Pause only while something is playing or about to - see PlayerBar.
                val active = playback is PlaybackState.Playing || playback is PlaybackState.Loading
                FilledTonalIconButton(
                    onClick = onTogglePause,
                    enabled = song != null,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = tint,
                    ),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        if (active) OuterTuneIcons.pauseRounded else OuterTuneIcons.playRounded,
                        contentDescription = if (active) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = queue.hasNext) {
                    Icon(
                        OuterTuneIcons.skipNext,
                        contentDescription = "Next",
                        tint = tint.copy(alpha = if (queue.hasNext) 1f else 0.38f),
                        modifier = Modifier.size(26.dp),
                    )
                }
                // And repeat as the mini player has it too: dimmed while off.
                IconButton(onClick = onCycleRepeat, enabled = hasQueue) {
                    Icon(
                        if (queue.repeat == RepeatMode.ONE) OuterTuneIcons.repeatOne else OuterTuneIcons.repeat,
                        contentDescription = when (queue.repeat) {
                            RepeatMode.OFF -> "Repeat off"
                            RepeatMode.ALL -> "Repeat all"
                            RepeatMode.ONE -> "Repeat one"
                        },
                        tint = tint.copy(alpha = if (hasQueue && queue.repeat != RepeatMode.OFF) 1f else 0.4f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(MENU_PADDING))
            TrayMenuRow(text = "Show OuterTune", onClick = onShow) {
                // The app's own icon, in its own colours: this is the row that brings the app back.
                Image(appIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            TrayMenuRow(text = "Quit", onClick = onQuit) {
                Icon(
                    OuterTuneIcons.powerSettingsNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // The pin: an outline while it is a popup, filled and in the accent once it is a widget.
        IconButton(
            onClick = onTogglePin,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (pinned) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(32.dp),
        ) {
            Icon(
                if (pinned) OuterTuneIcons.pushPin else OuterTuneIcons.pushPinOutlined,
                contentDescription = if (pinned) "Unpin and close" else "Pin",
                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The line under the song, as a seek bar: click anywhere on it to go there, or drag along it. Slim
 * at rest, as the mini player's line is, and only thickening - with a dot at the playhead - under
 * the pointer, so it reads as a progress line until it is about to be used.
 *
 * The time either side, elapsed and total, follows the drag rather than the song while dragging, so
 * where it will land can be read before letting go. A live stream has nowhere to go: the line is
 * full, LIVE stands where the clock would be, and nothing reacts to the pointer.
 */
@Composable
private fun TraySeek(
    positionMs: () -> Long,
    durationMs: Long,
    live: Boolean,
    hasSong: Boolean,
    onSeek: (Long) -> Unit,
) {
    val seekable = hasSong && !live && durationMs > 0
    val seek by rememberUpdatedState(onSeek)
    val duration by rememberUpdatedState(durationMs)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Where the pointer has the playhead while it is held down; null otherwise.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val active = seekable && (hovered || dragFraction != null)
    val thickness by animateDpAsState(if (active) 6.dp else 4.dp)
    val played = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier = Modifier
            .padding(start = SECTION_PADDING, end = SECTION_PADDING, top = SEEK_GAP)
            .fillMaxWidth()
            // Taller than the line it draws, so it is not a 4dp target.
            .height(SEEK_HEIGHT)
            .then(
                if (seekable) {
                    Modifier
                        .hoverable(interaction)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                                val down = awaitFirstDown()
                                down.consume()
                                dragFraction = at(down.position.x)
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    dragFraction = at(change.position.x)
                                }
                                // Once, on letting go: each seek is a jump in the audio, and one per
                                // pointer move while dragging would stutter through the song.
                                dragFraction?.let { seek((it * duration).toLong()) }
                                dragFraction = null
                            }
                        }
                } else {
                    Modifier
                },
            ),
    ) {
        val fraction = dragFraction ?: when {
            !hasSong -> 0f
            live -> 1f
            durationMs > 0 -> (positionMs().toFloat() / durationMs).coerceIn(0f, 1f)
            else -> 0f
        }
        val stroke = thickness.toPx()
        val y = size.height / 2
        val start = stroke / 2
        val end = size.width - stroke / 2
        val head = start + (end - start) * fraction
        drawLine(track, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
        if (fraction > 0f) drawLine(played, Offset(start, y), Offset(head, y), stroke, StrokeCap.Round)
        if (active) drawCircle(played, radius = 6.dp.toPx(), center = Offset(head, y))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(TIME_ROW_HEIGHT).padding(horizontal = SECTION_PADDING),
    ) {
        when {
            live -> LiveLabel()
            seekable -> {
                SeekClock { formatClock(dragFraction?.let { (it * durationMs).toLong() } ?: positionMs()) }
                Spacer(Modifier.weight(1f))
                SeekClock { formatClock(durationMs) }
            }
        }
    }
}

/** One side's time, in its own scope so a tick redraws these few characters and nothing else. */
@Composable
private fun SeekClock(text: () -> String) {
    Text(
        text(),
        // Figures of one width, so the clock does not shuffle sideways as it counts.
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One of the card's two text rows, highlighted on hover as a menu entry would be. */
@Composable
private fun TrayMenuRow(text: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(MENU_ROW_HEIGHT).padding(horizontal = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(MENU_ROW_HEIGHT)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
        ) {
            leading()
            Spacer(Modifier.width(14.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Where the popup opens: its window's bounds on the screen, and that screen.
 *
 * Deliberately a plain class rather than a data class - each right-click is its own opening, and
 * two at the same spot must still count as two (see the keyed window in [OuterTuneTray]).
 */
internal class TrayPopupPlacement(
    val bounds: Rectangle,
    val configuration: GraphicsConfiguration?,
    /** A pinned widget brought back at start-up, rather than opened by a click. */
    val restored: Boolean = false,
) {
    companion object {
        private val cardSize get() = Dimension(TRAY_POPUP_WIDTH.value.toInt(), TRAY_POPUP_HEIGHT.value.toInt())
        private val margin get() = TRAY_POPUP_MARGIN.value.toInt()

        /**
         * Against the taskbar of whichever screen the pointer is on, beside the pointer - or above
         * the overflow flyout, when the icon was clicked in that.
         *
         * The pointer rather than the click event's own position: on a high-DPI screen, or a second
         * screen scaled differently, AWT has reported tray clicks in the wrong units, and the
         * pointer is where the click was in any case. Called as the click arrives, so the flyout is
         * still open to be found; it closes itself once the popup takes the focus.
         */
        fun atPointer(): TrayPopupPlacement {
            val pointer = runCatching { MouseInfo.getPointerInfo() }.getOrNull()
            val configuration = pointer?.device?.defaultConfiguration ?: defaultConfiguration()
            val flyout = pointer?.location?.let { at -> configuration?.let { OverflowFlyout.find(at, it) } }
            // A dp is one of AWT's units on Windows - both are already scaled for the screen.
            val card = trayPopupCardBounds(pointer?.location, cardSize, screenOf(configuration), insetsOf(configuration), flyout)
            return TrayPopupPlacement(card.withMargin(), configuration)
        }

        /**
         * Where the pinned widget was left, on the screen it was left on - or, with that screen gone,
         * where it was last left on one still attached. Held on that screen, in case it has since
         * changed size or resolution. With nowhere saved that still exists, beside the tray.
         */
        fun pinned(saved: String): TrayPopupPlacement {
            val devices = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList() }
                .getOrDefault(emptyList())
            PinnedSpots.decode(saved).forEach { (screen, at) ->
                val configuration = devices.firstOrNull { it.iDstring == screen }?.defaultConfiguration ?: return@forEach
                val window = Dimension(cardSize.width + 2 * margin, cardSize.height + 2 * margin)
                val bounds = pinnedWindowBounds(at, window, usableArea(screenOf(configuration), insetsOf(configuration)), margin)
                return TrayPopupPlacement(bounds, configuration, restored = true)
            }
            val configuration = defaultConfiguration()
            val card = trayPopupCardBounds(null, cardSize, screenOf(configuration), insetsOf(configuration))
            return TrayPopupPlacement(card.withMargin(), configuration, restored = true)
        }

        private fun defaultConfiguration(): GraphicsConfiguration? =
            runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration }.getOrNull()

        private fun screenOf(configuration: GraphicsConfiguration?) = configuration?.bounds ?: Rectangle(0, 0, 1920, 1080)

        private fun insetsOf(configuration: GraphicsConfiguration?) =
            configuration?.let { runCatching { Toolkit.getDefaultToolkit().getScreenInsets(it) }.getOrNull() } ?: Insets(0, 0, 0, 0)

        private fun Rectangle.withMargin() = Rectangle(x - margin, y - margin, width + 2 * margin, height + 2 * margin)
    }
}

/**
 * The pinned widget's places, one per screen: `screen|x,y` entries joined by `;`, the latest first.
 *
 * Per screen so that unplugging a monitor the widget was on, and plugging it back in later, puts it
 * back where it was on that monitor rather than wherever it was last dragged meanwhile.
 */
internal object PinnedSpots {
    fun decode(text: String): List<Pair<String, Point>> = text.split(';').mapNotNull { entry ->
        val parts = entry.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val xy = parts[1].split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
        parts[0] to Point(xy[0], xy[1])
    }

    fun encode(spots: List<Pair<String, Point>>): String = spots.joinToString(";") { (screen, at) -> "$screen|${at.x},${at.y}" }

    /** [saved] with [screen]'s place now [at], and that screen first. */
    fun with(saved: String, screen: String, at: Point): String =
        encode(listOf(screen to at) + decode(saved).filter { it.first != screen })

    /** [saved] with where [window] now is. */
    fun with(saved: String, window: java.awt.Window): String {
        val screen = runCatching { window.graphicsConfiguration.device.iDstring }.getOrNull() ?: return saved
        return with(saved, screen, window.location)
    }
}

/**
 * A saved widget window's bounds, held so the card - the window less its shadow [margin] - is wholly
 * inside [usable]: the screen may be smaller than it was, or the taskbar moved over where it was.
 */
internal fun pinnedWindowBounds(saved: Point, window: Dimension, usable: Rectangle, margin: Int): Rectangle {
    val x = maxOf(usable.x - margin, minOf(saved.x, usable.x + usable.width + margin - window.width))
    val y = maxOf(usable.y - margin, minOf(saved.y, usable.y + usable.height + margin - window.height))
    return Rectangle(x, y, window.width, window.height)
}

/** The screen less the taskbar - see [trayPopupCardBounds] for a hidden one. */
private fun usableArea(screen: Rectangle, insets: Insets) = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    screen.width - insets.left - insets.right,
    screen.height - insets.top - insets.bottom,
)

/**
 * Where the card goes: against the taskbar, beside the tray.
 *
 * Against the taskbar rather than at the pointer, because the pointer is not always on it. An icon
 * kept in Windows' overflow - the ^ beside the clock - is clicked in a little flyout above the
 * taskbar, which closes itself as soon as the popup opens; placed above the pointer, the popup was
 * left hanging well clear of the taskbar with nothing beneath it. So the card sits a [gap] inside
 * the edge the taskbar is on - whichever of the screen's insets is largest - and only its position
 * along that edge comes from the pointer, which is near the icon whether it was clicked on the
 * taskbar or in the flyout.
 *
 * A pointer that is nowhere near the taskbar did not just click the icon there - the popup was
 * opened from the keyboard, or the pointer has since moved on - so the card goes to the tray's end
 * of the taskbar instead: the right of a horizontal one, the bottom of a vertical one, which is
 * where Windows keeps the notification area. Then it is held inside the free area, which keeps it
 * on screen at a corner.
 *
 * Unless the icon was clicked in that flyout and it is known where the flyout is: then the card goes
 * a [gap] beyond the flyout, on the side away from the taskbar, centred on it - so the icons in it
 * are not hidden behind the popup while they are still showing, and the popup reads as coming from
 * the box it was opened from. Still held inside the free area.
 *
 * A taskbar set to hide itself leaves no insets; it is then taken to be along the bottom, as it is
 * by default, and [hiddenTaskbar] tall, since it slides up over the screen while it is being used.
 */
internal fun trayPopupCardBounds(
    cursor: Point?,
    size: Dimension,
    screen: Rectangle,
    insets: Insets,
    /** The overflow flyout the icon was clicked in, in AWT's units - see [OverflowFlyout]. */
    flyout: Rectangle? = null,
    gap: Int = 8,
    hiddenTaskbar: Int = 48,
): Rectangle {
    val hidden = insets.top == 0 && insets.bottom == 0 && insets.left == 0 && insets.right == 0
    val usable = usableArea(screen, if (hidden) Insets(0, 0, hiddenTaskbar, 0) else insets)
    val left = usable.x
    val top = usable.y
    val right = usable.x + usable.width
    val bottom = usable.y + usable.height
    val edge = when (maxOf(insets.bottom, insets.top, insets.left, insets.right)) {
        insets.bottom -> TaskbarEdge.Bottom
        insets.top -> TaskbarEdge.Top
        insets.right -> TaskbarEdge.Right
        else -> TaskbarEdge.Left
    }
    // How far the pointer is from the taskbar, into the free area; at most nothing while it is on it.
    val reach = cursor?.takeIf { screen.contains(it) }?.let {
        when (edge) {
            TaskbarEdge.Bottom -> bottom - it.y
            TaskbarEdge.Top -> it.y - top
            TaskbarEdge.Left -> it.x - left
            TaskbarEdge.Right -> right - it.x
        }
    }
    // Within the overflow flyout's height of the taskbar - a few rows of icons at most.
    val along = if (reach != null && reach <= NEAR_TASKBAR) cursor else null
    val (x, y) = if (flyout != null) {
        when (edge) {
            TaskbarEdge.Bottom -> flyout.centerX.toInt() - size.width / 2 to flyout.y - gap - size.height
            TaskbarEdge.Top -> flyout.centerX.toInt() - size.width / 2 to flyout.y + flyout.height + gap
            TaskbarEdge.Left -> flyout.x + flyout.width + gap to flyout.centerY.toInt() - size.height / 2
            TaskbarEdge.Right -> flyout.x - gap - size.width to flyout.centerY.toInt() - size.height / 2
        }
    } else when (edge) {
        TaskbarEdge.Bottom, TaskbarEdge.Top -> {
            val y = if (edge == TaskbarEdge.Bottom) bottom - gap - size.height else top + gap
            (along?.x ?: right) - size.width / 2 to y
        }
        TaskbarEdge.Left, TaskbarEdge.Right -> {
            val x = if (edge == TaskbarEdge.Right) right - gap - size.width else left + gap
            x to (along?.y ?: bottom) - size.height / 2
        }
    }
    // Not coerceIn: a screen smaller than the card would have its bounds the wrong way round, and
    // the start edge is the one worth keeping then.
    return Rectangle(
        maxOf(left + gap, minOf(x, right - gap - size.width)),
        maxOf(top + gap, minOf(y, bottom - gap - size.height)),
        size.width,
        size.height,
    )
}

private enum class TaskbarEdge { Bottom, Top, Left, Right }

/** How far from the taskbar the pointer can be and still have just clicked the icon - see above. */
private const val NEAR_TASKBAR = 360

/**
 * The two things AWT cannot be relied on for on Windows: bringing a window to the front, which can
 * be refused for a window that was not the last thing clicked, and keeping one on top once something
 * else has claimed the top. Harmless anywhere else, where the first does nothing and the second
 * falls back on AWT's own.
 */
private object NativeWindows {
    private interface User32 : StdCallLibrary {
        fun SetForegroundWindow(hwnd: Pointer): Boolean
        fun SetWindowPos(hwnd: Pointer, insertAfter: Pointer, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    }

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val user32 by lazy {
        if (windows) runCatching { Native.load("user32", User32::class.java) }.getOrNull() else null
    }

    fun bring(window: java.awt.Window) {
        runCatching { user32?.SetForegroundWindow(Pointer(Native.getWindowID(window))) }
    }

    fun keepOnTop(window: java.awt.Window) {
        if (!window.isShowing) return
        val user32 = user32
        if (user32 == null) {
            // AWT skips a request for what it believes is already so, hence off and on again.
            window.isAlwaysOnTop = false
            window.isAlwaysOnTop = true
            return
        }
        // HWND_TOPMOST, without moving, resizing or activating it.
        runCatching {
            user32.SetWindowPos(Pointer(Native.getWindowID(window)), Pointer(-1), 0, 0, 0, 0, SWP_NOSIZE or SWP_NOMOVE or SWP_NOACTIVATE)
        }
    }

    private const val SWP_NOSIZE = 0x1
    private const val SWP_NOMOVE = 0x2
    private const val SWP_NOACTIVATE = 0x10
}

// The card's parts, each a fixed height so the window can be sized before it is drawn.
private val SECTION_PADDING = 16.dp
private val COVER_SIZE = 64.dp
private val SEEK_GAP = 8.dp
private val SEEK_HEIGHT = 20.dp
private val TIME_ROW_HEIGHT = 14.dp
private val CONTROLS_HEIGHT = 68.dp
private val MENU_PADDING = 6.dp
private val MENU_ROW_HEIGHT = 40.dp

internal val TRAY_POPUP_WIDTH = 320.dp
internal val TRAY_POPUP_CORNER = 14.dp
/** Room around the card for its shadow, inside the window. */
internal val TRAY_POPUP_MARGIN = 16.dp
internal val TRAY_POPUP_HEIGHT =
    SECTION_PADDING + COVER_SIZE + SEEK_GAP + SEEK_HEIGHT + TIME_ROW_HEIGHT + CONTROLS_HEIGHT +
        1.dp + MENU_PADDING + MENU_ROW_HEIGHT * 2 + MENU_PADDING
