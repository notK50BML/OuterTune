/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.pages.HomePage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRail
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop client: a content area, a queue beside it, and a player bar along the bottom.
 *
 * Laid out the way the Android app is - the player is always present rather than appearing only
 * once something is playing. An earlier version hid the bar while idle, which meant a fresh window
 * had no player in it at all and no way to reach the full-screen one; a music player that only looks
 * like a music player after you have already started a song is the wrong way round.
 *
 * The Android screens themselves could not be recompiled here: they reach for Hilt, Room, DataStore
 * and MediaSession through composition locals, none of which exist off Android. So this is the same
 * shape rebuilt on the desktop plumbing, not a port of that code.
 *
 * Deliberately missing, so it is not mistaken for finished: no real database (the library is a text
 * file - see [LibraryStore]) and no playlists. A song is fetched in full before it starts, which
 * costs a second or two on a big track and buys two things: no network remains to fail once audio
 * starts, so a mid-song 403 cannot happen, and seeking is arithmetic rather than a new request.
 */
fun main() = application {
    // Set here because it needs no network call, and search will not work without it - YouTube
    // answers 400 INVALID_ARGUMENT, which reads like a platform problem and is not one.
    // The content language and country come from settings once the library is open - see below.
    remember { YouTube.locale = YouTubeLocale(gl = "US", hl = "en") }

    val player = remember { DesktopPlayer() }
    // Built here rather than inside App, because the theme is chosen above App and needs the stored
    // settings to do it. One instance either way - App only ever remembered them once.
    val library = remember { LibraryStore() }
    // Writes a stack dump beside the library if the window ever stops responding - see the class.
    remember { FreezeWatchdog.start(defaultDataDirectory()) }
    // And a crash-*.txt if something goes uncaught, so a vanished window leaves a trace.
    remember { CrashLog.install(defaultDataDirectory()) }
    // The modern Windows picker compiles once, in the background, long before anyone clicks.
    remember { FilePicker.warmUp() }
    val settings = remember(library) { Settings(library.settings) }
    // Before anything can be played, so a stored curve is in effect from the first note rather
    // than from whenever the equaliser drawer is first opened.
    remember(settings) {
        settings.equalizerStore().applyTo(player.equalizer, player.compressor, player.balance)
    }

    val windowState = rememberWindowState(width = 1000.dp, height = 700.dp)
    val useNativeFrame = remember { System.getProperty("os.name").orEmpty().startsWith("Windows") }
    Window(
        onCloseRequest = { player.stop(); exitApplication() },
        title = "OuterTune",
        // No Windows title bar: the app runs to the top edge and draws its own buttons. On Windows
        // the window keeps its real frame - shadow, snap, resize, animations - with only the caption
        // taken off (NativeFrame); elsewhere, or if that cannot be done, it is borderless instead.
        undecorated = !useNativeFrame,
        // The phone's launcher icon, for the title bar and the taskbar.
        icon = remember {
            androidx.compose.ui.graphics.painter.BitmapPainter(
                androidx.compose.ui.res.useResource("icon.png") { androidx.compose.ui.res.loadImageBitmap(it) }
            )
        },
        state = windowState,
        // Every key nothing focused has used - see KeyRouter and KeyBindings.
        onKeyEvent = { KeyRouter.handle(it) },
    ) {
        LaunchedEffect(Unit) {
            if (useNativeFrame) NativeFrame.install(window) else WindowCorners.round(window)
        }
        // A real frame that could not be hooked keeps Windows' own title bar, so draws no second one.
        val customChrome = !useNativeFrame || NativeFrame.active
        // Pushed up from App, which is where the playing song is known. A plain state rather than a
        // flow: it changes once per track and is read once per frame.
        var seed by remember { mutableStateOf<Color?>(null) }
        // The cover's second colour, when it has one worth using - see secondaryHueSeed.
        var accentSeed by remember { mutableStateOf<Color?>(null) }

        val dark = when (settings.themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        // Eased rather than cut - see DynamicTheme.animated for why that is worth doing.
        val scheme = DynamicTheme.animated(
            DynamicTheme.schemeFor(
                seed.takeIf { settings.dynamicTheme },
                dark,
                accentSeed = accentSeed.takeIf { settings.dynamicTheme },
            )
        )

        // True black where it was asked for and only in dark mode - a black light theme is not a
        // theme. Surfaces go black too, or the panels would sit as grey slabs on it.
        val finalScheme = if (dark && settings.pureBlack) {
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0A0A0A),
                surfaceContainer = Color(0xFF111111),
            )
        } else {
            scheme
        }
        val typography = remember(settings.fontFamily) {
            SystemFonts.typography(SystemFonts.family(settings.fontFamily))
        }
        val density = LocalDensity.current
        val scaledDensity = remember(density, settings.textScale) {
            Density(density.density, density.fontScale * settings.textScale / 100f)
        }

        CompositionLocalProvider(LocalDensity provides scaledDensity) {
        // Windows' media controls, once the window exists to attach them to.
        LaunchedEffect(Unit) { WindowsMediaControls.start(window) }

        MaterialTheme(colorScheme = finalScheme, typography = typography) {
            val captionTint = remember { CaptionTint() }
            CompositionLocalProvider(
                LocalCaptionInset provides (if (customChrome) WindowChrome.height else 0.dp),
                LocalCaptionTint provides captionTint,
            ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                App(
            player, library, settings,
            onSeedChanged = { seed = it },
            onAccentSeedChanged = { accentSeed = it },
        )
                    // Over everything, so it stays reachable whatever is open.
                    if (customChrome) CaptionBar(
                        state = windowState,
                        defaultTint = MaterialTheme.colorScheme.onSurface,
                        onClose = { player.stop(); exitApplication() },
                    )
                }
            }
            }
        }
        }
    }
}

@Composable
internal fun App(
    player: DesktopPlayer,
    library: LibraryStore,
    settings: Settings,
    onSeedChanged: (Color?) -> Unit,
    onAccentSeedChanged: (Color?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val account = remember { Account(library.database) }
    val eqStore = remember(settings) { settings.equalizerStore() }
    // Only while history is switched on. The setting existed and was never consulted, so turning it
    // off changed nothing.
    remember(library) { ArtistImages.library = library }
    // Where a restored queue's song was when the last session ended - see the persistent queue.
    var restoredPositionMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(settings.contentLanguage, settings.contentCountry) {
        YouTube.locale = YouTubeLocale(gl = settings.contentCountry, hl = settings.contentLanguage)
    }
    LaunchedEffect(settings.audioNormalisation) { player.normalise = settings.audioNormalisation }
    val playerQueue = remember {
        // Counted below, once enough of the song has been heard - not here, at the start.
        PlayerQueue(player, scope, onPlayed = {})
    }

    val playback by player.state.collectAsState()
    val queue by playerQueue.state.collectAsState()
    val savedQueues by playerQueue.saved.collectAsState()
    LaunchedEffect(settings.maxQueues) { playerQueue.maxSaved = settings.maxQueues }
    // The queues kept aside last time, back as they were; written again whenever they change.
    LaunchedEffect(Unit) {
        playerQueue.restoreSaved(QueueSnapshot.decodeAll(settings.savedQueues))
        playerQueue.saved.collect { settings.savedQueues = QueueSnapshot.encodeAll(it) }
    }
    val recent by library.recentlyPlayed.collectAsState()
    val liked by library.liked.collectAsState()
    // Held as state but never read here. Reading it in this function made the whole window
    // recompose on every playback tick - dozens of times a second - for the sake of a seek
    // bar and a clock. It is handed down as a provider and read only where it is shown.
    val positionState = player.positionMs.collectAsState()
    val duration by player.durationMs.collectAsState()

    var query by remember { mutableStateOf("") }
    // What was last searched for; null when no search is showing.
    var searchedFor by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    var showFullPlayer by remember { mutableStateOf(false) }
    // A playlist or album downloading in the background - see BatchDownload.
    var batch by remember { mutableStateOf<BatchDownload?>(null) }
    var batchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null)}
    // Something for the full player to do as it appears - a shortcut's equaliser, lyrics or queue.
    var playerCommand by remember { mutableStateOf<PlayerCommand?>(null) }
    // Bumped by the search shortcut; the search box takes focus each time it changes.
    var focusSearch by remember { mutableStateOf(0) }

    // The home feed. Reloaded when the account changes, because signed out it is a prompt to sign in
    // and signed in it is a different page entirely - not a variation on the same one.
    val homeFeed = remember { HomeFeed() }
    val accountState by account.state.collectAsState()
    var home by remember { mutableStateOf<HomeState>(HomeState.Loading) }
    // Bumped to ask again. Without it a feed that failed once - opened on a train, or while the
    // network was still coming up - stayed failed until the app was restarted or the account
    // changed, which is a long way to go for a transient error.
    var homeAttempt by remember { mutableStateOf(0) }

    // The playlists this account saved on YouTube. Fetched alongside the feed and on the same
    // trigger, since both are answers to "who is signed in" and neither means anything without one.
    var savedPlaylists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    LaunchedEffect(accountState, homeAttempt) {
        savedPlaylists = if (accountState is AccountState.SignedIn) loadSavedPlaylists() else emptyList()
    }
    // A mood chip, when one is lit. The chips themselves come from the unfiltered feed and are kept
    // while a filtered one is showing, since a filtered page does not list them again.
    var homeChip by remember { mutableStateOf<HomePage.Chip?>(null) }
    var homeLoadingMore by remember { mutableStateOf(false) }
    var homeChips by remember { mutableStateOf<List<HomePage.Chip>>(emptyList()) }
    LaunchedEffect(accountState, homeAttempt, homeChip) {
        home = HomeState.Loading
        home = homeFeed.load(signedIn = accountState is AccountState.SignedIn, chip = homeChip)
        (home as? HomeState.Ready)?.chips?.takeIf { it.isNotEmpty() }?.let { homeChips = it }
    }

    // The two local rows the phone builds from play counts. Recomputed when history or likes change,
    // off the main thread since both are queries. Shuffled by a seed that changes once a day, so the
    // rows are fresh daily but do not reshuffle under the pointer every time a song finishes.
    var quickPicks by remember { mutableStateOf<List<StoredSong>>(emptyList()) }
    var forgotten by remember { mutableStateOf<List<StoredSong>>(emptyList()) }
    LaunchedEffect(recent, liked) {
        val day = System.currentTimeMillis() / 86_400_000L
        val (picks, old) = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val lately = library.topSongs(now - 30L * 86_400_000L, 40).map { it.first }
            val picks = (lately + liked.shuffled(kotlin.random.Random(day)))
                .distinctBy { it.id }
                .take(20)
            val heardRecently = (library.topSongs(now - 14L * 86_400_000L, 500).map { it.first.id } +
                recent.take(30).map { it.id }).toSet()
            val old = liked.filterNot { it.id in heardRecently }
                .shuffled(kotlin.random.Random(day + 1))
                .take(20)
            picks to old
        }
        quickPicks = picks
        forgotten = old
    }

    // The colour the whole window is themed from. Sampled from the cover of what is playing, which
    // is the desktop's nearest thing to Android seeding its scheme from the wallpaper.
    val (artworkPrimary, _) = rememberArtworkColours(queue.current?.thumbnail)
    LaunchedEffect(artworkPrimary) { onSeedChanged(artworkPrimary) }
    val artworkAccent = rememberArtworkAccent(queue.current?.thumbnail)
    LaunchedEffect(artworkAccent) { onAccentSeedChanged(artworkAccent) }

    val playlists by library.playlists.collectAsState()
    // Which section the navigation rail has selected. One value rather than a flag per pane, so two
    // panes can never both think they are showing.
    var tab by remember { mutableStateOf(settings.startTab) }
    // A tab switched off while it is open hands over to the first one still on.
    LaunchedEffect(settings.tabs) {
        val tabs = NavTab.decode(settings.tabs)
        if (tab != NavTab.Settings && tab !in tabs) tab = tabs.first()
    }
    var openArtist by remember { mutableStateOf<StoredArtist?>(null) }

    // The album being looked at, by browse id, and whatever has been loaded for it. Two states
    // rather than one: the id is what navigation sets, and the page is what arrives afterwards.
    var openAlbumId by remember { mutableStateOf<String?>(null) }
    var album by remember { mutableStateOf<AlbumState>(AlbumState.Loading) }
    // A YouTube playlist being looked at - saved ones from the Playlists section, or a card on Home.
    var openOnlinePlaylist by remember { mutableStateOf<PlaylistItem?>(null) }
    var onlinePlaylist by remember { mutableStateOf<OnlinePlaylistState>(OnlinePlaylistState.Loading) }
    LaunchedEffect(openOnlinePlaylist?.id) {
        val id = openOnlinePlaylist?.id ?: return@LaunchedEffect
        onlinePlaylist = OnlinePlaylistState.Loading
        onlinePlaylist = loadOnlinePlaylist(id)
    }
    LaunchedEffect(openAlbumId) {
        val id = openAlbumId ?: return@LaunchedEffect
        album = AlbumState.Loading
        album = loadAlbum(id)
    }

    // Opening an artist closes the player, because the artist page is where the user is going and
    // the full-window player would otherwise cover it entirely.
    val openArtistPage = { artist: StoredArtist ->
        openArtist = artist
        openAlbumId = null
        showFullPlayer = false
    }
    val openAlbumPage = { browseId: String ->
        openAlbumId = browseId
        openArtist = null
        showFullPlayer = false
    }
    // Handed over once. The player resolves audio, so it is the thing that knows how to fill the
    // cache, and it consults it before reaching for the network.
    LaunchedEffect(library) { player.downloads = library.downloads }

    // Discord rich presence. One instance for the window's lifetime, same client the phone app
    // uses. Rebuilt whenever the stored token or the enable switch changes, and re-sent whenever
    // the song or the playing/paused state does - see DiscordPresence for why those are the only
    // two triggers, rather than anything tied to positionMs ticking every frame.
    val discordPresence = remember { DiscordPresence(scope) }
    DisposableEffect(discordPresence) {
        discordPresence.start()
        onDispose { discordPresence.shutdown() }
    }
    LaunchedEffect(settings.discordToken, settings.enableDiscordRpc) {
        discordPresence.updateAuth(
            settings.discordToken,
            settings.enableDiscordRpc,
            DiscordNowPlaying(queue.current, playback !is PlaybackState.Playing, player.positionMs.value),
        )
    }
    LaunchedEffect(queue.current?.id, playback) {
        discordPresence.requestUpdate(
            DiscordNowPlaying(queue.current, playback !is PlaybackState.Playing, player.positionMs.value)
        )
    }

    // Listen Together. One instance for the window's lifetime, same protocol the phone speaks -
    // see ListenTogetherManager for why the scope has to be Swing-dispatched. The bridge is attached
    // once player, playerQueue and library all exist, and detached on dispose so a session cannot
    // outlive the window trying to touch a player that is gone.
    val listenTogether = remember { ListenTogetherManager() }
    DisposableEffect(listenTogether) {
        listenTogether.attachPlayer(DesktopPlaybackBridge(player, playerQueue, library))
        onDispose { listenTogether.attachPlayer(null) }
    }
    LaunchedEffect(settings.listenTogetherOffsetMs) {
        listenTogether.offsetMs = settings.listenTogetherOffsetMs.toLong()
    }

    // What the download button shows. Kept here rather than read from disk on every recomposition:
    // "is this downloaded" is a file check, and doing one per frame to colour a button is wasteful.
    // Filled in off the main thread rather than read during composition: ids() is a database query
    // plus a file check per row, and composition is not the place for either.
    var downloadedIds by remember(library) { mutableStateOf(emptySet<String>()) }
    /**
     * Downloads [songs] one after another, skipping any already saved, and puts each in the
     * library first so it shows under Downloaded. One list at a time; cancelling stops after the
     * song in flight. Sequential on purpose: several whole songs at once would each be slower, and
     * the one being listened to would be competing with all of them.
     */
    val downloadAll: (String, List<SongItem>) -> Unit = downloadAll@{ title, songs ->
        if (batch?.finished == false) return@downloadAll
        val todo = songs.distinctBy { it.id }.filter { it.id !in downloadedIds && !LocalMusic.isLocal(it.id) }
        if (todo.isEmpty()) return@downloadAll
        batch = BatchDownload(title, todo.size)
        batchJob = scope.launch {
            var done = 0
            var failed = 0
            for (song in todo) {
                withContext(Dispatchers.IO) { library.database.upsertSong(song.toStored(), (song.duration ?: 0) * 1000L) }
                val result = player.download(song.id)
                if (result.isSuccess) done++ else failed++
                batch = BatchDownload(title, todo.size, done + failed, failed)
                downloadedIds = withContext(Dispatchers.IO) { library.downloads.ids().toSet() }
            }
            batch = BatchDownload(title, todo.size, done + failed, failed, finished = true)
        }
    }
    val downloadingTitle = batch?.takeIf { !it.finished }?.title
    LaunchedEffect(library) {
        downloadedIds = withContext(Dispatchers.IO) { library.downloads.ids().toSet() }
    }
    var downloading by remember { mutableStateOf<String?>(null) }

    // The sleep timer, and the countdown the button shows. Polled once a second rather than driven
    // by an alarm: a second of imprecision does not matter here, and the readout has to be redrawn
    // at about that rate anyway.
    val sleepTimer = remember { SleepTimer() }
    var sleepRemaining by remember { mutableStateOf<Long?>(null) }
    var showSleepDialog by remember { mutableStateOf(false) }
    LaunchedEffect(sleepTimer) {
        playerQueue.stopAtBoundary = { sleepTimer.songEnded() }
        while (true) {
            if (sleepTimer.dueNow()) {
                player.pause()
                player.volume = 1f
            }
            // The phone's sleep fade: the last half minute ramps down to silence instead of the
            // music stopping mid-phrase. Back to full volume for whatever plays next.
            player.volume = sleepTimer.deadlineMs
                ?.takeIf { settings.sleepFade }
                ?.let { (sleepTimer.remainingMs() / SLEEP_FADE_MS.toFloat()).coerceIn(0f, 1f) }
                ?: 1f
            sleepRemaining = when {
                sleepTimer.deadlineMs != null -> sleepTimer.remainingMs()
                // End-of-song has no countdown to show. Zero is the signal for "set, but not
                // counting", which the button renders as a dash rather than as a time.
                sleepTimer.pauseWhenSongEnds -> 0L
                else -> null
            }
            // Finer while fading, so the ramp is smooth rather than a step a second.
            delay(if (sleepTimer.deadlineMs != null && sleepTimer.remainingMs() < SLEEP_FADE_MS) 200 else 1000)
        }
    }

    var showDetails by remember { mutableStateOf(false) }

    // Fetched for whatever is playing, whether or not the pane is open. Waiting until it is opened
    // would mean a spinner every single time, and the lookup is two requests that are then cached
    // for good - so doing it eagerly costs one round trip per new song and makes the pane instant.
    val lyricsRepository = remember(library) { LyricsRepository(library) }
    // As the provider sent them. The offset and estimated word timings are applied below, on the
    // way to the screen, so moving the offset slider never re-fetches - it used to, on every step,
    // and the lyrics blanked and reloaded under the slider for as long as it was dragged.
    var fetchedLyrics by remember { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    val nowPlaying = queue.current
    // Bumped to fetch again - the lyrics menu's "Fetch lyrics again".
    var lyricsAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(nowPlaying?.id, duration, settings.wordByWordLyrics, lyricsAttempt) {
        fetchedLyrics = null
        val song = nowPlaying ?: return@LaunchedEffect
        // Held off until the duration is known, because LrcLib matches on it - asking with zero
        // gets the wrong version of a song as often as it gets nothing.
        if (duration <= 0) return@LaunchedEffect
        lyricsLoading = true
        lyricsRepository.useWordByWord = settings.wordByWordLyrics
        lyricsRepository.useLrclib = settings.lyricsLrclib
        lyricsRepository.useKugou = settings.lyricsKugou
        val fetched = lyricsRepository.lyricsFor(
            songId = song.id,
            title = song.title,
            artist = song.artists.firstOrNull()?.name.orEmpty(),
            durationMs = duration,
        )
        // Applied on the way out rather than baked into the cache, so changing the offset does not
        // mean re-fetching, and so a cached file is still the file the provider sent.
        fetchedLyrics = fetched
        lyricsLoading = false
    }
    val lyrics = remember(fetchedLyrics, settings.lyricsOffsetMs, settings.estimateWordTimings) {
        fetchedLyrics?.shiftedBy(settings.lyricsOffsetMs.toLong())
            ?.let { if (settings.estimateWordTimings) it.withEstimatedWords() else it }
    }
    var openPlaylist by remember { mutableStateOf<StoredPlaylist?>(null) }
    var addingToPlaylist by remember { mutableStateOf<StoredSong?>(null) }

    // Read when the open playlist changes, and re-read whenever the playlist list does - which is
    // what changes after an add, a removal or a reorder. A flow per playlist would be more state to
    // keep in step for something only ever looked at one at a time.
    val openPlaylistSongs = remember(openPlaylist?.id, playlists) {
        openPlaylist?.let { library.playlistSongs(it.id) } ?: emptyList()
    }

    // The open playlist is re-read from the list so its song count stays current in the header, and
    // so it disappears cleanly if deleted from elsewhere.
    val selectedPlaylist = openPlaylist?.let { open -> playlists.firstOrNull { it.id == open.id } }

    // visitorData needs a network call, so it cannot be set the way locale is. Its failure used to
    // be silent, which does not look like a failure: search keeps working because search only needs
    // the locale, while every player request comes back refused.
    var session by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        session = withContext(Dispatchers.IO) {
            YouTube.visitorData().fold(
                onSuccess = { YouTube.visitorData = it; "ready" },
                onFailure = { "no session - playback will be refused (${it::class.simpleName}: ${it.message})" },
            )
        }
        // After visitorData, not alongside it: verifying a stored session is itself a request, and
        // one made without visitorData is refused - so restoring first would report a perfectly good
        // session as expired.
        account.restore()
    }

    // Music folders: what is known loads at once so local songs play immediately, then the
    // folders are rescanned in the background for anything added or changed since.
    LaunchedEffect(library) {
        withContext(Dispatchers.IO) { LocalMusic.load(library) }
        val folders = settings.localFolders.lines().filter { it.isNotBlank() }
        if (folders.isNotEmpty()) LocalMusic.scan(folders.map { java.io.File(it) }, library)
    }

    // The account's library, synced on sign-in and whenever asked. Likes made here go to YouTube as
    // they happen, so the two lists converge without a sync having to pick a winner.
    val youTubeSync = remember { YouTubeSync(library) }
    LaunchedEffect(accountState) {
        if (accountState is AccountState.SignedIn) youTubeSync.syncAll() else youTubeSync.clear()
    }
    fun likeAndPush(song: StoredSong) {
        library.toggleLiked(song)
        if (accountState is AccountState.SignedIn) {
            val nowLiked = library.isLiked(song.id)
            scope.launch { youTubeSync.pushLike(song.id, nowLiked) }
        }
    }

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        error = null
        searchedFor = text
        // A search is a place of its own: anything opened over the last one is left.
        openArtist = null
        openAlbumId = null
        openOnlinePlaylist = null
        if (settings.keepSearchHistory) {
            settings.searchHistory = (listOf(text) + settings.searchHistory.lines().filter { it.isNotBlank() && it != text })
                .take(MAX_SEARCH_HISTORY)
                .joinToString("\n")
        }
    }

    // Play with nothing loaded but a restored queue starts its song where the last session left it.
    val togglePlay: () -> Unit = {
        val resumeAt = restoredPositionMs
        if (player.state.value is PlaybackState.Idle && queue.current != null) {
            restoredPositionMs = null
            playerQueue.resume(resumeAt ?: 0L)
        } else {
            player.togglePause()
        }
    }

    val isLiked = queue.current?.let { current -> liked.any { it.id == current.id } } == true
    val toggleLike = { queue.current?.let { likeAndPush(it.toStored()) }; Unit }

    if (showSleepDialog) {
        SleepTimerDialog(
            timer = sleepTimer,
            onDismiss = { showSleepDialog = false },
            onChanged = {
                sleepRemaining = when {
                    sleepTimer.deadlineMs != null -> sleepTimer.remainingMs()
                    sleepTimer.pauseWhenSongEnds -> 0L
                    else -> null
                }
            },
            defaultMinutes = settings.sleepDefaultMinutes,
        )
    }

    if (showDetails) {
        queue.current?.let { song ->
            SongDetailsDialog(
                song = song,
                durationMs = duration,
                onDismiss = { showDetails = false },
                onCopyLink = { copyToClipboard(watchUrl(song.id)) },
            )
        }
    }

    addingToPlaylist?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = playlists,
            onDismiss = { addingToPlaylist = null },
            onAdd = { library.addToPlaylist(it.id, song) },
            onCreateAndAdd = { name -> library.addToPlaylist(library.createPlaylist(name), song) },
        )
    }


    // A play counts once this share of the song has been heard - the phone's minimum playback
    // duration - so skipping through a queue does not fill history and Stats with songs not heard.
    // Featured artists in the title, and artists to match up - looked up as the song starts, so
    // by the time it is recorded its credits are complete. See ArtistLinks.
    LaunchedEffect(queue.current?.id) {
        val song = queue.current ?: return@LaunchedEffect
        runCatching { ArtistLinks.prepare(library, song.toStored()) }
    }

    LaunchedEffect(queue.current?.id) {
        val song = queue.current ?: return@LaunchedEffect
        val playedId = song.id
        snapshotFlow { player.positionMs.value to player.durationMs.value }
            .first { (position, duration) ->
                duration > 0 && position >= duration * settings.minPlayPercent.coerceIn(0, 100) / 100
            }
        if (queue.current?.id == playedId && settings.keepHistory) {
            library.recordPlay(song.toStored(), (song.duration ?: 0) * 1000L)
        }
    }

    // A song that cannot be played is stepped past, as on the phone, rather than ending the music.
    LaunchedEffect(playback) {
        if (playback is PlaybackState.Failed && settings.skipOnError && queue.hasNext) {
            delay(1500)
            if (player.state.value is PlaybackState.Failed) playerQueue.next()
        }
    }

    // The queue as it was at the end of the last session, put back without playing.
    LaunchedEffect(Unit) {
        if (!settings.persistentQueue) return@LaunchedEffect
        QueueSnapshot.decode(settings.savedQueue)?.let { (saved, position) ->
            playerQueue.restore(saved)
            restoredPositionMs = position
        }
    }
    // Saved as it changes, and the position every ten seconds while something plays.
    LaunchedEffect(queue, settings.persistentQueue) {
        if (!settings.persistentQueue) {
            settings.savedQueue = ""
            return@LaunchedEffect
        }
        while (true) {
            settings.savedQueue = if (queue.songs.isEmpty()) "" else QueueSnapshot.encode(queue, player.positionMs.value)
            delay(10_000)
        }
    }

    // Windows' media keys and media flyout drive the same player as the window's own buttons, and
    // are told what is playing as it changes. Artwork is offered at a size the flyout looks good at.
    LaunchedEffect(Unit) {
        WindowsMediaControls.onButton = { button ->
            when (button) {
                WindowsMediaControls.Button.Play, WindowsMediaControls.Button.Pause -> togglePlay()
                WindowsMediaControls.Button.Stop -> player.pause()
                WindowsMediaControls.Button.Next -> playerQueue.next()
                WindowsMediaControls.Button.Previous -> playerQueue.previous()
            }
        }
    }
    LaunchedEffect(queue.current?.id, playback) {
        val song = queue.current
        WindowsMediaControls.update(
            title = song?.title,
            artist = song?.artists?.joinToString { it.name },
            album = song?.album?.name,
            artworkUrl = song?.thumbnail?.let { url ->
                when {
                    url.contains("=w") && url.contains("-h") -> url.substringBefore("=w") + "=w544-h544"
                    url.contains("=s") -> url.substringBefore("=s") + "=s544"
                    else -> url
                }
            },
            playing = when (playback) {
                is PlaybackState.Playing, is PlaybackState.Loading -> true
                is PlaybackState.Paused -> false
                else -> if (song != null) false else null
            },
        )
    }

    // The right-click menu every song in the window offers - see SongMenuArea.
    // Built once. Everything it reads is state read at call time, so a fresh copy per recomposition
    // bought nothing - and as an app-wide local, every new copy redrew the whole window.
    val songActions = remember {
    SongActions(
        playNext = { playerQueue.playNext(it) },
        addToQueue = { playerQueue.addToQueue(it) },
        startRadio = { song ->
            scope.launch {
                val radio = withContext(Dispatchers.IO) {
                    YouTube.next(WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}")).getOrNull()?.items
                }
                if (!radio.isNullOrEmpty()) playerQueue.play(radio, 0, "Radio: ${song.title}")
            }
        },
        addToPlaylist = { addingToPlaylist = it.toStored() },
        toggleLike = { likeAndPush(it.toStored()) },
        isLiked = { id -> liked.any { it.id == id } },
        toggleDownload = { song ->
            if (song.id in downloadedIds) {
                downloadedIds = downloadedIds - song.id
                scope.launch { withContext(Dispatchers.IO) { library.downloads.delete(song.id) } }
            } else if (downloading == null) {
                downloading = song.id
                scope.launch {
                    player.download(song.id)
                    downloadedIds = withContext(Dispatchers.IO) { library.downloads.ids().toSet() }
                    downloading = null
                }
            }
        },
        isDownloaded = { it in downloadedIds },
        openArtist = openArtistPage,
        openAlbum = openAlbumPage,
        copyLink = { copyToClipboard(watchUrl(it.id)) },
    )
    }

    // Media keys, but only while the search box does not have focus: space has to keep typing a
    // space and the arrows have to keep moving the caret. A shortcut that eats the text field is
    // worse than no shortcut.
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    // Keyboard shortcuts. Rebuilt every composition so it always acts on the current state; the
    // window calls it for any key nothing focused has used - see KeyRouter.
    val keyBindings = remember(settings.keyBindings) { KeyBindings.decode(settings.keyBindings) }
    var volumeBeforeMute by remember { mutableStateOf<Float?>(null) }
    SideEffect {
        KeyRouter.handler = handler@{ event ->
            if (event.type != KeyEventType.KeyDown || searchFocused) return@handler false
            // Windows' media controls handle the media keys once they are on - handling them here
            // as well would act on every press twice.
            when (event.key) {
                Key.MediaPlayPause -> { if (!WindowsMediaControls.active) player.togglePause(); return@handler true }
                Key.MediaNext -> { if (!WindowsMediaControls.active) playerQueue.next(); return@handler true }
                Key.MediaPrevious -> { if (!WindowsMediaControls.active) playerQueue.previous(); return@handler true }
                else -> Unit
            }
            val chord = Chord.of(event) ?: return@handler false
            when (keyBindings.actionFor(chord) ?: return@handler false) {
                KeyAction.PlayPause -> togglePlay()
                KeyAction.Next -> playerQueue.next()
                KeyAction.Previous -> playerQueue.previous()
                KeyAction.SeekForward -> player.seekTo(player.positionMs.value + 10_000)
                KeyAction.SeekBack -> player.seekTo((player.positionMs.value - 10_000).coerceAtLeast(0))
                KeyAction.VolumeUp -> { player.volume = (player.volume + 0.05f).coerceAtMost(1f); volumeBeforeMute = null }
                KeyAction.VolumeDown -> { player.volume = (player.volume - 0.05f).coerceAtLeast(0f); volumeBeforeMute = null }
                KeyAction.Mute -> {
                    val before = volumeBeforeMute
                    if (before != null) { player.volume = before; volumeBeforeMute = null }
                    else { volumeBeforeMute = player.volume; player.volume = 0f }
                }
                KeyAction.Shuffle -> playerQueue.toggleShuffle()
                KeyAction.Repeat -> playerQueue.cycleRepeat()
                KeyAction.Like -> toggleLike()
                KeyAction.Equaliser -> { showFullPlayer = true; playerCommand = PlayerCommand.ToggleEqualiser }
                KeyAction.Lyrics -> { showFullPlayer = true; playerCommand = PlayerCommand.ToggleLyrics }
                KeyAction.Queue -> {
                    if (showFullPlayer) playerCommand = PlayerCommand.ToggleQueue
                    else settings.sideQueueOpen = !settings.sideQueueOpen
                }
                KeyAction.FullPlayer -> showFullPlayer = !showFullPlayer
                KeyAction.Back -> {
                    if (showFullPlayer) playerCommand = PlayerCommand.Back
                    else return@handler false
                }
                KeyAction.Search -> {
                    showFullPlayer = false
                    if (tab == NavTab.Settings) tab = NavTab.Home
                    focusSearch++
                }
                KeyAction.SleepTimer -> showSleepDialog = true
            }
            true
        }
    }

    // The full player takes the whole window. It is placed after every effect above, not before
    // them: returning early used to take those effects out of composition while the player was
    // open, so closing it ran them all again - and the one that restores the saved queue put the
    // last session's queue back, leaving the bar on a different song from the one playing.
    val fullPlayer: @Composable () -> Unit = {
        NowPlayingScreen(
            playback = playback,
            queue = queue,
            seekBarStyle = settings.sliderStyle,
            command = playerCommand,
            onCommandHandled = { playerCommand = null },
            playerLayout = remember(settings.playerLayout) {
                settings.playerLayout.takeIf { it.isNotBlank() }?.let { PlayerLayout.parse(it).getOrNull() }
            },
            savedQueues = savedQueues,
            onSwitchQueue = playerQueue::switchTo,
            onRemoveSavedQueue = playerQueue::removeSaved,
            ferroReactivity = settings.ferroReactivity,
            positionMs = { positionState.value },
            durationMs = duration,
            liked = isLiked,
            onSeek = player::seekTo,
            onTogglePause = togglePlay,
            onNext = playerQueue::next,
            onPrevious = playerQueue::previous,
            onToggleLike = toggleLike,
            onToggleShuffle = playerQueue::toggleShuffle,
            onCycleRepeat = playerQueue::cycleRepeat,
            onClose = { showFullPlayer = false },
            // Passed only when it is switched on - the pane shows nothing when the tap is null,
            // so the setting is enforced by simply not handing it over.
            spectrum = player.spectrum.takeIf { settings.showVisualizer },
            liquidTap = player.spectrum.takeIf { settings.background == BackgroundStyle.Ferrofluid },
            playedFrames = player::playedFrames,
            equalizer = player.equalizer,
            eqStore = eqStore,
            balance = player.balance,
            timeStretch = player.timeStretch,
            compressor = player.compressor,
            // Only what the desktop build can actually do. Absent callbacks mean absent menu items,
            // so nothing here promises a feature that has not been written - see PlayerActions.
            lyrics = lyrics,
            lyricsLoading = lyricsLoading,
            onMoveInQueue = { from, to -> playerQueue.move(from, to) },
            onRemoveFromQueue = { position -> playerQueue.removeAt(position) },
            sleepRemainingMs = sleepRemaining,
            onSleepTimer = { showSleepDialog = true },
            downloaded = queue.current?.id in downloadedIds,
            downloading = downloading != null && downloading == queue.current?.id,
            showSeekButtons = settings.showSeekButtons,
            lyricsTextScale = settings.lyricsTextScale,
            lyricsAlignment = settings.lyricsAlignment,
            playerButtons = PlayerButtonKind.decode(settings.playerButtons),
            showQueueTitle = settings.showQueueTitle,
            onRefetchLyrics = {
                queue.current?.id?.let(lyricsRepository::forget)
                lyricsAttempt++
            },
            lyricsOffsetMs = settings.lyricsOffsetMs,
            onLyricsOffsetChange = { settings.lyricsOffsetMs = it },
            lyricsOnCoverClick = settings.lyricsOnCoverClick,
            backgroundStyle = settings.background,
            colourByValue = settings.colourByValue,
            actions = PlayerActions(
                onAddToQueue = queue.current?.let { song -> { playerQueue.playNext(song) } },
                onAddToPlaylist = queue.current?.let { song -> { addingToPlaylist = song.toStored() } },
                onViewArtist = queue.current?.artists?.firstOrNull()?.let { artist ->
                    {
                        openArtistPage(
                            artist.id?.let { StoredArtist(it, artist.name) }
                                ?: StoredArtist.unlinked(artist.name)
                        )
                    }
                },
                onDownload = queue.current?.let { song ->
                    {
                        if (song.id in downloadedIds) {
                            downloadedIds = downloadedIds - song.id
                            scope.launch { withContext(Dispatchers.IO) { library.downloads.delete(song.id) } }
                        } else if (downloading == null) {
                            downloading = song.id
                            scope.launch {
                                player.download(song.id)
                                downloadedIds = withContext(Dispatchers.IO) {
                                    library.downloads.ids().toSet()
                                }
                                downloading = null
                            }
                        }
                    }
                },
                // Only when the song actually carries one. A menu entry that opens an empty page is
                // worse than one that is not there.
                onViewAlbum = queue.current?.album?.id?.let { id -> { openAlbumPage(id) } },
                onShare = queue.current?.let { song -> { copyToClipboard(watchUrl(song.id)) } },
                onDetails = queue.current?.let { { showDetails = true } },
            ),
            onJumpToQueueIndex = { playerQueue.jumpTo(it) },
            onOpenArtist = openArtistPage,
        )
    }

    // The full player is laid over the main view rather than replacing it, so everything underneath
    // keeps its place while it is open - the settings tab being read, scroll positions, the library
    // view - instead of starting over when it closes.
    Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(
        LocalRowButtons provides RowButtons(RowButton.decode(settings.rowButtons), settings.rowMenu),
        LocalSongActions provides songActions,
        LocalPlaybackActive provides (playback is PlaybackState.Playing),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keys)
            .focusable()
    ) {
        // Content and queue share the space above the player bar; the bar owns the bottom edge and
        // is always there. The rail down the left is the phone's landscape layout - the same
        // sections, in the same order, as a NavigationRail rather than a row of text buttons.
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            NavRail(
                selected = tab, tabs = NavTab.decode(settings.tabs),
                onSelect = { next ->
                    // Choosing a section is leaving wherever you were: an open artist or album, a
                    // search, a playlist. Otherwise the rail would light up a tab whose page was
                    // still hidden behind the last thing opened.
                    tab = next
                    openArtist = null
                    openAlbumId = null
                    openPlaylist = null
                    openOnlinePlaylist = null
                    query = ""
                    searchedFor = null
                    error = null
                },
            )
            // The rail and the queue run to the top edge; what is in this column keeps clear of the
            // window buttons over it.
            Column(
                modifier = Modifier
                    .weight(2f)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp + LocalCaptionInset.current, bottom = 16.dp),
            ) {
                if (tab != NavTab.Settings) {
                    SearchBar(
                        focusRequest = focusSearch,
                        query = query,
                        onQueryChange = {
                            query = it
                            // Clearing the box is leaving the search, as on the phone.
                            if (it.isBlank()) {
                                searchedFor = null
                                error = null
                            }
                        },
                        onSearch = ::search,
                        onFocusChange = { searchFocused = it },
                        searching = searching,
                    )
                    if (searchFocused && query.trim() != searchedFor) {
                        SearchSuggestionsPanel(
                            query = query,
                            history = settings.searchHistory.lines().filter { it.isNotBlank() },
                            onPick = {
                                query = it
                                search()
                            },
                            onRemoveHistory = { gone ->
                                settings.searchHistory = settings.searchHistory.lines().filter { it != gone }.joinToString("\n")
                            },
                            onOpenItem = { item ->
                                when (item) {
                                    is SongItem -> playerQueue.play(listOf(item), 0, item.title)
                                    is AlbumItem -> openAlbumPage(item.browseId)
                                    is ArtistItem -> openArtistPage(StoredArtist(item.id, item.title))
                                    is PlaylistItem -> openOnlinePlaylist = item
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                SessionStatus(session)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                val albumId = openAlbumId
                val artist = openArtist
                val onlineList = openOnlinePlaylist
                if (onlineList != null) {
                    OnlinePlaylistPane(
                        onDownloadAll = downloadAll,
                        downloadedIds = downloadedIds,
                        downloadingTitle = downloadingTitle,
                        state = onlinePlaylist,
                        currentId = queue.current?.id,
                        onBack = { openOnlinePlaylist = null },
                        onPlay = { songs, index -> playerQueue.play(songs, index, onlineList.title) },
                        onShuffle = { songs ->
                            val order = songs.shuffled()
                            if (order.isNotEmpty()) playerQueue.play(order, 0, onlineList.title)
                        },
                        onAddToPlaylist = { addingToPlaylist = it },
                    )
                } else if (albumId != null) {
                    AlbumPane(
                        onDownloadAll = downloadAll,
                        downloadedIds = downloadedIds,
                        downloadingTitle = downloadingTitle,
                        state = album,
                        currentId = queue.current?.id,
                        onBack = { openAlbumId = null },
                        onPlay = { songs, index -> playerQueue.play(songs, index, albumTitle(album)) },
                        onShuffle = { songs ->
                            // Shuffled by handing the queue a shuffled list rather than by turning
                            // the shuffle mode on: "shuffle this album" is a way of starting it, not
                            // a setting that should still be on for whatever is played next.
                            val order = songs.shuffled()
                            if (order.isNotEmpty()) playerQueue.play(order, 0, albumTitle(album))
                        },
                        onOpenArtist = openArtistPage,
                        onAddToPlaylist = { addingToPlaylist = it },
                    )
                } else if (artist != null) {
                    ArtistPane(
                        requested = artist,
                        library = library,
                        currentId = queue.current?.id,
                        onBack = { openArtist = null },
                        onPlay = { songs, index -> playerQueue.play(songs, index, artist.name) },
                        onAddToPlaylist = { addingToPlaylist = it },
                        onOpenAlbum = openAlbumPage,
                        onOpenArtist = openArtistPage,
                        onOpenPlaylist = { openArtist = null; openOnlinePlaylist = it },
                        hideExplicit = settings.hideExplicit,
                    )
                } else if (tab == NavTab.Settings) {
                    SettingsPane(
                        settings = settings,
                        account = account,
                        listenTogether = listenTogether,
                        libraryPath = library.databasePath,
                        onBack = { tab = NavTab.Home },
                        onClearLyricsCache = {
                            queue.current?.id?.let(lyricsRepository::forget)
                            fetchedLyrics = null
                        },
                        downloads = library.downloads,
                        library = library,
                        // An imported equaliser is in the settings; the player reads them once.
                        onProfileApplied = {
                            settings.equalizerStore().applyTo(player.equalizer, player.compressor, player.balance)
                        },
                        onDownloadsChanged = {
                            // Off the main thread like the other three. ids() is a query plus a file
                            // check per row, and this one was missed when the others were moved.
                            scope.launch {
                                downloadedIds = withContext(Dispatchers.IO) {
                                    library.downloads.ids().toSet()
                                }
                            }
                        },
                    )
                } else if (searchedFor != null) {
                    SearchPane(
                        query = searchedFor!!,
                        currentId = queue.current?.id,
                        hideExplicit = settings.hideExplicit,
                        onPlay = { songs, index -> playerQueue.play(songs, index, "Search: $searchedFor") },
                        onOpenAlbum = openAlbumPage,
                        onOpenArtist = openArtistPage,
                        onOpenPlaylist = { openOnlinePlaylist = it },
                    )
                } else if (tab == NavTab.Stats) {
                    StatsPane(
                        library = library,
                        currentId = queue.current?.id,
                        onPlay = { songs, index -> playerQueue.play(songs.map { it.toItem() }, index, "Stats") },
                        onOpenArtist = openArtistPage,
                    )
                } else if (tab == NavTab.Playlists) {
                    PlaylistsPane(
                        onDownloadAll = { title, songs -> downloadAll(title, songs.map { it.toItem() }) },
                        downloadedIds = downloadedIds,
                        downloadingTitle = downloadingTitle,
                        playlists = playlists,
                        selected = selectedPlaylist,
                        songsInSelected = openPlaylistSongs,
                        currentId = queue.current?.id,
                        onSelect = { openPlaylist = it },
                        onCreate = { library.createPlaylist(it) },
                        onRename = { playlist, name -> library.renamePlaylist(playlist.id, name) },
                        onDelete = { library.deletePlaylist(it.id) },
                        onPlay = { songs, index -> playerQueue.play(songs.map { it.toItem() }, index, selectedPlaylist?.name ?: "Playlist") },
                        onRemoveSong = { playlist, song -> library.removeFromPlaylist(playlist.id, song.id) },
                        saved = savedPlaylists,
                        // Opens the playlist's page, as on the phone, rather than playing it blind.
                        onPlaySaved = { playlist -> openOnlinePlaylist = playlist },
                        onMoveSong = { playlist, from, to ->
                            val ids = openPlaylistSongs.map { it.id }.toMutableList()
                            if (to in ids.indices) {
                                ids.add(to, ids.removeAt(from))
                                library.reorderPlaylist(playlist.id, ids)
                                // The reorder does not touch the playlist list, so nothing would
                                // re-read the songs. Touching it is what makes the new order appear.
                                library.refreshAll()
                            }
                        },
                    )
                } else if (tab == NavTab.Home) {
                    // The feed is the landing page when nothing is being searched for. Liked and
                    // recent still live below it, since those are the two things looked for by name
                    // rather than browsed to.
                            HomePane(
                                // Explicit items dropped here, once, rather than in every card.
                                state = (home as? HomeState.Ready)?.takeIf { settings.hideExplicit }?.let { ready ->
                                    ready.copy(
                                        sections = ready.sections
                                            .map { section -> section.copy(items = section.items.filterNot { it.explicit }) }
                                            .filter { it.items.isNotEmpty() }
                                    )
                                } ?: home,
                                currentId = queue.current?.id,
                                liked = liked,
                                recent = recent,
                                onPlayStored = { songs, index, title ->
                                    playerQueue.play(songs.map { it.toItem() }, index, title)
                                },
                                onRetry = { homeAttempt++ },
                                quickPicks = quickPicks,
                                forgotten = forgotten,
                                chips = homeChips,
                                selectedChip = homeChip,
                                onChip = { homeChip = it },
                                loadingMore = homeLoadingMore,
                                onLoadMore = {
                                    val ready = home as? HomeState.Ready
                                    if (ready != null && ready.continuation != null && !homeLoadingMore) {
                                        homeLoadingMore = true
                                        scope.launch {
                                            val more = homeFeed.more(ready)
                                            // Only if the feed is still the one this was asked for: a
                                            // chip or a refresh in the meantime replaced it.
                                            if (home === ready) home = more
                                            homeLoadingMore = false
                                        }
                                    }
                                },
                                onPlaySong = { songs, index ->
                                    playerQueue.play(songs, index, "Home")
                                },
                                onOpenAlbum = openAlbumPage,
                                onPlayList = { item ->
                                    if (item is PlaylistItem) {
                                        openOnlinePlaylist = item
                                        return@HomePane
                                    }
                                    item.playlistIdOrNull?.let { playlistId ->
                                        scope.launch {
                                            val songs = withContext(Dispatchers.IO) {
                                                YouTube.queue(playlistId = playlistId).getOrNull()
                                            }
                                            if (!songs.isNullOrEmpty()) {
                                                playerQueue.play(songs, 0, item.title)
                                            }
                                        }
                                    }
                                },
                                onOpenArtist = openArtistPage,
                            )
                } else {
                    LibraryPane(
                        // Songs, Artists, Albums and Local files are the Library opened on one view.
                        fixedView = when (tab) {
                            NavTab.Songs, NavTab.LocalFiles -> LibraryView.Songs
                            NavTab.Artists -> LibraryView.Artists
                            NavTab.Albums -> LibraryView.Albums
                            else -> null
                        },
                        initialSource = if (tab == NavTab.LocalFiles) SongSource.Local else null,
                        library = library,
                        sync = youTubeSync,
                        signedIn = accountState is AccountState.SignedIn,
                        downloadedIds = downloadedIds,
                        currentId = queue.current?.id,
                        onPlay = { songs, index, title -> playerQueue.play(songs.map { it.toItem() }, index, title) },
                        onOpenArtist = openArtistPage,
                        onOpenAlbum = openAlbumPage,
                        savedPlaylists = savedPlaylists,
                        onOpenPlaylist = {
                            tab = NavTab.Playlists
                            openPlaylist = it
                        },
                        onOpenOnlinePlaylist = { openOnlinePlaylist = it },
                    )
                }
            }

            // Slides in and out rather than popping, and can be put away entirely: the button for
            // it lives on the player bar.
            AnimatedVisibility(
                visible = queue.songs.isNotEmpty() && settings.sideQueueOpen,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                Row {
                    HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    QueuePane(
                        queue,
                        saved = savedQueues,
                        onSwitch = playerQueue::switchTo,
                        onRemoveSaved = playerQueue::removeSaved,
                        onClose = { settings.sideQueueOpen = false },
                    ) { playerQueue.jumpTo(it) }
                }
            }
        }

        batch?.let { current ->
            BatchDownloadBar(
                batch = current,
                onCancel = {
                    batchJob?.cancel()
                    batch = current.copy(finished = true)
                },
                onDismiss = { batch = null },
            )
        }
        PlayerBar(
            playback = playback,
            queue = queue,
            positionMs = { positionState.value },
            durationMs = duration,
            liked = isLiked,
            onTogglePause = togglePlay,
            onNext = playerQueue::next,
            onPrevious = playerQueue::previous,
            onToggleLike = toggleLike,
            onExpand = { showFullPlayer = true },
            queueOpen = settings.sideQueueOpen,
            onToggleQueue = { settings.sideQueueOpen = !settings.sideQueueOpen },
        )
    }
    }
    if (showFullPlayer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Clicks on the player's empty space stop here rather than reaching the page below.
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (!it.isConsumed) it.consume() } }
                },
        ) { fullPlayer() }
    }
    }
}

/** The sections the rail offers, in the phone's order. */
/**
 * The sections the rail can show - the phone's tabs, with its letters, plus Stats. Which appear,
 * and in what order, is a setting (see [Settings.tabs]); Settings is always there, at the bottom.
 */
enum class NavTab(val label: String, val code: Char) {
    Home("Home", 'H'),
    Songs("Songs", 'S'),
    Artists("Artists", 'A'),
    Albums("Albums", 'B'),
    Playlists("Playlists", 'L'),
    Library("Library", 'M'),
    LocalFiles("Local files", 'O'),
    Stats("Stats", 'T'),
    Settings("Settings", '-'),
    ;

    companion object {
        /** What the rail showed before tabs could be chosen, so updating changes nothing. */
        const val DEFAULT = "HMLT"
        val choosable get() = entries.filter { it != Settings }

        fun decode(stored: String): List<NavTab> =
            stored.ifBlank { DEFAULT }.mapNotNull { c -> choosable.firstOrNull { it.code == c } }.distinct()
                .ifEmpty { listOf(Home) }

        fun encode(tabs: List<NavTab>): String = tabs.distinct().joinToString("") { it.code.toString() }
    }
}

/**
 * The phone's landscape navigation, as a rail down the left edge.
 *
 * The same container colour and the same vertically centred group as MainActivity's rail, so the
 * two read as one app. Settings sits apart at the bottom: it is not a place music is found in.
 */
@Composable
private fun NavRail(selected: NavTab, tabs: List<NavTab>, onSelect: (NavTab) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(), contentDescription = null) },
                label = { Text(tab.label, maxLines = 1) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        NavigationRailItem(
            selected = selected == NavTab.Settings,
            onClick = { onSelect(NavTab.Settings) },
            icon = { Icon(NavTab.Settings.icon(), contentDescription = null) },
            label = { Text(NavTab.Settings.label, maxLines = 1) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

/** The phone's icon for each section - Screens.kt - and its trending line for Stats. */
internal fun NavTab.icon() = when (this) {
    NavTab.Home -> OuterTuneIcons.home
    NavTab.Songs -> OuterTuneIcons.musicNote
    NavTab.Artists -> OuterTuneIcons.person
    NavTab.Albums -> OuterTuneIcons.album
    NavTab.Playlists -> OuterTuneIcons.queueMusic
    NavTab.Library -> OuterTuneIcons.libraryMusic
    NavTab.LocalFiles -> OuterTuneIcons.sdCard
    NavTab.Stats -> OuterTuneIcons.trendingUp
    NavTab.Settings -> OuterTuneIcons.settings
}

/**
 * The search field, shaped as the phone's search bar: a filled pill with the magnifier leading,
 * rather than an outlined form field with a floating label, which read as a settings input.
 */
@Composable
private fun SearchBar(
    /** Changes when the search shortcut is pressed; the box takes focus. */
    focusRequest: Int = 0,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    searching: Boolean,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) runCatching { focus.requestFocus() } }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search YouTube Music") },
        singleLine = true,
        shape = CircleShape,
        leadingIcon = { Icon(OuterTuneIcons.search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = {
            when {
                searching -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                query.isNotEmpty() -> IconButton(onClick = { onQueryChange("") }) {
                    Icon(OuterTuneIcons.close, contentDescription = "Clear", modifier = Modifier.size(20.dp))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        // Enter searches, so the keyboard flow is unbroken.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .focusRequester(focus)
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.isFocused) },
    )
}

/** Only speaks up while connecting or when it failed; a working session needs no announcement. */
@Composable
private fun SessionStatus(session: String?) {
    when (session) {
        null -> Text(
            "connecting…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        "ready" -> Unit
        else -> Text(
            session,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Content(
    results: List<SongItem>,
    liked: List<StoredSong>,
    recent: List<StoredSong>,
    currentId: String?,
    onPlayResult: (Int) -> Unit,
    onPlayStored: (List<StoredSong>, Int) -> Unit,
    onAddToPlaylist: (StoredSong) -> Unit,
) {
    if (results.isNotEmpty()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(results) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        SongRow(song, playing = song.id == currentId) { onPlayResult(index) }
                    }
                }
            }
        }
        return
    }

    val shelves = listOfNotNull(
        ("Liked" to liked).takeIf { liked.isNotEmpty() },
        ("Recently played" to recent).takeIf { recent.isNotEmpty() },
    )
    if (shelves.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Search for something to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        shelves.forEach { (heading, songs) ->
            item {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            // The whole shelf is queued, so playing from Liked plays the lot rather than one song
            // followed by silence.
            itemsIndexed(songs) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        StoredSongRow(song, playing = song.id == currentId) { onPlayStored(songs, index) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePane(
    queue: QueueState,
    saved: List<SavedQueue>,
    onSwitch: (SavedQueue) -> Unit,
    onRemoveSaved: (SavedQueue) -> Unit,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // Brings the playing song into view when it changes, so the highlighted row is never off
    // somewhere above or below.
    LaunchedEffect(queue.orderPosition) {
        if (queue.orderPosition >= 0) listState.animateScrollToItem((queue.orderPosition - 1).coerceAtLeast(0))
    }
    Column(modifier = Modifier.width(300.dp).padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 16.dp + LocalCaptionInset.current)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            Icon(
                OuterTuneIcons.queueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
            QueueSwitcher(
                title = "${queue.title}  ${queue.orderPosition + 1}/${queue.songs.size}",
                saved = saved,
                colour = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                onSwitch = onSwitch,
                onRemove = onRemoveSaved,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    OuterTuneIcons.expandLess,
                    contentDescription = "Hide queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(90f),
                )
            }
        }
        LazyColumn(state = listState) {
            // Shown in play order rather than the order added, so with shuffle on the list reads as
            // what is coming next - which is the only thing a queue is for.
            itemsIndexed(queue.order) { position, index ->
                val song = queue.songs.getOrNull(index) ?: return@itemsIndexed
                SongRow(song, playing = position == queue.orderPosition, actions = false) { onJump(index) }
            }
        }
    }
}

/**
 * The bar along the bottom. Always present, whether or not anything is playing.
 *
 * Clicking it opens the full player, so there is always a way through to it - which there was not
 * when this only appeared once a song had started.
 */
@Composable
private fun PlayerBar(
    playback: PlaybackState,
    queue: QueueState,
    positionMs: () -> Long,
    durationMs: Long,
    liked: Boolean,
    onTogglePause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onExpand: () -> Unit,
    queueOpen: Boolean = true,
    onToggleQueue: () -> Unit = {},
) {
    val song = queue.current
    Column {
        // A thin progress line rather than a slider: the bar is for glancing at, and the full
        // player is where scrubbing belongs.
        LinearProgressIndicator(
            progress = { if (durationMs > 0) positionMs().toFloat() / durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                // The rail's colour, so the two frame the content as one surface - which is how the
                // phone's mini player sits against its navigation bar.
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp))
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Artwork(song?.thumbnail, size = 48.dp, cornerRadius = 6.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title ?: "Nothing playing",
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (song == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                )
                Text(
                    text = when (playback) {
                        is PlaybackState.Loading -> "Loading…"
                        is PlaybackState.Failed -> playback.reason
                        else -> song?.artists?.joinToString { it.name }?.ifBlank { "Unknown artist" }
                            ?: "Search for something to play"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (playback is PlaybackState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (song != null) {
                ClockText(positionMs, durationMs)
                LikeButton(liked = liked, tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onToggleLike)
            }
            IconButton(onClick = onPrevious, enabled = queue.hasPrevious) {
                Icon(OuterTuneIcons.skipPrevious, "Previous")
            }
            // Filled, as the full player's is: it is the button being reached for.
            // Pause only while something is actually playing or about to; idle or failed, the button
            // offers what pressing it would do, which is play.
            val active = playback is PlaybackState.Playing || playback is PlaybackState.Loading
            FilledIconButton(onClick = onTogglePause, enabled = song != null) {
                Icon(
                    if (active) OuterTuneIcons.pause else OuterTuneIcons.play,
                    contentDescription = if (active) "Pause" else "Play",
                )
            }
            IconButton(onClick = onNext, enabled = queue.hasNext) {
                Icon(OuterTuneIcons.skipNext, "Next")
            }
            IconButton(onClick = onToggleQueue, enabled = queue.songs.isNotEmpty()) {
                Icon(
                    OuterTuneIcons.queueMusic,
                    contentDescription = if (queueOpen) "Hide queue" else "Show queue",
                    tint = if (queueOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SongRow(song: SongItem, playing: Boolean = false, actions: Boolean = true, onPlay: () -> Unit) = SongMenuArea(song) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        SongArtwork(song.thumbnail, size = 48.dp, playing = playing)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artists.joinToString { it.name }.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actions) RowActions(song)
    }
}

/** A row for a song out of the library, which keeps artists as one display string. */
@Composable
internal fun StoredSongRow(song: StoredSong, playing: Boolean = false, actions: Boolean = true, onPlay: () -> Unit) = SongMenuArea(song.toItem()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        SongArtwork(song.thumbnail, size = 48.dp, playing = playing)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artists.ifBlank { "Unknown artist" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actions) RowActions(song.toItem())
    }
}

/** The bar's clock, on its own so a tick redraws these few characters and nothing else. */
@Composable
private fun ClockText(positionMs: () -> Long, durationMs: Long) {
    Text(
        "${formatClock(positionMs())} / ${formatClock(durationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * What is actually playing, in plain terms.
 *
 * Mostly here to answer "why does this one sound different" and "is this the version I think it is".
 * The video id is included and copyable because it is the only thing that identifies a track
 * unambiguously when two uploads share a title.
 */
@Composable
private fun SongDetailsDialog(
    song: SongItem,
    durationMs: Long,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                DetailRow("Title", song.title)
                DetailRow("Artists", song.artists.joinToString { it.name }.ifBlank { "Unknown" })
                DetailRow("Video ID", song.id)
                if (durationMs > 0) DetailRow("Duration", formatClock(durationMs))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onCopyLink) { Text("Copy link") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        // Selectable, so the video id can be taken out by hand as well as by the copy button.
        SelectionContainer { Text(text = value, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * Setting the sleep timer.
 *
 * Presets rather than a picker. Nobody sets a sleep timer to forty-three minutes, and a list of
 * plausible lengths is one click where a picker is several - which matters for a control whose
 * entire audience is someone already in bed.
 */
@Composable
private fun SleepTimerDialog(
    timer: SleepTimer,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    defaultMinutes: Int = 30,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (timer.isActive) {
                    Text(
                        text = if (timer.deadlineMs != null) {
                            "Stopping in ${SleepTimer.format(timer.remainingMs())}"
                        } else {
                            "Stopping at the end of this song"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                // The chosen default first, then the presets - without repeating it.
                (listOf(defaultMinutes) + SleepTimer.PRESET_MINUTES.filter { it != defaultMinutes }).forEach { minutes ->
                    TextButton(
                        onClick = {
                            timer.start(minutes)
                            onChanged()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("$minutes minutes", modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(
                    onClick = {
                        timer.endOfSong()
                        onChanged()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("End of this song", modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            // Only when there is something to cancel, so the dialog does not offer to undo nothing.
            if (timer.isActive) {
                TextButton(
                    onClick = {
                        timer.cancel()
                        onChanged()
                        onDismiss()
                    }
                ) {
                    Text("Cancel timer", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** The album's name once it has loaded, for naming the queue it starts. */
private fun albumTitle(state: AlbumState): String =
    (state as? AlbumState.Ready)?.page?.album?.title ?: "Album"

/** The public watch page for a video id - what "Share" puts on the clipboard. */
internal fun watchUrl(videoId: String) = "https://music.youtube.com/watch?v=$videoId"

/**
 * Puts [text] on the system clipboard.
 *
 * Straight to AWT rather than through a Compose clipboard manager: this is a desktop-only build, the
 * call is one line, and wrapping it would add an abstraction over something with exactly one
 * implementation. Failures are swallowed because a headless or locked clipboard is the environment's
 * business, not a reason to interrupt playback with an error.
 */
internal fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }
}

/** The stored shape of a search result - only what the library needs to show it and replay it. */
internal fun SongItem.toStored() = StoredSong(
    id = id,
    title = title,
    artists = artists.joinToString { it.name },
    thumbnail = thumbnail,
    // Kept as separate entries as well as a joined string. The string is what gets shown when
    // nothing better is known; these are what make a name clickable.
    artistList = artists.map { artist ->
        artist.id?.let { StoredArtist(it, artist.name) } ?: StoredArtist.unlinked(artist.name)
    },
)

/**
 * A stored song back as a playable item.
 *
 * The stored artists are one display string rather than structured entries, so they come back as a
 * single unlinked artist. That is lossy - there is no channel to navigate to - but it is the name,
 * and returning an empty list instead made every song played from the library show "Unknown artist"
 * while it was playing, which is worse than lossy: it is wrong about something it knows.
 */
internal fun StoredSong.toItem() = SongItem(
    id = id,
    title = title,
    // Structured credits where the library has them, falling back to the display string for
    // anything stored before it did.
    artists = artistList.takeIf { it.isNotEmpty() }
        ?.map { Artist(name = it.name, id = it.id.takeIf { id -> !id.startsWith(StoredArtist.LOCAL_PREFIX) }) }
        ?: artists.takeIf { it.isNotBlank() }?.let { listOf(Artist(name = it, id = null)) }.orEmpty(),
    album = null,
    duration = null,
    thumbnail = thumbnail,
    explicit = false,
    endpoint = null,
)

/** How many past searches are kept. */
private const val MAX_SEARCH_HISTORY = 20

/** How long the sleep timer's fade-out lasts. */
private const val SLEEP_FADE_MS = 30_000L
