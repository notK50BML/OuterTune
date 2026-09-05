/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
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
) {
    var naming by remember { mutableStateOf<NameRequest?>(null) }

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

            if (playlists.isEmpty()) {
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

        LazyColumn {
            itemsIndexed(songsInSelected) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        StoredSongRow(song, playing = song.id == currentId) {
                            onPlay(songsInSelected, index)
                        }
                    }
                    // Buttons rather than drag. Drag-to-reorder is what this wants to be, and it is
                    // a good deal more work to do well - it needs its own gesture handling, an
                    // animated placeholder and autoscroll. Buttons reorder correctly today and do
                    // not have to be undone to add dragging later.
                    TextButton(
                        onClick = { onMoveSong(selected, index, index - 1) },
                        enabled = index > 0,
                    ) { Text("↑") }
                    TextButton(
                        onClick = { onMoveSong(selected, index, index + 1) },
                        enabled = index < songsInSelected.lastIndex,
                    ) { Text("↓") }
                    TextButton(onClick = { onRemoveSong(selected, song) }) { Text("✕") }
                }
            }
        }
    }
}

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
