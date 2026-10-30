package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.MaxSongCacheSizeKey
import com.dd3boh.outertune.constants.RemovedDownloadsToCacheKey
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
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

    init {
        instance = this
    }

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)

    /**
     * Mirrors of the two preferences the download click path needs, kept current by a collector in
     * [init] so that path never has to read DataStore itself - see there for why that mattered.
     * Defaults match the preference defaults, and only apply for the moment before the first
     * emission arrives.
     */
    @Volatile
    private var downloadOnWifiOnly = true

    /** Removed downloads go to the song cache (when it has room) - see [removeDownload]. */
    @Volatile
    private var removedDownloadsToCache = true

    /** The song cache's size limit in MB as set; 0 means it keeps nothing. */
    @Volatile
    private var songCacheMb = 0

    /** One fetch per cover at a time - see [downloadThumbnail]. */
    private val thumbnailLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    /** Same shape as MusicService's - see that one's doc for why the headers have to travel
     *  with the URL. */
    private data class CachedStreamUrl(val url: String, val expiresAt: Long, val headers: Map<String, String>)

    // Concurrent: up to five download tasks read and write it at once, on Media3's own threads.
    private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, CachedStreamUrl>()
    private val thumbnailHttpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    /** Used only for the content-length probe below - kept separate so it carries no timeouts. */
    private val streamHttpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    /** Where downloadThumbnail/removeThumbnail keep full-res thumbnails - app-managed storage,
     *  independent of the user-chosen audio download directory. */
    private val thumbnailDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            // Read what the song cache already holds, but do not write to it: the download cache is
            // where these bytes are going, and writing them here too doubled every download's flash
            // writes and let a few downloads at once push the songs being streamed out of the cache.
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(
                    OkHttpClient.Builder()
                        .proxy(YouTube.proxy)
                        // No callTimeout, deliberately. There used to be a 45s one here, to force
                        // a reconnect before a "~60s connection-duration cutoff" that turned out
                        // not to exist - what actually failed at about a minute was a web url being
                        // requested as a run of small bounded ranges (see StreamRangePolicy).
                        // OkHttp's callTimeout covers the whole call including reading the body, so
                        // on a download it is a hard cap on transfer time: anything not finished in
                        // 45s was aborted mid-file, and once Media3's retries were spent the file
                        // stayed truncated. Metrolist sets no timeouts on its stream client either.
                        .build()
                )
            )
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")
        // Only a song the cache holds all of may go unresolved. Checking just its first byte, as this
        // did for an open-ended request, passed a bare video id on whenever the start was cached, and
        // the read failed at the first gap until Media3's retry resolved it properly.
        val cached = if (dataSpec.length >= 0) {
            playerCache.isCached(mediaId, dataSpec.position, dataSpec.length)
        } else {
            cachedLengthOrNull(playerCache, mediaId) != null
        }
        if (cached) {
            return@Factory dataSpec
        }

        songUrlCache[mediaId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
            return@Factory dataSpec.withUri(it.url.toUri())
                // Merged, not replaced - matching Metrolist v13.6.3's DownloadUtil. Replacing
                // discards whatever the caller had already put on the spec, and googlevideo will
                // refuse a request that is missing a header the url was signed alongside.
                .withRequestHeaders(dataSpec.httpRequestHeaders + it.headers)
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
                // No contentHints: those steer the client list toward uploaded, live or kids
                // content, and a download has none of that context to offer - the default list is
                // the same one playback uses for an ordinary track.
            )
        }.getOrThrow()
        val format = playbackData.format

        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=").getOrNull(1)
                        ?.removeSurrounding("\"") ?: "",
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
        // googlevideo's CDN up front to serve the entire file as one continuous transfer,
        // regardless of how the resulting HTTP request is otherwise shaped - exactly what was
        // driving downloads into the ~60s connection-duration/PoToken cutoff. MusicService never
        // did this either.
        //
        // Deliberately NOT bounding this dataSpec's length (e.g. via subrange()) the way
        // MusicService bounds its own playback requests to CHUNK_LENGTH: that pattern relies on
        // ExoPlayer's own chunk-based playback loader re-invoking this resolver for each
        // successive chunk. Media3's offline DownloadManager drives its downloads very
        // differently - the whole download is one CacheWriter.cache() call against a DataSpec
        // whose length is C.LENGTH_UNSET ("give me everything"), wrapped in an extra
        // CacheDataSource DownloadManager adds automatically around this factory. Explicitly
        // declaring a short length here doesn't chunk that download into many requests the way it
        // does for playback - it just makes the download stop after that length, silently
        // truncating every file to CHUNK_LENGTH (256KB, ~15s of audio) rather than erroring. The
        // this dataSpec should just ask for the whole file.
        //
        // The whole file is asked for on the *url* instead, as &range=0-<length>, which is what
        // Metrolist v13.6.3 does and is the reason its downloads finish while these crawled.
        // googlevideo paces a plain progressive request at roughly playback speed - fine for
        // playback, which is why streaming was never affected, but it means a download of a three
        // minute song takes about three minutes. An explicit range asks for those same bytes as a
        // bulk transfer and they arrive as fast as the connection allows.
        //
        // contentLength comes from the format where the client gave one; where it did not, a HEAD
        // request is worth the round trip, since without a length there is no range to ask for and
        // the download falls back to being paced.
        val contentLength = playbackData.format.contentLength ?: runCatching {
            val probe = Request.Builder()
                .head()
                .url(playbackData.streamUrl)
                .apply { playbackData.streamHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
            streamHttpClient.newCall(probe).execute().use { it.header("Content-Length")?.toLongOrNull() }
        }.getOrNull()

        val streamUrl = playbackData.streamUrl.let {
            if (contentLength != null && contentLength > 0) "$it&range=0-$contentLength" else it
        }
        if (contentLength == null) {
            Log.w(TAG, "No content length for $mediaId - downloading without a range, which will be slow")
        }

        songUrlCache[mediaId] = CachedStreamUrl(
            url = streamUrl,
            // streamExpiresInSeconds is YouTube's own claimed validity (routinely hours), but the
            // embedded streaming PoToken has been observed to actually stop working after roughly
            // a minute regardless of connection count or chunk size - see
            // MusicService.STREAM_URL_TRUST_WINDOW_MS's own doc. Capping our own trust the same
            // way means a download that gets interrupted and
            // retries after this window has passed re-resolves for a fresh URL/PoToken instead of
            // reusing one already past its real-world validity.
            expiresAt = System.currentTimeMillis() + minOf(playbackData.streamExpiresInSeconds * 1000L, MusicService.STREAM_URL_TRUST_WINDOW_MS),
            headers = playbackData.streamHeaders,
        )
        dataSpec.withUri(streamUrl.toUri())
            // Merged for the same reason as the cached branch above.
            .withRequestHeaders(dataSpec.httpRequestHeaders + playbackData.streamHeaders)
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            // Per-file speed is already handled by the &range=0-<length> trick above, so the only
            // lever left on a bulk queue is how many run at once. Each download is a single
            // sequential stream that spends most of its life waiting on the network rather than
            // saturating it, so three at a time leaves a decent connection idle. Five is a
            // deliberate stopping point rather than a maximum: every concurrent download is a
            // separate googlevideo connection carrying the same PoToken, and pushing that count
            // high is a good way to attract the throttling and 403s this fork has already spent
            // time escaping. This only affects queues - a single song downloads no faster.
            maxParallelDownloads = 5
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

    /**
     * Guards [scanDownloads] and [rescanDownloads] against each other specifically.
     *
     * Both used to share [isProcessingDownloads] as a plain "busy" flag with an early return on
     * contention. rescanDownloads() runs unconditionally from init as soon as this class is
     * constructed, so a scanDownloads() call landing while that was still in flight - which
     * scanInit() does on every app start where the scan cooldown has expired - saw the flag
     * already set and returned immediately, silently skipping the scan it was asked to do.
     * A real mutex makes the second caller wait its turn instead of being dropped.
     */
    private val downloadScanMutex = Mutex()

    // Only when this song's own state changes - the map changes with every other download too, and
    // a row watching it redrew on each of them.
    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }.distinctUntilChanged()

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
        if (thumbnailUrl == null) return
        // Only when there is not already a whole one - a song downloaded again, or one whose cover
        // was fetched on its own, needs nothing more.
        if (isCompleteImage(File(thumbnailDir, "$songId.jpg"))) return
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
        val original = thumbnailUrl ?: database.song(songId).first()?.song?.thumbnailUrl ?: return
        val file = File(thumbnailDir, "$songId.jpg")
        // One at a time per song. Two fetches of the same cover - a download and "download all
        // thumbnails", or a refresh - used to write the same file at once.
        thumbnailLocks.getOrPut(songId) { kotlinx.coroutines.sync.Mutex() }.withLock {
            // Fetched into a file of its own and only moved into place once it is known to be whole.
            // Written straight into place, a cover was readable while half-written - the player drew
            // its top half and then kept that - and a dropped connection left a cut-off file behind
            // for good. The full-resolution copy first, then the size the song came with, since
            // YouTube has no full-resolution image for some videos - and then a small one, because
            // a video song's stored URL is often already the full-resolution maxresdefault, so for
            // a low-resolution upload the first two were the same missing image.
            val part = File(thumbnailDir, "$songId.jpg.part")
            for (url in listOf(original.resize(1080, 1080), original, original.resize(320, 320)).distinct()) {
                try {
                    val ok = withContext(Dispatchers.IO) {
                        thumbnailHttpClient.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                            val body = response.body
                            if (!response.isSuccessful) return@use false
                            part.outputStream().use { out -> body.byteStream().copyTo(out) }
                            val expected = body.contentLength()
                            (expected < 0 || part.length() == expected) && isCompleteImage(part)
                        }
                    }
                    if (!ok) {
                        part.delete()
                        continue
                    }
                    // File.renameTo rather than java.nio.file.Files.move: nio's file API only exists
                    // from Android 8, and the app still runs on 7, where the move crashed after every
                    // download. Both files are in the same directory, and on Android a rename there is
                    // atomic and replaces the old cover, so nothing ever sees half of one.
                    val moved = withContext(Dispatchers.IO) { part.renameTo(file) }
                    if (!moved) {
                        part.delete()
                        Log.w(TAG, "Could not move the thumbnail for $songId into place")
                        continue
                    }
                    database.song(songId).first()?.song?.takeIf { it.thumbnailPath != file.absolutePath }
                        ?.copy(thumbnailPath = file.absolutePath)
                        ?.let { database.update(it) }
                    return@withLock
                } catch (e: IOException) {
                    part.delete()
                    Log.w(TAG, "Thumbnail download failed for $songId from $url: ${e.message}")
                }
            }
            Log.w(TAG, "No usable thumbnail for $songId")
        }
    }

    /**
     * Whether [file] is a whole image: there, not empty, and - for a JPEG, which is what these are -
     * ending in the end-of-image marker. A cover cut off mid-download draws as only its top part.
     */
    private fun isCompleteImage(file: File): Boolean {
        val length = file.length()
        if (!file.isFile || length < 4) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(2).also { raf.readFully(it) }
                if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return true // not a JPEG: no marker to check
                // The marker can be followed by a little padding.
                val tailSize = minOf(length, 64L).toInt()
                raf.seek(length - tailSize)
                val tail = ByteArray(tailSize).also { raf.readFully(it) }
                (0 until tailSize - 1).any { tail[it] == 0xFF.toByte() && tail[it + 1] == 0xD9.toByte() }
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Fetches again any downloaded cover that is missing or was cut off - covers saved before
     * [downloadThumbnail] wrote them safely, which is where a song's cover missing its bottom half
     * came from. Cheap when there is nothing to do: one look at each file.
     */
    suspend fun repairThumbnails() {
        database.downloadedSongs().first()
            .filter { it.song.thumbnailPath != null && !isCompleteImage(File(it.song.thumbnailPath!!)) }
            // Not deleted first: a fetch that works replaces the cover in one step, and one that fails
            // would otherwise leave the song pointing at a file that is gone, so its rows drew no
            // cover at all rather than the cut-off one or the online one.
            .forEach { song ->
                downloadThumbnail(song.id, song.song.thumbnailUrl)
                // A cover that was already gone and could not be fetched again is forgotten, so the
                // song's rows fall back to its online cover instead of a path to nothing. "Download
                // all thumbnails" picks it up again later.
                if (!File(song.song.thumbnailPath!!).exists()) {
                    database.song(song.id).first()?.song?.takeIf { it.thumbnailPath == song.song.thumbnailPath }
                        ?.copy(thumbnailPath = null)?.let { database.update(it) }
                }
            }
    }

    /**
     * Removes the download of [id] - the one way every screen removes one.
     *
     * When "keep removed downloads in the song cache" is on and the song cache has room, the song is
     * copied into the song cache first, so it still plays offline until the cache needs the space,
     * and downloading it again needs no fetch (see [promoteCachedSong]). Otherwise, or if the copy
     * fails, it is deleted outright as before. Its saved cover goes either way.
     */
    fun removeDownload(id: String) {
        enqueueScope.launch {
            if (removedDownloadsToCache && songCacheMb != 0) demoteToPlayerCache(id)
            removeThumbnail(id)
            DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
        }
    }

    /** Copies a downloaded song into the song cache - the reverse of [promoteCachedSong]. */
    private fun demoteToPlayerCache(mediaId: String) {
        val contentLength = cachedLengthOrNull(downloadCache, mediaId) ?: return
        if (playerCache.isCached(mediaId, 0, contentLength)) return
        try {
            val dataSpec = DataSpec.Builder()
                .setUri(mediaId.toUri())
                .setKey(mediaId)
                .setPosition(0)
                .setLength(contentLength)
                .build()
            val writer = CacheDataSource.Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    CacheDataSource.Factory()
                        .setCache(downloadCache)
                        .setCacheWriteDataSinkFactory(null)
                )
                .createDataSource()
            CacheWriter(writer, dataSpec, null, null).cache()
            // A read with a set length never tells the cache how long the song is, and without that
            // the copy did not count as whole: downloading the song again fetched it anew instead of
            // moving it back (see cachedLengthOrNull), and playback could run off its end.
            playerCache.applyContentMetadataMutations(
                mediaId,
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, contentLength) },
            )
            Log.d(TAG, "Kept $mediaId in the song cache as its download is removed")
        } catch (e: Exception) {
            reportException(e)
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
                .filter { it.song.thumbnailPath == null || !isCompleteImage(File(it.song.thumbnailPath!!)) }
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
        if (downloadOnWifiOnly && connectivityManager.isActiveNetworkMetered) {
            Toast.makeText(context, R.string.download_waiting_for_wifi, LENGTH_SHORT).show()
        }
    }

    /**
     * The whole song, in bytes, if [cache] holds every one of them - null otherwise.
     *
     * Coverage has to be checked rather than assumed. A cache holds whatever spans happened to be
     * read, so a song skipped halfway through leaves a real but incomplete entry, and treating that
     * as a copy of the song would produce a truncated download.
     */
    private fun cachedLengthOrNull(cache: SimpleCache, mediaId: String): Long? {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(mediaId))
        if (contentLength <= 0) return null
        return contentLength.takeIf { cache.getCachedBytes(mediaId, 0, it) == it }
    }

    /**
     * Copies a song the player has already streamed out of the play cache and into the download
     * cache, then drops the play cache's copy.
     *
     * Downloading a song you had just listened to used to fetch the whole thing again over the
     * network, and then keep both copies: the download cache's, and the play cache's, which lingers
     * until the LRU evictor gets round to it. The bytes are identical and they are already on the
     * device, so the download is pure waste and the second copy is pure waste twice over.
     *
     * This deliberately runs *before* the download request rather than instead of it. Media3 still
     * owns the download - its index is what the downloaded state in the UI is read from - so the
     * request is still sent; it simply finds everything already cached and completes without
     * touching the network. Seeding rather than short-circuiting keeps one code path for downloads
     * instead of two, and means a partial or failed transfer just falls back to downloading.
     *
     * Returns true when the song was transferred.
     */
    private fun promoteCachedSong(mediaId: String): Boolean {
        val contentLength = cachedLengthOrNull(playerCache, mediaId) ?: return false
        if (downloadCache.isCached(mediaId, 0, contentLength)) return true

        return try {
            val dataSpec = DataSpec.Builder()
                .setUri(mediaId.toUri())
                .setKey(mediaId)
                .setPosition(0)
                .setLength(contentLength)
                .build()
            // Upstream is the play cache with no upstream of its own, so this cannot silently fall
            // through to the network: coverage was checked above, and if that check were ever wrong
            // the read fails and we fall back to a normal download rather than writing a hole.
            val writer = CacheDataSource.Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(
                    CacheDataSource.Factory()
                        .setCache(playerCache)
                        .setCacheWriteDataSinkFactory(null)
                )
                .createDataSource()
            CacheWriter(writer, dataSpec, null, null).cache()
            releasePlayerCacheCopy(mediaId)
            Log.d(TAG, "Moved $mediaId from the play cache into downloads - no fetch needed")
            true
        } catch (e: Exception) {
            reportException(e)
            false
        }
    }

    /**
     * Drops the play cache's copy of a song that now lives in the download cache.
     *
     * Playback reads the download cache first and only falls through to the play cache on a miss,
     * so once a song is downloaded its cached copy can never be read again - it is duplicated bytes
     * occupying the LRU budget that songs which are *not* downloaded have to share.
     *
     * Best-effort on purpose. removeResource throws if a span is currently locked, which is exactly
     * the case where the player is reading the song right now, and a failure here costs nothing:
     * the copy simply survives until the evictor reclaims it in the ordinary way.
     */
    private fun releasePlayerCacheCopy(mediaId: String) {
        try {
            playerCache.removeResource(mediaId)
        } catch (e: Exception) {
            if (DOWNLOAD_DEBUG) Log.d(TAG, "Play cache copy of $mediaId is in use, leaving it: ${e.message}")
        }
    }

    /**
     * One lane, so songs are enqueued in the order they were asked for.
     *
     * Enqueueing has to happen off the main thread now that a cache promotion may precede it, but
     * the shared download dispatcher runs several jobs at once, and a promotion takes as long as
     * the song is big - so a playlist queued through that would hand its songs to Media3 in
     * whatever order the copies happened to finish. Serialising costs nothing worth having, since
     * these are local disk copies that do not go faster in parallel.
     */
    private val enqueueScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    private fun downloadSong(id: String, title: String) {
        // An invalid entry is not a download. Guarding on null alone is what made a failed download
        // permanent: the map held epoch zero, which is not null, so this returned and nothing was
        // ever enqueued.
        downloads.value[id]?.takeIf { it != STATE_INVALID }?.let { return }
        enqueueScope.launch {
            promoteCachedSong(id)
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
            // Sorted by position, because getCachedSpans returns a Set and iteration order is not
            // file order. A song held as more than one span - anything that was paused, seeked or
            // resumed while caching - would otherwise be reassembled with its pieces shuffled, and
            // the result is a file of exactly the right size that does not play.
            for (span in spans.sortedBy { it.position }) {
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
        // Created below and released in finally: a DownloadManager owns a worker thread and a
        // handle on the download index, and this one is only needed for the migration.
        var oldDownloadManager: DownloadManager? = null
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
            oldDownloadManager = downloadManager

            // actual migration code
            val downloadedSongs = mutableMapOf<String, Download>()
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    downloadedSongs[cursor.download.request.id] = cursor.download
                }
            }

            // copy all completed downloads
            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            val copied = ArrayList<String>()
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
                    downloadMgr.enqueue(
                        mediaId = s.key,
                        data = songFromCache,
                        displayName = runBlocking { database.song(s.key).first()?.title ?: "" })
                    copied += s.key
                }
            }

            // The cached copy is only dropped once its file is really in the download folder. The
            // in-memory enqueue reports a failed save as an event nobody listens to, and removing
            // the cache entry first lost the song whenever that save failed. One rescan of the
            // folder covers every song, as the folder listing is cached and saving does not add to it.
            val saved = localMgr.getAvailableFiles(false).keys
            copied.filter { it in saved }.forEach { downloadCache.removeResource(it) }
            scanDownloads()
        } catch (e: Exception) {
            reportException(e)
        } finally {
            oldDownloadManager?.release()
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
    suspend fun rescanDownloads() = downloadScanMutex.withLock {
        if (DOWNLOAD_DEBUG) Log.i(TAG, "+rescanDownloads()")
        isProcessingDownloads.value = true
        try {
            val dbDownloads = database.downloadedOrQueuedSongs().first()
            val result = mutableMapOf<String, LocalDateTime>()

            // get missing files not in custom downloads or in internal downloads, remove them
            val missingFiles =
                localMgr.getMissingFiles(dbDownloads.filterNot { it.song.dateDownload == null }).toMutableList()
            if (DOWNLOAD_DEBUG) Log.d(TAG, "Found ${missingFiles.size}/${dbDownloads.size} songs not in custom download directories")
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    missingFiles.removeIf { it.id == cursor.download.request.id }
                }
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
            val poisoned = mutableListOf<String>()
            availableDownloads.forEach { s ->
                val date = s.song.dateDownload!! // the query is IS NOT NULL, so this holds
                if (date == STATE_INVALID) {
                    // Written by an older build, which stored a sentinel where it meant null. Left
                    // alone it keeps the song un-downloadable forever, so this clears it rather than
                    // only skipping it - a fix that stops creating bad rows still leaves every song
                    // already affected broken.
                    poisoned += s.song.id
                    return@forEach
                }
                result[s.song.id] = date
            }
            if (poisoned.isNotEmpty()) {
                Log.i(TAG, "Clearing ${poisoned.size} songs stuck at an invalid download state")
                database.transaction { poisoned.forEach { updateDownloadStatus(it, null) } }
            }

            downloads.value = result
        } finally {
            isProcessingDownloads.value = false
        }
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
        // A separate withLock (rather than wrapping this whole function) so this suspends until
        // any in-flight rescanDownloads() - notably the one launched unconditionally from init -
        // finishes and then actually runs, instead of bailing out because the shared busy flag
        // was still set. rescanDownloads() is called again below, after this lock is released, so
        // the two never try to acquire the same non-reentrant mutex at once.
        downloadScanMutex.withLock {
            isProcessingDownloads.value = true
            try {
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
                var count = 0
                database.transaction {
                    // Opened inside the block because transaction runs it later on Room's executor;
                    // a cursor closed out here would already be shut by the time the block reads it.
                    downloadManager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            // The nullable form. Writing stateToLocalDateTime's sentinel here is what poisoned
                            // the column: epoch zero is not null, so "IS NOT NULL" counted a failed download as
                            // a download and the song could never be queued again.
                            updateDownloadStatus(
                                cursor.download.request.id,
                                downloadDateFor(cursor.download.state, cursor.download.updateTimeMs),
                            )
                            count++
                        }
                    }
                }
                if (DOWNLOAD_DEBUG) Log.d(TAG, "Registered $count files from internal downloads")
            } finally {
                isProcessingDownloads.value = false
            }
        }
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
        /** The one instance, for [removeDownloadKeepingCache]. Set as Hilt creates it. */
        @Volatile
        var instance: DownloadUtil? = null
            private set

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

        // Kept mirrored here so the download click path never reads DataStore directly. The
        // dataStore.get(key, default) helper is runBlocking underneath, and download() is called
        // straight from a UI click handler - so every tap on Download blocked the main thread on a
        // preferences file read (twice: wifi-only and thumbnails), which is exactly the freeze that
        // shows up when tapping download. Reading a volatile field instead costs nothing.
        CoroutineScope(dlCoroutine).launch {
            context.dataStore.data
                .map { Triple(it[DownloadOnWifiOnlyKey] != false, it[RemovedDownloadsToCacheKey] != false, it[MaxSongCacheSizeKey] ?: 0) }
                .distinctUntilChanged()
                .collect { (wifiOnly, toCache, cacheMb) ->
                    downloadOnWifiOnly = wifiOnly
                    removedDownloadsToCache = toCache
                    songCacheMb = cacheMb
                }
        }
        // Covers saved before they were written safely may be cut off; fetch those again.
        CoroutineScope(dlCoroutine).launch { runCatching { repairThumbnails() }.onFailure { reportException(it) } }

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
                            // The song is in the download cache now, and playback reads that cache
                            // before the play cache, so any copy still sitting in the play cache
                            // can never be read again - it is only taking LRU budget away from
                            // songs that are not downloaded. Covers downloads that were fetched
                            // normally; a promoted one released its copy already.
                            releasePlayerCacheCopy(download.request.id)
                            val updateTime =
                                Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
                            database.updateDownloadStatus(download.request.id, updateTime)
                        } else if (download.state != Download.STATE_QUEUED &&
                            download.state != Download.STATE_DOWNLOADING &&
                            download.state != Download.STATE_RESTARTING
                        ) {
                            // Only when it can have changed anything. Queued and in-progress downloads
                            // wrote "not downloaded" over a song that already was not, and every such
                            // write re-ran every live song query in the app - with several downloads
                            // going, a steady storm of them, which is what froze the phone.
                            database.updateDownloadStatus(download.request.id, null)
                            if (download.state == Download.STATE_REMOVING) removeThumbnail(download.request.id)
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

/**
 * What a Media3 download state means for `song.dateDownload`, or null for "not downloaded".
 *
 * Null rather than a sentinel, and that is the whole point of this function. `dateDownload` already
 * has a value meaning "not downloaded" - NULL - and every query is written against it: the one that
 * lists downloads is `dateDownload IS NOT NULL`. Writing a stand-in for "invalid" instead put a real
 * timestamp of epoch zero in that column, which those queries happily returned, so a failed download
 * came back as a download.
 *
 * What that cost: `downloadSong` refuses to enqueue a song the map already has an entry for, and the
 * map is built from those queries. So a download that failed - or was stopped, or interrupted - left
 * a row that made its song permanently un-downloadable. Tapping Download did nothing at all, with no
 * error and no notification, and no amount of tapping would change it.
 *
 * Takes the state as an Int rather than a Download so it can be tested without Android: building a
 * Download needs a DownloadRequest, which needs android.net.Uri, which is not available in a plain
 * unit test.
 */
fun downloadDateFor(state: Int, updateTimeMs: Long): LocalDateTime? = when (state) {
    Download.STATE_COMPLETED ->
        Instant.ofEpochMilli(updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()

    // Restarting counts as in flight. It used to fall into the catch-all below and be recorded as
    // invalid, which briefly showed an active download as absent.
    Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> STATE_DOWNLOADING

    else -> null
}

fun stateToLocalDateTime(download: Download): LocalDateTime =
    downloadDateFor(download.state, download.updateTimeMs) ?: STATE_INVALID

/**
 * Removes a download through [DownloadUtil.removeDownload], which keeps the song in the song cache
 * when that setting is on. Callable from anywhere a song's download is removed. Does nothing before
 * the download utility exists - MainActivity has it injected before any screen can offer a removal.
 */
fun removeDownloadKeepingCache(id: String) {
    DownloadUtil.instance?.removeDownload(id)
}
