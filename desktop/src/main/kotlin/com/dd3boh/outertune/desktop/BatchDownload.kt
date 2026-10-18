/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A playlist or album being downloaded: which, how far, and how many could not be fetched. */
data class BatchDownload(
    val title: String,
    val total: Int,
    val done: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
)

/**
 * "Download" for a whole list of songs. Shows "Downloaded", with the phone's downloaded mark, once
 * every song is saved - pressing it then removes them - and a spinner while this list downloads.
 */
@Composable
fun DownloadAllButton(
    songIds: List<String>,
    downloadedIds: Set<String>,
    busy: Boolean,
    onDownload: () -> Unit,
) {
    val missing = songIds.count { it !in downloadedIds }
    OutlinedButton(onClick = onDownload, enabled = songIds.isNotEmpty() && !busy) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                if (missing == 0) OuterTuneIcons.offlinePin else OuterTuneIcons.download,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            when {
                busy -> "Downloading…"
                songIds.isNotEmpty() && missing == 0 -> "Downloaded"
                missing < songIds.size -> "Download $missing more"
                else -> "Download"
            }
        )
    }
}

/** The strip above the player bar while a playlist downloads, and briefly after. */
@Composable
fun BatchDownloadBar(batch: BatchDownload, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Icon(
            OuterTuneIcons.download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            if (batch.finished) {
                "Downloaded ${batch.title}" + if (batch.failed > 0) " · ${batch.failed} could not be fetched" else ""
            } else {
                "Downloading ${batch.title} · ${batch.done} of ${batch.total}" +
                    if (batch.failed > 0) " · ${batch.failed} failed" else ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!batch.finished) {
            LinearProgressIndicator(
                progress = { if (batch.total > 0) batch.done / batch.total.toFloat() else 0f },
                modifier = Modifier.width(160.dp).padding(horizontal = 12.dp),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        } else {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
