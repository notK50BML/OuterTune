/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_SLIDER_STYLE
import com.dd3boh.outertune.constants.SliderStyleKey
import com.dd3boh.outertune.constants.SliderWeight
import com.dd3boh.outertune.constants.SliderWeightKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.WidgetLayoutKey
import com.dd3boh.outertune.constants.WidgetLayoutMediumKey
import com.dd3boh.outertune.constants.WidgetLayoutSmallKey
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.drawPlayerSliderStill
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.widget.PlayerWidget
import com.dd3boh.outertune.widget.WidgetElement
import com.dd3boh.outertune.widget.WidgetLayout
import com.dd3boh.outertune.widget.WidgetMetrics
import com.dd3boh.outertune.widget.WidgetPlacement
import kotlin.math.roundToInt
import androidx.compose.material3.IconButton as M3IconButton

/**
 * A typical widget of each size, in dp - about 4x1, 4x2 and 4x3 home-screen cells. The editor is
 * drawn in that shape, with the widget's own row heights and cover sizing (WidgetMetrics), so the
 * space it shows is the space the widget has.
 */
private val PREVIEW = listOf(340 to 100, 340 to 210, 340 to 320)

/** Each size's usual rows - a finer grid adds one (WidgetLayout.rowsFor). */
private val BASE_ROWS = listOf(WidgetLayout.SMALL_ROWS, WidgetLayout.MEDIUM_ROWS, WidgetLayout.MAX_ROWS)

/** How far back undo goes, per size. */
private const val MAX_UNDO = 50

/**
 * The widget editor: drag a piece and it snaps into the grid. Dropped on another piece, the two
 * swap; dropped partly over its neighbours, they are pushed along the row. Tap a piece to resize or
 * remove it. One arrangement per widget size, each on its own grid, with undo and redo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WidgetLayoutSettings(navController: NavController, scrollBehavior: TopAppBarScrollBehavior) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(2) }
    var small by rememberPreference(WidgetLayoutSmallKey, "")
    var medium by rememberPreference(WidgetLayoutMediumKey, "")
    var large by rememberPreference(WidgetLayoutKey, "")

    // Every arrangement this visit, per size, and which one is showing - the undo history. Held
    // here rather than read back from the stored preference, which is written asynchronously: a
    // second drag straight after the first would otherwise start from the arrangement before it.
    val history = remember { mutableStateMapOf<Int, List<String>>() }
    val position = remember { mutableStateMapOf<Int, Int>() }
    val entries = history[tab] ?: listOf(WidgetLayout.parse(listOf(small, medium, large)[tab], BASE_ROWS[tab]).encode())
    val pos = (position[tab] ?: 0).coerceIn(0, entries.lastIndex)
    val layout = remember(tab, entries, pos) { WidgetLayout.parse(entries[pos], BASE_ROWS[tab]) }
    var selected by remember { mutableStateOf<WidgetElement?>(null) }

    fun write(new: WidgetLayout) {
        val text = new.encode()
        when (tab) {
            0 -> small = text
            1 -> medium = text
            else -> large = text
        }
        PlayerWidget.applyLayout(context, new, BASE_ROWS[tab])
    }

    fun save(new: WidgetLayout?) {
        if (new == null || new == layout) return
        val kept = (entries.take(pos + 1) + new.encode()).takeLast(MAX_UNDO)
        history[tab] = kept
        position[tab] = kept.lastIndex
        write(new)
    }

    fun step(by: Int) {
        val to = (pos + by).coerceIn(0, entries.lastIndex)
        if (to == pos) return
        history[tab] = entries
        position[tab] = to
        selected = null
        write(WidgetLayout.parse(entries[to], BASE_ROWS[tab]))
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
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.widget_grid_title), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val labels = listOf(R.string.widget_grid_coarse, R.string.widget_grid_normal, R.string.widget_grid_fine)
            WidgetLayout.GRIDS.forEachIndexed { i, columns ->
                SegmentedButton(
                    selected = layout.columns == columns,
                    onClick = { save(layout.regridded(columns, WidgetLayout.rowsFor(BASE_ROWS[tab], columns))) },
                    shape = SegmentedButtonDefaults.itemShape(i, WidgetLayout.GRIDS.size),
                ) { Text(stringResource(labels[i])) }
            }
        }
        Spacer(Modifier.height(12.dp))

        WidgetCanvas(layout, PREVIEW[tab], selected, { selected = if (selected == it) null else it }) { element, col, row ->
            save(layout.drop(element, col, row))
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

        TextButton(onClick = { save(WidgetLayout.defaultFor(layout.maxRows, layout.columns)); selected = null }) {
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
        actions = {
            M3IconButton(onClick = { step(-1) }, enabled = pos > 0) {
                Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.widget_undo))
            }
            M3IconButton(onClick = { step(1) }, enabled = pos < entries.lastIndex) {
                Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.widget_redo))
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The widget, drawn to scale: its shape, its padding, its rows at the heights they have on the home
 * screen, every empty cell as a faint rounded tile, and each piece as a labelled preview of itself.
 * While a piece is dragged the others move to where the drop would put them - pushed aside or
 * swapped - and the outline shows where it lands, red if there is no room.
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
    val slots = if (heights.size < layout.maxRows) heights + 40 else heights
    val tops = slots.runningFold(top) { acc, h -> acc + h }

    // The gesture handlers outlive recompositions, so they read these rather than what they were
    // created with - reading a stale arrangement is what made a drop put everything else back.
    val latestLayout by rememberUpdatedState(layout)
    val drop by rememberUpdatedState(onDrop)
    val select by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)

    var dragging by remember { mutableStateOf<WidgetElement?>(null) }
    var target by remember { mutableStateOf<Pair<Int, Int>?>(null) } // col, row
    val preview = remember(layout, dragging, target) {
        val e = dragging
        val t = target
        if (e != null && t != null) layout.drop(e, t.first, t.second) else null
    }
    val shown = preview ?: layout

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(wDp.toFloat() / maxOf(hDp, tops.last() + pad))
            .clip(RoundedCornerShape(24.dp))
            .background(colours.secondaryContainer)
    ) {
        val scale = maxWidth.value / wDp
        fun d(v: Number): Dp = (v.toFloat() * scale).dp
        val colW = (wDp - 2 * pad) / layout.columns.toFloat()
        val pxPerDp = with(density) { 1.dp.toPx() } * scale

        fun rowAt(yDp: Float): Int {
            slots.indices.forEach { r -> if (yDp < tops[r + 1]) return r }
            return slots.lastIndex
        }

        // The grid itself: every cell a faint rounded tile, so the free spaces are plain to see.
        val tile = colours.onSecondaryContainer.copy(alpha = 0.08f)
        slots.indices.forEach { r ->
            repeat(layout.columns) { c ->
                Box(
                    Modifier
                        .offset(d(pad + c * colW + 1.5f), d(tops[r] + 1.5f))
                        .size(d(colW - 3), d(slots[r] - 3))
                        .background(tile, RoundedCornerShape(d(6)))
                )
            }
        }

        // Where the dragged piece will land.
        val e = dragging
        val t = target
        if (e != null && t != null) {
            val landing = preview?.placements?.find { it.element == e }
            val span = latestLayout.placements.find { it.element == e }?.span ?: 1
            val (c, r, s) = landing?.let { Triple(it.col, it.row, it.span) } ?: Triple(t.first, t.second, span)
            Box(
                Modifier
                    .offset(d(pad + c * colW), d(tops.getOrElse(r) { tops.last() }))
                    .size(d(s * colW), d(slots.getOrElse(r) { 40 }))
                    .border(1.dp, if (landing != null) colours.primary else colours.error, RoundedCornerShape(d(8)))
            )
        }

        shown.placements.forEach { p ->
            key(p.element) {
                var drag by remember { mutableStateOf(Offset.Zero) }
                val isDragged = p.element == dragging
                // A dragged piece follows the finger from where it started; the rest glide to where
                // the drop would put them, and the dragged one glides from the finger into its spot.
                val origin = if (isDragged) latestLayout.placements.find { it.element == p.element } ?: p else p
                val xDp = pad + origin.col * colW + if (isDragged) drag.x / pxPerDp else 0f
                val yDp = tops.getOrElse(origin.row) { tops.last() } + if (isDragged) drag.y / pxPerDp else 0f
                val x by animateFloatAsState(xDp, if (isDragged) snap<Float>() else spring<Float>(), label = "x")
                val y by animateFloatAsState(yDp, if (isDragged) snap<Float>() else spring<Float>(), label = "y")

                fun snapTo(start: WidgetPlacement) = Pair(
                    ((start.col * colW + drag.x / pxPerDp) / colW).roundToInt(),
                    rowAt(tops[start.row] + slots[start.row] / 2f + drag.y / pxPerDp),
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset { IntOffset((x * pxPerDp).roundToInt(), (y * pxPerDp).roundToInt()) }
                        .size(d(p.span * colW), d(slots.getOrElse(p.row) { 40 }))
                        .padding(d(1.5f))
                        .background(colours.surface.copy(alpha = if (isDragged) 0.95f else 0.8f), RoundedCornerShape(d(8)))
                        .then(if (p.element == selected) Modifier.border(1.dp, colours.primary, RoundedCornerShape(d(8))) else Modifier)
                        .pointerInput(p.element, colW, slots) {
                            var start: WidgetPlacement? = null
                            detectDragGestures(
                                onDragStart = {
                                    start = latestLayout.placements.find { it.element == p.element }
                                    dragging = p.element
                                    if (currentSelected != p.element) select(p.element)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount
                                    start?.let { target = snapTo(it) }
                                },
                                onDragEnd = {
                                    val landed = start?.let { snapTo(it) }
                                    drag = Offset.Zero
                                    dragging = null
                                    target = null
                                    if (landed != null) drop(p.element, landed.first, landed.second)
                                },
                                onDragCancel = { drag = Offset.Zero; dragging = null; target = null },
                            )
                        }
                        .pointerInput(p.element) { detectTapGestures { select(p.element) } }
                ) { Piece(p.element, cover, scale, colours.onSurface) }
            }
        }
    }
}

/** A piece as it looks on the widget, in miniature: the cover as a picture, the text as words. */
@Composable
private fun Piece(element: WidgetElement, cover: Int, scale: Float, ink: Color) {
    val density = LocalDensity.current
    val colours = MaterialTheme.colorScheme
    fun d(v: Number): Dp = (v.toFloat() * scale).dp
    fun sp(v: Number): TextUnit = with(density) { d(v).toSp() }
    val faint = ink.copy(alpha = 0.45f)

    @Composable
    fun Line(text: String, size: Number, colour: Color, bold: Boolean = false) = Text(
        text,
        fontSize = sp(size),
        lineHeight = sp(size.toFloat() * 1.2f),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    when (element) {
        WidgetElement.COVER -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(d(cover)).background(colours.primaryContainer, RoundedCornerShape(d(10))),
        ) { Icon(Icons.Rounded.Album, null, tint = colours.onPrimaryContainer, modifier = Modifier.size(d(cover * 0.55f))) }

        WidgetElement.ARTIST -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(d(cover * 2 / 3)).background(colours.tertiaryContainer, CircleShape),
        ) { Icon(Icons.Rounded.Person, null, tint = colours.onTertiaryContainer, modifier = Modifier.size(d(cover * 0.4f))) }

        WidgetElement.INFO -> Column(Modifier.fillMaxWidth().padding(horizontal = d(6))) {
            Text(
                stringResource(R.string.widget_preview_title), fontSize = sp(15), fontWeight = FontWeight.Bold,
                color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.widget_preview_artists), fontSize = sp(12),
                color = ink.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        WidgetElement.LYRICS -> Column(Modifier.fillMaxWidth().padding(horizontal = d(4))) {
            Line(stringResource(R.string.widget_preview_lyric_previous), 10, faint)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = ink)) { append(stringResource(R.string.widget_preview_lyric_sung)) }
                    withStyle(SpanStyle(color = faint)) { append(stringResource(R.string.widget_preview_lyric_rest)) }
                },
                fontSize = sp(13), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Line(stringResource(R.string.widget_preview_lyric_next), 10, faint)
        }

        WidgetElement.UP_NEXT -> Line(
            stringResource(R.string.widget_up_next, stringResource(R.string.widget_preview_song)), 11, ink.copy(alpha = 0.7f),
        )

        WidgetElement.SEEK_BAR -> {
            val style by rememberEnumPreference(SliderStyleKey, DEFAULT_SLIDER_STYLE)
            val weight by rememberEnumPreference(SliderWeightKey, SliderWeight.THINNER)
            val accent = colours.primary
            Canvas(Modifier.fillMaxWidth().padding(horizontal = d(4)).height(d(24))) {
                drawPlayerSliderStill(style, 0.4f, accent, accent.copy(alpha = 0.25f), weight.scale, dot = false, playing = true)
            }
        }

        else -> Icon(
            element.icon, null,
            tint = if (element == WidgetElement.PLAY_PAUSE) colours.primary else ink,
            modifier = Modifier.size(d(if (element == WidgetElement.PLAY_PAUSE) 34 else 26)),
        )
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
