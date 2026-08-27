package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DOWNLOAD_DEBUG
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadOnWifiOnlyKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.DownloadThumbnailsKey
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.ArtistCreditEnricher
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.InvalidAudioFileException
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    val TAG = DownloadUtil::class.simpleName.toString()

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    /** Same shape as MusicService's - see that one's doc for why the headers have to travel
     *  with the URL. */
    private data class CachedStreamUrl(val url: String, val expiresAt: Long, val headers: Map<String, String>)

    private val songUrlCache = HashMap<String, CachedStreamUrl>()
    private val thumbnailHttpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    /** Where downloadThumbnail/removeThumbnail keep full-res thumbnails - app-managed storage,
     *  independent of the user-chosen audio download directory. */
    private val thumbnailDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        // Same backstop as MusicService's own data source: forces a reconnect
                        // well before googlevideo's own ~60s connection-duration cutoff, in case
                        // the subrange bound below somehow doesn't trigger a re-resolve in time.
                        .callTimeout(45, TimeUnit.SECONDS)
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        val length = if (dataSpec.length >= 0) dataSpec.length else 1
        if (playerCache.isCached(mediaId, dataSpec.position, length)) {
            return@Factory dataSpec
        }

        songUrlCache[mediaId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
            // Bounded the same as the fresh-resolve path below - left unbounded, a download
            // (unlike playback's own buffered reads) turns into exactly one long-lived
            // connection past googlevideo's cutoff. See MusicService.CHUNK_LENGTH's own doc.
            return@Factory dataSpec.withUri(it.url.toUri())
                .withRequestHeaders(it.headers)
                .subrange(dataSpec.uriPositionOffset, MusicService.CHUNK_LENGTH)
        }

        // A cache entry existing (even an expired one) means this song was already resolved once
        // before - the aging streaming PoToken embedded in that URL is exactly what's being worked
        // around here, so force a fresh mint rather than risk PoTokenGenerator's own session-level
        // cache handing back the same one. Mirrors MusicService's own isRefresh handling.
        val isRefresh = songUrlCache.containsKey(mediaId)

        val playbackData = runBlocking(Dispatchers.IO) {
            if (isRefresh) {
                YTPlayerUtils.invalidatePoTokenSession()
            }
            YTPlayerUtils.playerResponseForPlayback(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            )
        }.getOrThrow()
        val format = playbackData.format

        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    // YouTube omits Content-Length for some formats/streams; !! here crashed the
                    // download's data source resolution outright for exactly those.
                    contentLength = format.contentLength ?: 10000000,
                    loudnessDb = playbackData.audioConfig?.loudnessDb,
                    playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
        }

        // No "&range=0-contentLength" query param here (as this used to have): that tells
        // googlevideo's CDN up front to serve the entire file as one continuous transfer, which
        // defeats the subrange-based chunking below and is exactly what was driving downloads
        // into the ~60s connection-duration/PoToken cutoff. MusicService never did this either -
        // HTTP Range headers via subrange() are the only chunking mechanism now, matching it.
        val streamUrl = playbackData.streamUrl

        songUrlCache[mediaId] = CachedStreamUrl(
            url = streamUrl,
            // streamExpiresInSeconds is YouTube's own claimed validity (routinely hours), but the
            // embedded streaming PoToken has been observed to actually stop working after roughly
            // a minute regardless of connection count or chunk size - see
            // MusicService.STREAM_URL_TRUST_WINDOW_MS's own doc. Capping our own trust the same
            // way forces the proactive refresh above before the CDN ever gets a chance to reject
            // the old one.
            expiresAt = System.currentTimeMillis() + minOf(playbackData.streamExpiresInSeconds * 1000L, MusicService.STREAM_URL_TRUST_WINDOW_MS),
            headers = playbackData.streamHeaders,
        )
        dataSpec.withUri(streamUrl.toUri())
            .withRequestHeaders(playbackData.streamHeaders)
            .subrange(dataSpec.uriPositionOffset, MusicService.CHUNK_LENGTH)
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = 3
            requirements = downloadRequirements(context.dataStore.get(DownloadOnWifiOnlyKey, true))
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
                )
            )
        }
    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
    )
    val downloadMgr = DownloadManagerOt(localMgr)
    var isProcessingDownloads = MutableStateFlow(false)

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }

    fun download(songs: List<MediaMetadata>) {
        if (songs.any { downloads.value[it.id] == null }) notifyIfWaitingForWifi()
        songs.forEach { song ->
            downloadSong(song.id, song.title)
            maybeDownloadThumbnail(song.id, song.thumbnailUrl)
        }
    }

    fun download(song: MediaMetadata) {
        if (downloads.value[song.id] == null) notifyIfWaitingForWifi()
        downloadSong(song.id, song.title)
        maybeDownloadThumbnail(song.id, song.thumbnailUrl)
    }

    fun download(song: SongEntity) {
        if (downloads.value[song.id] == null) notifyIfWaitingForWifi()
        downloadSong(song.id, song.title)
        maybeDownloadThumbnail(song.id, song.thumbnailUrl)
    }

    private fun maybeDownloadThumbnail(songId: String, thumbnailUrl: String?) {
        if (thumbnailUrl == null || !context.dataStore.get(DownloadThumbnailsKey, false)) return
        CoroutineScope(dlCoroutine).launch { downloadThumbnail(songId, thumbnailUrl) }
    }

    /**
     * Downloads [songId]'s full-res thumbnail into app-managed storage, independent of its audio
     * download (see SongEntity.thumbnailPath's own doc) - called automatically from download()
     * when [DownloadThumbnailsKey] is on, or directly as a per-song "download thumbnail" action.
     * Overwrites any thumbnail already downloaded for this song, so re-running it also serves as
     * a refresh. [thumbnailUrl] is looked up from the database when not already known to the
     * caller.
     */
    suspend fun downloadThumbnail(songId: String, thumbnailUrl: String? = null) {
        val url = (thumbnailUrl ?: database.song(songId).first()?.song?.thumbnailUrl)
            ?.resize(1080, 1080) ?: return
        val file = File(thumbnailDir, "$songId.jpg")
        try {
            val request = okhttp3.Request.Builder().url(url).build()
            withContext(Dispatchers.IO) {
                thumbnailHttpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        Log.w(TAG, "Thumbnail download failed for $songId: HTTP ${response.code}")
                        return@withContext
                    }
                    file.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
            }
            if (!file.exists()) return
            database.song(songId).first()?.song?.copy(thumbnailPath = file.absolutePath)
                ?.let { database.update(it) }
        } catch (e: IOException) {
            reportException(e)
            file.delete()
        }
    }

    /** Removes a downloaded full-res thumbnail without touching the song's own audio download. */
    suspend fun removeThumbnail(songId: String) {
        File(thumbnailDir, "$songId.jpg").delete()
        database.song(songId).first()?.song?.takeIf { it.thumbnailPath != null }
            ?.copy(thumbnailPath = null)?.let { database.update(it) }
    }

    /** true while [downloadAllThumbnails] is running, so the settings entry that triggers it can
     *  show progress instead of allowing another run to stack on top of it. */
    val isDownloadingAllThumbnails = MutableStateFlow(false)

    /** Downloads a full-res thumbnail (see [downloadThumbnail]) for every currently downloaded
     *  song that doesn't already have one - the bulk counterpart to the per-song action, for
     *  songs that were downloaded before thumbnail downloading existed or was turned on. */
    suspend fun downloadAllThumbnails() {
        if (isDownloadingAllThumbnails.value) return
        isDownloadingAllThumbnails.value = true
        try {
            database.downloadedSongs().first()
                .filter { it.song.thumbnailPath == null }
                .forEach { downloadThumbnail(it.id, it.song.thumbnailUrl) }
        } finally {
            isDownloadingAllThumbnails.value = false
        }
    }

    /**
     * Update the network requirement for downloads. Takes effect immediately, including for
     * downloads that are already queued or in progress.
     */
    fun setDownloadRequirements(wifiOnly: Boolean) {
        DownloadService.sendSetRequirements(
            context,
            ExoDownloadService::class.java,
            downloadRequirements(wifiOnly),
            false
        )
    }

    /**
     * Show a hint when a download is requested on a metered network while Wi-Fi only is enabled,
     * since the download is queued silently and only starts once Wi-Fi is available.
     */
    private fun notifyIfWaitingForWifi() {
        if (context.dataStore.get(DownloadOnWifiOnlyKey, true) && connectivityManager.isActiveNetworkMetered) {
            Toast.makeText(context, R.string.download_waiting_for_wifi, LENGTH_SHORT).show()
        }
    }

    private fun downloadSong(id: String, title: String) {
        if (downloads.value[id] != null) return
        val downloadRequest = DownloadRequest.Builder(id, id.toUri())
            .setCustomCacheKey(id)
            .setData(title.toByteArray())
            .build()
        DownloadService.sendAddDownload(
            context,
            ExoDownloadService::class.java,
            downloadRequest,
            false
        )
    }

    fun resumeDownloadsOnStart() {
        DownloadService.sendResumeDownloads(
            context,
            ExoDownloadService::class.java,
            false
        )
    }


// Deletes from custom dl

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)

    fun delete(song: SongItem) = deleteSong(song.id)

    fun delete(song: Song) = deleteSong(song.song.id)

    fun delete(song: SongEntity) = deleteSong(song.id)

    fun delete(song: MediaMetadata) = deleteSong(song.id)

    private fun deleteSong(id: String): Boolean {
        val deleted = localMgr.deleteFile(id)
        if (!deleted) return false
        downloads.update { map ->
            map.toMutableMap().apply {
                remove(id)
            }
        }

        runBlocking {
            database.song(id).first()?.song?.copy(localPath = null)?.let { database.update(it) }
            database.updateDownloadStatus(id, null)
        }
        return true
    }

    /**
     * Retrieve song from cache, and delete it from cache afterwards
     */
    fun getFromCache(cache: SimpleCache, mediaId: String): ByteArray? {
        val spans: Set<CacheSpan> = cache.getCachedSpans(mediaId)
        if (spans.isEmpty()) return null

        val output = ByteArrayOutputStream()
        try {
            for (span in spans) {
                val file: File? = span.file
                FileInputStream(file).use { fis ->
                    fis.copyTo(output)
                }
            }
            return output.toByteArray()
        } catch (e: IOException) {
            reportException(e)
        } finally {
            output.close()
        }
        return null
    }

    /**
     * Migrated existing downloads from the download cache to the new system in external storage
     */
    suspend fun migrateDownloads() {
        if (isProcessingDownloads.value) return
        isProcessingDownloads.value = true

        var runs = 0
        try {
            // "skeleton" of old download manager to access old download data
            val dataSourceFactory = ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            OkHttpClient.Builder()
                                .proxy(YouTube.proxy)
                                .build()
                        )
                    )
            ) { dataSpec ->
                return@Factory dataSpec
            }

            val downloadManager: DownloadManager = DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                Executor(Runnable::run)
            ).apply {
                maxParallelDownloads = 3
            }

            // actual migration code
            val downloadedSongs = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                downloadedSongs[cursor.download.request.id] = cursor.download
            }

            // copy all completed downloads
            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            toMigrate.forEach { s ->
                if (runs++ % 10 == 0) {
                    if (DOWNLOAD_DEBUG) Log.d(TAG, "Migrating download: $runs/${toMigrate.size}")
                    if (runs % 20 == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "$runs/${toMigrate.size}", LENGTH_SHORT).show()
                        }
                    }
                }
                val songFromCache = getFromCache(downloadCache, s.key)
                if (songFromCache != null) {
                    downloadCache.removeResource(s.key)
                    downloadMgr.enqueue(
                        mediaId = s.key,
                        data = songFromCache,
                        displayName = runBlocking { database.song(s.key).first()?.title ?: "" })
                }
            }
            scanDownloads()
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isProcessingDownloads.value = false
        }
    }


    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    /**
     * Copies every file out of [oldDirUri] into [newDirUri], deleting each source file only
     * after its copy has landed - so a picker choice that turns out to be read-only, or a copy
     * that fails partway, leaves files in their original folder rather than losing them. This is
     * what changing the external download folder in Settings has never actually done on its own:
     * swapping [DownloadPathKey] only changes where *new* downloads and rescans look, and leaves
     * anything already in the old folder orphaned there. [onProgress] reports (copied, total)
     * after each file. Caller is responsible for updating [DownloadPathKey] and calling [cd] once
     * this returns.
     */
    suspend fun moveDownloads(
        oldDirUri: Uri,
        newDirUri: Uri,
        onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        if (isProcessingDownloads.value) return
        isProcessingDownloads.value = true
        try {
            withContext(Dispatchers.IO) {
                val oldDir = documentFileFromUri(context, oldDirUri)
                    ?.takeIf { it.isDirectory }
                    ?: throw IOException("The current download folder is no longer accessible")
                val newDir = DocumentFile.fromTreeUri(context, newDirUri)
                    ?.takeIf { it.isDirectory }
                    ?: throw IOException("The chosen folder is not valid")

                val files = ArrayList<DocumentFile>()
                scanDfRecursive(oldDir, files, true)
                val toMove = files.filter { it.name != null }
                val resolver = context.contentResolver

                toMove.forEachIndexed { index, file ->
                    val name = file.name!!
                    val mimeType = file.type ?: "application/octet-stream"
                    val newFile = newDir.createFile(mimeType, name)
                        ?: throw IOException("Failed to create \"$name\" in the new folder")
                    resolver.openInputStream(file.uri)?.use { input ->
                        resolver.openOutputStream(newFile.uri)?.use { output ->
                            input.copyTo(output)
                        } ?: throw IOException("Failed to write \"$name\" to the new folder")
                    } ?: throw IOException("Failed to read \"$name\" from the current folder")
                    file.delete()
                    onProgress(index + 1, toMove.size)
                }
            }
        } finally {
            isProcessingDownloads.value = false
        }
    }

    /**
     * Rescan download directory and updates songs
     */
    suspend fun rescanDownloads() {
        if (DOWNLOAD_DEBUG) Log.i(TAG, "+rescanDownloads()")
        isProcessingDownloads.value = true
        val dbDownloads = database.downloadedOrQueuedSongs().first()
        val result = mutableMapOf<String, LocalDateTime>()

        // get missing files not in custom downloads or in internal downloads, remove them
        val missingFiles =
            localMgr.getMissingFiles(dbDownloads.filterNot { it.song.dateDownload == null }).toMutableList()
        if (DOWNLOAD_DEBUG) Log.d(TAG, "Found ${missingFiles.size}/${dbDownloads.size} songs not in custom download directories")
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            missingFiles.removeIf { it.id == cursor.download.request.id }
        }
        if (DOWNLOAD_DEBUG) Log.d(
            TAG,
            "Found ${missingFiles.size}/${dbDownloads.size} song not in custom download directories + internal cache. Removing these files now"
        )

        database.transaction {
            missingFiles.forEach {
                if (DOWNLOAD_DEBUG) Log.v(TAG, "Shedding: [${it.id}] ${it.song.title}")
                removeDownloadSong(it.song.id)
            }
        }

        // new files
        val availableDownloads = dbDownloads.minus(missingFiles)
        availableDownloads.forEach { s ->
            result[s.song.id] = s.song.dateDownload!! // sql should cover our butts
        }

        downloads.value = result
        isProcessingDownloads.value = false
        if (DOWNLOAD_DEBUG) Log.i(TAG, "-rescanDownloads()")
    }


    /**
     * Scan and import downloaded songs from main and extra directories.
     *
     * This is intended for re-importing existing songs (ex. songs get moved, after restoring app backup), thus all
     * songs will already need to exist in the database.
     */
    suspend fun scanDownloads() {
        if (DOWNLOAD_DEBUG) Log.i(TAG, "+scanDownloads()")
        if (isProcessingDownloads.value) {
            if (DOWNLOAD_DEBUG) Log.i(TAG, "-scanDownloads()")
            return
        }
        isProcessingDownloads.value = true

//            val scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER_DL)
        database.removeAllDownloadedSongs()
        val timeNow = LocalDateTime.now()

        // add custom downloads
        val availableFiles = localMgr.getAvailableFiles(false)
        database.transaction {
            availableFiles.forEach { f ->
                try {
                    val file = fileFromUri(context, f.value)
                    if (file == null) throw (InvalidAudioFileException("Hello darkness my old friend"))
                    // TODO: validate files in download folder
//                        val format: FormatEntity? = scanner.advancedScan(f.value).format
//                        if (format != null) {
//                            database.upsert(format)
//                        }
                    registerDownloadSong(f.key, timeNow, file.absolutePath)

                } catch (e: InvalidAudioFileException) {
                    reportException(e)
                }
            }
        }
//            LocalMediaScanner.destroyScanner(SCANNER_OWNER_DL)
        if (DOWNLOAD_DEBUG) Log.d(TAG, "Registered ${availableFiles.size} files from custom downloads")

        // add internal downloads
        val cursor = downloadManager.downloadIndex.getDownloads()
        var count = 0
        database.transaction {
            while (cursor.moveToNext()) {
                updateDownloadStatus(cursor.download.request.id, stateToLocalDateTime(cursor.download))
                count ++
            }
        }
        if (DOWNLOAD_DEBUG) Log.d(TAG, "Registered $count files from internal downloads")
        isProcessingDownloads.value = false
        if (DOWNLOAD_DEBUG) Log.d(TAG, "Database registration complete, triggering map registry rebuild")
        rescanDownloads()
        // Fire-and-forget: a batch of searches (one per song that actually has something to check
        // - see ArtistCreditEnricher's own doc) shouldn't hold up this scan finishing.
        CoroutineScope(dlCoroutine).launch {
            downloads.value.keys.forEach { songId -> ArtistCreditEnricher.enrich(database, songId) }
        }
        if (DOWNLOAD_DEBUG) Log.i(TAG, "-scanDownloads()")
    }

    companion object {
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        val STATE_INVALID: LocalDateTime = Instant.ofEpochMilli(0).atZone(ZoneOffset.UTC).toLocalDateTime()

        fun downloadRequirements(wifiOnly: Boolean): Requirements =
            Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }


    init {
        if (DOWNLOAD_DEBUG) Log.i(TAG, "DownloadUtil init")
        // TODO: make sure db is update when download is queued
        CoroutineScope(dlCoroutine).launch {
            rescanDownloads()
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    // A rejected stream URL (403/410) is the CDN's own signal that the resolved
                    // URL is dead - the same class of failure playback recovers from by re-resolving
                    // via a different client. Media3 retries a failed download task on its own, but
                    // it would keep reusing this same now-known-bad cached URL every time without
                    // this, since the ResolvingDataSource factory above only re-resolves when the
                    // cache entry is gone.
                    if (finalException?.isExpiredStreamError() == true) {
                        Log.w(TAG, "Stream expired for ${download.request.id}, invalidating cached URL for retry")
                        songUrlCache.remove(download.request.id)
                    }

                    downloads.update { map ->
                        map.toMutableMap().apply {
                            val state = stateToLocalDateTime(download)
                            if (state == STATE_INVALID) {
                                Log.w(TAG, "Invalid download state for ${download.request.id}. Removing download")
                                remove(download.request.id)
                            } else {
                                set(download.request.id, state)
                            }
                        }
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        if (download.state == Download.STATE_COMPLETED) {
                            val updateTime =
                                Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
                            database.updateDownloadStatus(download.request.id, updateTime)
                        } else {
                            database.updateDownloadStatus(download.request.id, null)
                        }
                    }
                }
            }
        )
    }
}

/**
 * Whether this exception (or any exception in its cause chain) is an HTTP 403 or 410 response -
 * googlevideo.com's way of saying a resolved stream URL has expired or was rejected outright, the
 * same failure mode ExoPlayer sees mid-playback. Unwrapping the chain is necessary because Media3
 * wraps the underlying [androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException] in
 * its own downloader/task exceptions before it reaches [DownloadManager.Listener].
 */
private fun Throwable.isExpiredStreamError(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException &&
            (cause.responseCode == 403 || cause.responseCode == 410)
        ) {
            return true
        }
        cause = cause.cause
    }
    return false
}

fun stateToLocalDateTime(download: Download): LocalDateTime {
    return when (download.state) {
        Download.STATE_COMPLETED -> {
            Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
        }

        Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> STATE_DOWNLOADING
        else -> STATE_INVALID
    }
}