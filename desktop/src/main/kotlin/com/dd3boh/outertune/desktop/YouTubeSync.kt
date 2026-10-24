/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.launch
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.utils.completed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * The signed-in account's library, brought into the desktop: liked songs, saved albums, and the
 * artists in it - what the phone's SyncUtils fetches, from the same places.
 *
 * Liked songs are merged in rather than mirrored. A song liked here and not yet on YouTube stays
 * liked; one liked on YouTube becomes liked here, in YouTube's order. Liking or unliking here is sent
 * to YouTube as it happens (see [pushLike]), so the two converge without a sync ever having to
 * decide which side to throw away.
 *
 * Albums and artists are held in memory, not stored: they are YouTube's lists, shown as YouTube has
 * them, and refreshed each time rather than allowed to go stale in a table.
 */
/** What a sync can bring in, with the phone's letters for each (its SyncContent encoding). */
enum class SyncKind(val code: Char, val label: String) {
    LikedSongs('L', "Liked songs"),
    LibrarySongs('S', "Library songs"),
    Albums('A', "Albums"),
    Artists('R', "Artists"),
    Playlists('P', "Playlists"),
    RecentActivity('C', "Recent activity"),
    ;

    companion object {
        const val ALL = "ARPLSC"
        fun decode(stored: String) = entries.filter { it.code in stored }
        fun encode(kinds: Collection<SyncKind>) = kinds.joinToString("") { it.code.toString() }
    }
}

/** The phone's sync modes: whether changes made here are sent to the account. */
enum class SyncMode(val label: String) {
    ReadOnly("Read only (view remote content)"),
    ReadWrite("Read write (also change remote content)"),
}

/** The phone's conflict resolution: what happens to local likes the account no longer has. */
enum class SyncConflict(val label: String) {
    AddOnly("Keep all local content, add new remote content"),
    Overwrite("Overwrite local content with remote content"),
}

class YouTubeSync(private val library: LibraryStore) {

    val syncing = MutableStateFlow(false)

    /** What the last sync did, in a sentence, or null before the first. */
    val status = MutableStateFlow<String?>(null)

    val albums = MutableStateFlow<List<AlbumItem>>(emptyList())
    val artists = MutableStateFlow<List<ArtistItem>>(emptyList())
    /** The account's library songs - uploads and saved tracks - as YouTube lists them. */
    val librarySongs = MutableStateFlow<List<SongItem>>(emptyList())
    /** Playlists saved on the account. */
    val playlists = MutableStateFlow<List<PlaylistItem>>(emptyList())
    /** What the account played lately, for Home's Recent activity row. */
    val recentActivity = MutableStateFlow<List<YTItem>>(emptyList())

    /** What a sync brings in, and whether it overwrites - kept in step with Settings by Main. */
    @Volatile var kinds: Set<SyncKind> = SyncKind.entries.toSet()
    @Volatile var overwrite: Boolean = false

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        if (syncing.value) return@withContext
        syncing.value = true
        val want = kinds
        try {
            coroutineScope {
                val liked = async {
                    if (SyncKind.LikedSongs in want) YouTube.playlist("LM").completed().getOrNull()?.songs else null
                }
                val savedAlbums = async {
                    if (SyncKind.Albums !in want) return@async null
                    YouTube.library("FEmusic_liked_albums").completed().getOrNull()?.items?.filterIsInstance<AlbumItem>()
                }
                val savedArtists = async {
                    if (SyncKind.Artists !in want) return@async null
                    YouTube.library("FEmusic_library_corpus_track_artists").completed().getOrNull()
                        ?.items?.filterIsInstance<ArtistItem>()
                }
                val savedSongs = async {
                    if (SyncKind.LibrarySongs !in want) return@async null
                    // The phone's two: liked videos and private uploads, as one list.
                    (YouTube.library("FEmusic_liked_videos").completed().getOrNull()?.items.orEmpty() +
                        YouTube.library("FEmusic_library_privately_owned_tracks").completed().getOrNull()?.items.orEmpty())
                        .filterIsInstance<SongItem>().distinctBy { it.id }
                }
                val savedPlaylists = async {
                    if (SyncKind.Playlists !in want) return@async null
                    YouTube.library("FEmusic_liked_playlists").completed().getOrNull()?.items?.filterIsInstance<PlaylistItem>()
                }
                val recent = async {
                    if (SyncKind.RecentActivity !in want) return@async null
                    // The phone drops the first item, which is the shelf's own header entry.
                    YouTube.libraryRecentActivity().getOrNull()?.items?.drop(1)
                }

                val songs = liked.await()
                // Remote order: index 0 is the most recently liked.
                val added = songs?.let { library.importLiked(it.map { song -> song.toStored() }) }
                // Overwrite: a like here that the account no longer has is taken away, as the
                // phone's "overwrite local content with remote content" does.
                var removed = 0
                if (songs != null && overwrite) {
                    val remote = songs.mapTo(HashSet()) { it.id }
                    library.liked.value.filter { it.id !in remote && !LocalMusic.isLocal(it.id) }.forEach {
                        library.database.setLiked(it, false, System.currentTimeMillis())
                        removed++
                    }
                    if (removed > 0) library.refreshAll()
                }
                savedAlbums.await()?.let { albums.value = it }
                savedArtists.await()?.let { artists.value = it }
                savedSongs.await()?.let { librarySongs.value = it }
                savedPlaylists.await()?.let { playlists.value = it }
                recent.await()?.let { recentActivity.value = it }
                // Switched off: nothing of that kind is shown, rather than whatever the last sync left.
                if (SyncKind.Albums !in want) albums.value = emptyList()
                if (SyncKind.Artists !in want) artists.value = emptyList()
                if (SyncKind.LibrarySongs !in want) librarySongs.value = emptyList()
                if (SyncKind.Playlists !in want) playlists.value = emptyList()
                if (SyncKind.RecentActivity !in want) recentActivity.value = emptyList()

                status.value = when {
                    SyncKind.LikedSongs !in want -> "Synced."
                    songs == null -> "Could not reach your YouTube library."
                    added == 0 && removed == 0 -> "Up to date - ${songs.size} liked songs."
                    else -> listOfNotNull(
                        added?.takeIf { it > 0 }?.let { "Added $it liked ${if (it == 1) "song" else "songs"}" },
                        removed.takeIf { it > 0 }?.let { "removed $it no longer liked on YouTube" },
                    ).joinToString(", ") + "."
                }
            }
        } finally {
            syncing.value = false
        }
    }

    /** Sends a like or unlike made here to the account, so YouTube's list follows this one. */
    suspend fun pushLike(songId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        YouTube.likeVideo(songId, liked)
    }

    /** Forgets the account's lists, on sign-out. Liked songs already brought in stay. */
    fun clear() {
        albums.value = emptyList()
        artists.value = emptyList()
        librarySongs.value = emptyList()
        playlists.value = emptyList()
        recentActivity.value = emptyList()
        status.value = null
    }
}

/**
 * Likes and unlikes on their way to YouTube Music, a minute late on purpose.
 *
 * Sent at once, an accidental unlike reached the account before it could be taken back, and its
 * list reordered. Now each change waits [DELAY_MS]; changed back inside that, nothing is sent at
 * all. Whatever is still waiting is sent when the window closes ([flushBlocking]).
 */
object LikePushes {
    const val DELAY_MS = 60_000L

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private class Pending(val job: kotlinx.coroutines.Job, val remoteLiked: Boolean)

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    fun schedule(songId: String, liked: Boolean) {
        val previous = pending.remove(songId)
        previous?.job?.cancel()
        val remote = previous?.remoteLiked ?: !liked
        if (remote == liked) return // back to what YouTube already has
        val job = scope.launch {
            kotlinx.coroutines.delay(DELAY_MS)
            pending.remove(songId)
            runCatching { YouTube.likeVideo(songId, liked) }
        }
        pending[songId] = Pending(job, remote)
    }

    /** Sends everything still waiting, for up to a few seconds - called as the app closes. */
    fun flushBlocking() {
        val waiting = pending.entries.toList()
        if (waiting.isEmpty()) return
        pending.clear()
        waiting.forEach { it.value.job.cancel() }
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                waiting.map { (id, p) -> async { runCatching { YouTube.likeVideo(id, !p.remoteLiked) } } }.forEach { it.await() }
            }
        }
    }
}
