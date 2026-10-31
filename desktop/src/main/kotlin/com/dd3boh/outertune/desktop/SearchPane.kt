/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The phone's search filters, in its order. */
enum class SearchKind(val label: String, val filter: YouTube.SearchFilter?) {
    All("All", null),
    Songs("Songs", YouTube.SearchFilter.FILTER_SONG),
    Videos("Videos", YouTube.SearchFilter.FILTER_VIDEO),
    Albums("Albums", YouTube.SearchFilter.FILTER_ALBUM),
    Artists("Artists", YouTube.SearchFilter.FILTER_ARTIST),
    Playlists("Playlists", YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST),
}

private sealed interface SearchContent {
    data object Loading : SearchContent
    data class Summary(val sections: List<Pair<String, List<YTItem>>>) : SearchContent
    data class Results(val items: List<YTItem>, val continuation: String?) : SearchContent
    data class Failed(val message: String) : SearchContent
}

/**
 * Search results: every kind of thing YouTube Music finds, not just songs.
 *
 * "All" is YouTube's own overview - a top result and a short shelf of each kind - and each chip is
 * the full, paged list of one kind. Songs and videos are rows, since they are played; albums,
 * artists and playlists are cards, since they are opened.
 */
@Composable
fun SearchPane(
    query: String,
    currentId: String?,
    hideExplicit: Boolean,
    onPlay: (List<SongItem>, Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (StoredArtist) -> Unit,
    onOpenPlaylist: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by remember(query) { mutableStateOf(SearchKind.All) }
    var content by remember { mutableStateOf<SearchContent>(SearchContent.Loading) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun List<YTItem>.visible() = filter { it.isSupported && !(hideExplicit && it.explicit) }

    LaunchedEffect(query, kind) {
        content = SearchContent.Loading
        content = withContext(Dispatchers.IO) {
            val filter = kind.filter
            if (filter == null) {
                YouTube.searchSummary(query).fold(
                    { page -> SearchContent.Summary(page.summaries.map { it.title to it.items }) },
                    { SearchContent.Failed(it.message ?: "Search failed.") },
                )
            } else {
                YouTube.search(query, filter).fold(
                    { SearchContent.Results(it.items, it.continuation) },
                    { SearchContent.Failed(it.message ?: "Search failed.") },
                )
            }
        }
    }

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

    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(SearchKind.entries) {
                FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it.label) })
            }
        }

        when (val current = content) {
            SearchContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is SearchContent.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
            }

            is SearchContent.Summary -> {
                val sections = current.sections
                    // YouTube sends the top result as an untitled shelf.
                    .map { (title, items) -> title.ifBlank { "Top result" } to items.visible() }
                    .filter { it.second.isNotEmpty() }
                if (sections.isEmpty()) {
                    NothingFound(query)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        sections.forEach { (title, items) ->
                            item(key = "h-$title") {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                                )
                            }
                            val rows = items.filter { it is SongItem }
                            val cards = items.filterNot { it is SongItem }
                            itemsIndexed(rows, key = { i, it -> "$title-$i-${it.id}" }) { _, item ->
                                SongRow(item as SongItem, playing = item.id == currentId) { open(item, rows) }
                            }
                            if (cards.isNotEmpty()) {
                                item(key = "c-$title") {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        val cardsUniqueKeys = uniqueKeys(cards) { it.id }
                                        itemsIndexed(cards, key = { i, _ -> cardsUniqueKeys[i] }) { _, item ->
                                            Card(item = item, playing = false) { open(item, cards) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is SearchContent.Results -> {
                val items = current.items.visible()
                fun loadMore() {
                    val next = current.continuation ?: return
                    if (loadingMore) return
                    loadingMore = true
                    scope.launch {
                        val more = withContext(Dispatchers.IO) { YouTube.searchContinuation(next).getOrNull() }
                        if (more != null) {
                            content = SearchContent.Results((current.items + more.items).distinctBy { it.id }, more.continuation)
                        }
                        loadingMore = false
                    }
                }
                when {
                    items.isEmpty() -> NothingFound(query)
                    items.all { it is SongItem } -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        val itemKeys = uniqueKeys(items) { it.id }
                        itemsIndexed(items, key = { i, _ -> itemKeys[i] }) { _, item ->
                            SongRow(item as SongItem, playing = item.id == currentId) { open(item, items) }
                        }
                        if (current.continuation != null) item { MoreButton(loadingMore, ::loadMore) }
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(176.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        val itemsUniqueKeys = uniqueKeys(items) { it.id }
                        gridItemsIndexed(items, key = { i, _ -> itemsUniqueKeys[i] }) { _, item ->
                            Card(item = item, playing = item.id == currentId) { open(item, items) }
                        }
                        if (current.continuation != null) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                MoreButton(loadingMore, ::loadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NothingFound(query: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Nothing found for \"$query\".",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MoreButton(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            OutlinedButton(onClick = onClick) { Text("Load more") }
        }
    }
}

/**
 * What appears under the search box while it has focus: recent searches when it is empty, YouTube's
 * suggestions as soon as something is typed - the phone's search screen before a search is run.
 */
@Composable
fun SearchSuggestionsPanel(
    query: String,
    history: List<String>,
    onPick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onOpenItem: (YTItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var items by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            suggestions = emptyList()
            items = emptyList()
            return@LaunchedEffect
        }
        // A pause before asking, so typing a word is one request rather than one per letter.
        kotlinx.coroutines.delay(SUGGEST_DELAY_MS)
        val result = withContext(Dispatchers.IO) { YouTube.searchSuggestions(query).getOrNull() }
        suggestions = result?.queries.orEmpty()
        items = result?.recommendedItems.orEmpty().filter { it.isSupported }
    }

    val shownHistory = if (query.isBlank()) history else history.filter { it.contains(query, ignoreCase = true) }.take(3)
    if (shownHistory.isEmpty() && suggestions.isEmpty() && items.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 6.dp),
    ) {
        shownHistory.forEach { past ->
            SuggestionRow(past, OuterTuneIcons.history, onClick = { onPick(past) }, onRemove = { onRemoveHistory(past) })
        }
        suggestions.filterNot { it in shownHistory }.take(6).forEach { text ->
            SuggestionRow(text, OuterTuneIcons.search, onClick = { onPick(text) })
        }
        items.take(4).forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onOpenItem(item) }.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Artwork(item.thumbnail, size = 36.dp, cornerRadius = if (item is ArtistItem) 18.dp else 4.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.shownSubtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) {
            Icon(
                OuterTuneIcons.close,
                contentDescription = "Remove from history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable(onClick = onRemove),
            )
        }
    }
}

private const val SUGGEST_DELAY_MS = 250L
