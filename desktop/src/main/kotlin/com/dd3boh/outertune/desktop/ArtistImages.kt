/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Artists' pictures, for places that only know an artist's name and channel - Stats, the Library's
 * artist grid, the list a song with several artists asks you to pick from.
 *
 * Fetched the first time an artist is shown, from their page, and kept in the library (the artist
 * table's thumbnail) so the next visit is instant and offline. A few at a time, so a grid of thirty
 * does not open thirty connections at once. Local-only artists - names from file tags, with no
 * channel - have no picture to fetch and keep their initial.
 */
object ArtistImages {

    private val memory = ConcurrentHashMap<String, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(4)

    @Volatile
    var library: LibraryStore? = null

    /** Remembers a picture seen elsewhere - an artist page that was opened anyway. */
    fun remember(artistId: String, url: String?) {
        if (url.isNullOrBlank()) return
        memory[artistId] = url
        library?.setArtistThumbnail(artistId, url)
    }

    suspend fun url(artist: StoredArtist): String? {
        if (!artist.linkable) {
            // A local artist linked to YouTube Music has their picture - see ArtistLinks.
            val link = withContext(Dispatchers.IO) { library?.database?.artistLink(ArtistLinks.key(artist.name)) }
            return link?.channelId?.let { url(StoredArtist(it, artist.name)) }
        }
        memory[artist.id]?.let { return it }
        if (artist.id in missing) return null
        return withContext(Dispatchers.IO) {
            // Blank is how the artist table stores "no picture yet", not a picture.
            library?.artistThumbnail(artist.id)?.takeIf { it.isNotBlank() }?.let { stored ->
                memory[artist.id] = stored
                return@withContext stored
            }
            gate.withPermit {
                val page = YouTube.artist(artist.id).getOrNull()?.artist
                // The page is fetched anyway, so its current name is learned too - see ArtistRenamer.
                page?.title?.let { ArtistRenamer.learned(artist.id, it) }
                val fetched = page?.thumbnail
                if (fetched.isNullOrBlank()) {
                    missing += artist.id
                    null
                } else {
                    remember(artist.id, fetched)
                    fetched
                }
            }
        }
    }
}

/** An artist's round picture, or their initial on a tinted disc until (or unless) there is one. */
@Composable
fun ArtistAvatar(artist: StoredArtist, size: Dp) {
    var url by remember(artist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(artist.id) { url = ArtistImages.url(artist) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        val picture = url
        if (picture != null) {
            Artwork(picture, size = size, cornerRadius = size / 2)
        } else {
            Text(
                artist.name.take(1).uppercase(),
                fontSize = (size.value * 0.4f).sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
