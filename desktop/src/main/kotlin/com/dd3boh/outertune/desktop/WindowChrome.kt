/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.Toolkit

/**
 * The window without Windows' title bar: the app runs to the top edge, and the minimise, maximise
 * and close buttons sit over it in whatever colour reads against what is under them.
 *
 * What a title bar did that still has to be done is done here: the strip along the top drags the
 * window and double-clicking it maximises; edges still resize (Compose does that for an
 * undecorated window); and a maximised window stops at the taskbar rather than covering it, which
 * an undecorated window would otherwise do.
 */
object WindowChrome {
    /** The height of the strip the buttons sit in, and what content keeps clear of at the top. */
    val height: Dp = 32.dp

    /** Minimise, maximise and close, side by side at the right of the strip. */
    val buttonsWidth: Dp = 46.dp * 3
}

/** How far content should keep clear of the top edge. Zero where there is no custom frame (tests). */
val LocalCaptionInset = compositionLocalOf { 0.dp }

/**
 * The colour the window buttons should take, set by whatever is showing - the full player sets
 * its own, since its background is the cover's colour rather than the theme's.
 */
class CaptionTint {
    var colour by mutableStateOf<Color?>(null)
}

val LocalCaptionTint = compositionLocalOf { CaptionTint() }

@Composable
fun FrameWindowScope.CaptionBar(state: WindowState, defaultTint: Color, onClose: () -> Unit) {
    val tint = LocalCaptionTint.current.colour ?: defaultTint
    fun toggleMaximised() {
        if (state.placement == WindowPlacement.Maximized) {
            state.placement = WindowPlacement.Floating
        } else {
            // An undecorated window maximises over the taskbar unless told where to stop.
            val config = window.graphicsConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            val b = config.bounds
            window.maximizedBounds = java.awt.Rectangle(
                b.x + insets.left, b.y + insets.top,
                b.width - insets.left - insets.right, b.height - insets.top - insets.bottom,
            )
            state.placement = WindowPlacement.Maximized
        }
    }
    // With the native frame (NativeFrame), the strip is Windows' caption and the maximise button is
    // Windows' maximise button, so they are only drawn here; where they are is reported back.
    val native = NativeFrame.active
    fun Modifier.reportAs(maximise: Boolean, index: Int) = onGloballyPositioned { c ->
        if (!native) return@onGloballyPositioned
        val b = c.boundsInWindow()
        val rect = java.awt.Rectangle(b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
        if (maximise) NativeFrame.maximiseButton = rect
        else NativeFrame.otherButtons = NativeFrame.otherButtons.toMutableList().apply {
            while (size <= index) add(java.awt.Rectangle())
            set(index, rect)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WindowChrome.height)
            .onGloballyPositioned { if (native) NativeFrame.captionHeightPx = it.size.height },
    ) {
        if (native) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight())
        } else {
            WindowDraggableArea(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleMaximised() }) },
            ) { Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) }
        }
        CaptionButton(tint, modifier = Modifier.reportAs(false, 0), onClick = { window.extendedState = window.extendedState or Frame.ICONIFIED }) { c ->
            drawLine(c, Offset(size.width * 0.5f - 5.dp.toPx(), size.height / 2), Offset(size.width * 0.5f + 5.dp.toPx(), size.height / 2), strokeWidth = 1.dp.toPx())
        }
        val maximised = state.placement == WindowPlacement.Maximized
        CaptionButton(
            tint,
            modifier = Modifier.reportAs(true, 0),
            hoveredOverride = if (native) NativeFrame.maximiseHovered else null,
            onClick = ::toggleMaximised,
        ) { c ->
            val s = 10.dp.toPx()
            val r = 1.5.dp.toPx()
            val stroke = Stroke(width = 1.dp.toPx())
            val cx = size.width / 2
            val cy = size.height / 2
            if (maximised) {
                // Restore: a square with another behind it.
                val small = s * 0.8f
                drawRoundRect(c, Offset(cx - s / 2, cy - s / 2 + s - small), Size(small, small), androidx.compose.ui.geometry.CornerRadius(r), style = stroke)
                drawLine(c, Offset(cx - s / 2 + s - small, cy - s / 2), Offset(cx + s / 2, cy - s / 2), 1.dp.toPx())
                drawLine(c, Offset(cx + s / 2, cy - s / 2), Offset(cx + s / 2, cy + s / 2 - (s - small)), 1.dp.toPx())
            } else {
                drawRoundRect(c, Offset(cx - s / 2, cy - s / 2), Size(s, s), androidx.compose.ui.geometry.CornerRadius(r), style = stroke)
            }
        }
        CaptionButton(tint, modifier = Modifier.reportAs(false, 1), hoverColour = Color(0xFFC42B1C), hoverTint = Color.White, onClick = onClose) { c ->
            val h = 5.dp.toPx()
            val cx = size.width / 2
            val cy = size.height / 2
            drawLine(c, Offset(cx - h, cy - h), Offset(cx + h, cy + h), 1.dp.toPx())
            drawLine(c, Offset(cx + h, cy - h), Offset(cx - h, cy + h), 1.dp.toPx())
        }
    }
}

@Composable
private fun CaptionButton(
    tint: Color,
    modifier: Modifier = Modifier,
    /** Hover as Windows reports it, for the button Compose cannot see the pointer over. */
    hoveredOverride: Boolean? = null,
    hoverColour: Color = tint.copy(alpha = 0.10f),
    hoverTint: Color = tint,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val composeHovered by interaction.collectIsHoveredAsState()
    val hovered = hoveredOverride ?: composeHovered
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(if (hovered) hoverColour else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Canvas(modifier = Modifier.size(46.dp, WindowChrome.height)) { glyph(if (hovered) hoverTint else tint) }
    }
}

/**
 * Windows 11 rounds the corners of a window it frames; an undecorated one has to ask. Harmless
 * where the attribute does not exist (Windows 10 just ignores it).
 */
object WindowCorners {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    fun round(window: java.awt.Window) {
        runCatching {
            val dwm = Native.load("dwmapi", Dwm::class.java)
            val hwnd = Pointer(Native.getWindowID(window))
            // DWMWA_WINDOW_CORNER_PREFERENCE = 33, DWMWCP_ROUND = 2.
            dwm.DwmSetWindowAttribute(hwnd, 33, IntByReference(2), 4)
        }
    }
}
