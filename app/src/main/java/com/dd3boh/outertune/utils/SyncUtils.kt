/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * Sync engine architecture (operation queue, coalescing, retry/backoff, structured
 * per-category status) ported from the Metrolist project, which is itself GPL-3.0
 * and derives from OuterTune. See git history for contributors.
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.LastAlbumSyncKey
import com.dd3boh.outertune.constants.LastArtistSyncKey
import com.dd3boh.outertune.constants.LastFullSyncKey
import com.dd3boh.outertune.constants.LastLibSongSyncKey
import com.dd3boh.outertune.constants.LastLikeSongSyncKey
import com.dd3boh.outertune.constants.LastPlaylistSyncKey
import com.dd3boh.outertune.constants.LastRecentActivitySyncKey
import com.dd3boh.outertune.constants.SYNC_COOLDOWN
import com.dd3boh.outertune.constants.SYNC_DEBUG
import com.dd3boh.outertune.constants.SyncConflictResolution
import com.dd3boh.outertune.constants.SyncContent
import com.dd3boh.outertune.constants.YtmSyncConflictKey
import com.dd3boh.outertune.constants.YtmSyncContentKey
import com.dd3boh.outertune.constants.decodeSyncString
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.isAutoSyncEnabled
import com.dd3boh.outertune.extensions.isInternetConnected
import com.dd3boh.outertune.extensions.isUserLoggedIn
import com.dd3boh.outertune.extensions.toEnum
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.utils.completed
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A unit of sync work. Operations are pushed onto a channel and executed one at a
 * time, so two screens asking for the same sync can never race each other.
 */
sealed class SyncOperation {
    data object FullSync : SyncOperation()
    data object LikedSongs : SyncOperation()
    data object LibrarySongs : SyncOperation()
    data object Albums : SyncOperation()
    data object Artists : SyncOperation()
    data object Playlists : SyncOperation()
    data object RecentActivity : SyncOperation()
    data class SinglePlaylist(val browseId: String, val playlistId: String) : SyncOperation()
    data class LikeSong(val song: SongEntity) : SyncOperation()
}

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
    data object Completed : SyncStatus()
}

data class SyncState(
    val overallStatus: SyncStatus = SyncStatus.Idle,
    val likedSongs: SyncStatus = SyncStatus.Idle,
    val librarySongs: SyncStatus = SyncStatus.Idle,
    val albums: SyncStatus = SyncStatus.Idle,
    val artists: SyncStatus = SyncStatus.Idle,
    val playlists: SyncStatus = SyncStatus.Idle,
    val recentActivity: SyncStatus = SyncStatus.Idle,
    val currentOperation: String = "",
)

/**
 * Singleton class for syncing local data from remote YouTube Music.
 *
 * Work is funnelled through a single-consumer channel guarded by [syncExecutionMutex],
 * so only one sync body ever touches the database at a time. Duplicate requests for the
 * same category are coalesced while one is already queued, and network calls are retried
 * with exponential backoff.
 */
@Singleton
class SyncUtils @Inject constructor(
    val database: MusicDatabase,
    private val downloadUtil: DownloadUtil,
    @ApplicationContext private val context: Context
) {
    private val TAG = "SyncUtils"

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Sync coroutine exception", throwable)
        }
    }

    private val syncJob = SupervisorJob()
    private val scope = CoroutineScope(syncCoroutine + syncJob + exceptionHandler)

    private val syncChannel = Channel<SyncOperation>(Channel.BUFFERED)
    private var processingJob: Job? = null

    /** Only one sync body runs at a time. NOTE: [Mutex] is not reentrant. */
    private val syncExecutionMutex = Mutex()
    private val queuedOperationKeys = ConcurrentHashMap.newKeySet<String>()

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // Legacy boolean flows, derived from syncState so existing UI keeps working unchanged.
    val isSyncingRemoteLikedSongs: StateFlow<Boolean> = derive { it.likedSongs }
    val isSyncingRemoteSongs: StateFlow<Boolean> = derive { it.librarySongs }
    val isSyncingRemoteAlbums: StateFlow<Boolean> = derive { it.albums }
    val isSyncingRemoteArtists: StateFlow<Boolean> = derive { it.artists }
    val isSyncingRemotePlaylists: StateFlow<Boolean> = derive { it.playlists }
    val isSyncingRecentActivity: StateFlow<Boolean> = derive { it.recentActivity }

    companion object {
        const val DEFAULT_SYNC_CONTENT = "ARPLSC"

        /** Coalescing key for a whole-library sync. */
        private const val FULL_SYNC_KEY = "full"

        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L

        /** Small pause between database writes so a large sync doesn't starve the UI. */
        private const val DB_OPERATION_DELAY_MS = 20L
    }

    init {
        startProcessingQueue()
    }

    private fun derive(selector: (SyncState) -> SyncStatus): StateFlow<Boolean> =
        _syncState.map { selector(it) is SyncStatus.Syncing }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private fun updateState(update: SyncState.() -> SyncState) {
        _syncState.update { it.update() }
    }

    // region queue plumbing

    private fun startProcessingQueue() {
        processingJob = scope.launch {
            for (operation in syncChannel) {
                try {
                    if (operation.isCoveredByFullSync() && FULL_SYNC_KEY in queuedOperationKeys) {
                        if (SYNC_DEBUG) Log.d(TAG, "Skipping $operation, a full sync is already queued")
                    } else {
                        syncExecutionMutex.withLock { processOperation(operation) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing sync operation: $operation", e)
                } finally {
                    operation.coalescingKey()?.let(queuedOperationKeys::remove)
                }
            }
        }
    }

    private fun enqueue(operation: SyncOperation) {
        val key = operation.coalescingKey()
        if (key != null && !queuedOperationKeys.add(key)) {
            if (SYNC_DEBUG) Log.d(TAG, "Skipping duplicate sync operation: $operation")
            return
        }
        scope.launch {
            try {
                syncChannel.send(operation)
            } catch (e: Exception) {
                key?.let(queuedOperationKeys::remove)
                Log.e(TAG, "Failed to enqueue $operation", e)
            }
        }
    }

    private fun SyncOperation.coalescingKey(): String? = when (this) {
        SyncOperation.FullSync -> FULL_SYNC_KEY
        SyncOperation.LikedSongs -> "likedSongs"
        SyncOperation.LibrarySongs -> "librarySongs"
        SyncOperation.Albums -> "albums"
        SyncOperation.Artists -> "artists"
        SyncOperation.Playlists -> "playlists"
        SyncOperation.RecentActivity -> "recentActivity"
        is SyncOperation.SinglePlaylist -> "playlist:$browseId"
        is SyncOperation.LikeSong -> null // never coalesce user-initiated writes
    }

    private fun SyncOperation.isCoveredByFullSync(): Boolean = when (this) {
        SyncOperation.LikedSongs,
        SyncOperation.LibrarySongs,
        SyncOperation.Albums,
        SyncOperation.Artists,
        SyncOperation.Playlists,
        -> true

        else -> false
    }

    private suspend fun processOperation(operation: SyncOperation) {
        when (operation) {
            is SyncOperation.FullSync -> executeFullSync()
            is SyncOperation.LikedSongs -> executeSyncRemoteLikedSongs()
            is SyncOperation.LibrarySongs -> executeSyncRemoteSongs()
            is SyncOperation.Albums -> executeSyncRemoteAlbums()
            is SyncOperation.Artists -> executeSyncRemoteArtists()
            is SyncOperation.Playlists -> executeSyncRemotePlaylists()
            is SyncOperation.RecentActivity -> executeSyncRecentActivity()
            is SyncOperation.SinglePlaylist -> executeSyncPlaylist(operation.browseId, operation.playlistId)
            is SyncOperation.LikeSong -> executeLikeSong(operation.song)
        }
    }

    // endregion

    // region gating

    private fun checkEnabled(item: SyncContent): Boolean =
        decodeSyncString(context.dataStore.get(YtmSyncContentKey, DEFAULT_SYNC_CONTENT)).contains(item)

    private fun checkOverwrite(item: SyncConflictResolution): Boolean =
        context.dataStore.get(YtmSyncConflictKey, SyncConflictResolution.ADD_ONLY.name)
            .toEnum(defaultValue = SyncConflictResolution.ADD_ONLY) == item

    /**
     * Returns true when enough time has passed since the last sync of this category.
     *
     * The previous implementation compared a millisecond constant against a delta measured
     * in seconds *and* inverted the comparison, which made the cooldown reject syncs that
     * were due and allow ones that weren't.
     */
    private fun cooldownElapsed(key: Preferences.Key<Long>): Boolean {
        val lastSync = context.dataStore.get(key, 0L)
        if (lastSync <= 0L) return true
        val elapsed = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - lastSync
        if (elapsed < SYNC_COOLDOWN) {
            if (SYNC_DEBUG) Log.d(TAG, "Cooldown active for $key, ${SYNC_COOLDOWN - elapsed}s remaining")
            return false
        }
        return true
    }

    private suspend fun markSynced(key: Preferences.Key<Long>) {
        context.dataStore.edit { settings ->
            settings[key] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        }
    }

    /**
     * Common preconditions for every remote sync: signed in, online, and the category
     * enabled in settings. When [bypass] is false the auto-sync toggle and the per-category
     * cooldown are honoured too.
     */
    private fun canSync(category: SyncContent, lastSyncKey: Preferences.Key<Long>, bypass: Boolean): Boolean {
        if (!context.isUserLoggedIn()) {
            if (SYNC_DEBUG) Log.d(TAG, "Skipping $category sync, not signed in")
            return false
        }
        if (!context.isInternetConnected()) {
            if (SYNC_DEBUG) Log.d(TAG, "Skipping $category sync, no internet")
            return false
        }
        if (!checkEnabled(category)) {
            if (SYNC_DEBUG) Log.d(TAG, "Skipping $category sync, category disabled")
            return false
        }
        if (!bypass && (!context.isAutoSyncEnabled() || !cooldownElapsed(lastSyncKey))) {
            return false
        }
        return true
    }

    private suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelay: Long = INITIAL_RETRY_DELAY_MS,
        block: suspend () -> T,
    ): Result<T> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                if (attempt == maxRetries - 1) return Result.failure(e)
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return Result.failure(IllegalStateException("Max retries exceeded"))
    }

    // endregion

    // region public API — fire and forget

    fun performFullSync() = enqueue(SyncOperation.FullSync)

    fun queueSyncLikedSongs() = enqueue(SyncOperation.LikedSongs)

    fun queueSyncLibrarySongs() = enqueue(SyncOperation.LibrarySongs)

    fun queueSyncAlbums() = enqueue(SyncOperation.Albums)

    fun queueSyncArtists() = enqueue(SyncOperation.Artists)

    fun queueSyncPlaylists() = enqueue(SyncOperation.Playlists)

    fun queueSyncRecentActivity() = enqueue(SyncOperation.RecentActivity)

    fun queueSyncPlaylist(browseId: String, playlistId: String) =
        enqueue(SyncOperation.SinglePlaylist(browseId, playlistId))

    /**
     * Like/unlike a single song upstream. Queued so rapid taps are executed in order.
     */
    fun likeSong(s: SongEntity) = enqueue(SyncOperation.LikeSong(s))

    /**
     * Add/remove to library single song
     */
    fun changeInLibrary(s: SongEntity) {
        // we don't have an api call yet
    }

    // endregion

    // region public API — suspending (source compatible with the previous implementation)

    suspend fun tryAutoSync(bypassCd: Boolean = false) {
        if (!context.isAutoSyncEnabled()) return
        if (!bypassCd && !cooldownElapsed(LastFullSyncKey)) return
        syncExecutionMutex.withLock { executeFullSync(bypass = bypassCd) }
    }

    suspend fun syncRemoteLikedSongs(bypass: Boolean = false) =
        syncExecutionMutex.withLock { executeSyncRemoteLikedSongs(bypass) }

    suspend fun syncRemoteSongs(bypass: Boolean = false) =
        syncExecutionMutex.withLock { executeSyncRemoteSongs(bypass) }

    suspend fun syncRemoteAlbums(bypass: Boolean = false) =
        syncExecutionMutex.withLock { executeSyncRemoteAlbums(bypass) }

    suspend fun syncRemoteArtists(bypass: Boolean = false) =
        syncExecutionMutex.withLock { executeSyncRemoteArtists(bypass) }

    suspend fun syncRemotePlaylists(bypass: Boolean = false) =
        syncExecutionMutex.withLock { executeSyncRemotePlaylists(bypass) }

    suspend fun syncRecentActivity(bypass: Boolean = false) =
        syncExecutionMutex.withLock { executeSyncRecentActivity(bypass) }

    suspend fun syncPlaylist(browseId: String, playlistId: String) =
        syncExecutionMutex.withLock { executeSyncPlaylist(browseId, playlistId) }

    // endregion

    // region execution — these must never take syncExecutionMutex themselves

    private suspend fun executeFullSync(bypass: Boolean = false) {
        updateState { copy(overallStatus = SyncStatus.Syncing, currentOperation = "Starting sync") }
        try {
            executeSyncRemoteLikedSongs(bypass)
            executeSyncRemoteSongs(bypass)
            executeSyncRemoteAlbums(bypass)
            executeSyncRemoteArtists(bypass)
            executeSyncRemotePlaylists(bypass)
            markSynced(LastFullSyncKey)
            updateState { copy(overallStatus = SyncStatus.Completed, currentOperation = "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during full sync", e)
            updateState {
                copy(overallStatus = SyncStatus.Error(e.message ?: "Unknown error"), currentOperation = "")
            }
        }
    }

    private suspend fun executeLikeSong(s: SongEntity) = withContext(Dispatchers.IO) {
        if (!context.isUserLoggedIn()) return@withContext
        withRetry { YouTube.likeVideo(s.id, s.liked) }
            .onFailure { Log.e(TAG, "Failed to like song upstream: ${s.id}", it) }
        Unit
    }

    /**
     * Mirror the remote "Liked songs" (LM) playlist into the local database.
     *
     * Ordering: YouTube returns LM newest-like-first, and it is the only ordering
     * signal available — the API exposes no per-song like timestamp. We therefore derive
     * a synthetic [SongEntity.likedDate] from each song's position in the remote list, so
     * sorting the local Liked playlist by liked date reproduces YouTube's order exactly.
     *
     * Songs that are already liked locally get re-stamped as well. Skipping them (as the
     * previous implementation did) meant a song's local liked date stayed frozen at
     * whenever it happened to be first seen, so the local order drifted permanently out
     * of step with YouTube.
     */
    private suspend fun executeSyncRemoteLikedSongs(bypass: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canSync(SyncContent.LIKED_SONGS, LastLikeSongSyncKey, bypass)) return@withContext
        updateState { copy(likedSongs = SyncStatus.Syncing, currentOperation = "Syncing liked songs") }

        try {
            if (SYNC_DEBUG) Log.d(TAG, "Liked songs synchronization started")

            val page = withRetry { YouTube.playlist("LM").completed().getOrThrow() }
                .onFailure {
                    Log.e(TAG, "Failed to fetch liked songs", it)
                    updateState { copy(likedSongs = SyncStatus.Error(it.message ?: "Fetch failed")) }
                }
                .getOrNull() ?: return@withContext

            if (!context.isInternetConnected()) return@withContext

            // Remote order, NOT reversed: index 0 is the most recently liked song.
            val remoteSongs = page.songs
            val remoteIds = remoteSongs.mapTo(HashSet()) { it.id }

            // Unlike anything that is no longer liked remotely. Local files are never
            // represented upstream, so they are left alone.
            database.likedSongsByNameAsc().first()
                .filterNot { it.song.isLocal }
                .filterNot { it.id in remoteIds }
                .forEach { song ->
                    try {
                        database.update(song.song.localToggleLike())
                        delay(DB_OPERATION_DELAY_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unlike song ${song.id}", e)
                    }
                }

            // Insert or re-stamp, deriving likedDate from the remote position.
            val now = LocalDateTime.now()
            remoteSongs.forEachIndexed { index, remoteSong ->
                try {
                    val likedDate = now.minusSeconds(index.toLong())
                    val localSong = database.song(remoteSong.id).firstOrNull()?.song
                    database.transaction {
                        if (localSong == null) {
                            insert(remoteSong.toMediaMetadata()) {
                                it.copy(liked = true, likedDate = likedDate)
                            }
                        } else if (!localSong.liked || localSong.likedDate != likedDate) {
                            update(localSong.copy(liked = true, likedDate = likedDate))
                        }
                    }
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process liked song ${remoteSong.id}", e)
                }
            }

            markSynced(LastLikeSongSyncKey)
            updateState { copy(likedSongs = SyncStatus.Completed) }
            if (SYNC_DEBUG) Log.d(TAG, "Synced ${remoteSongs.size} liked songs")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing liked songs", e)
            updateState { copy(likedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
        } finally {
            if (SYNC_DEBUG) Log.i(TAG, "Liked songs synchronization ended")
        }
    }

    private suspend fun executeSyncRemoteSongs(bypass: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canSync(SyncContent.PRIVATE_SONGS, LastLibSongSyncKey, bypass)) return@withContext
        updateState { copy(librarySongs = SyncStatus.Syncing, currentOperation = "Syncing library songs") }

        try {
            if (SYNC_DEBUG) Log.i(TAG, "Library songs synchronization started")

            val remoteSongs =
                getRemoteData<SongItem>("FEmusic_liked_videos", "FEmusic_library_privately_owned_tracks")
            if (!context.isInternetConnected()) return@withContext
            val remoteIds = remoteSongs.mapTo(HashSet()) { it.id }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                database.songsByNameAsc().first()
                    .filterNot { it.song.isLocal }
                    .filterNot { it.id in remoteIds }
                    .forEach { song ->
                        try {
                            database.update(song.song.toggleLibrary())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to remove song from library ${song.id}", e)
                        }
                    }
            }

            remoteSongs.forEach { song ->
                try {
                    val dbSong = database.song(song.id).firstOrNull()?.song
                    database.transaction {
                        if (dbSong == null) {
                            insert(song.toMediaMetadata(), SongEntity::toggleLibrary)
                        } else if (dbSong.inLibrary == null) {
                            update(dbSong.toggleLibrary())
                        }
                    }
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process library song ${song.id}", e)
                }
            }

            markSynced(LastLibSongSyncKey)
            updateState { copy(librarySongs = SyncStatus.Completed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing library songs", e)
            updateState { copy(librarySongs = SyncStatus.Error(e.message ?: "Unknown error")) }
        } finally {
            if (SYNC_DEBUG) Log.i(TAG, "Library songs synchronization ended")
        }
    }

    private suspend fun executeSyncRemoteAlbums(bypass: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canSync(SyncContent.ALBUMS, LastAlbumSyncKey, bypass)) return@withContext
        updateState { copy(albums = SyncStatus.Syncing, currentOperation = "Syncing albums") }

        try {
            if (SYNC_DEBUG) Log.i(TAG, "Library albums synchronization started")

            val remoteAlbums =
                getRemoteData<AlbumItem>("FEmusic_liked_albums", "FEmusic_library_privately_owned_releases")
            if (!context.isInternetConnected()) return@withContext
            val remoteIds = remoteAlbums.mapTo(HashSet()) { it.id }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                database.albumsLikedAsc().first()
                    .filterNot { it.album.isLocal }
                    .filterNot { it.id in remoteIds }
                    .forEach { album ->
                        try {
                            database.update(album.album.localToggleLike())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to unlike album ${album.id}", e)
                        }
                    }
            }

            remoteAlbums.forEach { remoteAlbum ->
                try {
                    val localAlbum = database.album(remoteAlbum.id).firstOrNull()
                    if (localAlbum == null) {
                        database.insert(remoteAlbum)
                        database.album(remoteAlbum.id).firstOrNull()?.let {
                            database.update(it.album.localToggleLike())
                        }
                    } else if (localAlbum.album.bookmarkedAt == null) {
                        database.update(localAlbum.album.localToggleLike())
                    }
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process album ${remoteAlbum.id}", e)
                }
            }

            markSynced(LastAlbumSyncKey)
            updateState { copy(albums = SyncStatus.Completed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing albums", e)
            updateState { copy(albums = SyncStatus.Error(e.message ?: "Unknown error")) }
        } finally {
            if (SYNC_DEBUG) Log.i(TAG, "Library albums synchronization ended")
        }
    }

    private suspend fun executeSyncRemoteArtists(bypass: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canSync(SyncContent.ARTISTS, LastArtistSyncKey, bypass)) return@withContext
        updateState { copy(artists = SyncStatus.Syncing, currentOperation = "Syncing artists") }

        try {
            if (SYNC_DEBUG) Log.i(TAG, "Artist subscriptions synchronization started")

            val likedArtists = getRemoteData<ArtistItem>(
                "FEmusic_library_corpus_artists",
                "FEmusic_library_privately_owned_artists"
            )
            val trackArtists = getRemoteData<ArtistItem>(
                "FEmusic_library_corpus_track_artists",
                "FEmusic_library_privately_owned_artists"
            )
            if (!context.isInternetConnected()) return@withContext

            val likedArtistIds = likedArtists.mapTo(HashSet()) { it.id }
            val remoteArtists = likedArtists + trackArtists.filterNot { it.id in likedArtistIds }

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                database.artistsBookmarkedAsc().first()
                    .filterNot { it.artist.isLocal }
                    .filterNot { it.id in likedArtistIds }
                    .forEach { artist ->
                        try {
                            database.update(artist.artist.localToggleLike())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to unsubscribe artist ${artist.id}", e)
                        }
                    }
            }

            remoteArtists.forEach { remoteArtist ->
                try {
                    val localArtist = database.artist(remoteArtist.id).firstOrNull()
                    val isLikedArtist = remoteArtist.id in likedArtistIds
                    database.transaction {
                        if (localArtist == null) {
                            insert(
                                ArtistEntity(
                                    id = remoteArtist.id,
                                    name = remoteArtist.title,
                                    thumbnailUrl = remoteArtist.thumbnail,
                                    channelId = remoteArtist.channelId,
                                    bookmarkedAt = if (isLikedArtist) LocalDateTime.now() else null
                                )
                            )
                        } else if (localArtist.artist.bookmarkedAt == null && isLikedArtist) {
                            update(localArtist.artist.localToggleLike())
                        }
                    }
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process artist ${remoteArtist.id}", e)
                }
            }

            markSynced(LastArtistSyncKey)
            updateState { copy(artists = SyncStatus.Completed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing artists", e)
            updateState { copy(artists = SyncStatus.Error(e.message ?: "Unknown error")) }
        } finally {
            if (SYNC_DEBUG) Log.i(TAG, "Artist subscriptions synchronization ended")
        }
    }

    private suspend fun executeSyncRemotePlaylists(bypass: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canSync(SyncContent.PLAYLISTS, LastPlaylistSyncKey, bypass)) return@withContext
        updateState { copy(playlists = SyncStatus.Syncing, currentOperation = "Syncing playlists") }

        try {
            if (SYNC_DEBUG) Log.i(TAG, "Library playlist synchronization started")

            val page = withRetry { YouTube.library("FEmusic_liked_playlists").completed().getOrThrow() }
                .onFailure {
                    Log.e(TAG, "Failed to fetch playlists", it)
                    updateState { copy(playlists = SyncStatus.Error(it.message ?: "Fetch failed")) }
                }
                .getOrNull() ?: return@withContext

            if (!context.isInternetConnected()) return@withContext

            val remotePlaylists = page.items.filterIsInstance<PlaylistItem>()
                .filterNot { it.id == "LM" || it.id == "SE" }
                .reversed()
                .distinctBy { it.id }
            val remoteIds = remotePlaylists.mapTo(HashSet()) { it.id }

            val localPlaylists = database.playlistInLibraryAsc().first()

            if (checkOverwrite(SyncConflictResolution.OVERWRITE_WITH_REMOTE)) {
                localPlaylists.filterNot { it.playlist.isLocal }.forEach { playlist ->
                    val browseId = playlist.playlist.browseId ?: return@forEach
                    if (browseId in remoteIds) return@forEach
                    try {
                        database.update(playlist.playlist.localToggleLike())
                        delay(DB_OPERATION_DELAY_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unbookmark playlist ${playlist.id}", e)
                    }
                }
            }

            // Sequential on purpose: the previous implementation fanned these out with
            // runBlocking + launch, which hammered the API and interleaved playlist writes.
            remotePlaylists.forEach { remotePlaylist ->
                try {
                    // forcefully assign isEditable. These playlists are at mercy of YouTube
                    var localPlaylist =
                        localPlaylists.find { remotePlaylist.id == it.playlist.browseId }?.playlist
                            ?.copy(isEditable = remotePlaylist.isEditable)
                    if (localPlaylist == null) {
                        localPlaylist = PlaylistEntity(
                            name = remotePlaylist.title,
                            browseId = remotePlaylist.id,
                            isEditable = remotePlaylist.isEditable,
                            bookmarkedAt = LocalDateTime.now(),
                            thumbnailUrl = remotePlaylist.thumbnail,
                            remoteSongCount = remotePlaylist.songCountText?.let {
                                Regex("""\d+""").find(it)?.value?.toIntOrNull()
                            },
                            playEndpointParams = remotePlaylist.playEndpoint?.params,
                            shuffleEndpointParams = remotePlaylist.shuffleEndpoint?.params,
                            radioEndpointParams = remotePlaylist.radioEndpoint?.params
                        )
                        database.insert(localPlaylist)
                    } else {
                        database.update(localPlaylist, remotePlaylist)
                    }

                    val updatedPlaylist = database.playlistByBrowseId(remotePlaylist.id).firstOrNull()
                    updatedPlaylist?.let {
                        val playlistSongMaps = database.songMapsToPlaylist(it.id)
                        if (it.playlist.isEditable || playlistSongMaps.isNotEmpty()) {
                            executeSyncPlaylist(remotePlaylist.id, it.id)
                        }
                    }
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync playlist ${remotePlaylist.title}", e)
                }
            }

            markSynced(LastPlaylistSyncKey)
            updateState { copy(playlists = SyncStatus.Completed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing playlists", e)
            updateState { copy(playlists = SyncStatus.Error(e.message ?: "Unknown error")) }
        } finally {
            if (SYNC_DEBUG) Log.i(TAG, "Library playlist synchronization ended")
        }
    }

    private suspend fun executeSyncPlaylist(browseId: String, playlistId: String) = withContext(Dispatchers.IO) {
        if (!context.isInternetConnected()) return@withContext

        val playlistPage = withRetry { YouTube.playlist(browseId).completed().getOrThrow() }
            .onFailure { Log.e(TAG, "Failed to fetch playlist $browseId", it) }
            .getOrNull() ?: return@withContext

        if (!context.isInternetConnected()) return@withContext

        // Never wipe a local playlist because a page came back empty.
        if (playlistPage.songs.isEmpty()) {
            if (SYNC_DEBUG) Log.w(TAG, "Remote playlist $browseId is empty, skipping")
            return@withContext
        }

        try {
            database.transaction {
                clearPlaylist(playlistId)
                val songEntities = playlistPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach { insert(it) }

                songEntities.mapIndexed { position, song ->
                    PlaylistSongMap(
                        songId = song.id,
                        playlistId = playlistId,
                        position = position,
                        setVideoId = song.setVideoId
                    )
                }.forEach { insert(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write playlist $playlistId", e)
        }
        Unit
    }

    private suspend fun executeSyncRecentActivity(bypass: Boolean = false) = withContext(Dispatchers.IO) {
        if (!canSync(SyncContent.RECENT_ACTIVITY, LastRecentActivitySyncKey, bypass)) return@withContext
        updateState { copy(recentActivity = SyncStatus.Syncing, currentOperation = "Syncing recent activity") }

        try {
            if (SYNC_DEBUG) Log.i(TAG, "Recent activity synchronization started")

            val page = withRetry { YouTube.libraryRecentActivity().getOrThrow() }
                .onFailure {
                    Log.e(TAG, "Failed to fetch recent activity", it)
                    updateState { copy(recentActivity = SyncStatus.Error(it.message ?: "Fetch failed")) }
                }
                .getOrNull() ?: return@withContext

            val recentActivity = page.items.drop(1)
            database.clearRecentActivity()
            recentActivity.reversed().forEach { database.insertRecentActivityItem(it) }

            markSynced(LastRecentActivitySyncKey)
            updateState { copy(recentActivity = SyncStatus.Completed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error processing recent activity", e)
            updateState { copy(recentActivity = SyncStatus.Error(e.message ?: "Unknown error")) }
        } finally {
            if (SYNC_DEBUG) Log.i(TAG, "Recent activity synchronization ended")
        }
    }

    // endregion

    private suspend inline fun <reified T> getRemoteData(libraryId: String, uploadsId: String): List<T> =
        coroutineScope {
            listOf(libraryId to 0, uploadsId to 1)
                .map { (browseId, tab) ->
                    async {
                        YouTube.library(browseId, tab).completed().getOrNull()
                            ?.items?.filterIsInstance<T>()?.reversed().orEmpty()
                    }
                }
                .awaitAll()
                .flatten()
        }
}
