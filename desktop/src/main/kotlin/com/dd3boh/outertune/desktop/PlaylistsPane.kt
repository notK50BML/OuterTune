/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import com.zionhuang.innertube.models.PlaylistItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The list of playlists, and the songs in whichever is open.
 *
 * Selection is held by the caller rather than here. A playlist stays open while the user searches
 * for something to put in it, so this cannot own that state without the act of searching closing the
 * thing being added to.
 */
@Composable
fun PlaylistsPane(
    playlists: List<StoredPlaylist>,
    selected: StoredPlaylist?,
    songsInSelected: List<StoredSong>,
    currentId: String?,
    onSelect: (StoredPlaylist?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (StoredPlaylist, String) -> Unit,
    onDelete: (StoredPlaylist) -> Unit,
    onPlay: (List<StoredSong>, Int) -> Unit,
    onRemoveSong: (StoredPlaylist, StoredSong) -> Unit,
    onMoveSong: (StoredPlaylist, Int, Int) -> Unit,
    /** Playlists saved on YouTube. Empty when signed out, which is not an error. */
    saved: List<PlaylistItem> = emptyList(),
    onPlaySaved: (PlaylistItem) -> Unit = {},
) {
    var naming by remember { mutableStateOf<NameRequest?>(null) }
    // Kept beside the local ones rather than on a tab of their own. They are the same kind of thing
    // to the person looking for them, and a tab would mean knowing which of two places a playlist
    // lives in before being able to find it.


    naming?.let { request ->
        NameDialog(
            title = if (request.existing == null) "New playlist" else "Rename playlist",
            initial = request.existing?.name ?: "",
            onDismiss = { naming = null },
            onConfirm = { name ->
                if (request.existing == null) onCreate(name) else onRename(request.existing, name)
                naming = null
            },
        )
    }

    if (selected == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            ) {
                Text(
                    "Playlists",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { naming = NameRequest(null) }) { Text("New") }
            }

            // Both, not just the local ones. With saved playlists present and none made here, the
            // old check bailed out and showed "No playlists yet" over a list that was not empty.
            if (playlists.isEmpty() && saved.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No playlists yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn {
                if (saved.isNotEmpty()) {
                    item {
                        Text(
                            text = "Saved on YouTube",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                        )
                    }
                    itemsIndexed(saved) { _, playlist ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                // Plays rather than opens. There is no page for an online playlist
                                // yet, and a card that opens nothing is worse than one that does the
                                // obvious thing with what it holds.
                                .clickable { onPlaySaved(playlist) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp),
                            ) {
                                Artwork(playlist.thumbnail, size = 40.dp, cornerRadius = 6.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        playlist.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(
                                            playlist.author?.name,
                                            playlist.songCountText,
                                        ).joinToString(" · ").ifBlank { "Playlist" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (playlists.isNotEmpty()) {
                        item {
                            Text(
                                text = "On this computer",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                itemsIndexed(playlists) { _, playlist ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onSelect(playlist) },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (playlist.songCount == 1) "1 song" else "${playlist.songCount} songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        ) {
            TextButton(onClick = { onSelect(null) }) { Text("← Playlists") }
            Text(
                selected.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { naming = NameRequest(selected) }) { Text("Rename") }
            TextButton(onClick = { onDelete(selected); onSelect(null) }) { Text("Delete") }
        }

        if (songsInSelected.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet. Search for a song and use its + button.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        // Which row is being dragged, and how far it has travelled since it last stepped. Held by
        // the list rather than by a row, so a row can hand the drag on to its neighbour as it
        // passes - the gesture belongs to the list, not to whichever composable began it.
        var dragging by remember(selected.id) { mutableStateOf(-1) }
        var dragOffset by remember(selected.id) { mutableStateOf(0f) }
        val rowHeightPx = with(LocalDensity.current) { PLAYLIST_ROW_HEIGHT.toPx() }

        LazyColumn {
            // Keyed by song id so a composable follows its song across a reorder. Keyed by index
            // instead, every row would be told it now holds different content the moment anything
            // moved, and the drag in progress would be cancelled by its own first step.
            itemsIndexed(songsInSelected, key = { _, song -> song.id }) { index, song ->
                val held = index == dragging
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PLAYLIST_ROW_HEIGHT)
                        // Lifted while held, so the row under the pointer is visibly the one being
                        // moved rather than one of several that shifted around it.
                        .graphicsLayer {
                            translationY = if (held) dragOffset else 0f
                            shadowElevation = if (held) 12f else 0f
                            alpha = if (held) 0.92f else 1f
                        }
                        .background(
                            if (held) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            } else {
                                Color.Transparent
                            }
                        ),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StoredSongRow(song, playing = song.id == currentId) {
                            onPlay(songsInSelected, index)
                        }
                    }
                    TextButton(onClick = { onRemoveSong(selected, song) }) { Text("✕") }

                    // A handle rather than the whole row, for the same reason the queue uses one:
                    // the row is already a click target for playing the song, and a list where
                    // pressing anything might mean "drag" is a list that cannot be scrolled with
                    // confidence.
                    Icon(
                        OuterTuneIcons.dragHandle,
                        contentDescription = "Reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(22.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .pointerInput(song.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = index
                                        dragOffset = 0f
                                    },
                                    onDragEnd = { dragging = -1; dragOffset = 0f },
                                    onDragCancel = { dragging = -1; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    // Stepped a whole row at a time rather than continuously, so
                                    // the list settles between moves instead of thrashing while the
                                    // pointer crosses a boundary.
                                    val steps = (dragOffset / rowHeightPx).toInt()
                                    if (steps != 0 && dragging >= 0) {
                                        val target = (dragging + steps)
                                            .coerceIn(0, songsInSelected.lastIndex)
                                        if (target != dragging) {
                                            onMoveSong(selected, dragging, target)
                                            dragging = target
                                        }
                                        // Whatever the step consumed is taken off, so the remainder
                                        // still counts towards the next one.
                                        dragOffset -= steps * rowHeightPx
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

/**
 * Fixed so a drag can be stepped in whole rows.
 *
 * The gesture converts a distance into a number of rows, which needs every row to be the same known
 * height - a list of rows that size themselves to their content has no such number.
 */
private val PLAYLIST_ROW_HEIGHT = 60.dp

/** Which playlist a name is being asked for, or null when creating a new one. */
private data class NameRequest(val existing: StoredPlaylist?)

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                // A playlist with no name is unfindable in a list of playlists.
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Offers the playlists a song can be added to.
 *
 * Creating one from here as well, because the moment someone wants to file a song is exactly when
 * they discover they have nowhere to put it, and sending them elsewhere to make one loses the song
 * they were looking at.
 */
@Composable
fun AddToPlaylistDialog(
    song: StoredSong,
    playlists: List<StoredPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (StoredPlaylist) -> Unit,
    onCreateAndAdd: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (creating) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("New playlist name") },
                    )
                } else {
                    if (playlists.isEmpty()) {
                        Text(
                            "No playlists yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn {
                        itemsIndexed(playlists) { _, playlist ->
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(playlist); onDismiss() }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { onCreateAndAdd(name.trim()); onDismiss() },
                    enabled = name.isNotBlank(),
                ) { Text("Create and add") }
            } else {
                TextButton(onClick = { creating = true }) { Text("New playlist") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
