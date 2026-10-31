/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.flow.collectLatest
import com.zionhuang.innertube.models.isStationPlaylistId
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import com.zionhuang.innertube.NewPipeUtils
import kotlinx.coroutines.flow.combine
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
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
fun main() {
    // Before anything else: started again while already running - in the tray, most often - this
    // shows the running copy and goes no further. See SingleInstance.
    if (!SingleInstance.claim(defaultDataDirectory())) return
    // Before the window: which way it is drawn cannot change once it is open - see RenderMode.
    RenderMode.apply()
    runApplication()
}

private fun runApplication() = application {
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

    // One way out, whichever close button is used: playback stopped and likes still waiting sent.
    // The app's own caption bar - the usual path on Windows - only stopped the player, so a like
    // made in the last minute never reached YouTube Music.
    val quit = {
        player.stop()
        LikePushes.flushBlocking()
        exitApplication()
    }
    // And the library closed once the window has finished with it: the last settings and the
    // queue written, SQLite closed cleanly - on every way out, the updater's included.
    remember(library) {
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { library.close() } })
    }

    // Where it was last time - see WindowMemory. Opened floating at its floating size even when it
    // was maximised: asked to start maximised the window simply did not, and maximising once it is
    // up (below) also leaves it the right size to go back to.
    val startMaximised = remember { settings.windowMaximised }
    val windowState = rememberWindowState(
        position = WindowMemory.initialPosition(settings),
        size = WindowMemory.initialSize(settings),
    )
    val useNativeFrame = remember { System.getProperty("os.name").orEmpty().startsWith("Windows") }
    // The phone's launcher icon, for the title bar, the taskbar and the tray.
    val appIcon = remember {
        androidx.compose.ui.graphics.painter.BitmapPainter(
            androidx.compose.ui.res.useResource("icon.png") { androidx.compose.ui.res.loadImageBitmap(it) }
        )
    }
    // Closed to the tray: the window hidden, the music playing on. A hidden window draws nothing at
    // all, and gives its memory back as a minimised one does.
    var windowVisible by remember { mutableStateOf(true) }
    var minimised by remember { mutableStateOf(false) }
    // Bumped each time the window is asked to come back; the window brings itself to the front on
    // each, once it is on screen again - see below.
    var showRequests by remember { mutableStateOf(0) }
    // Back from the tray, out of the taskbar, and in front: the tray icon's click and its popup's
    // Show OuterTune both come here.
    val showWindow: () -> Unit = {
        windowVisible = true
        windowState.isMinimized = false
        showRequests++
    }
    // And a second start of the app, handed over to this one. It arrives on the listening thread,
    // so the window's state changes are made on the UI thread as the tray's clicks are.
    SideEffect { SingleInstance.onActivate = { javax.swing.SwingUtilities.invokeLater { showWindow() } } }
    val closeWindow: () -> Unit = {
        if (settings.closeToTray) {
            windowVisible = false
            MemoryTrim.trimSoon { !windowVisible }
        } else {
            quit()
        }
    }
    // Nothing on screen to work things out for: minimised, hidden in the tray, or saving battery.
    LaunchedEffect(minimised, windowVisible, settings.batterySaver) {
        player.inBackground = minimised || !windowVisible || settings.batterySaver
    }
    if (settings.closeToTray) {
        val trayPlayback by player.state.collectAsState()
        // Its own popup on a right-click rather than a native menu - see OuterTuneTray.
        OuterTuneTray(
            icon = appIcon,
            tooltip = (trayPlayback as? PlaybackState.Playing)?.title?.let { "OuterTune - $it" } ?: "OuterTune",
            player = player,
            library = library,
            settings = settings,
            onShow = showWindow,
            onQuit = quit,
        )
    }
    Window(
        onCloseRequest = closeWindow,
        visible = windowVisible,
        title = "OuterTune",
        // No Windows title bar: the app runs to the top edge and draws its own buttons. On Windows
        // the window keeps its real frame - shadow, snap, resize, animations - with only the caption
        // taken off (NativeFrame); elsewhere, or if that cannot be done, it is borderless instead.
        undecorated = !useNativeFrame,
        icon = appIcon,
        state = windowState,
        // Every key nothing focused has used - see KeyRouter and KeyBindings.
        onKeyEvent = { KeyRouter.handle(it) },
    ) {
        LaunchedEffect(Unit) {
            // Below this the layout runs out of room: the player bar's title shrinks to a letter.
            window.minimumSize = java.awt.Dimension(WindowMemory.MIN_WIDTH, WindowMemory.MIN_HEIGHT)
            if (useNativeFrame) NativeFrame.install(window) else WindowCorners.round(window)
            MemoryTrim.install(window, onMinimised = { minimised = it })
            // Through the window's own state, and only once it is on screen: set on the frame directly,
            // while the window was still being shown, it sometimes came up minimised instead.
            if (startMaximised) {
                while (!window.isShowing) kotlinx.coroutines.delay(16)
                windowState.placement = androidx.compose.ui.window.WindowPlacement.Maximized
            }
        }
        // Brought forward once it is showing again: asked while it was still hidden, toFront does
        // nothing, and a window restored behind whatever was in use is not much of a restore.
        LaunchedEffect(showRequests) {
            if (showRequests == 0) return@LaunchedEffect
            while (!window.isShowing) kotlinx.coroutines.delay(16)
            window.toFront()
            window.requestFocus()
        }
        LaunchedEffect(windowState) {
            snapshotFlow { listOf(windowState.placement, windowState.position, windowState.size, windowState.isMinimized) }
                .collectLatest {
                    // Settled first: a drag sends a change every frame, and each is a settings write.
                    kotlinx.coroutines.delay(500)
                    WindowMemory.remember(settings, windowState)
                }
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
        // For the tray icon's popup, which is a window of its own and would otherwise not know.
        SideEffect {
            TrayControls.colors = finalScheme
            TrayControls.typography = typography
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
                        onClose = closeWindow,
                    )
                }
            }
            }
        }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    remember(library) {
        ArtistImages.library = library
        ArtistRenamer.library = library
    }
    // Where a restored queue's song was when the last session ended - see the persistent queue.
    var restoredPositionMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(settings.contentLanguage, settings.contentCountry) {
        YouTube.locale = YouTubeLocale(gl = settings.contentCountry, hl = settings.contentLanguage)
    }
    LaunchedEffect(settings.audioNormalisation) { player.normalise = settings.audioNormalisation }
    LaunchedEffect(settings.eqCutMakeup) { player.equalizer.cutMakeup = settings.eqCutMakeup }
    // Each start of a song, as its own value: the play counting below is keyed on it rather than
    // on the song's id, which stays the same when repeat-one loops a song or one is played again -
    // only the first of those was ever counted.
    val songStart = remember { mutableStateOf<SongStart?>(null) }
    val playerQueue = remember {
        // Counted below, once enough of the song has been heard - not here, at the start.
        PlayerQueue(player, scope, onPlayed = { songStart.value = SongStart(it) })
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
    // Whether the suggestions are open. Not the same as the box having focus: clicking anything in
    // the panel takes focus off the box, and closing on that shut the panel before the click
    // landed. Opened by focusing or typing; closed by Escape, a search, opening a result, or a
    // click anywhere outside the box and its panel.
    var searchOpen by remember { mutableStateOf(false) }
    var searchArea by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var contentCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
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
    // Part of the sync now (Settings > Account and sync > Playlists), so switching them off hides them.
    val youTubeSync = remember { YouTubeSync(library) }
    val savedPlaylists by youTubeSync.playlists.collectAsState()
    val recentActivity by youTubeSync.recentActivity.collectAsState()
    // Browsing signed in or not - the phone's "Use login for browsing content".
    LaunchedEffect(settings.useLoginForBrowse) { YouTube.useLoginForBrowse = settings.useLoginForBrowse }
    // The phone's shelves beside YouTube's feed: "Similar to" from what has been played, and Explore's
    // new releases. On the same trigger as the feed, and each on its own so neither waits for the
    // other or for the feed.
    var homeSimilar by remember { mutableStateOf<List<HomePage.Section>>(emptyList()) }
    var homeNewReleases by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    LaunchedEffect(accountState, homeAttempt) { homeSimilar = loadSimilar(library) }
    LaunchedEffect(accountState, homeAttempt) { homeNewReleases = loadNewReleases() }
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

    // Where Back goes from an artist or album page: the artist or album it was opened from, in
    // turn, and only then out to wherever the first of them was opened. Without it, an album opened
    // from an artist's page replaced the artist outright, and Back from the album went all the way
    // home. Anywhere that leaves these pages for somewhere else - a tab, a search, a playlist -
    // starts it again.
    val pageHistory = remember { mutableStateListOf<Pair<StoredArtist?, String?>>() }
    fun rememberPage() {
        if (openArtist != null || openAlbumId != null) pageHistory.add(openArtist to openAlbumId) else pageHistory.clear()
    }
    val pageBack = {
        val previous = pageHistory.removeLastOrNull()
        openArtist = previous?.first
        openAlbumId = previous?.second
    }
    // Opening an artist closes the player, because the artist page is where the user is going and
    // the full-window player would otherwise cover it entirely.
    val openArtistPage = { artist: StoredArtist ->
        rememberPage()
        openArtist = artist
        openAlbumId = null
        showFullPlayer = false
    }
    val openAlbumPage = { browseId: String ->
        rememberPage()
        openAlbumId = browseId
        openArtist = null
        showFullPlayer = false
    }
    // An artist or album opened from a playlist page is drawn over it, and Back returns to the
    // playlist - so a playlist opened while one of those is showing has to clear it, or it would open
    // underneath.
    val openOnlinePlaylistPage = { item: PlaylistItem ->
        openOnlinePlaylist = item
        openArtist = null
        openAlbumId = null
        pageHistory.clear()
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
            DiscordNowPlaying(queue.current, playback !is PlaybackState.Playing, player.positionMs.value, duration),
        )
    }
    // Also when the length arrives - playing starts a moment before the player knows it, and the
    // card's progress bar is drawn from it.
    LaunchedEffect(queue.current?.id, playback, duration) {
        discordPresence.requestUpdate(
            DiscordNowPlaying(queue.current, playback !is PlaybackState.Playing, player.positionMs.value, duration)
        )
    }
    // And after a seek, so the bar jumps with the song instead of carrying on from where it was:
    // a position more than a few seconds from where steady playback would have it is a seek.
    LaunchedEffect(discordPresence) {
        var last = player.positionMs.value
        var lastAt = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            val now = player.positionMs.value
            val at = System.currentTimeMillis()
            val expected = if (player.state.value is PlaybackState.Playing) last + (at - lastAt) else last
            if (kotlin.math.abs(now - expected) > 3_000 && player.state.value is PlaybackState.Playing) {
                discordPresence.requestUpdate(
                    DiscordNowPlaying(queue.current, false, now, player.durationMs.value)
                )
            }
            last = now
            lastAt = at
        }
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
        val online = songs.distinctBy { it.id }.filter { !LocalMusic.isLocal(it.id) }
        val todo = online.filter { it.id !in downloadedIds }
        if (todo.isEmpty()) {
            // Everything is already saved: the button reads "Downloaded", and pressing it removes
            // the list's downloads, as pressing a downloaded song's mark does.
            val gone = online.map { it.id }.toSet()
            if (gone.isEmpty()) return@downloadAll
            downloadedIds = downloadedIds - gone
            scope.launch { withContext(Dispatchers.IO) { gone.forEach { library.downloads.delete(it) } } }
            return@downloadAll
        }
        batch = BatchDownload(title, todo.size)
        batchJob = scope.launch {
            var done = 0
            var failed = 0
            for (song in todo) {
                withContext(Dispatchers.IO) {
                    library.database.upsertSong(song.toStored(), (song.duration ?: 0) * 1000L, song.album?.name.orEmpty())
                }
                val result = player.download(song.id).also { if (it.isSuccess) ArtworkCache.keepForOffline(song.thumbnail) }
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
        // The volume as it was when a fade began, put back when the fade ends either way.
        var volumeBeforeFade: Float? = null
        while (true) {
            if (sleepTimer.dueNow()) {
                player.pause()
                volumeBeforeFade?.let { player.volume = it }
                volumeBeforeFade = null
            }
            // The phone's sleep fade: the last half minute ramps down to silence instead of the
            // music stopping mid-phrase. The volume is only touched while that is happening - this
            // used to set it to full every second, timer or not, so the volume keys lasted a second.
            val fade = sleepTimer.deadlineMs
                ?.takeIf { settings.sleepFade }
                ?.let { (sleepTimer.remainingMs() / (settings.sleepFadeSeconds * 1000f)).coerceIn(0f, 1f) }
            if (fade != null && fade < 1f) {
                val base = volumeBeforeFade ?: player.volume.also { volumeBeforeFade = it }
                player.volume = base * fade
            } else if (volumeBeforeFade != null) {
                player.volume = volumeBeforeFade!!
                volumeBeforeFade = null
            }
            sleepRemaining = when {
                sleepTimer.deadlineMs != null -> sleepTimer.remainingMs()
                // End-of-song has no countdown to show. Zero is the signal for "set, but not
                // counting", which the button renders as a dash rather than as a time.
                sleepTimer.pauseWhenSongEnds -> 0L
                else -> null
            }
            // Finer while fading, so the ramp is smooth rather than a step a second.
            delay(if (sleepTimer.deadlineMs != null && sleepTimer.remainingMs() < settings.sleepFadeSeconds * 1000L) 200 else 1000)
        }
    }

    // Whose details are open: the playing song's from the player, or any song's from its menu.
    var detailsFor by remember { mutableStateOf<SongItem?>(null) }

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
        // Reset before anything can return early, and again however this run ends: a lookup
        // cancelled by a skip used to leave the spinner turning over the next song's lyrics.
        lyricsLoading = false
        val song = nowPlaying ?: return@LaunchedEffect
        // Held off until the duration is known, because LrcLib matches on it - asking with zero
        // gets the wrong version of a song as often as it gets nothing.
        if (duration <= 0) return@LaunchedEffect
        lyricsLoading = true
        try {
            lyricsRepository.enabled = LyricsSource.entries.filter { settings.isEnabled(it) }.toSet()
            lyricsRepository.order = LyricsSource.ordered(settings.lyricsProviderOrder)
            lyricsRepository.inOrder = settings.lyricsFetchMode == LyricsFetchMode.InMyOrder
            val fetched = lyricsRepository.lyricsFor(
                songId = song.id,
                title = song.title,
                // Without a " - Topic" suffix, which no lyrics provider knows the artist by.
                artist = song.artists.firstOrNull()?.name.orEmpty().replace(Regex("""\s*-\s*Topic$"""), ""),
                durationMs = duration,
            )
            // Applied on the way out rather than baked into the cache, so changing the offset does
            // not mean re-fetching, and so a cached file is still the file the provider sent.
            fetchedLyrics = fetched
        } finally {
            lyricsLoading = false
        }
    }
    // The playing song's own timing offset, read from the library as it starts. Per song because an
    // offset fixes one file timed against a different master; one value for everything put every
    // other song out. Null until read, so an adjustment cannot land on the song before.
    var lyricsOffset by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(nowPlaying?.id) {
        lyricsOffset = null
        val id = nowPlaying?.id ?: return@LaunchedEffect
        lyricsOffset = id to withContext(Dispatchers.IO) { lyricsRepository.offset(id) }
    }
    val lyricsOffsetMs = lyricsOffset?.takeIf { it.first == nowPlaying?.id }?.second ?: 0
    val lyrics = remember(fetchedLyrics, lyricsOffsetMs, settings.estimateWordTimings, settings.singerNames) {
        fetchedLyrics?.shiftedBy(lyricsOffsetMs.toLong())
            // Before the estimate, so a singer's name taken off the front of a line is not given
            // words of its own.
            ?.withSingerNames(settings.singerNames)
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
    // What went wrong, for the offline banner's tooltip - the banner itself says what it means.
    var sessionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Retried until it succeeds. It was asked once, and one failure - started before the
        // network was up, a dropped request, YouTube having a bad minute - left every song refused
        // until the app was restarted, with nothing to do about it but read the message. The delay
        // grows so a machine that is genuinely offline is not hammering anything.
        var attempt = 0
        while (true) {
            val result = withContext(Dispatchers.IO) { YouTube.visitorData() }
            val data = result.getOrNull()
            if (data != null) {
                YouTube.visitorData = data
                session = "ready"
                sessionError = null
                break
            }
            val e = result.exceptionOrNull()
            val wait = SESSION_RETRY_SECONDS[minOf(attempt, SESSION_RETRY_SECONDS.lastIndex)]
            session = "offline"
            sessionError = "Could not reach YouTube Music - trying again in ${wait}s. " +
                "(${e?.let { it::class.simpleName }}: ${e?.message})"
            attempt++
            delay(wait * 1000L)
        }
        // After visitorData, not alongside it: verifying a stored session is itself a request, and
        // one made without visitorData is refused - so restoring first would report a perfectly good
        // session as expired.
        account.restore()
    }

    // Updates: last time's download removed, then a look for a new version if it has been a while.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DesktopUpdater.cleanUp()
            ArtworkCache.pruneDisk()
        }
        if (!settings.updateAutoCheck) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - (settings.updateLastCheck.toLongOrNull() ?: 0L) < UPDATE_CHECK_INTERVAL_MS) return@LaunchedEffect
        settings.updateLastCheck = now.toString()
        val found = DesktopUpdater.check() ?: return@LaunchedEffect
        if (settings.updateAutoDownload) DesktopUpdater.download(found)
    }

    // Music folders: what is known loads at once so local songs play immediately, then the
    // folders are rescanned in the background for anything added or changed since.
    LaunchedEffect(library) {
        if (!settings.localEnabled) return@LaunchedEffect
        LocalMusic.removed = LocalMusic.removedFrom(settings.localRemoved)
        withContext(Dispatchers.IO) { LocalMusic.load(library) }
        // Rescanning is what a large local library pays for at every launch, so it is declinable.
        // Loading what is already known is not: that is the library, not a scan.
        if (!settings.localScanOnStart) return@LaunchedEffect
        val folders = settings.localFolders.lines().filter { it.isNotBlank() }
        if (folders.isNotEmpty()) LocalMusic.scan(folders.map { java.io.File(it) }, library)
    }

    // The account's library, synced on sign-in and whenever asked. Likes made here go to YouTube as
    // they happen, so the two lists converge without a sync having to pick a winner.
    youTubeSync.kinds = SyncKind.decode(settings.syncContent).toSet()
    youTubeSync.overwrite = settings.syncConflict == SyncConflict.Overwrite
    LaunchedEffect(accountState) {
        if (accountState !is AccountState.SignedIn) youTubeSync.clear()
        else if (settings.syncAuto) youTubeSync.syncAll()
    }
    var unlikedRecently by remember { mutableStateOf<List<StoredSong>>(emptyList()) }
    // Offered for ten seconds after the last unlike.
    if (unlikedRecently.isNotEmpty()) {
        LaunchedEffect(unlikedRecently) {
            delay(10_000)
            unlikedRecently = emptyList()
        }
    }
    fun likeAndPush(song: StoredSong) {
        library.toggleLiked(song)
        val nowLiked = library.isLiked(song.id)
        // Offered back for a few seconds - see the bar above the player.
        unlikedRecently = if (nowLiked) unlikedRecently.filterNot { it.id == song.id } else unlikedRecently + song
        // Read only (Settings > Account and sync > Sync mode): a like here stays here. Sent a minute
        // late, and not at all if changed back - see LikePushes.
        if (accountState is AccountState.SignedIn && settings.syncMode == SyncMode.ReadWrite) {
            LikePushes.schedule(song.id, nowLiked)
        }
    }

    val undoUnlikes = {
        val songs = unlikedRecently
        unlikedRecently = emptyList()
        songs.forEach { if (!library.isLiked(it.id)) likeAndPush(it) }
    }

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        error = null
        searchOpen = false
        searchedFor = text
        // A search is a place of its own: anything opened over the last one is left.
        openArtist = null
        openAlbumId = null
        pageHistory.clear()
        openOnlinePlaylist = null
        if (settings.keepSearchHistory) {
            settings.searchHistory = (listOf(text) + settings.searchHistory.lines().filter { it.isNotBlank() && it != text })
                .take(MAX_SEARCH_HISTORY)
                .joinToString("\n")
        }
    }

    // Play with nothing loaded but a restored queue starts its song where the last session left it.
    // Before anything has started - a queue restored from last time - the player has no track
    // loaded, so it reports no duration and no position, and the seek bar hid itself until play was
    // pressed. The song's own length and the restored position stand in until then, and a seek in
    // that state moves where it will start rather than asking an empty player to seek.
    //
    // The same while the next song is being fetched: the player has no length for it yet either,
    // and the bar used to vanish for those seconds - between every two songs of a station.
    val shownDuration = when {
        duration > 0 -> duration
        // A song from the library carries no length of its own; a file on this computer knows it.
        else -> queue.current?.let { song ->
            song.duration?.times(1000L) ?: LocalMusic.track(song.id)?.durationMs
        } ?: 0L
    }
    val shownPosition: () -> Long = {
        if (player.state.value is PlaybackState.Idle && queue.current != null) restoredPositionMs ?: 0L
        else positionState.value
    }
    val seekTo: (Long) -> Unit = { ms ->
        if (player.state.value is PlaybackState.Idle && queue.current != null) restoredPositionMs = ms
        else player.seekTo(ms)
    }

    val togglePlay: () -> Unit = {
        val resumeAt = restoredPositionMs
        if (player.state.value is PlaybackState.Idle && queue.current != null) {
            restoredPositionMs = null
            playerQueue.resume(resumeAt ?: 0L)
        } else {
            player.togglePause()
        }
    }
    // The mini player put away by scrolling down over it - the phone's swipe down, which stops the
    // music and drops the bar while keeping the queue. Here the queue stays exactly as it was, the
    // current song and where it had got to included, in the same state as a queue restored at
    // launch: play from anywhere (the space bar, the media keys, the tray, Windows' media flyout)
    // carries on from that point, and anything that starts playing brings the bar back.
    var miniPlayerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(playback) {
        if (playback !is PlaybackState.Idle) miniPlayerDismissed = false
    }
    val dismissMiniPlayer: () -> Unit = {
        // A restored queue that never started already knows where it will start; anything else is
        // remembered from where it stopped. Not a live stream, which has no place to go back to.
        if (player.state.value !is PlaybackState.Idle) {
            restoredPositionMs = if (player.live.value) 0L else positionState.value
        }
        player.stop()
        miniPlayerDismissed = true
    }
    // Every scroll in the window, for the mini player's gestures - see ScrollStreaks.
    val scrollStreaks = remember { ScrollStreaks() }

    // The tray icon's menu, which sits outside this and reaches the queue through these.
    SideEffect {
        TrayControls.togglePlay = togglePlay
        TrayControls.next = playerQueue::next
        TrayControls.previous = playerQueue::previous
        TrayControls.toggleShuffle = playerQueue::toggleShuffle
        TrayControls.cycleRepeat = playerQueue::cycleRepeat
        TrayControls.seekTo = seekTo
        TrayControls.queue = playerQueue.state
        // What the player bar shows, a restored queue's song and starting point included.
        TrayControls.positionMs = shownPosition
        TrayControls.durationMs = shownDuration
    }

    val isLiked = queue.current?.let { current -> liked.any { it.id == current.id } } == true
    /** The current song is a file on this computer - no like, download or link for it. */
    val currentIsLocal = queue.current?.id?.let(LocalMusic::isLocal) == true
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

    detailsFor?.let { song ->
        val playing = song.id == queue.current?.id
        val playingStream by player.stream.collectAsState()
        // A library song is stored without its album and length - see songDetailsLine.
        val facts = library.songFacts.collectAsState().value[song.id]
        // Otherwise what its file on disk says, when it has one - a file on this computer or a
        // download. Also for the current song before it has loaded, as after a restored queue.
        // A song with neither has no stream to describe until it plays.
        val stored by produceState<StreamDetails?>(null, song.id) {
            value = withContext(Dispatchers.IO) { storedStreamDetails(song.id, library.downloads) }
        }
        SongDetailsDialog(
            song = song,
            album = song.album?.name?.takeIf { it.isNotBlank() } ?: facts?.album,
            durationMs = duration.takeIf { playing && it > 0 }
                ?: song.duration?.let { it * 1000L }
                ?: facts?.durationMs
                ?: 0L,
            stream = playingStream?.takeIf { playing } ?: stored,
            plays = remember(song.id) { library.playCounts(0)[song.id] ?: 0 },
            onDismiss = { detailsFor = null },
            onCopyLink = { copyToClipboard(watchUrl(song.id)) },
        )
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
        // And whether its artists still go by the names their credits carry - see ArtistRenamer.
        ArtistRenamer.check(library.database.songCredits(song.id).ifEmpty { song.creditedArtists() })
    }

    LaunchedEffect(songStart.value) {
        val song = songStart.value?.song ?: return@LaunchedEffect
        val playedId = song.id
        // As it was when the song started: a station's picks stay its picks.
        val fromStation = playerQueue.state.value.station
        // The player's own flows, combined. This was snapshotFlow { positionMs.value ... }, but
        // those are StateFlows, not Compose state, so snapshotFlow saw no reads to track: it
        // emitted once, at the start of the song, and never again - so no play was ever recorded
        // and Stats only ever showed what a phone backup had brought in.
        val heard = HeardTime()
        combine(player.positionMs, player.durationMs) { position, duration -> position to duration }
            .first { (position, duration) ->
                heard.advance(position)
                countsAsPlay(heard.heardMs, duration, settings.minPlayPercent)
            }
        // A song heard from a station is the station's pick, not the listener's - not counted.
        if (playerQueue.state.value.current?.id == playedId && settings.keepHistory && !fromStation) {
            library.recordPlay(song.toStored(), (song.duration ?: 0) * 1000L, song.album?.name.orEmpty())
            // And on the account, if that was asked for - not for a file on this computer, which
            // YouTube has never heard of. On the window's scope rather than this effect's: the
            // effect ends with the song, and a song skipped while the request was out took its
            // play on the account with it.
            if (settings.shareHistoryWithYtm && account.state.value is AccountState.SignedIn && !LocalMusic.isLocal(playedId)) {
                scope.launch(Dispatchers.IO) { registerPlayOnAccount(playedId) }
            }
        }
    }

    // The proxy, applied here rather than only at start-up so that changing it takes effect without
    // a restart - the phone needs one, because it reads the setting once in App.onCreate. Assigning
    // closes the old client and builds a new one, so requests already in flight are not moved over;
    // nothing retries them, which is why this is a setting people change while idle.
    LaunchedEffect(settings.audioQuality) { player.quality = settings.audioQuality }

    // One check at start-up, and one an hour after that. The interval itself is hours, so polling
    // faster buys nothing, and a desktop app is left running for days - a start-up-only check would
    // never fire for the people who need it most.
    LaunchedEffect(settings.autoBackup, settings.autoBackupHours, settings.autoBackupKeep) {
        while (settings.autoBackup) {
            AutoBackup.runIfDue(library, settings.autoBackupHours, settings.autoBackupKeep)
            delay(3_600_000L)
        }
    }

    LaunchedEffect(settings.proxyEnabled, settings.proxyUrl, settings.proxyType) {
        YouTube.proxy = if (!settings.proxyEnabled || settings.proxyUrl.isBlank()) {
            null
        } else {
            runCatching {
                val (host, port) = settings.proxyUrl.trim().split(":").let { it[0] to it[1].toInt() }
                java.net.Proxy(settings.proxyType.toJavaType(), java.net.InetSocketAddress(host, port))
            }.getOrNull()
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
    // Saved as it changes, and the position every ten seconds while something plays. The shown
    // position rather than the player's: with nothing loaded - a queue restored at launch, or one the
    // mini player was put away on - the player's is not where the queue will carry on from.
    LaunchedEffect(queue, settings.persistentQueue) {
        if (!settings.persistentQueue) {
            settings.savedQueue = ""
            return@LaunchedEffect
        }
        while (true) {
            settings.savedQueue = if (queue.songs.isEmpty()) "" else QueueSnapshot.encode(queue, shownPosition())
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
        // Windows' own shuffle and repeat buttons, which ask for a state rather than a toggle.
        WindowsMediaControls.onShuffle = { on -> if (playerQueue.state.value.shuffled != on) playerQueue.toggleShuffle() }
        WindowsMediaControls.onRepeat = { mode ->
            playerQueue.setRepeat(
                when (mode) {
                    1 -> RepeatMode.ONE
                    2 -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                }
            )
        }
    }
    LaunchedEffect(queue.current?.id, playback, queue.shuffled, queue.repeat) {
        val song = queue.current?.shown(library.credits.value)
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
            shuffle = queue.shuffled,
            repeat = when (queue.repeat) {
                RepeatMode.OFF -> 0
                RepeatMode.ONE -> 1
                RepeatMode.ALL -> 2
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
                    YouTube.next(WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}")).getOrNull()
                }
                // The song the radio grew from carries on rather than starting over - it heads
                // the radio's list, and restarting it was a gap of its own at the very first song.
                if (radio != null && radio.items.isNotEmpty()) {
                    playerQueue.play(radio.items, 0, "Radio: ${song.title}", keepCurrent = true, more = radioFeed(radio))
                }
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
                    player.download(song.id).also { if (it.isSuccess) ArtworkCache.keepForOffline(song.thumbnail) }
                    downloadedIds = withContext(Dispatchers.IO) { library.downloads.ids().toSet() }
                    downloading = null
                }
            }
        },
        isDownloaded = { it in downloadedIds },
        openArtist = openArtistPage,
        openAlbum = openAlbumPage,
        copyLink = { copyToClipboard(watchUrl(it.id)) },
        removeFromLibrary = { song ->
            LocalMusic.remove(song.id, library)?.let { path ->
                settings.localRemoved = (settings.localRemoved.lines().filter { it.isNotBlank() } + path).distinct().joinToString("\n")
            }
        },
        showDetails = { detailsFor = it },
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
                // togglePlay, not the player's own toggle: with a restored queue nothing is loaded yet,
                // and only togglePlay knows to start it.
                Key.MediaPlayPause -> { if (!WindowsMediaControls.active) togglePlay(); return@handler true }
                Key.MediaNext -> { if (!WindowsMediaControls.active) playerQueue.next(); return@handler true }
                Key.MediaPrevious -> { if (!WindowsMediaControls.active) playerQueue.previous(); return@handler true }
                else -> Unit
            }
            val chord = Chord.of(event) ?: return@handler false
            when (keyBindings.actionFor(chord) ?: return@handler false) {
                KeyAction.PlayPause -> togglePlay()
                KeyAction.Next -> playerQueue.next()
                KeyAction.Previous -> playerQueue.previous()
                // By the "Seek by" setting, as the player's seek buttons are.
                KeyAction.SeekForward -> player.seekTo(player.positionMs.value + settings.seekIncrementSeconds * 1000L)
                KeyAction.SeekBack -> player.seekTo((player.positionMs.value - settings.seekIncrementSeconds * 1000L).coerceAtLeast(0))
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
            // And a still seek bar - the squiggle and the waves redraw every frame while playing.
            seekBarStyle = if (settings.batterySaver && settings.sliderStyle in ANIMATED_SEEK_BARS) SeekBarStyle.Slim else settings.sliderStyle,
            seekBarDot = settings.seekBarDot,
            seekBarWeight = settings.seekBarWeight.scale,
            live = player.live.collectAsState().value,
            command = playerCommand,
            onCommandHandled = { playerCommand = null },
            playerLayout = remember(settings.playerLayout) {
                settings.playerLayout.takeIf { it.isNotBlank() }?.let { PlayerLayout.parse(it).getOrNull() }
            },
            savedQueues = savedQueues,
            onSwitchQueue = playerQueue::switchTo,
            onRemoveSavedQueue = playerQueue::removeSaved,
            ferroReactivity = settings.ferroReactivity,
            positionMs = shownPosition,
            durationMs = shownDuration,
            liked = isLiked,
            onSeek = seekTo,
            onTogglePause = togglePlay,
            onNext = playerQueue::next,
            onPrevious = playerQueue::previous,
            onToggleLike = toggleLike,
            onToggleShuffle = playerQueue::toggleShuffle,
            onCycleRepeat = playerQueue::cycleRepeat,
            onClose = { showFullPlayer = false },
            // Passed only when it is switched on - the pane shows nothing when the tap is null,
            // so the setting is enforced by simply not handing it over.
            spectrum = player.spectrum.takeIf { settings.showVisualizer && !settings.batterySaver },
            liquidTap = player.spectrum.takeIf { settings.background == BackgroundStyle.Ferrofluid && !settings.batterySaver },
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
            lyricsHighlight = settings.lyricsHighlight,
            lyricsKind = lyricsKindOf(fetchedLyrics, settings.estimateWordTimings).takeIf { settings.lyricsShowKind },
            playerButtons = when {
                !currentIsLocal -> PlayerButtonKind.decode(settings.playerButtons)
                settings.localPlayerButtonsOwn -> PlayerButtonKind.decodeLocal(settings.localPlayerButtons)
                else -> PlayerButtonKind.decode(settings.playerButtons).filter { it.forLocalFiles }
            },
            showQueueTitle = settings.showQueueTitle,
            queueHandleTop = settings.queueHandleTop,
            onRefetchLyrics = {
                queue.current?.id?.let(lyricsRepository::forget)
                lyricsAttempt++
            },
            lyricsActions = queue.current?.let { song ->
                val artist = song.artists.firstOrNull()?.name.orEmpty().replace(Regex("""\s*-\s*Topic$"""), "")
                LyricsActions(
                    title = song.title,
                    artist = artist,
                    stored = { lyricsRepository.stored(song.id) },
                    save = { text, source -> lyricsRepository.save(song.id, text, source); lyricsAttempt++ },
                    search = { title, by, onFound -> lyricsRepository.candidates(title, by, duration, onFound) },
                    remove = { lyricsRepository.remove(song.id); lyricsAttempt++ },
                    source = fetchedLyrics?.source,
                    useProvider = { source ->
                        val found = lyricsRepository.lyricsFrom(source, song.id, song.title, artist, duration)
                        if (found != null) lyricsAttempt++
                        found != null
                    },
                )
            },
            lyricsOffsetMs = lyricsOffsetMs,
            // Live, on every step of the control, so the lyrics can be tuned by ear while they play.
            onLyricsOffsetChange = { ms ->
                lyricsOffset?.first?.takeIf { it == queue.current?.id }?.let { id ->
                    lyricsOffset = id to ms
                    lyricsRepository.setOffset(id, ms)
                }
            },
            playerScroll = settings.playerScroll,
            seekStepMs = settings.seekIncrementSeconds * 1000L,
            eqSpectrum = player.spectrum.takeIf { settings.eqSpectrum && !settings.batterySaver },
            lyricsOnCoverClick = settings.lyricsOnCoverClick,
            // Saving battery: a still background in place of the ones that move or blur every frame.
            backgroundStyle = if (settings.batterySaver && settings.background != BackgroundStyle.Solid) BackgroundStyle.Gradient else settings.background,
            colourByValue = settings.colourByValue,
            actions = PlayerActions(
                onAddToQueue = queue.current?.let { song -> { playerQueue.playNext(song) } },
                onAddToPlaylist = queue.current?.let { song -> { addingToPlaylist = song.toStored() } },
                onViewArtist = queue.current?.shown(library.credits.value)?.creditedArtists()?.firstOrNull()?.let { artist ->
                    { openArtistPage(artist) }
                },
                artists = queue.current?.shown(library.credits.value)?.creditedArtists().orEmpty(),
                onOpenArtist = openArtistPage,
                downloaded = queue.current?.id in downloadedIds,
                // A file on this computer is already here, has no link and no YouTube album.
                onDownload = queue.current?.takeUnless { currentIsLocal }?.let { song ->
                    {
                        if (song.id in downloadedIds) {
                            downloadedIds = downloadedIds - song.id
                            scope.launch { withContext(Dispatchers.IO) { library.downloads.delete(song.id) } }
                        } else if (downloading == null) {
                            downloading = song.id
                            scope.launch {
                                player.download(song.id).also { if (it.isSuccess) ArtworkCache.keepForOffline(song.thumbnail) }
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
                onShare = queue.current?.takeUnless { currentIsLocal }?.let { song -> { copyToClipboard(watchUrl(song.id)) } },
                onDetails = queue.current?.let { song -> { detailsFor = song } },
            ),
            onJumpToQueueIndex = { playerQueue.jumpTo(it) },
            onOpenArtist = openArtistPage,
        )
    }

    // The full player is laid over the main view rather than replacing it, so everything underneath
    // keeps its place while it is open - the settings tab being read, scroll positions, the library
    // view - instead of starting over when it closes.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Every scroll in the window is noted on its way in, before anything inside sees it, so
            // the mini player can tell a scroll that began over it from one that began on the list
            // above - and once it has acted on one, the rest of that scroll is swallowed here rather
            // than landing on whatever it just opened or uncovered. See ScrollStreaks.
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                scrollStreaks.note(event.changes.first().uptimeMillis)
                if (scrollStreaks.spent) event.changes.forEach { it.consume() }
            },
    ) {
    CompositionLocalProvider(
        LocalRowButtons provides RowButtons(RowButton.decode(settings.rowButtons), settings.rowMenu),
        // The library's credits and artists' current names, which every song shown goes through -
        // see SongItem.shown.
        LocalCredits provides library.credits.collectAsState().value,
        // What goes under every song's title, and the library's albums and lengths to fill it with.
        LocalSongDetails provides SongDetail.decode(settings.songDetails),
        LocalSongFacts provides library.songFacts.collectAsState().value,
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
        // is there until put away by scrolling down over it. The rail down the left is the phone's landscape layout - the same
        // sections, in the same order, as a NavigationRail rather than a row of text buttons.
        // Choosing a section is leaving wherever you were: an open artist or album, a search, a
        // playlist. Otherwise the rail would light up a tab whose page was still hidden behind the
        // last thing opened. Shared by the rail and the offline banner's "local files" link.
        val selectTab: (NavTab) -> Unit = { next ->
            tab = next
            openArtist = null
            openAlbumId = null
            pageHistory.clear()
            openPlaylist = null
            openOnlinePlaylist = null
            query = ""
            searchedFor = null
            error = null
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { contentCoords = it }
                .pointerInput(Unit) {
                    // Watches presses on their way down without consuming them, to close the
                    // suggestions on a click outside - the click itself still does what it does.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            if (event.type != androidx.compose.ui.input.pointer.PointerEventType.Press || !searchOpen) continue
                            val at = contentCoords?.localToRoot(event.changes.first().position) ?: continue
                            if (searchArea?.contains(at) != true) searchOpen = false
                        }
                    }
                },
        ) {
            NavRail(
                selected = tab, tabs = NavTab.decode(settings.tabs),
                onSelect = selectTab,
            )
            // The rail and the queue run to the top edge; what is in this column keeps clear of the
            // window buttons over it.
            Column(
                modifier = Modifier
                    .weight(2f)
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp + LocalCaptionInset.current, bottom = 16.dp),
            ) {
                if (tab != NavTab.Settings) Column(modifier = Modifier.onGloballyPositioned { searchArea = it.boundsInRoot() }) {
                    SearchBar(
                        focusRequest = focusSearch,
                        query = query,
                        onQueryChange = {
                            query = it
                            searchOpen = true
                            // Clearing the box is leaving the search, as on the phone.
                            if (it.isBlank()) {
                                searchedFor = null
                                error = null
                            }
                        },
                        onSearch = ::search,
                        onFocusChange = {
                            searchFocused = it
                            if (it) searchOpen = true
                        },
                        onEscape = { searchOpen = false },
                        searching = searching,
                    )
                    if (searchOpen && query.trim() != searchedFor) {
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
                                searchOpen = false
                                when (item) {
                                    is SongItem -> playerQueue.play(listOf(item), 0, item.title)
                                    is AlbumItem -> openAlbumPage(item.browseId)
                                    is ArtistItem -> openArtistPage(StoredArtist(item.id, item.title))
                                    is PlaylistItem -> openOnlinePlaylistPage(item)
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                // Not on the Local files tab: that is where the banner sends you, and once there it
                // has said all it has to say.
                if (tab != NavTab.LocalFiles) SessionStatus(
                    session = session,
                    detail = sessionError,
                    onLocalFiles = { selectTab(NavTab.LocalFiles) },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                val albumId = openAlbumId
                val artist = openArtist
                val onlineList = openOnlinePlaylist
                // A page opened from another is drawn over it: an artist or album opened from a
                // playlist (or from the player while one was showing) used to stay hidden under the
                // playlist until Back was pressed.
                if (albumId != null) {
                    AlbumPane(
                        onDownloadAll = downloadAll,
                        downloadedIds = downloadedIds,
                        downloadingTitle = downloadingTitle,
                        state = album,
                        currentId = queue.current?.id,
                        onBack = pageBack,
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
                        onOpenAlbum = openAlbumPage,
                    )
                } else if (artist != null) {
                    ArtistPane(
                        requested = artist,
                        library = library,
                        currentId = queue.current?.id,
                        onBack = pageBack,
                        onPlay = { songs, index -> playerQueue.play(songs, index, artist.name) },
                        onAddToPlaylist = { addingToPlaylist = it },
                        onOpenAlbum = openAlbumPage,
                        onOpenArtist = openArtistPage,
                        onOpenPlaylist = openOnlinePlaylistPage,
                        hideExplicit = settings.hideExplicit,
                    )
                } else if (onlineList != null) {
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
                } else if (tab == NavTab.Settings) {
                    SettingsPane(
                        sync = youTubeSync,
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
                        onOpenPlaylist = openOnlinePlaylistPage,
                    )
                } else if (tab == NavTab.Stats) {
                    StatsPane(
                        library = library,
                        settings = settings,
                        currentId = queue.current?.id,
                        onPlay = { songs, index -> playerQueue.play(songs.map { it.toItem() }, index, "Stats") },
                        onOpenArtist = openArtistPage,
                        includeRemote = settings.statsIncludeRemote && accountState is AccountState.SignedIn,
                    )
                } else if (tab == NavTab.Playlists) {
                    PlaylistsPane(
                        liked = liked,
                        // Music on this computer is as offline as a download, so it is listed here too,
                        // after the downloads.
                        // Read off the UI thread - it used to run a query per downloaded song in the
                        // middle of drawing the screen, again after every song of a batch download.
                        downloaded = run {
                            val localTracks by LocalMusic.tracks.collectAsState()
                            val songs by produceState(emptyList<StoredSong>(), downloadedIds, localTracks) {
                                value = withContext(Dispatchers.IO) {
                                    library.songs(downloadedIds) + localTracks.map { it.song }.sortedBy { it.title.lowercase() }
                                }
                            }
                            songs
                        },
                        grid = settings.playlistsGrid,
                        onToggleGrid = { settings.playlistsGrid = !settings.playlistsGrid },
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
                        // Liked and Downloaded songs are not playlists of their own, so they name their queue;
                        // before, they fell through to "Playlist".
                        onPlay = { songs, index, title ->
                            playerQueue.play(songs.map { it.toItem() }, index, title ?: selectedPlaylist?.name ?: "Playlist")
                        },
                        onRemoveSong = { playlist, song -> library.removeFromPlaylist(playlist.id, song.id) },
                        saved = savedPlaylists,
                        // Opens the playlist's page, as on the phone, rather than playing it blind.
                        onPlaySaved = openOnlinePlaylistPage,
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
                                yourPlaylists = savedPlaylists,
                                recentActivity = recentActivity,
                                similar = homeSimilar.filterExplicitIf(settings.hideExplicit),
                                newReleases = homeNewReleases.filterNot { settings.hideExplicit && it.explicit },
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
                                    // A station: its own list, from its first song on.
                                    val station = (item as? SongItem)?.endpoint?.takeIf { item.isStation }
                                    if (station != null) {
                                        scope.launch {
                                            val page = withContext(Dispatchers.IO) { YouTube.next(station).getOrNull() }
                                            if (page != null && page.items.isNotEmpty()) {
                                                playerQueue.play(
                                                    // Where the card's own song sits in the list - the
                                                    // top for a station, which leads with it.
                                                    page.items, (page.currentIndex ?: 0).coerceIn(0, page.items.lastIndex), item.title,
                                                    station = isStationPlaylistId(station.playlistId),
                                                    more = radioFeed(page),
                                                )
                                            }
                                        }
                                        return@HomePane
                                    }
                                    if (item is PlaylistItem) {
                                        openOnlinePlaylistPage(item)
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
                        onOpenOnlinePlaylist = openOnlinePlaylistPage,
                        onRefreshLocal = {
                            val folders = settings.localFolders.lines().filter { it.isNotBlank() }
                            if (folders.isNotEmpty()) scope.launch { LocalMusic.scan(folders.map { java.io.File(it) }, library) }
                        },
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

        // Undo for unliking: liked again, each song goes back to where it was in Liked songs. Over the
        // full player instead when that is open - see below.
        if (unlikedRecently.isNotEmpty() && !showFullPlayer) {
            UnlikeUndoBar(unlikedRecently.size, onUndo = undoUnlikes, modifier = Modifier.fillMaxWidth())
        }
        UpdateBar()
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
        // Folds down out of the way when put away rather than vanishing, so the content above
        // grows into the space instead of jumping into it.
        AnimatedVisibility(
            visible = !miniPlayerDismissed,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            PlayerBar(
                playback = playback,
                live = player.live.collectAsState().value,
                queue = queue,
                positionMs = shownPosition,
                durationMs = shownDuration,
                liked = isLiked,
                onTogglePause = togglePlay,
                onNext = playerQueue::next,
                onPrevious = playerQueue::previous,
                onToggleLike = toggleLike,
                onExpand = { showFullPlayer = true },
                queueOpen = settings.sideQueueOpen,
                onToggleQueue = { settings.sideQueueOpen = !settings.sideQueueOpen },
                buttons = MiniPlayerButtonKind.decode(settings.miniPlayerButtons),
                onToggleShuffle = playerQueue::toggleShuffle,
                onCycleRepeat = playerQueue::cycleRepeat,
                volume = player.volumeLevel.collectAsState().value,
                onVolumeChange = {
                    player.volume = it
                    volumeBeforeMute = null
                },
                onLyrics = { settings.miniPlayerLyrics = !settings.miniPlayerLyrics },
                lyricsShown = settings.miniPlayerLyrics,
                lyrics = lyrics,
                sleepRemainingMs = sleepRemaining,
                onSleepTimer = { showSleepDialog = true },
                onDismiss = dismissMiniPlayer,
                scrollStreaks = scrollStreaks,
            )
        }
    }
    }
    if (showFullPlayer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Clicks on the player's empty space stop here rather than reaching the page below:
                // a node with pointer input that is hit hides the siblings beneath it from the
                // event, so listening is enough. It must not consume. This wrapper sees every event
                // after the player's own controls, and a click is cancelled when a later handler
                // consumes the pointer moving while it is held - so consuming here killed every
                // click whose pointer drifted a pixel between press and release: the button lit
                // up and nothing happened.
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
        ) { fullPlayer() }
        // The full player covers the window, the bar above the player bar included - and its heart
        // is where an unlike is likeliest to be a slip. So Undo is offered over it, as a snackbar.
        if (unlikedRecently.isNotEmpty()) {
            UnlikeUndoBar(
                unlikedRecently.size,
                onUndo = undoUnlikes,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
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
    onEscape: () -> Unit = {},
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
                // Never takes focus itself: a click moving focus here took it off the box, which
                // closed the search instead of clearing it. Clears and keeps typing in the box.
                query.isNotEmpty() -> IconButton(
                    onClick = {
                        onQueryChange("")
                        runCatching { focus.requestFocus() }
                    },
                    modifier = Modifier.focusProperties { canFocus = false },
                ) {
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
        modifier = Modifier.typingField()
            .focusRequester(focus)
            .fillMaxWidth()
            .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { onEscape(); true } else false
            }
            .onFocusChanged { onFocusChange(it.isFocused) },
    )
}

/** Only speaks up while connecting or when it failed; a working session needs no announcement. */
/** How long to wait between attempts at a session: quick at first, then once a minute. */
private val SESSION_RETRY_SECONDS = longArrayOf(2, 5, 10, 20, 30, 60)

/**
 * What the app can do while it has no YouTube session: play local files, and nothing else.
 *
 * Worded as a mode rather than an error. It used to print the raw failure in red - "no session -
 * playback will be refused (UnknownHostException: ...)" - which read as something broken when it
 * is usually just a machine that is offline, and it said nothing about what still works. The
 * detail is kept for anyone who wants it, on hover. The session keeps retrying underneath (see
 * App), so the banner goes away by itself once YouTube can be reached.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionStatus(session: String?, detail: String?, onLocalFiles: () -> Unit) {
    when (session) {
        null -> Text(
            "connecting…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        "ready" -> Unit
        else -> TooltipArea(
            tooltip = {
                if (detail != null) Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(8.dp).widthIn(max = 420.dp),
                    )
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        OuterTuneIcons.wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Offline mode: you can only play ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "local files",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onLocalFiles)
                            .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand),
                    )
                }
            }
        }
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

@Composable
internal fun SongRow(
    song: SongItem,
    playing: Boolean = false,
    actions: Boolean = true,
    /** False on the album's own page, where the album under every title would only repeat it. */
    showAlbum: Boolean = true,
    onPlay: () -> Unit,
) = SongMenuArea(song) {
    @Suppress("NAME_SHADOWING") val song = song.shown()
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
            // Artists, album and length as Song details has them - see songDetailsLine.
            SongDetailsText(song.detailsLine(showAlbum))
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
            SongDetailsText(song.detailsLine())
        }
        if (actions) RowActions(song.toItem())
    }
}

/** The line under a song row's title - none at all when Song details has everything off. */
@Composable
private fun SongDetailsText(line: String) {
    if (line.isEmpty()) return
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A length or a position as minutes and seconds - the mini player's clock, and the details dialog. */
internal fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * What is actually playing, in plain terms - or, opened from a song's menu, what any song is. The
 * audio rows show only when the stream is known: the song is playing, or has a file on disk.
 *
 * Mostly here to answer "why does this one sound different" and "is this the version I think it is".
 * The video id is included and copyable because it is the only thing that identifies a track
 * unambiguously when two uploads share a title.
 */
@Composable
private fun SongDetailsDialog(
    song: SongItem,
    /** The song's own, or the library's for a library song, which is stored without one. */
    album: String?,
    durationMs: Long,
    stream: StreamDetails?,
    plays: Int,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
) {
    val local = LocalMusic.isLocal(song.id)
    // Views, likes and upload date from the video's page - the phone's details read the same.
    val stats by produceState<NewPipeUtils.VideoStats?>(null, song.id) {
        if (!local) value = withContext(Dispatchers.IO) { NewPipeUtils.videoStats(song.id).getOrNull() }
    }
    val numbers = java.text.NumberFormat.getIntegerInstance(java.util.Locale.ENGLISH)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("Title", song.title)
                DetailRow("Artists", song.shown().artists.joinToString { it.name }.ifBlank { "Unknown" })
                album?.takeIf { it.isNotBlank() }?.let { DetailRow("Album", it) }
                if (!local) DetailRow("Video ID", song.id)
                if (durationMs > 0) DetailRow("Duration", formatClock(durationMs))
                DetailRow("Plays", numbers.format(plays))
                if (!local) {
                    DetailRow("Views", stats?.views?.let(numbers::format) ?: "…")
                    DetailRow("Likes", stats?.likes?.let(numbers::format) ?: "…")
                    stats?.uploaded?.let { DetailRow("Uploaded", it) }
                    stats?.uploader?.let { DetailRow("Channel", it) }
                }
                stream?.let { s ->
                    DetailRow("Format", s.format)
                    s.bitrateKbps?.let { DetailRow("Bitrate", "$it kbps") }
                    s.sampleRate?.let { DetailRow("Sample rate", "${numbers.format(it)} Hz") }
                    s.bitsPerSample?.let { DetailRow("Bit depth", "$it-bit") }
                    s.loudnessDb?.let { DetailRow("Loudness", "%.1f dB".format(java.util.Locale.ENGLISH, it)) }
                    s.sizeBytes?.let { DetailRow("Size", Downloads.formatSize(it)) }
                    s.path?.let { DetailRow(if (local) "File" else "Saved at", it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (local) null else ({ TextButton(onClick = onCopyLink) { Text("Copy link") } }),
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
        artist.id?.let { StoredArtist(it.normalizeArtistId(), artist.name) } ?: StoredArtist.unlinked(artist.name)
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

/** Drops explicit items from shelves when the setting asks, and shelves left empty by it. */
private fun List<HomePage.Section>.filterExplicitIf(hide: Boolean): List<HomePage.Section> =
    if (!hide) this else map { section -> section.copy(items = section.items.filterNot { it.explicit }) }
        .filter { it.items.isNotEmpty() }

/** "Removed from liked songs", with Undo - shared by the bar above the player and the full player. */
@Composable
private fun UnlikeUndoBar(count: Int, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            if (count == 1) "Removed from liked songs" else "$count songs removed from liked songs",
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
    }
}

/** How often the automatic update check may run. */
private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

/**
 * Runs a downloaded update and closes OuterTune for it: likes still waiting are sent first, as on
 * any close, and the installer opens the app again when it is done.
 */
internal fun installUpdateAndQuit(installer: java.io.File) {
    if (!DesktopUpdater.install(installer)) {
        DesktopUpdater.state.value = DesktopUpdater.State.Failed("The installer could not be started.")
        return
    }
    LikePushes.flushBlocking()
    kotlin.system.exitProcess(0)
}

/** A new version, found or downloaded: one line above the player with what to do about it. */
@Composable
private fun UpdateBar() {
    val scope = rememberCoroutineScope()
    val state by DesktopUpdater.state.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    val text: String
    val action: Pair<String, () -> Unit>?
    when (val s = state) {
        is DesktopUpdater.State.Available -> {
            text = "OuterTune ${s.update.name} is available."
            action = "Download" to { scope.launch { DesktopUpdater.download(s.update) }; Unit }
        }
        is DesktopUpdater.State.Downloading -> {
            text = "Downloading OuterTune ${s.update.name}… ${(s.progress * 100).toInt()}%"
            action = null
        }
        is DesktopUpdater.State.Ready -> {
            text = "OuterTune ${s.update.name} is ready. Installing closes and reopens the app."
            action = "Install" to { installUpdateAndQuit(s.installer) }
        }
        else -> return
    }
    if (dismissed) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
        TextButton(onClick = { dismissed = true }) { Text("Later") }
    }
}

/** Seek bar styles that redraw every frame while a song plays - set aside by the battery saver. */
private val ANIMATED_SEEK_BARS = setOf(SeekBarStyle.Squiggly, SeekBarStyle.Waves, SeekBarStyle.WaveLines)
