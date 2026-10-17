/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.ArtistSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** What an artist screen has managed to gather. */
private sealed interface ArtistContent {
    data object Loading : ArtistContent
    data class Loaded(val page: ArtistPage?) : ArtistContent
    data class Failed(val reason: String) : ArtistContent
}

/** A section's "see all" page, once opened. */
private sealed interface MoreContent {
    data object Loading : MoreContent
    data class Loaded(val title: String, val items: List<YTItem>) : MoreContent
    data class Failed(val reason: String) : MoreContent
}

/**
 * One artist, laid out as the phone does it.
 *
 * A large picture with the name over it and Play and Shuffle beneath; then the artist's top songs,
 * five of them, with an arrow through to the rest; then a carousel for each of the other things
 * YouTube lists - albums, singles and EPs, videos, live performances, appearances, similar artists -
 * each with its own arrow through to everything in it. What the library already holds by them sits
 * at the end, where the phone puts it too, and is there at once while the rest is fetched.
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
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (StoredArtist) -> Unit = {},
    onOpenPlaylist: (PlaylistItem) -> Unit = {},
    hideExplicit: Boolean = false,
) {
    var content by remember(artist.id) { mutableStateOf<ArtistContent>(ArtistContent.Loading) }
    val librarySongs = remember(artist.id) { library.songsByArtist(artist.id) }
    var more by remember(artist.id) { mutableStateOf<ArtistSection?>(null) }

    LaunchedEffect(artist.id) {
        // A locally-keyed credit has no channel to ask about - see StoredArtist. The page is still
        // worth showing for the library half.
        if (!artist.linkable) {
            content = ArtistContent.Loaded(null)
            return@LaunchedEffect
        }
        content = withContext(Dispatchers.IO) {
            YouTube.artist(artist.id).fold(
                onSuccess = {
                    // Kept for Stats and the Library, which show this artist without opening them.
                    ArtistImages.remember(artist.id, it.artist.thumbnail)
                    ArtistContent.Loaded(it)
                },
                onFailure = { ArtistContent.Failed(it.message ?: "Could not load that artist.") },
            )
        }
    }

    fun List<YTItem>.visible() = filter { it.isSupported && !(hideExplicit && it.explicit) }

    fun open(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is SongItem -> {
                val songs = siblings.filterIsInstance<SongItem>()
                onPlay(songs, songs.indexOf(item).coerceAtLeast(0))
            }
            is AlbumItem -> onOpenAlbum(item.browseId)
            is ArtistItem -> onOpenArtist(StoredArtist(item.id, item.title))
            is PlaylistItem -> onOpenPlaylist(item)
        }
    }

    more?.let { section ->
        MoreItems(
            artistName = (content as? ArtistContent.Loaded)?.page?.artist?.title ?: artist.name,
            section = section,
            currentId = currentId,
            visible = { it.visible() },
            onBack = { more = null },
            onOpen = ::open,
            modifier = modifier,
        )
        return
    }

    val page = (content as? ArtistContent.Loaded)?.page
    val name = page?.artist?.title ?: artist.name
    val sections = page?.sections.orEmpty()
        .map { it.copy(items = it.items.visible()) }
        .filter { it.items.isNotEmpty() }
    val allSongs = sections.flatMap { it.items.filterIsInstance<SongItem>() }.distinctBy { it.id }
        .ifEmpty { librarySongs.map { it.toItem() } }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ArtistHeader(
                name = name,
                thumbnail = page?.artist?.thumbnail,
                description = page?.description,
                loading = content is ArtistContent.Loading,
                canPlay = allSongs.isNotEmpty(),
                onBack = onBack,
                onPlay = { onPlay(allSongs, 0) },
                onShuffle = {
                    val order = allSongs.shuffled()
                    if (order.isNotEmpty()) onPlay(order, 0)
                },
            )
        }

        (content as? ArtistContent.Failed)?.let { failed ->
            item {
                Text(
                    failed.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
        }

        sections.forEach { section ->
            val songsOnly = section.items.all { it is SongItem }
            item(key = "head-${section.title}") {
                SectionHeader(
                    title = section.title,
                    onMore = section.moreEndpoint?.let { { more = section } },
                )
            }
            if (songsOnly) {
                // The phone's top-songs list: five rows, the rest behind the arrow.
                val shown = section.items.filterIsInstance<SongItem>().take(TOP_SONGS)
                itemsIndexed(shown, key = { _, song -> "${section.title}-${song.id}" }) { index, song ->
                    SongListRow(song, index + 1, playing = song.id == currentId) { open(song, section.items) }
                }
            } else {
                item(key = "row-${section.title}") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                    ) {
                        items(section.items, key = { it.id }) { item ->
                            Card(item = item, playing = item.id == currentId) { open(item, section.items) }
                        }
                    }
                }
            }
        }

        if (librarySongs.isNotEmpty()) {
            item { SectionHeader(title = "In your library", onMore = null) }
            itemsIndexed(librarySongs, key = { _, song -> "lib-${song.id}" }) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        StoredSongRow(song, playing = song.id == currentId) {
                            onPlay(librarySongs.map { it.toItem() }, index)
                        }
                    }
                    TextButton(onClick = { onAddToPlaylist(song) }) { Text("+") }
                }
            }
        }

        if (content !is ArtistContent.Loading && sections.isEmpty() && librarySongs.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing to show for this artist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** How many of an artist's songs the page shows before the arrow. The phone's number. */
private const val TOP_SONGS = 5

/**
 * The picture, the name and the buttons.
 *
 * The photo runs the full width and fades into the page at the bottom, with the name set over the
 * fade - the phone's artist header, which is what makes the page read as about a person rather
 * than as a list with a title.
 */
@Composable
private fun ArtistHeader(
    name: String,
    thumbnail: String?,
    description: String?,
    loading: Boolean,
    canPlay: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val height = (maxWidth * 0.42f).coerceIn(240.dp, 420.dp)
        val fullWidth = maxWidth
        Box(modifier = Modifier.fillMaxWidth().height(height).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))) {
            if (thumbnail != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Square art drawn at the full width and cropped by the box, centred on the face
                    // that is nearly always in the upper middle of an artist photo.
                    Artwork(thumbnail, size = fullWidth, cornerRadius = 0.dp)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer))
            }
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to background,
                    )
                ),
            )
            TextButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                Text("← Back", color = Color.White)
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 28.dp, vertical = 16.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (loading) {
                    Spacer(modifier = Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
    ) {
        Button(onClick = onPlay, enabled = canPlay) {
            Icon(OuterTuneIcons.play, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Play")
        }
        OutlinedButton(onClick = onShuffle, enabled = canPlay) {
            Icon(OuterTuneIcons.shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Shuffle")
        }
    }
    if (!description.isNullOrBlank()) {
        var expanded by remember(description) { mutableStateOf(false) }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 28.dp, vertical = 4.dp)
                .clickable { expanded = !expanded },
        )
    }
}

/** A section title, with an arrow through to everything in it when there is more. */
@Composable
private fun SectionHeader(title: String, onMore: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
            .let { if (onMore != null) it.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onMore) else it },
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Icon(
                OuterTuneIcons.expandLess,
                contentDescription = "See all $title",
                tint = if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp).rotate(90f),
            )
        }
    }
}

/** One of an artist's songs, numbered as a chart is. */
@Composable
private fun SongListRow(song: SongItem, rank: Int, playing: Boolean, onClick: () -> Unit) = SongMenuArea(song) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        SongArtwork(song.thumbnail, size = 48.dp, playing = playing, cornerRadius = 6.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(song.artists.joinToString { it.name }, song.album?.name).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Everything in one section - every song, every album - fetched from the section's own page and
 * followed through its continuations. Songs as a list, everything else as a grid of cards.
 */
@Composable
private fun MoreItems(
    artistName: String,
    section: ArtistSection,
    currentId: String?,
    visible: (List<YTItem>) -> List<YTItem>,
    onBack: () -> Unit,
    onOpen: (YTItem, List<YTItem>) -> Unit,
    modifier: Modifier,
) {
    var state by remember(section) { mutableStateOf<MoreContent>(MoreContent.Loading) }
    LaunchedEffect(section) {
        val endpoint = section.moreEndpoint ?: return@LaunchedEffect
        state = withContext(Dispatchers.IO) {
            YouTube.artistItems(endpoint).fold(
                onSuccess = { first ->
                    val items = first.items.toMutableList()
                    var next = first.continuation
                    var pages = 0
                    while (next != null && pages < 10) {
                        val page = YouTube.artistItemsContinuation(next).getOrNull() ?: break
                        items += page.items
                        next = page.continuation
                        pages++
                    }
                    MoreContent.Loaded(first.title.ifBlank { section.title }, items.distinctBy { it.id })
                },
                onFailure = { MoreContent.Failed(it.message ?: "Could not load ${section.title}.") },
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            TextButton(onClick = onBack) { Text("← $artistName") }
            Text(
                (state as? MoreContent.Loaded)?.title ?: section.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        when (val current = state) {
            MoreContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is MoreContent.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(current.reason, color = MaterialTheme.colorScheme.error)
            }
            is MoreContent.Loaded -> {
                val items = visible(current.items)
                if (items.all { it is SongItem }) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                            SongListRow(item as SongItem, index + 1, playing = item.id == currentId) { onOpen(item, items) }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(176.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            Card(item = item, playing = item.id == currentId) { onOpen(item, items) }
                        }
                    }
                }
            }
        }
    }
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
            // Underlined under the pointer only, as a link on a web page is. Permanently underlined,
            // every credit on the player read as a hyperlink in a document rather than a title - the
            // phone shows them plain, and the hand cursor already says they can be clicked.
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Text(
                text = credit.artist.name,
                style = style.copy(
                    textDecoration = if (credit.linked && hovered) TextDecoration.Underline else null,
                ),
                color = colour,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null) { onClick(credit.artist) },
            )
        }
    }
}
