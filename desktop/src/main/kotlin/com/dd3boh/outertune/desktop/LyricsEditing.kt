/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the lyrics menu can do to the current song's lyrics beyond fetching them again - the phone's
 * Edit, Search and Delete.
 */
class LyricsActions(
    val title: String,
    val artist: String,
    /** The stored text, LRC or TTML as it came, for editing. */
    val stored: () -> String?,
    /** Keeps [text] as this song's lyrics and shows it. */
    val save: (text: String, source: String) -> Unit,
    /** Every candidate the providers have for [title] and [artist], each as it arrives. */
    val search: suspend (title: String, artist: String, onFound: (LyricsCandidate) -> Unit) -> Unit,
    /** No lyrics for this song, for good - until fetched again by hand. */
    val remove: () -> Unit,
)

/** One provider's lyrics for a search. */
data class LyricsCandidate(val source: String, val text: String) {
    val synced: Boolean get() = LRC_TIME.containsMatchIn(text) || text.trimStart().startsWith("<")

    /** The first few sung lines, without timestamps or tags. */
    val preview: String
        get() = text.lineSequence()
            .map { it.replace(LRC_TAG, "").replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(" / ")

    private companion object {
        val LRC_TIME = Regex("""\[\d{1,2}:\d{2}""")
        val LRC_TAG = Regex("""\[[^\]]*]""")
    }
}

/** The stored lyrics as text, edited and saved - the phone's lyrics editor. */
@Composable
fun EditLyricsDialog(actions: LyricsActions, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(actions.stored().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit lyrics") },
        text = {
            Column(modifier = Modifier.width(560.dp)) {
                Text(
                    "LRC timestamps like [01:23.45] keep a line synced. Without them the lyrics show as plain text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().height(380.dp).padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.save(text, EDITED_SOURCE); onDismiss() }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Every provider's lyrics for a title and artist, to pick from - the phone's lyrics search. */
@Composable
fun SearchLyricsDialog(actions: LyricsActions, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(actions.title) }
    var artist by remember { mutableStateOf(actions.artist) }
    val results = remember { mutableStateListOf<LyricsCandidate>() }
    var searching by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun search() {
        job?.cancel()
        results.clear()
        searching = true
        job = scope.launch {
            try {
                actions.search(title, artist) { found -> if (found !in results) results += found }
            } finally {
                searching = false
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search lyrics") },
        text = {
            Column(modifier = Modifier.width(560.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::search, enabled = title.isNotBlank()) { Text("Search") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    if (searching) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                    Text(
                        when {
                            searching -> "  Searching… ${results.size} found"
                            results.isEmpty() -> "Nothing found. Try a shorter title, or the artist's name as written there."
                            else -> "${results.size} found. Click one to use it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(results) { candidate ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { actions.save(candidate.text, candidate.source); onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "${candidate.source} · ${if (candidate.synced) "synced" else "plain"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                candidate.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The source recorded for lyrics typed or changed by hand. */
const val EDITED_SOURCE = "Edited"
