/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioDecoderKey
import com.dd3boh.outertune.constants.AudioGaplessOffloadKey
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioOffloadKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.DiscordTokenKey
import com.dd3boh.outertune.audio.EqualizerAudioProcessor
import com.dd3boh.outertune.constants.EqualizerSettingsKey
import com.dd3boh.outertune.models.EqualizerSettings
import com.dd3boh.outertune.constants.EnableDiscordRPCKey
import com.dd3boh.outertune.constants.DEFAULT_AUDIO_DECODER
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.EnableLyricsPrefetchKey
import com.dd3boh.outertune.constants.EnableStreamPrecacheKey
import com.dd3boh.outertune.constants.IgnoreAudioFocusKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.LyricsPrefetchCountKey
import com.dd3boh.outertune.constants.MAX_PLAYER_CONSECUTIVE_ERR
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleLike
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleShuffle
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleStartRadio
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.PauseRemoteListenHistoryKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayerVolumeKey
import com.dd3boh.outertune.constants.RepeatModeKey
import com.dd3boh.outertune.constants.SERVICE_DEBUG
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.extensions.SilentHandler
import com.dd3boh.outertune.extensions.collect
import com.dd3boh.outertune.extensions.collectLatest
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.findNextMediaItemById
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.setOffloadEnabled
import com.dd3boh.outertune.lyrics.LyricsFetchRole
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.models.HybridCacheDataSinkFactory
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.utils.ArtistCreditEnricher
import com.dd3boh.outertune.utils.CoilBitmapLoader
import com.dd3boh.outertune.utils.DiscordRPC
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.playerCoroutine
import com.dd3boh.outertune.utils.reportException
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.response.PlayerResponse
import dagger.hilt.android.AndroidEntryPoint
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    val TAG = MusicService::class.simpleName.toString()

    @Inject
    lateinit var database: MusicDatabase

    // Parent of every service coroutine scope. Cancelled once in onDestroy() so no scope outlives the
    // service. Each scope owns a SupervisorJob child of this so a failure in one coroutine neither
    // cancels its siblings nor the whole service.
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(SupervisorJob(serviceJob) + Dispatchers.Main)
    private val offloadScope = CoroutineScope(SupervisorJob(serviceJob) + playerCoroutine)

    // Scope for the per-request playQueue() coroutine. Uses the Main dispatcher because it mutates the
    // player, which must run on the application thread; the SupervisorJob binds it to the service lifecycle.
    private val queueLoadScope = CoroutineScope(SupervisorJob(serviceJob) + Dispatchers.Main)

    // Critical player components
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager

    val qbInit = MutableStateFlow(false)
    var queueBoard = MutableStateFlow(QueueBoard(this, maxQueues = 1))
    var queuePlaylistId: String? = null

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    // Constructed eagerly (not lateinit) because createRenderersFactory() needs it already built
    // by the time the ExoPlayer.Builder in onCreate() runs.
    val equalizerAudioProcessor = EqualizerAudioProcessor()

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    private var discordRpc: DiscordRPC? = null

    /**
     * Presence updates are *requested*, never pushed directly.
     *
     * Two independent writers used to publish presences: a collector on [currentSong] and the
     * play/pause hook in [onEvents]. On a track change both fire, and whichever finished its
     * network round trip last decided what Discord showed - so roughly one skip in three left the
     * card describing the outgoing song. Everything now funnels through this one request, which a
     * single collector serves, so there is no ordering to lose.
     */
    private val discordUpdateRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun requestDiscordUpdate() {
        discordUpdateRequests.tryEmit(Unit)
    }

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    // Player vars
    val currentMediaMetadata = MutableStateFlow<MediaMetadata?>(null)

    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(offloadScope, SharingStarted.Lazily, null)

    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    private val normalizeFactor = MutableStateFlow(1f)

    private val audioDecoder = dataStore.get(AudioDecoderKey, DEFAULT_AUDIO_DECODER)
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0

    /**
     * A resolved stream URL, its expiry time, and the headers (User-Agent, and for a
     * browser-origin client like WEB_REMIX, Referer/Origin) of the client it was resolved from -
     * googlevideo.com has been observed rejecting a fetch whose headers don't match the client
     * the URL was signed for, so they have to be replayed alongside the URL itself wherever it's
     * actually GETed from, not just at the request that originally resolved it.
     */
    private data class CachedStreamUrl(
        val url: String,
        val expiresAt: Long,
        val headers: Map<String, String>,
        /** Carried through to [maybeSendPlaybackTelemetry] - see its own doc. */
        val cpn: String,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        /** Name of the client this stream came from - read in onPlayerError to tell
         *  YTPlayerUtils a WEB_REMIX stream specifically was what just got rejected. */
        val clientName: String,
    )

    /**
     * mediaId -> its cached stream URL. Lives for the player's lifetime (was a local inside
     * createDataSourceFactory() before - fine for the resolver itself, but onPlayerError needs to
     * be able to invalidate an entry after a bad HTTP status, which it can't do to a variable it
     * has no reference to).
     */
    private val songUrlCache = HashMap<String, CachedStreamUrl>()

    /**
     * MediaIds already given one automatic retry - cache entry dropped, player re-prepared - after
     * a source/HTTP error this process lifetime. A bad HTTP status (ExoPlayer's 2004, almost
     * always a 403 from an expired or otherwise rejected stream URL) is frequently just a stale
     * URL rather than a genuinely unplayable video: re-resolving from scratch, possibly landing on
     * a different fallback client than the one that just failed, can well succeed where the cached
     * URL didn't. Capped at one attempt per song so a video that's actually broken/removed doesn't
     * retry forever and instead falls through to the normal skip/stop handling.
     */
    private val retriedAfterSourceError = mutableSetOf<String>()

    /**
     * mediaId of the next song already precached (or currently being precached) for the playback
     * in progress, so [precacheNextSongStream] doesn't redo it on every tick while the lead window
     * is open. Reset on every track transition.
     */
    private var precachedForMediaId: String? = null

    /**
     * mediaId of the song [maybeSendPlaybackTelemetry] last fired its ping sequence for, so a
     * mid-song URL refresh (or a precached entry being reused) doesn't refire it - only the first
     * time a song's stream is actually handed to the player counts as "playback started". Reset on
     * every track transition.
     */
    private var telemetrySentForMediaId: String? = null

    // Current song plus upcoming songs to fetch lyrics for, read synchronously from the player on each
    // track transition. A data class so the StateFlow deduplicates identical updates.
    private data class LyricsFetchTargets(
        val current: MediaMetadata?,
        val upcoming: List<MediaMetadata>,
    )

    private val lyricsFetchTargets = MutableStateFlow(LyricsFetchTargets(null, emptyList()))

    override fun onCreate() {
        if (SERVICE_DEBUG) Log.i(TAG, "Starting MusicService")
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), !dataStore.get(IgnoreAudioFocusKey, false)
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                // listeners
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                sleepTimer.onFinish = { this@MusicService.pauseAllPlayersAndStopSelf() }
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

                // misc
                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
            }

        EqualizerSettings.parse(dataStore.get(EqualizerSettingsKey, ""))
            .onSuccess { equalizerAudioProcessor.setSettings(it) }

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }

        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // TODO: do i even want to have smaller art for media notification
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        currentSong.collect(scope) {
            updateNotification()
        }

        // The one and only writer of Discord presence.
        //
        // It reads what to publish from the player at the moment it publishes, rather than being
        // handed a song by whoever triggered it. That matters because currentSong lags the player:
        // it is database.song(id), so on a skip it still holds the *previous* track for as long as
        // the new row takes to read. The old code trusted that value and published the outgoing
        // song against the incoming one's timestamps.
        //
        // Nothing is silently dropped here either. The previous version required
        // playbackState == STATE_READY, but a track change usually spends its first moment
        // BUFFERING, so the update was discarded with nothing left to retry it - which is exactly
        // the state where the presence froze until you rewound.
        discordUpdateRequests.debounce(700).collectLatest(scope) {
            val rpc = discordRpc ?: return@collectLatest
            // playWhenReady, not isPlaying: isPlaying also goes false while a track buffers, and
            // treating that as a pause made the card disappear on every skip.
            val paused = !player.playWhenReady
            val mediaId = player.currentMediaItem?.mediaId
            // Nothing loaded, or the song is paused, is the same "nothing to show" case now - no
            // paused card, just no presence, the same way it disappears when playback stops
            // entirely.
            if (mediaId == null || paused) {
                rpc.stopActivity()
                return@collectLatest
            }
            // Wait for the row instead of giving up on it. A freshly queued track may not be in
            // the database yet; that used to mean no presence at all for that song.
            val song = withTimeoutOrNull(DISCORD_SONG_ROW_TIMEOUT_MS) {
                database.song(mediaId).first { it != null }
            } ?: return@collectLatest
            // The wait above can outlast the track it was for.
            if (player.currentMediaItem?.mediaId != mediaId) return@collectLatest
            rpc.updateSong(song, player.currentPosition)
        }

        // Track changes that the player does not announce as an event still move the metadata.
        currentMediaMetadata
            .map { it?.id }
            .distinctUntilChanged()
            .collect(scope) { requestDiscordUpdate() }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@MusicService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )

        // lateinit tasks
        offloadScope.launch {
            if (SERVICE_DEBUG) Log.i(TAG, "Launching MusicService offloadScope tasks")
            if (!qbInit.value) {
                initQueue()
            }

            combine(playerVolume, normalizeFactor, sleepTimer.fadeFactor) { playerVolume, normalizeFactor, fadeFactor ->
                playerVolume * normalizeFactor * fadeFactor
            }.collectLatest(scope) {
                withContext(Dispatchers.Main) {
                    player.volume = it
                }
            }

            playerVolume.debounce(1000).collect(scope) { volume ->
                dataStore.edit { settings ->
                    settings[PlayerVolumeKey] = volume
                }
            }

            // Rebuild the RPC client whenever the token or the enable switch changes, and push the
            // current song immediately so the presence isn't blank until the next track.
            dataStore.data
                .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] != false) }
                .debounce(300)
                .distinctUntilChanged()
                .collect(scope) { (token, enabled) ->
                    if (discordRpc?.isRpcRunning() == true) {
                        discordRpc?.closeRPC()
                    }
                    discordRpc = null
                    if (!token.isNullOrEmpty() && enabled) {
                        discordRpc = DiscordRPC(this@MusicService, token)
                        // Let the single writer decide what to publish, so turning the switch back
                        // on behaves exactly like a track change rather than being its own path
                        // with its own guards.
                        requestDiscordUpdate()
                    }
                }

            dataStore.data
                .map { it[SkipSilenceKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                        player.skipSilenceEnabled = it
                    }
                }

            dataStore.data
                .map { it[IgnoreAudioFocusKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) { ignoreAudioFocus ->
                    withContext(Dispatchers.Main) {
                        player.setAudioAttributes(player.audioAttributes, !ignoreAudioFocus)
                    }
                }

            combine(
                currentFormat,
                dataStore.data
                    .map { it[AudioNormalizationKey] ?: true }
                    .distinctUntilChanged()
            ) { format, normalizeAudio ->
                format to normalizeAudio
            }.collectLatest(scope) { (format, normalizeAudio) ->
                normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    1f
                }
            }

            // Fetch lyrics for the current song and prefetch the upcoming ones in a single coroutine so
            // they never run concurrently: the current fetch (only while lyrics display is on) completes
            // before prefetch starts. collectLatest restarts on a song change or a lyrics-display toggle,
            // cancelling any fetch in flight; already-fetched songs are skipped by the DB check.
            combine(
                dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
                lyricsFetchTargets
            ) { showLyrics, targets -> showLyrics to targets }
                .collectLatest(offloadScope) { (showLyrics, targets) ->
                    val current = targets.current
                    if (showLyrics && current != null && lyricsHelper.shouldFetch(current.id)) {
                        lyricsHelper.fetchAndStoreRemote(current, LyricsFetchRole.CURRENT)
                    }
                    // Read prefetch settings and connectivity after the current-song fetch so their changes
                    // do not cancel it. Changes take effect on the next lyricsFetchTargets emission.
                    if (dataStore.get(EnableLyricsPrefetchKey, true) && isNetworkConnected.value) {
                        val count = dataStore.get(LyricsPrefetchCountKey, 3)
                        targets.upcoming.take(count).forEach { mediaMetadata ->
                            if (!currentCoroutineContext().isActive) return@forEach
                            if (lyricsHelper.shouldFetch(mediaMetadata.id)) {
                                lyricsHelper.fetchAndStoreRemote(mediaMetadata, LyricsFetchRole.PREFETCH)
                            }
                        }
                    }
                }

            // Precache the next song's stream shortly before the current one ends, instead of
            // resolving it at the track transition (see precacheNextSongStream() for why this is
            // timed rather than just delayed by a fixed amount). A plain poll rather than a
            // position-change listener: this only needs roughly-once-a-few-seconds resolution, not
            // every frame, and a listener would fire far more often than it needs to for this.
            offloadScope.launch {
                while (isActive) {
                    delay(5000)
                    if (!dataStore.get(EnableStreamPrecacheKey, true)) continue
                    val (isPlaying, duration, position) = withContext(Dispatchers.Main) {
                        Triple(player.isPlaying, player.duration, player.currentPosition)
                    }
                    if (isPlaying && duration > 0 && duration - position in 0..PRECACHE_LEAD_MS) {
                        precacheNextSongStream()
                    }
                }
            }


            // network connectivity
            try {
                connectivityObserver.unregister()
            } catch (e: UninitializedPropertyAccessException) {
                // lol
            }
            connectivityObserver = NetworkConnectivityObserver(this@MusicService)

            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected && waitingForNetworkConnection.value) {
                        waitingForNetworkConnection.value = false
                        withContext(Dispatchers.Main) {
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
        }
    }


    /**
     * Fires the playback-start telemetry sequence (see [YouTube.initPlayback]) the first time
     * [cached]'s song is actually handed to the player - whether that's a fresh resolve or a
     * precached/cached entry being reused - but never again for the same song after that, since a
     * mid-song URL refresh or repeated chunk requests replaying the cache aren't a new playback.
     * Fire-and-forget on [offloadScope]: this is telemetry, not something playback waits on.
     */
    private fun maybeSendPlaybackTelemetry(mediaId: String, cached: CachedStreamUrl) {
        if (mediaId == telemetrySentForMediaId) return
        telemetrySentForMediaId = mediaId
        offloadScope.launch {
            YouTube.initPlayback(null, cached.playbackTracking, cached.cpn)
        }
    }

    /**
     * Resolves and caches the next song's stream, timed by the poller in the init block to land
     * inside [STREAM_URL_TRUST_WINDOW_MS] of when it'll actually be needed. Best-effort: any
     * failure here just leaves nothing cached, so createDataSourceFactory()'s own resolve-on-
     * transition path runs at that point exactly as if this had never fired.
     */
    private suspend fun precacheNextSongStream() {
        val nextMediaId = withContext(Dispatchers.Main) {
            player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
                ?.let { player.getMediaItemAt(it) }
                ?.mediaId
        } ?: return
        if (nextMediaId == precachedForMediaId) return
        if (songUrlCache[nextMediaId]?.expiresAt?.let { it > System.currentTimeMillis() } == true) return

        var song = queueBoard.value.getCurrentQueue()?.findSong(nextMediaId)
        if (song == null) {
            song = database.song(nextMediaId).first()?.toMediaMetadata()
        }
        if (song?.localPath != null) return // local/downloaded song, nothing to precache
        if (downloadCache.isCached(nextMediaId, 0, 1) || playerCache.isCached(nextMediaId, 0, CHUNK_LENGTH)) return

        precachedForMediaId = nextMediaId
        runCatching {
            val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
            val playbackData = YTPlayerUtils.playerResponseForPlayback(
                nextMediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            ).getOrThrow()
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = nextMediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength ?: 10000000,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }

            songUrlCache[nextMediaId] = CachedStreamUrl(
                url = playbackData.streamUrl,
                expiresAt = System.currentTimeMillis() + minOf(playbackData.streamExpiresInSeconds * 1000L, STREAM_URL_TRUST_WINDOW_MS),
                headers = playbackData.streamHeaders,
                cpn = playbackData.cpn,
                playbackTracking = playbackData.playbackTracking,
                clientName = playbackData.clientName,
            )
            // Telemetry deliberately NOT fired here - this runs up to PRECACHE_LEAD_MS before the
            // song actually starts, and the ping sequence's timing only makes sense measured from
            // real playback start. createDataSourceFactory() fires it once this cached entry is
            // actually handed to the player.
            if (SERVICE_DEBUG) Log.d(TAG, "Precached stream for upcoming song: mediaId=$nextMediaId")
        }.onFailure {
            if (SERVICE_DEBUG) Log.w(TAG, "Precache failed for mediaId=$nextMediaId, will resolve normally on transition", it)
            // Not genuinely unplayable - just let the normal path retry it at the transition.
            precachedForMediaId = null
        }
    }


// Library functions

    private suspend fun recoverSong(mediaId: String, playbackData: YTPlayerUtils.PlaybackData? = null) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        ArtistCreditEnricher.enrich(database, mediaId)
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint = YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)

                if (!song.isLocal) {
                    syncUtils.likeSong(song)
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = player.currentMetadata ?: return
        playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
    }


// Queue

    /**
     * Play a queue.
     *
     * @param queue Queue to play.
     * @param playWhenReady
     * @param shouldResume Set to true for the player should resume playing at the current song's last save position or
     * false to start from the beginning.
     * @param replace Replace media items instead of the underlying logic
     * @param title Title override for the queue. If this value us unspecified, this method takes the value from queue.
     * If both are unspecified, the title will default to "Queue".
     */
    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shouldResume: Boolean = false,
        replace: Boolean = false,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        if (!qbInit.value) {
            runBlocking(Dispatchers.IO) {
                initQueue()
            }
        }

        var queueTitle = title
        queuePlaylistId = queue.playlistId
        var q: MultiQueueObject? = null
        val preloadItem = queue.preloadItem
        queueLoadScope.launch {
            if (SERVICE_DEBUG) Log.d(TAG, "playQueue: Resolving additional queue data...")
            try {
                if (preloadItem != null) {
                    q = queueBoard.value.addQueue(
                        queueTitle ?: "Radio\u2060temp",
                        listOf(preloadItem),
                        shuffled = queue.startShuffled,
                        replace = replace,
                        continuationEndpoint = null // fulfilled later on after initial status
                    )
                    queueBoard.value.setCurrQueue(q, true)
                }

                val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                // do not find a title if an override is provided
                if ((title == null) && initialStatus.title != null) {
                    queueTitle = initialStatus.title

                    if (preloadItem != null && q != null) {
                        queueBoard.value.renameQueue(q!!, queueTitle)
                    }
                }

                val items = ArrayList<MediaMetadata>()
                if (SERVICE_DEBUG) Log.d(TAG, "playQueue: Queue initial status item count: ${initialStatus.items.size}")
                if (!initialStatus.items.isEmpty()) {
                    if (preloadItem != null) {
                        items.add(preloadItem)
                        items.addAll(initialStatus.items.subList(1, initialStatus.items.size))
                    } else {
                        items.addAll(initialStatus.items)
                    }
                    val q = queueBoard.value.addQueue(
                        queueTitle ?: getString(R.string.queue),
                        items,
                        shuffled = queue.startShuffled,
                        startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0,
                        replace = replace || preloadItem != null,
                        continuationEndpoint = if (isRadio) items.takeLast(4).shuffled().first().id else null // yq?.getContinuationEndpoint()
                    )
                    queueBoard.value.setCurrQueue(q, shouldResume)
                }

                player.prepare()
                player.playWhenReady = playWhenReady
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                Toast.makeText(this@MusicService, "plr: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }

            if (SERVICE_DEBUG) Log.d(TAG, "playQueue: Queue additional data resolution complete")
        }
    }

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        scope.launch {
            if (!qbInit.value) {

                // when enqueuing next when player isn't active, play as a new song
                if (items.isNotEmpty()) {
                    playQueue(
                        ListQueue(
                            title = items.first().mediaMetadata.title.toString(),
                            items = items.mapNotNull { it.metadata }
                        )
                    )
                }
            } else {
                // enqueue next
                queueBoard.value.getCurrentQueue()?.let {
                    queueBoard.value.addSongsToQueue(it, player.currentMediaItemIndex + 1, items.mapNotNull { it.metadata })
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        queueBoard.value.enqueueEnd(items.mapNotNull { it.metadata })
    }

    fun triggerShuffle() {
        val oldIndex = player.currentMediaItemIndex
        queueBoard.value.setCurrQueuePosIndex(oldIndex)
        val currentQueue = queueBoard.value.getCurrentQueue() ?: return

        // shuffle and update player playlist
        if (!currentQueue.shuffled) {
            queueBoard.value.shuffleCurrent()
        } else {
            queueBoard.value.unShuffleCurrent()
        }
        queueBoard.value.setCurrQueue()

        updateNotification()
    }

    suspend fun initQueue() {
        if (SERVICE_DEBUG) Log.i(TAG, "+initQueue()")
        val persistQueue = dataStore.get(PersistentQueueKey, true)
        val maxQueues = dataStore.get(MaxQueuesKey, 19)
        if (persistQueue) {
            queueBoard.value = QueueBoard(this, queueBoard.value.masterQueues, database.readQueue().toMutableList(), maxQueues)
        } else {
            queueBoard.value = QueueBoard(this, queueBoard.value.masterQueues, maxQueues = maxQueues)
        }
        if (SERVICE_DEBUG) Log.d(TAG, "Queue with $maxQueues queue limit. Persist queue = $persistQueue. Queues loaded = ${queueBoard.value.masterQueues.size}")
        qbInit.value = true
        if (SERVICE_DEBUG) Log.i(TAG, "-initQueue()")
    }

    fun deInitQueue() {
        if (SERVICE_DEBUG) Log.i(TAG, "+deInitQueue()")
        val pos = player.currentPosition
        queueBoard.value.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            runBlocking(Dispatchers.IO) {
                saveQueueToDisk(pos)
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        if (SERVICE_DEBUG) Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long) {
        val data = queueBoard.value.getAllQueues()
        // The queue can be empty when the service is torn down before initQueue() finishes loading it.
        data.lastOrNull()?.let { it.lastSongPos = currentPosition }
        database.updateAllQueues(data)
    }


// Audio playback

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }

    private fun createCacheDataSource(): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient.Builder()
                                    .proxy(YouTube.proxy)
                                    // Belt-and-suspenders alongside the dataSpec-level chunk bound
                                    // in createDataSourceFactory(): if a single call somehow runs
                                    // longer than this regardless of that bound (a device/ExoPlayer
                                    // version where the chunk continuation doesn't kick in the way
                                    // expected, for instance), this forces OkHttp itself to abort
                                    // and reconnect before reaching googlevideo's own connection
                                    // cutoff (confirmed at roughly a minute), rather than depending
                                    // on a single mechanism to always work.
                                    .callTimeout(45, TimeUnit.SECONDS)
                                    .build()
                            )
                        )
                    )
                    .setCacheWriteDataSinkFactory(
                        HybridCacheDataSinkFactory(playerCache) { dataSpec ->
                            val isLocal = queueBoard.value.getCurrentQueue()?.findSong(dataSpec.key ?: "")?.isLocal == true
                            if (SERVICE_DEBUG) Log.d(TAG, "SONG CACHE: ${!isLocal}")
                            !isLocal
                        }
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            )
            .setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            if (SERVICE_DEBUG) Log.d(TAG, "PLAYING: song id = $mediaId")

            var song = queueBoard.value.getCurrentQueue()?.findSong(dataSpec.key ?: "")
            if (song == null) { // in the case of resumption, queueBoard may not be ready yet
                song = runBlocking { database.song(dataSpec.key).first()?.toMediaMetadata() }
            } else if (song.localPath == null) {
                // The queue holds its own MediaMetadata snapshot from whenever this song was
                // queued, and nothing invalidates it if a download finishes afterward - a null
                // localPath here means "wasn't downloaded when queued", not "isn't downloaded
                // now". Re-check the database once before falling through to streaming, so a
                // song that finished downloading mid-queue actually plays from disk instead of
                // silently re-streaming until the queue is rebuilt from scratch.
                val refreshedLocalPath = runBlocking { database.song(mediaId).first()?.song?.localPath }
                if (refreshedLocalPath != null) {
                    song = song.copy(localPath = refreshedLocalPath)
                }
            }
            // local song
            if (song?.localPath != null) {
                if (song.isLocal) {
                    if (SERVICE_DEBUG) Log.d(TAG, "PLAYING: local song")
                    val file = File(song.localPath)
                    if (!file.exists()) {
                        throw PlaybackException(
                            "File not found",
                            Throwable(),
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                        )
                    }

                    return@Factory dataSpec.withUri(file.toUri())
                } else {
                    val isDownloadNew = downloadUtil.localMgr.getFilePathIfExists(mediaId)
                    isDownloadNew?.let {
                        if (SERVICE_DEBUG) Log.d(TAG, "PLAYING: Custom downloaded song")
                        return@Factory dataSpec.withUri(it)
                    }
                }
            }

            val isDownload =
                downloadCache.isCached(mediaId, dataSpec.position, if (dataSpec.length >= 0) dataSpec.length else 1)
            val isCache = playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)
            if (isDownload || isCache) {
                if (SERVICE_DEBUG) Log.d(TAG, "PLAYING: remote song (cache = ${isCache}, download = ${isDownload})")
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                if (SERVICE_DEBUG) Log.d(TAG, "PLAYING: remote song (temp cache)")
                offloadScope.launch { recoverSong(mediaId) }
                maybeSendPlaybackTelemetry(mediaId, it)
                // Bounded the same as the fresh-resolve path below, and for the same reason: left
                // unbounded (as this was before), this is the request that turns into one
                // long-lived connection past whatever duration googlevideo's CDN cuts a stream at -
                // confirmed independently of any auth/URL-freshness issue, since a much larger
                // CHUNK_LENGTH still failed with the same source error at roughly the same wall-clock
                // point regardless of the extra bytes available. Every read has to keep reconnecting
                // under that ceiling, not just the very first one.
                return@Factory dataSpec.withUri(it.url.toUri())
                    .withRequestHeaders(it.headers)
                    .subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
            }

            if (SERVICE_DEBUG) Log.d(TAG, "PLAYING: remote song (online fetch)")

            // A cache entry existing (even an expired one) means this song was already resolved
            // once before - the aging streaming PoToken embedded in that URL is exactly what's
            // being worked around here, so force a fresh mint rather than risk PoTokenGenerator's
            // own session-level cache handing back the same one. Skipped for a genuinely first-time
            // resolve so the very first song of a session doesn't pay this cost for no reason.
            val isRefresh = songUrlCache.containsKey(mediaId)

            val playbackData = runBlocking(Dispatchers.IO) {
                if (isRefresh) {
                    YTPlayerUtils.invalidatePoTokenSession()
                }
                val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                )
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is ConnectException, is UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }
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
                        // YouTube omits Content-Length for some formats/streams; !! here crashed
                        // playback's own data source resolution outright for exactly those songs -
                        // intermittent and song-specific, not a steady failure. contentLength is
                        // stored metadata only (the actual fetch below uses a fixed CHUNK_LENGTH),
                        // so an unknown length is a reasonable placeholder, not a lie anything
                        // downstream depends on being exact.
                        contentLength = format.contentLength ?: 10000000,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    )
                )
            }
            offloadScope.launch { recoverSong(mediaId, playbackData) }

            val streamUrl = playbackData.streamUrl

            val cachedStreamUrl = CachedStreamUrl(
                url = streamUrl,
                // streamExpiresInSeconds is YouTube's own claimed validity (routinely hours), but
                // the embedded streaming PoToken has been observed to actually stop working after
                // roughly a minute regardless of connection count or chunk size (both were tightened
                // independently with zero effect on the failure point, which time-since-minting
                // explains and connection-duration doesn't). Capping our own trust well under that
                // forces the proactive refresh above - fresh PoToken included - before the CDN ever
                // gets a chance to reject the old one.
                expiresAt = System.currentTimeMillis() + minOf(playbackData.streamExpiresInSeconds * 1000L, STREAM_URL_TRUST_WINDOW_MS),
                headers = playbackData.streamHeaders,
                cpn = playbackData.cpn,
                playbackTracking = playbackData.playbackTracking,
                clientName = playbackData.clientName,
            )
            songUrlCache[mediaId] = cachedStreamUrl
            maybeSendPlaybackTelemetry(mediaId, cachedStreamUrl)
            dataSpec.withUri(streamUrl.toUri())
                .withRequestHeaders(playbackData.streamHeaders)
                .subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
        }
    }

    private fun createRenderersFactory(gaplessOffloadAllowed: Boolean): DefaultRenderersFactory {
        if (ENABLE_FFMETADATAEX) {
            return object : NextRenderersFactory(this@MusicService) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                arrayOf<AudioProcessor>(equalizerAudioProcessor),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(
                            MyAudioOffloadSupportProvider(
                                DefaultAudioOffloadSupportProvider(context),
                                !gaplessOffloadAllowed
                            )
                        )
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(audioDecoder)
        } else {
            return object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                arrayOf<AudioProcessor>(equalizerAudioProcessor),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(
                            MyAudioOffloadSupportProvider(
                                DefaultAudioOffloadSupportProvider(context),
                                !gaplessOffloadAllowed
                            )
                        )
                        .build()
                }
            }
        }
    }


// Misc

    fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(getString(if (queueBoard.value.getCurrentQueue()?.shuffled == true) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .setCustomIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off)
                    .build(),
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setCustomIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat_off
                            REPEAT_MODE_ONE -> R.drawable.repeat_one
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(if (currentSong.value?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build()
            )
        )
    }

    fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG).show()
    }

    /**
     * Rotates the YouTube session identity, then re-prepares and resumes whatever is currently
     * loaded so the rotation actually takes effect immediately rather than on the next thing the
     * person happens to play. Shared by the automatic source-error retry in [onPlayerError] and
     * [resetYouTubeSessionAndRetry].
     */
    private fun retryCurrentItemWithFreshIdentity() {
        scope.launch {
            withContext(Dispatchers.IO) {
                YTPlayerUtils.rotateSessionIdentity()
            }
            player.prepare()
            player.play()
        }
    }

    /**
     * Manual escape hatch backing the "Reset YouTube session" settings action. Unlike the
     * automatic retry in [onPlayerError] - which only evicts and retries the one track that just
     * failed - this clears every cached stream URL and every song already given its one automatic
     * retry, since a person reaching for a manual reset usually suspects the problem isn't limited
     * to a single song.
     */
    fun resetYouTubeSessionAndRetry() {
        songUrlCache.clear()
        retriedAfterSourceError.clear()
        retryCurrentItemWithFreshIdentity()
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()

            Toast.makeText(this@MusicService, getString(R.string.err_play_next_on_error), Toast.LENGTH_SHORT).show()
            return
        }

        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_too_many_errors), Toast.LENGTH_LONG).show()
        consecutivePlaybackErr = 0
    }

    fun stopOnError() {
        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG).show()
    }

    /**
     * Read the current song and the upcoming queue entries from the player and publish them for the
     * lyrics fetch coroutine. Called on each track transition; the player is read synchronously here
     * because onMediaItemTransition runs before currentMediaMetadata is updated in onEvents.
     */
    private fun updateLyricsFetchTargets() {
        val mediaItemCount = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until mediaItemCount) {
            lyricsFetchTargets.value = LyricsFetchTargets(null, emptyList())
            return
        }
        val current = player.getMediaItemAt(currentIndex).metadata
        val upcoming = ((currentIndex + 1) until mediaItemCount)
            .mapNotNull { player.getMediaItemAt(it).metadata }
        lyricsFetchTargets.value = LyricsFetchTargets(current, upcoming)
    }


// Player overrides

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        // wait for reconnection
        val isConnectionError = (error.cause?.cause is PlaybackException)
                && (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        // A bad HTTP status (2004, almost always a 403 YouTube handed back for a stream URL that
        // expired or was otherwise rejected) is frequently just a stale resolution, not a
        // genuinely unplayable video - re-resolving from scratch can land on a fresh URL, or on a
        // different fallback client than whichever one just failed. Drop the cached URL and
        // re-prepare once before falling through to skip/stop; see retriedAfterSourceError's own
        // doc for why this is capped at one attempt per song.
        //
        // Also rotates the YouTube session identity before retrying: a rejected URL and a
        // bot-detection-flagged identity produce the identical symptom from here, and re-resolving
        // under the *same* identity does nothing for the latter. Rotating is cheap enough to just
        // always do alongside the retry rather than trying to tell the two cases apart first.
        val isRetryableSourceError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val failedMediaId = player.currentMediaItem?.mediaId
        if (isRetryableSourceError && failedMediaId != null && retriedAfterSourceError.add(failedMediaId)) {
            // Recorded before the entry is dropped below - lets the retry's re-resolution skip
            // straight to a fallback client instead of repeating this same WEB_REMIX rejection.
            if (songUrlCache[failedMediaId]?.clientName == "WEB_REMIX") {
                YTPlayerUtils.markWebRemixStreamFailed(failedMediaId)
            }
            songUrlCache.remove(failedMediaId)
            if (SERVICE_DEBUG) Log.w(TAG, "source error (${error.errorCode}), retrying with a fresh URL and identity: mediaId=$failedMediaId")
            retryCurrentItemWithFreshIdentity()
            return
        }

        if (dataStore.get(SkipOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }

        Toast.makeText(
            this@MusicService,
            "plr: ${error.message} (${error.errorCode}): ${error.cause?.message ?: ""} ",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) {
            val pos = player.currentPosition
            val q = queueBoard.value.getCurrentQueue()
            q?.lastSongPos = pos
        }
        super.onIsPlayingChanged(isPlaying)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        // +2 when and error happens, and -1 when transition. Thus when error, number increments by 1, else doesn't change
        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }

        precachedForMediaId = null
        telemetrySentForMediaId = null
        updateLyricsFetchTargets()

        if (player.isPlaying && reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            player.prepare()
            player.play()
        }

        // Auto load more songs
        val q = queueBoard.value.getCurrentQueue()
        val songCount = q?.getSize() ?: -1
        val playlistId = q?.playlistId
        if (dataStore.get(AutoLoadMoreKey, true) &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            playlistId != null // aka "hasNext"
        ) {
            if (SERVICE_DEBUG) Log.d(TAG, "onMediaItemTransition: Triggering queue auto load more")
            scope.launch(SilentHandler) {
                val endpoint = playlistId // playlistId.substringBefore("\n")
                val continuation = null // playlistId.substringAfter("\n")
                val yq = YouTubeQueue(WatchEndpoint(endpoint, continuation))
                val mediaItems = yq.nextPage()
                q.playlistId = mediaItems.takeLast(4).shuffled().first().id // yq.getContinuationEndpoint()
                if (SERVICE_DEBUG) Log.d(TAG, "onMediaItemTransition: Got ${mediaItems.size} songs from radio")
                if (player.playbackState != STATE_IDLE && songCount > 1) { // initial radio loading is handled by playQueue()
                    queueBoard.value.enqueueEnd(mediaItems.drop(1))
                }
            }
        }

        queueBoard.value.setCurrQueuePosIndex(player.currentMediaItemIndex)

        // reshuffle queue when shuffle AND repeat all are enabled
        // no, when repeat mode is on, player does not "STATE_ENDED"
        if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
            (reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            player.shuffleModeEnabled && player.repeatMode == REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                // or else race condition: Assertions.checkArgument(eventTime.realtimeMs >= currentPlaybackStateStartTimeMs) fails in updatePlaybackState()
                delay(200)
                queueBoard.value.shuffleCurrent(player.mediaItemCount > 2)
                queueBoard.value.setCurrQueue()
            }
        }

        updateNotification() // also updates when queue changes
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == STATE_IDLE) {
            queuePlaylistId = null
        }
    }


    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
                if (!player.playWhenReady) {
                    waitingForNetworkConnection.value = false
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }

        // Anything that can change what should be on the card asks for an update; the collector in
        // onCreate decides what that update contains. Asking too often is free - requests
        // collapse - whereas missing one leaves a stale presence with nothing to correct it, which
        // is the failure this replaced.
        if (events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                EVENT_MEDIA_ITEM_TRANSITION,
                EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            requestDiscordUpdate()
        }
    }

    override fun onPlaybackStatsReady(eventTime: AnalyticsListener.EventTime, playbackStats: PlaybackStats) {
        offloadScope.launch {
            val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            var minPlaybackDur = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100)
            // ensure within bounds
            if (minPlaybackDur >= 1f) {
                minPlaybackDur = 0.99f // Ehhh 99 is good enough to avoid any rounding errors
            } else if (minPlaybackDur < 0.01f) {
                minPlaybackDur = 0.01f // Still want "spam skipping" to not count as plays
            }

            val playRatio =
                playbackStats.totalPlayTimeMs.toFloat() / ((mediaItem.metadata?.duration?.times(1000)) ?: -1)
            if (SERVICE_DEBUG) Log.d(TAG, "Playback ratio: $playRatio Min threshold: $minPlaybackDur")
            if (playRatio >= minPlaybackDur && !dataStore.get(PauseListenHistoryKey, false)) {
                database.query {
                    incrementPlayCount(mediaItem.mediaId)
                    try {
                        insert(
                            Event(
                                songId = mediaItem.mediaId,
                                timestamp = LocalDateTime.now(),
                                playTime = playbackStats.totalPlayTimeMs
                            )
                        )
                    } catch (_: SQLException) {
                    }
                }

                // TODO: support playlist id
                val ytHist = mediaItem.metadata?.isLocal != true && !dataStore.get(PauseRemoteListenHistoryKey, false)
                if (SERVICE_DEBUG) Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    val metaResult = YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                    val response = metaResult.getOrNull()
                    if (SERVICE_DEBUG) Log.d(
                        TAG,
                        "History meta: success=${metaResult.isSuccess}" +
                            (metaResult.exceptionOrNull()?.let { " err=${it.javaClass.simpleName}:${it.message}" } ?: "") +
                            " playability=${response?.playabilityStatus?.status}/${response?.playabilityStatus?.reason}" +
                            " hasTracking=${response?.playbackTracking != null}" +
                            " hasVideostats=${response?.playbackTracking?.videostatsPlaybackUrl != null}" +
                            " loggedIn=${YouTube.cookie != null}"
                    )
                    val playbackUrl = response?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    if (SERVICE_DEBUG) Log.d(TAG, "Got playback url: $playbackUrl")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.value.getCurrentQueue()
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        if (q == null || q.shuffled == shuffleModeEnabled) return
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // FG keep alive
        if (player.isPlaying || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    fun startSleepTimer(minute: Int, fadeEnabled: Boolean, fadeDurationSeconds: Int) {
        sleepTimer.fadeEnabled = fadeEnabled
        sleepTimer.fadeDurationMs = fadeDurationSeconds * 1000L
        sleepTimer.start(minute)
    }

    override fun onDestroy() {
        if (SERVICE_DEBUG) Log.i(TAG, "Terminating MusicService.")
        serviceJob.cancel()
        // Unregister before deInitQueue: cancelling the collector leaves the ConnectivityManager
        // callback registered. isInitialized guards a teardown before onCreate's async init assigned it.
        if (::connectivityObserver.isInitialized) {
            connectivityObserver.unregister()
        }
        // deInitQueue reads player.currentPosition, so it must run before the player is released.
        deInitQueue()

        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null

        mediaSession.player.stop()
        mediaSession.release()
        mediaSession.player.release()
        super.onDestroy()
        if (SERVICE_DEBUG) Log.i(TAG, "Terminated MusicService.")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (SERVICE_DEBUG) Log.i(TAG, "onTaskRemoved called")
        if (dataStore.get(StopMusicOnTaskClearKey, true) && !dataStore.get(KeepAliveKey, false)) {
            if (SERVICE_DEBUG) Log.i(TAG, "onTaskRemoved kill")
            pauseAllPlayersAndStopSelf()
        } else {
            if (SERVICE_DEBUG) Log.i(TAG, "onTaskRemoved def")
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        /** How long to wait for a just-queued track's database row before giving up on it. */
        private const val DISCORD_SONG_ROW_TIMEOUT_MS = 5_000L

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        // Bounds every stream request (see createDataSourceFactory()) so no single one can still be
        // mid-transfer when the cached URL's credentials go stale - see songUrlCache's 40s trust cap
        // for why that matters. Comfortably covers even a low-bitrate song within that window; a
        // higher-bitrate one just means more frequent, still-small requests.
        const val CHUNK_LENGTH = 256 * 1024L

        // How long a freshly-resolved stream URL is trusted for before a proactive refresh is
        // forced - see songUrlCache's own doc for why 40s. Precaching (see precacheNextSongStream())
        // fires this many ms before the current song ends, comfortably inside that window so the
        // precached URL is still fresh when playback actually reaches the next song, with a few
        // seconds of margin for the resolve itself to complete.
        const val STREAM_URL_TRUST_WINDOW_MS = 40_000L
        const val PRECACHE_LEAD_MS = 35_000L

        const val COMMAND_GET_BINDER = "GET_BINDER"
    }
}

class MyAudioOffloadSupportProvider(
    private val default: DefaultAudioOffloadSupportProvider,
    private val disableGaplessOffload: Boolean
) : DefaultAudioSink.AudioOffloadSupportProvider by default {
    override fun getAudioOffloadSupport(
        format: Format,
        audioAttributes: AudioAttributes
    ): AudioOffloadSupport {
        val defaultResult = default.getAudioOffloadSupport(format, audioAttributes)
        val audioOffloadSupport = AudioOffloadSupport.Builder()
        return audioOffloadSupport
            .setIsFormatSupported(defaultResult.isFormatSupported)
            .setIsGaplessSupported(defaultResult.isGaplessSupported && !disableGaplessOffload)
            .setIsSpeedChangeSupported(defaultResult.isSpeedChangeSupported)
            .build()
    }
}
