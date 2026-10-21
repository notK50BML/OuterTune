/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every artist [this] song credits, in credit order, as the pages that would open them - a credit
 * with no channel becomes the same unlinked page the rest of the app opens for it. The same artist
 * credited twice (a feature that is also the main artist, say) is one entry.
 */
fun SongItem.creditedArtists(): List<StoredArtist> =
    artists.map { artist -> artist.id?.let { StoredArtist(it, artist.name) } ?: StoredArtist.unlinked(artist.name) }
        .distinctBy { it.id }

/**
 * An artist's picture as the library last cached it, or null. Provided by the window; read on the
 * IO dispatcher, since it is a database lookup.
 */
val LocalArtistThumbnail = staticCompositionLocalOf<(String) -> String?> { { null } }

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
    val thumbnailFor = LocalArtistThumbnail.current
    val thumbnails by produceState(emptyMap<String, String?>(), artists) {
        value = withContext(Dispatchers.IO) {
            artists.filter { it.linkable }.associate { it.id to runCatching { thumbnailFor(it.id) }.getOrNull() }
        }
    }

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
                ArtistPickerRow(artist, thumbnails[artist.id]) { onPick(artist) }
            }
        }
    }
}

@Composable
private fun ArtistPickerRow(artist: StoredArtist, thumbnail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        ArtistAvatar(artist.name, thumbnail, size = 40.dp)
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

/** The artist's picture in a circle, as the phone draws artists - or their initial until one is known. */
@Composable
private fun ArtistAvatar(name: String, thumbnail: String?, size: Dp) {
    if (thumbnail != null) {
        Artwork(thumbnail, size = size, cornerRadius = size / 2)
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
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
