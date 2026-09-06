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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an artist screen has managed to gather. */
private sealed interface ArtistContent {
    data object Loading : ArtistContent
    data class Loaded(val name: String, val thumbnail: String?, val songs: List<SongItem>) : ArtistContent
    data class Failed(val reason: String) : ArtistContent
}

/**
 * One artist: their songs from YouTube, and whatever the library already holds for them.
 *
 * The library half is shown immediately and the remote half arrives when it arrives. A page that
 * showed nothing until the network answered would be blank for a second on every visit, even for an
 * artist whose songs are already sitting in the recently-played shelf.
 */
@Composable
fun ArtistPane(
    artist: StoredArtist,
    library: LibraryStore,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int) -> Unit,
    onAddToPlaylist: (StoredSong) -> Unit,
    modifier: Modifier = Modifier,
) {
    var content by remember(artist.id) { mutableStateOf<ArtistContent>(ArtistContent.Loading) }
    val librarySongs = remember(artist.id) { library.songsByArtist(artist.id) }

    LaunchedEffect(artist.id) {
        // A locally-keyed credit has no channel to ask about - see StoredArtist. The page is still
        // worth showing for the library half.
        if (!artist.linkable) {
            content = ArtistContent.Loaded(artist.name, null, emptyList())
            return@LaunchedEffect
        }
        content = withContext(Dispatchers.IO) {
            YouTube.artist(artist.id).fold(
                onSuccess = { page ->
                    ArtistContent.Loaded(
                        name = page.artist.title,
                        thumbnail = page.artist.thumbnail,
                        // Every song the page lists, in the order it lists them. The sections are
                        // Songs, Albums, Singles and so on; only the ones carrying songs are of use
                        // here, and flattening them keeps this a list rather than a second browser.
                        songs = page.sections.flatMap { section ->
                            section.items.filterIsInstance<SongItem>()
                        }.distinctBy { it.id },
                    )
                },
                onFailure = { ArtistContent.Failed(it.message ?: "Could not load that artist.") },
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            val shown = (content as? ArtistContent.Loaded)?.name ?: artist.name
            Text(
                shown,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (content is ArtistContent.Loading) {
                CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        (content as? ArtistContent.Failed)?.let {
            Text(
                it.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        val remote = (content as? ArtistContent.Loaded)?.songs.orEmpty()
        if (remote.isEmpty() && librarySongs.isEmpty() && content !is ArtistContent.Loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing to show for this artist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (librarySongs.isNotEmpty()) {
                item { Heading("In your library") }
                itemsIndexed(librarySongs) { index, song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            StoredSongRow(song, playing = song.id == currentId) {
                                onPlay(librarySongs.map { it.toItem() }, index)
                            }
                        }
                        TextButton(onClick = { onAddToPlaylist(song) }) { Text("+") }
                    }
                }
            }
            if (remote.isNotEmpty()) {
                item { Heading("Songs") }
                itemsIndexed(remote) { index, song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            SongRow(song, playing = song.id == currentId) { onPlay(remote, index) }
                        }
                        TextButton(onClick = { onAddToPlaylist(song.toStored()) }) { Text("+") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * Artist names, with the ones that lead somewhere marked as leading somewhere.
 *
 * Only credits carrying a channel are underlined and clickable. A name with nowhere to go that
 * looked like a link would be worse than plain text, because the disappointment is on the click
 * rather than before it - and a good many credits arrive as bare names.
 *
 * Built as one annotated string rather than a Row of separate Texts so the whole credit wraps and
 * ellipsises as a sentence. A Row would break between names and leave "Artist A," on one line with
 * nothing after it.
 */
/**
 * One credit as it should be shown and what happens when it is clicked.
 *
 * [linked] is whether there is a real channel behind the name. It drives the underline only - both
 * kinds are clickable. That is deliberate: a credit that arrived as a bare name still has a page
 * worth opening, made of whatever the library already holds by that artist, and refusing the click
 * meant that on any song whose credits came back without channel ids - which is most of them from
 * some sources - nothing on the line responded to the pointer at all. Underlining only the linked
 * ones keeps the distinction visible without making half the line dead.
 */
data class ArtistCredit(val artist: StoredArtist, val linked: Boolean)

/**
 * The credits for a song, ready to draw.
 *
 * Falls back to the joined display string when there are no structured credits, so a song stored
 * before the library kept them still shows its artist rather than "Unknown artist" - it just has
 * one credit instead of several.
 */
fun creditsFor(artists: List<StoredArtist>, fallback: String): List<ArtistCredit> {
    if (artists.isNotEmpty()) return artists.map { ArtistCredit(it, it.linkable) }
    val trimmed = fallback.trim()
    if (trimmed.isEmpty()) return emptyList()
    return listOf(ArtistCredit(StoredArtist.unlinked(trimmed), linked = false))
}

/**
 * A song's credits, each name its own clickable target.
 *
 * Separate Text composables in a Row rather than one string with click offsets mapped back to
 * ranges. The offset approach worked in a test and not in the app, and even when it works it is a
 * silent failure waiting to happen - get the arithmetic wrong by two and clicking one artist opens
 * a different one, with nothing on screen to suggest anything is amiss. A composable per name cannot
 * be off by two.
 *
 * It also buys the thing a desktop expects and the annotated string could not give: the pointer
 * turns into a hand over each name, so the line advertises itself as clickable before it is clicked.
 */
@Composable
fun ArtistNames(
    artists: List<StoredArtist>,
    fallback: String,
    colour: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    onClick: (StoredArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val credits = remember(artists, fallback) { creditsFor(artists, fallback) }
    if (credits.isEmpty()) {
        Text(
            text = "Unknown artist",
            style = style,
            color = colour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    Row(modifier = modifier) {
        credits.forEachIndexed { index, credit ->
            if (index > 0) {
                Text(text = ", ", style = style, color = colour, maxLines = 1)
            }
            Text(
                text = credit.artist.name,
                style = style.copy(
                    textDecoration = if (credit.linked) TextDecoration.Underline else null,
                ),
                color = colour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onClick(credit.artist) },
            )
        }
    }
}
