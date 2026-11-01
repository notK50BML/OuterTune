/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.YTItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class SavedKind { Album, Playlist }

/**
 * An album or YouTube playlist saved to the library - the heart on its page, as on the phone.
 *
 * Kept as a reference rather than a copy of its songs: the page fetches them again when it opens, as
 * it does for any album, so a saved playlist that grows on YouTube shows the new songs here too.
 *
 * [id] is the album's browse id, or the playlist's id without YouTube's "VL" prefix. [playlistId] is
 * what YouTube's like takes - for an album its own OLAK… playlist, for a playlist the id again.
 */
data class SavedCollection(
    val id: String,
    val kind: SavedKind,
    val title: String,
    /** The album's artists, or the playlist's author, as one line. */
    val subtitle: String = "",
    val thumbnail: String = "",
    val playlistId: String = "",
    val savedAt: Long = 0L,
)

/** The playlist id as the library keys it - browse ids carry "VL" in front, playlist ids do not. */
fun String.bareCollectionId(): String = removePrefix("VL")

fun AlbumItem.toSaved(now: Long = System.currentTimeMillis()) = SavedCollection(
    id = browseId,
    kind = SavedKind.Album,
    title = title,
    subtitle = artists.orEmpty().joinToString { it.name },
    thumbnail = thumbnail,
    playlistId = playlistId,
    savedAt = now,
)

fun PlaylistItem.toSaved(now: Long = System.currentTimeMillis()) = SavedCollection(
    id = id.bareCollectionId(),
    kind = SavedKind.Playlist,
    title = title,
    subtitle = author?.name.orEmpty(),
    thumbnail = thumbnail.orEmpty(),
    playlistId = id.bareCollectionId(),
    savedAt = now,
)

/** Back to the item a card draws. The artists come back as one unlinked credit, which a card shows the same. */
fun SavedCollection.toAlbumItem() = AlbumItem(
    browseId = id,
    playlistId = playlistId,
    title = title,
    artists = subtitle.takeIf { it.isNotBlank() }?.let { listOf(Artist(it, null)) },
    thumbnail = thumbnail,
)

fun SavedCollection.toPlaylistItem() = PlaylistItem(
    id = id,
    title = title,
    author = subtitle.takeIf { it.isNotBlank() }?.let { Artist(it, null) },
    songCountText = null,
    thumbnail = thumbnail.takeIf { it.isNotBlank() },
    playEndpoint = null,
    shuffleEndpoint = null,
    radioEndpoint = null,
)

/**
 * Saved here and saved on the account, as one list: what was saved here first, newest first, then the
 * account's own. Something in both is shown once - the account's list catches up with a save here at
 * the next sync, and the same album twice in a row would look like a fault.
 */
fun <T : YTItem> mergeSaved(local: List<T>, remote: List<T>): List<T> {
    val seen = local.mapTo(HashSet()) { it.id.bareCollectionId() }
    return local + remote.filter { seen.add(it.id.bareCollectionId()) }
}

/** Whether an album or playlist can be saved at all: not Liked music, and not one of your own. */
fun YTItem.saveable(): Boolean = when (this) {
    is AlbumItem -> true
    is PlaylistItem -> id.bareCollectionId() != "LM" && !isEditable
    else -> false
}

fun YTItem.toSavedOrNull(): SavedCollection? = when (this) {
    is AlbumItem -> toSaved()
    is PlaylistItem -> toSaved()
    else -> null
}

/**
 * Saving and unsaving, with the account kept in step.
 *
 * The save here is the one that counts: it is written first and stays whatever happens to the
 * request to YouTube. That request goes when signed in with read-write sync, in the background, as
 * the phone's toggleLike does - YouTube.likePlaylist, which for an album is its OLAK… playlist id.
 * A failed request is not retried; the account's list simply lacks it until the next save.
 */
class LibrarySaves(
    private val library: LibraryStore,
    private val sync: YouTubeSync,
    /** Signed in, with read-write sync - read at each save, so a sign-in later counts. */
    private val canPush: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun set(collection: SavedCollection, saved: Boolean) {
        val id = collection.id.bareCollectionId()
        library.setSaved(collection.copy(id = id), saved)
        if (!saved) {
            // Off the account's lists as well, so it leaves the library now rather than at the next
            // sync. If the account still has it - read-only sync, or the request failed - that sync
            // brings it back, which is the truth.
            sync.albums.value = sync.albums.value.filterNot { it.browseId == id }
            sync.playlists.value = sync.playlists.value.filterNot { it.id.bareCollectionId() == id }
        }
        val remoteId = collection.playlistId.ifBlank { id }.bareCollectionId()
        if (canPush() && remoteId.isNotBlank()) scope.launch {
            YouTube.likePlaylist(remoteId, saved)
        }
    }
}

/** What the heart and the card menu read: which albums and playlists are saved, and how to change that. */
class CollectionSaves(private val savedIds: Set<String>, private val onSet: (SavedCollection, Boolean) -> Unit) {
    fun isSaved(id: String): Boolean = id.bareCollectionId() in savedIds
    fun toggle(collection: SavedCollection) = onSet(collection, !isSaved(collection.id))
}

/** Null where nothing is provided - a preview or a test - and then no heart is drawn. */
val LocalCollectionSaves = compositionLocalOf<CollectionSaves?> { null }

/** The heart on an album's or playlist's page: filled and red when saved, as the phone draws it. */
@Composable
fun SaveToLibraryButton(collection: SavedCollection) {
    val saves = LocalCollectionSaves.current ?: return
    val saved = saves.isSaved(collection.id)
    IconButton(onClick = { saves.toggle(collection) }) {
        Icon(
            if (saved) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder,
            contentDescription = if (saved) "Remove from library" else "Save to library",
            tint = if (saved) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
}

/** A right-click on an album or playlist card offers to save it, or to take it out again. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CollectionMenuArea(item: YTItem, content: @Composable () -> Unit) {
    val saves = LocalCollectionSaves.current
    val collection = item.takeIf { it.saveable() }?.toSavedOrNull()
    if (saves == null || collection == null) {
        content()
        return
    }
    var at by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = Modifier.onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) {
                at = event.changes.first().position
                event.changes.forEach { it.consume() }
            }
        },
    ) {
        content()
        val anchor = at
        if (anchor != null) {
            // A point at the click for the menu to drop from.
            Box(Modifier.offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) }.size(0.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { at = null }) {
                    val saved = saves.isSaved(collection.id)
                    DropdownMenuItem(
                        text = { Text(if (saved) "Remove from library" else "Save to library") },
                        leadingIcon = {
                            Icon(
                                if (saved) OuterTuneIcons.favorite else OuterTuneIcons.favoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            saves.toggle(collection)
                            at = null
                        },
                    )
                }
            }
        }
    }
}
