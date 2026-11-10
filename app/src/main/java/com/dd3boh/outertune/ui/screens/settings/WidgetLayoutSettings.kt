/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.WidgetLayoutKey
import com.dd3boh.outertune.constants.WidgetLayoutMediumKey
import com.dd3boh.outertune.constants.WidgetLayoutSmallKey
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.widget.PlayerWidget
import com.dd3boh.outertune.widget.WidgetElement
import com.dd3boh.outertune.widget.WidgetLayout
import com.dd3boh.outertune.widget.WidgetMetrics
import kotlin.math.roundToInt
import androidx.compose.material3.IconButton as M3IconButton

/**
 * A typical widget of each size, in dp - about 4x1, 4x2 and 4x3 home-screen cells. The editor is
 * drawn in that shape, with the widget's own row heights and cover sizing (WidgetMetrics), so the
 * space it shows is the space the widget has.
 */
private val PREVIEW = listOf(340 to 100, 340 to 210, 340 to 320)

/**
 * Arranges the home-screen widget: drag a piece and it snaps into the grid's columns and rows. A
 * drop that would cover another piece is refused - the outline turns red - so nothing overlaps.
 * Tap a piece to resize or remove it. One arrangement per widget size.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WidgetLayoutSettings(navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(2) }
    var small by rememberPreference(WidgetLayoutSmallKey, "")
    var medium by rememberPreference(WidgetLayoutMediumKey, "")
    var large by rememberPreference(WidgetLayoutKey, "")
    val layout = remember(tab, small, medium, large) {
        when (tab) {
            0 -> WidgetLayout.parse(small, WidgetLayout.SMALL_ROWS)
            1 -> WidgetLayout.parse(medium, WidgetLayout.MEDIUM_ROWS)
            else -> WidgetLayout.parse(large)
        }
    }
    var selected by remember { mutableStateOf<WidgetElement?>(null) }

    fun save(new: WidgetLayout) {
        when (tab) {
            0 -> small = new.encode()
            1 -> medium = new.encode()
            else -> large = new.encode()
        }
        PlayerWidget.applyLayout(context, new)
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text(
            stringResource(R.string.widget_layout_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(R.string.widget_size_small, R.string.widget_size_medium, R.string.widget_size_large).forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { tab = i; selected = null },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(12.dp))

        WidgetCanvas(layout, PREVIEW[tab], selected, { selected = if (selected == it) null else it }) { element, col, row ->
            save(layout.move(element, col, row))
        }

        selected?.takeIf { it in layout }?.let { current ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(current.icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(current.label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                M3IconButton(onClick = { save(layout.resize(current, -1)) }) { Icon(Icons.Rounded.RemoveCircleOutline, null) }
                M3IconButton(onClick = { save(layout.resize(current, 1)) }) { Icon(Icons.Rounded.AddCircleOutline, null) }
                M3IconButton(onClick = { save(layout.remove(current)); selected = null }) { Icon(Icons.Rounded.Delete, null) }
            }
        }

        if (layout.unused.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.widget_layout_add), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                layout.unused.forEach { element ->
                    FilterChip(
                        selected = false,
                        onClick = { save(layout.add(element)) },
                        label = { Text(stringResource(element.label)) },
                        leadingIcon = { Icon(element.icon, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }

        TextButton(onClick = { save(WidgetLayout.defaultFor(layout.maxRows)); selected = null }) {
            Icon(Icons.Rounded.Restore, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.widget_layout_reset))
        }
        Spacer(Modifier.height(24.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.widget_layout_title)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The widget, drawn to scale: its shape, its padding, its rows at the heights they have on the home
 * screen, and each piece as a small version of itself. Dragging snaps to the 12 columns and to the
 * rows, plus one empty row below while there is room for another.
 */
@Composable
private fun WidgetCanvas(
    layout: WidgetLayout,
    size: Pair<Int, Int>,
    selected: WidgetElement?,
    onSelect: (WidgetElement) -> Unit,
    onDrop: (WidgetElement, Int, Int) -> Unit,
) {
    val (wDp, hDp) = size
    val colours = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val cover = WidgetMetrics.coverSize(layout, wDp, hDp, false)
    val heights = WidgetMetrics.rowHeights(layout, false, cover)
    val pad = WidgetMetrics.padding(hDp)
    // Rows sit in the middle, as on the widget; an extra 40dp row is offered below for dropping into.
    val top = maxOf(pad, (hDp - heights.sum()) / 2)
    val tops = heights.runningFold(top) { acc, h -> acc + h }
    val slots = if (heights.size < layout.maxRows) heights + 40 else heights
    var target by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) } // col, row, span

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(wDp.toFloat() / maxOf(hDp, tops.last() + 40))
            .clip(RoundedCornerShape(24.dp))
            .background(colours.secondaryContainer)
    ) {
        val scale = maxWidth.value / wDp
        fun d(v: Number): Dp = (v.toFloat() * scale).dp
        val colW = (wDp - 2 * pad) / WidgetLayout.COLUMNS.toFloat()
        val pxPerDp = with(density) { 1.dp.toPx() } * scale

        fun rowAt(yDp: Float): Int {
            slots.indices.forEach { r -> if (yDp < tops[0] + slots.take(r + 1).sum()) return r }
            return slots.lastIndex
        }

        target?.let { (c, r, span) ->
            val fits = r < layout.maxRows && c >= 0 && c + span <= WidgetLayout.COLUMNS &&
                layout.placements.none { it.row == r && it.col < c + span && c < it.end && it.element != selected }
            Box(
                Modifier
                    .offset(d(pad + c * colW), d(tops[0] + slots.take(r).sum()))
                    .size(d(span * colW), d(slots.getOrElse(r) { 40 }))
                    .border(2.dp, if (fits) colours.primary else colours.error, RoundedCornerShape(10.dp))
            )
        }

        layout.placements.forEach { p ->
            key(p.element) {
                var drag by remember { mutableStateOf(Offset.Zero) }
                fun snap() = Triple(
                    ((p.col * colW + drag.x / pxPerDp) / colW).roundToInt(),
                    rowAt(tops[p.row] + heights[p.row] / 2f + drag.y / pxPerDp),
                    p.span,
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset(((pad + p.col * colW) * pxPerDp + drag.x).roundToInt(), (tops[p.row] * pxPerDp + drag.y).roundToInt()) }
                        .size(d(p.span * colW), d(heights[p.row]))
                        .then(if (p.element == selected) Modifier.border(2.dp, colours.primary, RoundedCornerShape(10.dp)) else Modifier)
                        .pointerInput(p) {
                            detectDragGestures(
                                onDragStart = { onSelect(p.element) },
                                onDrag = { change, amount -> change.consume(); drag += amount; target = snap() },
                                onDragEnd = { val (c, r, _) = snap(); drag = Offset.Zero; target = null; onDrop(p.element, c, r) },
                                onDragCancel = { drag = Offset.Zero; target = null },
                            )
                        }
                        .pointerInput(p) { detectTapGestures { onSelect(p.element) } }
                ) { Piece(p.element, cover, scale, colours.onSecondaryContainer) }
            }
        }
    }
}

/** A piece as it looks on the widget, in miniature. */
@Composable
private fun Piece(element: WidgetElement, cover: Int, scale: Float, ink: androidx.compose.ui.graphics.Color) {
    fun d(v: Number): Dp = (v.toFloat() * scale).dp
    val faint = ink.copy(alpha = 0.3f)
    when (element) {
        WidgetElement.COVER -> Box(Modifier.size(d(cover)).background(faint, RoundedCornerShape(d(10))))
        WidgetElement.ARTIST -> Box(Modifier.size(d(cover * 2 / 3)).background(faint, CircleShape))
        WidgetElement.INFO -> Column(Modifier.fillMaxWidth().padding(horizontal = d(8))) {
            Box(Modifier.fillMaxWidth(0.85f).height(d(12)).background(ink.copy(alpha = 0.7f), CircleShape))
            Spacer(Modifier.height(d(6)))
            Box(Modifier.fillMaxWidth(0.55f).height(d(9)).background(ink.copy(alpha = 0.4f), CircleShape))
        }
        WidgetElement.LYRICS -> Box(Modifier.fillMaxWidth(0.8f).height(d(12)).background(ink.copy(alpha = 0.6f), CircleShape))
        WidgetElement.UP_NEXT -> Box(Modifier.fillMaxWidth(0.7f).height(d(8)).background(faint, CircleShape))
        WidgetElement.SEEK_BAR -> Box(Modifier.fillMaxWidth().padding(horizontal = d(4)).height(d(3)).background(ink.copy(alpha = 0.6f), CircleShape))
        else -> Icon(element.icon, null, tint = ink, modifier = Modifier.size(d(if (element == WidgetElement.PLAY_PAUSE) 36 else 28)))
    }
}

private val WidgetElement.label: Int
    get() = when (this) {
        WidgetElement.COVER -> R.string.widget_el_cover
        WidgetElement.ARTIST -> R.string.widget_el_artist
        WidgetElement.INFO -> R.string.widget_el_info
        WidgetElement.LYRICS -> R.string.widget_el_lyrics
        WidgetElement.UP_NEXT -> R.string.widget_el_up_next
        WidgetElement.SEEK_BAR -> R.string.widget_el_seek_bar
        WidgetElement.PREVIOUS -> R.string.widget_el_previous
        WidgetElement.PLAY_PAUSE -> R.string.widget_el_play_pause
        WidgetElement.NEXT -> R.string.widget_el_next
        WidgetElement.SHUFFLE -> R.string.widget_el_shuffle
        WidgetElement.REPEAT -> R.string.widget_el_repeat
    }

// Icons the app already uses elsewhere, so the editor adds none of its own to the app.
private val WidgetElement.icon: ImageVector
    get() = when (this) {
        WidgetElement.COVER -> Icons.Rounded.Album
        WidgetElement.ARTIST -> Icons.Rounded.Person
        WidgetElement.INFO -> Icons.Rounded.MusicNote
        WidgetElement.LYRICS -> Icons.Rounded.Lyrics
        WidgetElement.UP_NEXT -> Icons.AutoMirrored.Rounded.QueueMusic
        WidgetElement.SEEK_BAR -> Icons.Rounded.MoreHoriz
        WidgetElement.PREVIOUS -> Icons.Rounded.SkipPrevious
        WidgetElement.PLAY_PAUSE -> Icons.Rounded.PlayArrow
        WidgetElement.NEXT -> Icons.Rounded.SkipNext
        WidgetElement.SHUFFLE -> Icons.Rounded.Shuffle
        WidgetElement.REPEAT -> Icons.Rounded.Repeat
    }
