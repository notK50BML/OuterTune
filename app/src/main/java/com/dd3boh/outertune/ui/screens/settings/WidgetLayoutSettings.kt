/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.constants.WidgetLayoutKey
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.widget.PlayerWidget
import com.dd3boh.outertune.widget.WidgetElement
import com.dd3boh.outertune.widget.WidgetLayout
import androidx.compose.material3.IconButton as M3IconButton

/**
 * Arranges the home-screen widget: which pieces it shows, in which row, in what order.
 *
 * Moved with buttons rather than dragged on a canvas, on purpose. The widget is rows of slots - see
 * [WidgetLayout] for why that is both all a widget can do and what rules out pieces landing on top
 * of each other - so "left / right / up a row / down a row" is the whole vocabulary, and buttons say
 * exactly that. The preview at the top redraws as you go, and the widget itself updates at once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WidgetLayoutSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    var stored by rememberPreference(WidgetLayoutKey, "")
    val layout = remember(stored) { WidgetLayout.parse(stored) }
    var selected by remember { mutableStateOf<WidgetElement?>(null) }

    fun save(newLayout: WidgetLayout) {
        stored = newLayout.encode()
        PlayerWidget.applyLayout(context, newLayout)
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        WidgetPreview(layout, selected)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.widget_layout_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        layout.rows.forEachIndexed { r, row ->
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.widget_layout_row, r + 1), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { element ->
                            FilterChip(
                                selected = selected == element,
                                onClick = { selected = if (selected == element) null else element },
                                label = { Text(stringResource(element.label)) },
                                leadingIcon = { Icon(element.icon, null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                    // The controls belong to the row the selected piece is in, so they appear right
                    // beside it rather than at the bottom of a long screen.
                    val current = selected
                    if (current != null && current in row) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            M3IconButton(onClick = { save(layout.moveWithinRow(current, -1)) }) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null)
                            }
                            M3IconButton(onClick = { save(layout.moveWithinRow(current, 1)) }) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                            }
                            M3IconButton(onClick = { save(layout.moveToRow(current, -1)) }) {
                                Icon(Icons.Rounded.KeyboardArrowUp, null)
                            }
                            M3IconButton(onClick = { save(layout.moveToRow(current, 1)) }) {
                                Icon(Icons.Rounded.KeyboardArrowDown, null)
                            }
                            Spacer(Modifier.weight(1f))
                            M3IconButton(onClick = {
                                save(layout.remove(current))
                                selected = null
                            }) {
                                Icon(Icons.Rounded.Delete, null)
                            }
                        }
                    }
                }
            }
        }

        val unused = layout.unused
        if (unused.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.widget_layout_add), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                unused.forEach { element ->
                    AssistChip(
                        onClick = {
                            // Into the selected piece's row when there is one, else the last row;
                            // a full row starts a new one below it.
                            val targetRow = selected?.let { s -> layout.rows.indexOfFirst { s in it } }
                                ?.takeIf { it >= 0 } ?: layout.rows.lastIndex
                            val added = layout.add(element, targetRow)
                            save(if (added == layout) layout.add(element, layout.rows.size) else added)
                        },
                        label = { Text(stringResource(element.label)) },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            save(WidgetLayout.DEFAULT)
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

/** A sketch of the widget in this arrangement, the selected piece outlined. */
@Composable
private fun WidgetPreview(layout: WidgetLayout, selected: WidgetElement?) {
    val onPreview = MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
            .padding(12.dp)
    ) {
        layout.rows.forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                row.forEach { element ->
                    val outline = if (element == selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    } else Modifier
                    val slot = if (element.flexible) Modifier.weight(1f) else Modifier
                    Box(slot.then(outline).padding(2.dp), contentAlignment = Alignment.CenterStart) {
                        when (element) {
                            WidgetElement.COVER -> Box(
                                Modifier.size(48.dp).background(onPreview.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                            )
                            WidgetElement.ARTIST -> Box(
                                Modifier.size(34.dp).background(onPreview.copy(alpha = 0.25f), CircleShape)
                            )
                            WidgetElement.INFO -> Column(Modifier.padding(start = 6.dp)) {
                                Box(Modifier.fillMaxWidth(0.8f).height(10.dp).background(onPreview.copy(alpha = 0.7f), CircleShape))
                                Spacer(Modifier.height(5.dp))
                                Box(Modifier.fillMaxWidth(0.5f).height(8.dp).background(onPreview.copy(alpha = 0.4f), CircleShape))
                            }
                            WidgetElement.LYRICS -> Box(
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).height(10.dp)
                                    .background(onPreview.copy(alpha = 0.75f), CircleShape)
                            )
                            WidgetElement.UP_NEXT -> Box(
                                Modifier.padding(start = 6.dp).fillMaxWidth(0.7f).height(7.dp)
                                    .background(onPreview.copy(alpha = 0.35f), CircleShape)
                            )
                            WidgetElement.SEEK_BAR -> Box(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp)
                                    .background(onPreview.copy(alpha = 0.6f), CircleShape)
                            )
                            else -> Icon(element.icon, null, tint = onPreview, modifier = Modifier.padding(6.dp).size(24.dp))
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
