/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
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
 */
@Composable
internal fun OuterTuneTray(
    icon: Painter,
    tooltip: String,
    player: DesktopPlayer,
    library: LibraryStore,
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
    // main one.
    var placement by remember { mutableStateOf<TrayPopupPlacement?>(null) }

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
                    e.isPopupTrigger || e.button == MouseEvent.BUTTON3 -> placement = TrayPopupPlacement.atPointer()
                    // Only the first click of a double-click: the second has a count of two.
                    e.button == MouseEvent.BUTTON1 && e.clickCount == 1 -> {
                        placement = null
                        show()
                    }
                }
            }
        }
        val action = ActionListener {
            placement = null
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
                // Only this window's own closing: a replaced window losing focus as it goes must not
                // take its replacement with it.
                onDismiss = { if (placement === at) placement = null },
                onShow = {
                    placement = null
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
    onDismiss: () -> Unit,
    onShow: () -> Unit,
    onQuit: () -> Unit,
    icon: Painter,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    Window(
        onPreviewKeyEvent = {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                dismiss()
                true
            } else {
                false
            }
        },
        create = {
            ComposeWindow(at.configuration).apply {
                // A tool window: no taskbar button and no place in Alt+Tab, as befits something that
                // is gone again the moment anything else is clicked.
                type = java.awt.Window.Type.UTILITY
                isUndecorated = true
                isTransparent = true
                isResizable = false
                isAlwaysOnTop = true
                title = "OuterTune"
                bounds = at.bounds
                // Gone as soon as anything else is clicked - another window, the desktop, the
                // taskbar - as a menu would be.
                addWindowFocusListener(object : WindowAdapter() {
                    override fun windowLostFocus(e: WindowEvent) = dismiss()
                })
            }
        },
        dispose = { it.dispose() },
    ) {
        LaunchedEffect(Unit) {
            // Activated, so that clicking elsewhere takes the focus away and closes it - a window that
            // never had the focus never loses it. Windows lets a program bring itself forward just
            // after its tray icon is clicked; toFront asks, and the direct call covers a refusal.
            window.toFront()
            window.requestFocus()
            ForegroundWindow.bring(window)
        }
        val playback by player.state.collectAsState()
        val queue by TrayControls.queue.collectAsState()
        val live by player.live.collectAsState()
        val position = player.positionMs.collectAsState()
        val duration by player.durationMs.collectAsState()
        // The theme the main window is showing, cover colours and font included; until it has drawn
        // once, the app's default for the system's light or dark.
        MaterialTheme(
            colorScheme = TrayControls.colors ?: DynamicTheme.schemeFor(null, isSystemInDarkTheme()),
            typography = TrayControls.typography ?: Typography(),
        ) {
            CompositionLocalProvider(LocalCredits provides library.credits.collectAsState().value) {
                TrayPopupSurface {
                    TrayPopupCard(
                        playback = playback,
                        queue = queue,
                        live = live,
                        positionMs = { position.value },
                        durationMs = duration,
                        appIcon = icon,
                        onTogglePause = { TrayControls.togglePlay() },
                        onNext = { TrayControls.next() },
                        onPrevious = { TrayControls.previous() },
                        onShow = onShow,
                        onQuit = onQuit,
                    )
                }
            }
        }
    }
}

/**
 * The card with room around it for its shadow, which is drawn into the window's transparent margin -
 * the window itself has no frame to cast one.
 */
@Composable
internal fun TrayPopupSurface(content: @Composable () -> Unit) {
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
                .shadow(10.dp, RoundedCornerShape(TRAY_POPUP_CORNER), clip = false),
        ) {
            content()
        }
    }
}

/**
 * What the tray popup shows: the song, a line for how far through it is, the three transport
 * buttons, and the two things the old menu could do besides - open the app, or quit it.
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
    onShow: () -> Unit,
    onQuit: () -> Unit,
) {
    val song = queue.current?.shown()
    // The mini player's colour for its buttons - see PlayerBar.
    val tint = MaterialTheme.colorScheme.onSecondaryContainer
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
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
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
        LinearProgressIndicator(
            progress = {
                when {
                    song == null -> 0f
                    live -> 1f
                    durationMs > 0 -> (positionMs().toFloat() / durationMs).coerceIn(0f, 1f)
                    else -> 0f
                }
            },
            drawStopIndicator = { },
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .padding(start = SECTION_PADDING, end = SECTION_PADDING, top = PROGRESS_GAP)
                .fillMaxWidth()
                .height(PROGRESS_HEIGHT),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(CONTROLS_HEIGHT),
        ) {
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
internal class TrayPopupPlacement(val bounds: Rectangle, val configuration: GraphicsConfiguration?) {
    companion object {
        /**
         * Beside the pointer, on whichever screen the pointer is on.
         *
         * The pointer rather than the click event's own position: on a high-DPI screen, or a second
         * screen scaled differently, AWT has reported tray clicks in the wrong units, and the
         * pointer is where the click was in any case.
         */
        fun atPointer(): TrayPopupPlacement {
            val pointer = runCatching { MouseInfo.getPointerInfo() }.getOrNull()
            val configuration = pointer?.device?.defaultConfiguration
                ?: runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration }.getOrNull()
            val screen = configuration?.bounds ?: Rectangle(0, 0, 1920, 1080)
            val insets = configuration?.let { runCatching { Toolkit.getDefaultToolkit().getScreenInsets(it) }.getOrNull() }
            // The screen less the taskbar, wherever it is docked.
            val usable = Rectangle(
                screen.x + (insets?.left ?: 0),
                screen.y + (insets?.top ?: 0),
                screen.width - (insets?.left ?: 0) - (insets?.right ?: 0),
                screen.height - (insets?.top ?: 0) - (insets?.bottom ?: 0),
            )
            val cursor = pointer?.location ?: Point(usable.x + usable.width, usable.y + usable.height)
            // A dp is one of AWT's units on Windows - both are already scaled for the screen.
            val card = trayPopupCardBounds(
                cursor,
                Dimension(TRAY_POPUP_WIDTH.value.toInt(), TRAY_POPUP_HEIGHT.value.toInt()),
                usable,
            )
            val margin = TRAY_POPUP_MARGIN.value.toInt()
            return TrayPopupPlacement(
                Rectangle(card.x - margin, card.y - margin, card.width + 2 * margin, card.height + 2 * margin),
                configuration,
            )
        }
    }
}

/**
 * Where the card goes, given the pointer and the part of its screen the taskbar leaves free.
 *
 * Centred on the pointer, as Windows 11 centres its own flyouts on the icon that opened them, and
 * on the far side of the pointer from the screen's nearer edge - above it with the taskbar along
 * the bottom, below it with the taskbar along the top. Then held inside the free area, which is
 * what keeps it off a taskbar docked at the side and on screen at a corner.
 */
internal fun trayPopupCardBounds(cursor: Point, size: Dimension, usable: Rectangle, gap: Int = 8): Rectangle {
    val upward = cursor.y >= usable.centerY
    val preferredX = cursor.x - size.width / 2
    val preferredY = if (upward) {
        minOf(cursor.y, usable.y + usable.height) - gap - size.height
    } else {
        maxOf(cursor.y, usable.y) + gap
    }
    // Not coerceIn: a screen smaller than the card would have its bounds the wrong way round, and
    // the start edge is the one worth keeping then.
    val x = maxOf(usable.x + gap, minOf(preferredX, usable.x + usable.width - gap - size.width))
    val y = maxOf(usable.y + gap, minOf(preferredY, usable.y + usable.height - gap - size.height))
    return Rectangle(x, y, size.width, size.height)
}

/**
 * Brings a window to the front on Windows, where AWT's toFront can be refused for a window that was
 * not the last thing clicked. Harmless anywhere else, where it does nothing.
 */
private object ForegroundWindow {
    private interface User32 : StdCallLibrary {
        fun SetForegroundWindow(hwnd: Pointer): Boolean
    }

    private val user32 by lazy {
        runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    fun bring(window: java.awt.Window) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching { user32?.SetForegroundWindow(Pointer(Native.getWindowID(window))) }
    }
}

// The card's parts, each a fixed height so the window can be sized before it is drawn.
private val SECTION_PADDING = 16.dp
private val COVER_SIZE = 64.dp
private val PROGRESS_GAP = 14.dp
private val PROGRESS_HEIGHT = 4.dp
private val CONTROLS_HEIGHT = 80.dp
private val MENU_PADDING = 6.dp
private val MENU_ROW_HEIGHT = 40.dp

internal val TRAY_POPUP_WIDTH = 320.dp
internal val TRAY_POPUP_CORNER = 14.dp
/** Room around the card for its shadow, inside the window. */
internal val TRAY_POPUP_MARGIN = 16.dp
internal val TRAY_POPUP_HEIGHT =
    SECTION_PADDING + COVER_SIZE + PROGRESS_GAP + PROGRESS_HEIGHT + CONTROLS_HEIGHT +
        1.dp + MENU_PADDING + MENU_ROW_HEIGHT * 2 + MENU_PADDING
