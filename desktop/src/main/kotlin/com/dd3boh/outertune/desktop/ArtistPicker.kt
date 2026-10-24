/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.SongItem

/**
 * Every artist [this] song credits, in credit order, as the pages that would open them - a credit
 * with no channel becomes the same unlinked page the rest of the app opens for it. The same artist
 * credited twice (a feature that is also the main artist, say) is one entry.
 */
fun SongItem.creditedArtists(): List<StoredArtist> =
    artists.map { artist -> artist.id?.let { StoredArtist(it, artist.name) } ?: StoredArtist.unlinked(artist.name) }
        .distinctBy { it.id }

/**
 * Which of a song's artists to open: the phone's ArtistDialog, as a page of the desktop's menu
 * panel - the same rounded container the ⋮ opened, rather than a second window stacked on it.
 *
 * Unlike the phone, nothing here is dimmed. There, an artist with no channel has no page to open;
 * here, it opens the unlinked page that lists your files by that name and offers to link it - so
 * it is only marked as unlinked, never refused.
 *
 * @param onBack shown as "← Back" when the list was reached from a menu, to return to it.
 */
@Composable
fun ArtistPickerList(
    artists: List<StoredArtist>,
    onBack: (() -> Unit)?,
    onPick: (StoredArtist) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, bottom = 4.dp),
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("← Back") }
            } else {
                Text(
                    "Artists",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${artists.size} artists",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(bottom = 6.dp))
        // A long feature list scrolls inside the panel instead of growing it past the window.
        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for (artist in artists) {
                ArtistPickerRow(artist) { onPick(artist) }
            }
        }
    }
}

@Composable
private fun ArtistPickerRow(artist: StoredArtist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Fetched from the artist's page when the library has no picture yet, and kept - it used to
        // show only what was already cached, so a featured artist never opened was just an initial.
        ArtistAvatar(artist, size = 40.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                artist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!artist.linkable) {
                Text(
                    "Not linked to YouTube",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The artist list on its own, for a button that is nothing but "open an artist" - the player's
 * artist button. Opens out of its anchor the way the ⋮ panel does.
 */
@Composable
fun ArtistPickerPanel(artists: List<StoredArtist>, onPick: (StoredArtist) -> Unit, onDismiss: () -> Unit) {
    MenuPanel(
        tiles = emptyList(),
        onDismiss = onDismiss,
        page = { close -> ArtistPickerList(artists, onBack = null) { onPick(it); close() } },
    )
}
