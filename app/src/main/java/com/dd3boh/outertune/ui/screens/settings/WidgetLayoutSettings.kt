/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.WidgetLayoutKey
import com.dd3boh.outertune.constants.WidgetLayoutMediumKey
import com.dd3boh.outertune.constants.WidgetLayoutSmallKey
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.widget.PlayerWidget
import com.dd3boh.outertune.widget.WidgetElement
import com.dd3boh.outertune.widget.WidgetLayout
import com.dd3boh.outertune.widget.WidgetPlacement
import kotlin.math.roundToInt
import androidx.compose.material3.IconButton as M3IconButton

/** Height of one grid row in the editor. */
private val EDITOR_ROW = 56.dp

/**
 * Arranges the home-screen widget on a grid: drag a piece and it snaps to the nearest cells.
 *
 * The grid is the widget's own - [WidgetLayout.COLUMNS] columns by [WidgetLayout.MAX_ROWS] rows - so
 * where a piece lands here is where it lands on the home screen. While dragging, an outline shows
 * the cells it would take, red where it would cover another piece; dropping there puts it back
 * where it was, which is how layering is ruled out. Tap a piece to make it wider or narrower, or to
 * remove it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WidgetLayoutSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    // One arrangement per widget size: Small (one row), Medium (two), Large (up to four). The
    // widget picks which to show from how big it is on the home screen.
    var sizeTab by remember { mutableStateOf(2) }
    var storedSmall by rememberPreference(WidgetLayoutSmallKey, "")
    var storedMedium by rememberPreference(WidgetLayoutMediumKey, "")
    var storedLarge by rememberPreference(WidgetLayoutKey, "")
    val layout = remember(sizeTab, storedSmall, storedMedium, storedLarge) {
        when (sizeTab) {
            0 -> WidgetLayout.parse(storedSmall, WidgetLayout.SMALL_ROWS)
            1 -> WidgetLayout.parse(storedMedium, WidgetLayout.MEDIUM_ROWS)
            else -> WidgetLayout.parse(storedLarge)
        }
    }
    var selected by remember { mutableStateOf<WidgetElement?>(null) }

    fun save(newLayout: WidgetLayout) {
        when (sizeTab) {
            0 -> storedSmall = newLayout.encode()
            1 -> storedMedium = newLayout.encode()
            else -> storedLarge = newLayout.encode()
        }
        PlayerWidget.applyLayout(context, newLayout)
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.widget_layout_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(R.string.widget_size_small, R.string.widget_size_medium, R.string.widget_size_large)
                .forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = sizeTab == i,
                        onClick = {
                            sizeTab = i
                            selected = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(stringResource(label)) }
                }
        }
        Spacer(Modifier.height(12.dp))

        GridEditor(
            layout = layout,
            selected = selected,
            onSelect = { selected = if (selected == it) null else it },
            onDrop = { element, col, row -> save(layout.move(element, col, row)) },
        )

        val current = selected?.takeIf { it in layout }
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Icon(current.icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(current.label), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                M3IconButton(onClick = { save(layout.resize(current, -1)) }) { Icon(Icons.Rounded.Remove, null) }
                M3IconButton(onClick = { save(layout.resize(current, 1)) }) { Icon(Icons.Rounded.Add, null) }
                M3IconButton(onClick = {
                    save(layout.remove(current))
                    selected = null
                }) { Icon(Icons.Rounded.Delete, null) }
            }
        }

        val unused = layout.unused
        if (unused.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.widget_layout_add), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                unused.forEach { element ->
                    AssistChip(
                        onClick = { save(layout.add(element)) },
                        label = { Text(stringResource(element.label)) },
                        leadingIcon = { Icon(element.icon, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            save(WidgetLayout.defaultFor(layout.maxRows))
            selected = null
        }) {
            Icon(Icons.Rounded.Restore, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.widget_layout_reset))
        }
        Spacer(Modifier.height(24.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.widget_layout_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun GridEditor(
    layout: WidgetLayout,
    selected: WidgetElement?,
    onSelect: (WidgetElement) -> Unit,
    onDrop: (WidgetElement, Int, Int) -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // Where the piece being dragged would land, and whether it may.
    var target by remember { mutableStateOf<WidgetPlacement?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(EDITOR_ROW * layout.maxRows)
            .background(colours.secondaryContainer, RoundedCornerShape(20.dp))
    ) {
        val cellW = maxWidth / WidgetLayout.COLUMNS
        val cellWPx = with(density) { cellW.toPx() }
        val cellHPx = with(density) { EDITOR_ROW.toPx() }

        // The cells, faintly, so it is clear where things can snap to; and the drop target.
        Canvas(Modifier.fillMaxSize()) {
            for (r in 0 until layout.maxRows) for (c in 0 until WidgetLayout.COLUMNS) {
                drawRoundRect(
                    color = colours.onSecondaryContainer.copy(alpha = 0.07f),
                    topLeft = Offset(c * cellWPx + 2f, r * cellHPx + 2f),
                    size = Size(cellWPx - 4f, cellHPx - 4f),
                    cornerRadius = CornerRadius(6f),
                )
            }
            target?.let { t ->
                drawRoundRect(
                    color = if (layout.fits(t)) colours.primary else colours.error,
                    topLeft = Offset(t.col * cellWPx, t.row * cellHPx),
                    size = Size(t.span * cellWPx, cellHPx),
                    cornerRadius = CornerRadius(16f),
                    style = Stroke(width = 4f),
                )
            }
        }

        layout.placements.forEach { placement ->
            key(placement.element) {
                var drag by remember { mutableStateOf(Offset.Zero) }
                val isSelected = placement.element == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (placement.col * cellWPx + drag.x).roundToInt(),
                                (placement.row * cellHPx + drag.y).roundToInt(),
                            )
                        }
                        .size(cellW * placement.span, EDITOR_ROW)
                        .padding(3.dp)
                        .background(
                            if (isSelected) colours.primaryContainer else colours.surfaceContainerHighest,
                            RoundedCornerShape(12.dp),
                        )
                        .then(
                            if (isSelected) Modifier.border(2.dp, colours.primary, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .pointerInput(placement) {
                            fun snap(): WidgetPlacement = placement.copy(
                                col = ((placement.col * cellWPx + drag.x) / cellWPx).roundToInt(),
                                row = ((placement.row * cellHPx + drag.y) / cellHPx).roundToInt(),
                            )
                            detectDragGestures(
                                onDragStart = { onSelect(placement.element) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount
                                    target = snap()
                                },
                                onDragEnd = {
                                    val landing = snap()
                                    drag = Offset.Zero
                                    target = null
                                    onDrop(placement.element, landing.col, landing.row)
                                },
                                onDragCancel = {
                                    drag = Offset.Zero
                                    target = null
                                },
                            )
                        }
                        .clickable { onSelect(placement.element) }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Icon(placement.element.icon, null, Modifier.size(20.dp), tint = colours.onSurface)
                        if (placement.span >= 4) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(placement.element.label),
                                style = MaterialTheme.typography.labelMedium,
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
        WidgetElement.INFO -> Icons.Rounded.Title
        WidgetElement.LYRICS -> Icons.Rounded.Lyrics
        WidgetElement.UP_NEXT -> Icons.AutoMirrored.Rounded.QueueMusic
        WidgetElement.SEEK_BAR -> Icons.Rounded.LinearScale
        WidgetElement.PREVIOUS -> Icons.Rounded.SkipPrevious
        WidgetElement.PLAY_PAUSE -> Icons.Rounded.PlayArrow
        WidgetElement.NEXT -> Icons.Rounded.SkipNext
        WidgetElement.SHUFFLE -> Icons.Rounded.Shuffle
        WidgetElement.REPEAT -> Icons.Rounded.Repeat
    }
