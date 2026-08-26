package com.dd3boh.outertune.ui.screens.artist

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AppBarHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.AutoResizeText
import com.dd3boh.outertune.ui.component.FontSizeRange
import com.dd3boh.outertune.ui.component.HideOnScrollFAB
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.items.YouTubeGridItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.component.shimmer.ArtistPagePlaceholder
import com.dd3boh.outertune.ui.menu.AlbumMenu
import com.dd3boh.outertune.ui.menu.YouTubeAlbumMenu
import com.dd3boh.outertune.ui.menu.YouTubeArtistMenu
import com.dd3boh.outertune.ui.menu.YouTubePlaylistMenu
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.utils.detectLetterboxContentBounds
import com.dd3boh.outertune.ui.utils.fadingEdge
import com.dd3boh.outertune.ui.utils.naturalAspectRatioOrNull
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.coilCoroutine
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.ArtistViewModel
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Scales the artist header's aspect ratio down (taller box for the same width) before it's
 * clamped - see the call site for why a header sized to the image's bare aspect ratio alone
 * still needed this. Used for a wide/banner photo, where the box is already fairly short and
 * benefits from a bit more height so the name doesn't feel cramped against it.
 */
private const val HEADER_HEIGHT_BOOST = 0.78f

/**
 * The stronger boost applied to a genuinely square photo with no letterboxing found - see the
 * call site. A plain square avatar with nothing else going on can afford (and reads better with)
 * more headroom above it than a banner photo does.
 */
private const val HEADER_HEIGHT_BOOST_SQUARE = 0.65f

/** How far from exactly 1:1 a photo's aspect ratio can be and still count as "square" below. */
private const val SQUARE_ASPECT_TOLERANCE = 0.15f

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val artistPage = viewModel.artistPage
    val libraryArtist by viewModel.libraryArtist.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current
    var showLocal by rememberSaveable { mutableStateOf(false) }

    val transparentAppBar by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0
        }
    }

    LaunchedEffect(libraryArtist) {
        // always show local page for local artists. Show local page remote artist when offline
        showLocal = libraryArtist?.artist?.isLocal == true
    }

    val artistHead = @Composable {
        if (artistPage != null || libraryArtist != null) {
            val thumbnail = artistPage?.artist?.thumbnail ?: libraryArtist?.artist?.thumbnailUrl
            val artistName = artistPage?.artist?.title ?: libraryArtist?.artist?.name

            // Requesting a fixed 4:3 crop regardless of the source's own shape cost the old
            // square channel avatars their top and bottom and newer wide banner photos their
            // sides - both are real artist-image shapes, not artifacts to crop away. The box now
            // takes on whichever shape the source actually has (old square avatars render at
            // roughly their own square-ish size instead of being squeezed into a wide slot; newer
            // wide/letterboxed photos render at their own width, "normally"), clamped so a stray
            // unusual source can't blow the header out to something absurd.
            val naturalAspect = remember(thumbnail) { thumbnail?.naturalAspectRatioOrNull() }

            // The URL's own aspect ratio only describes the *file's* shape, not necessarily the
            // photo's - some square avatars are a non-square picture letterboxed onto a square
            // canvas, black bars baked into the pixels rather than anything a resize parameter
            // can see or remove. Decoding the thumbnail once and looking for those bars is the
            // only way to find that content region; null means either it's still loading or there
            // genuinely isn't a bar to trim, and the URL-based aspect ratio is used meanwhile/instead.
            var croppedThumbnail by remember(thumbnail) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(thumbnail) {
                croppedThumbnail = null
                val url = thumbnail ?: return@LaunchedEffect
                withContext(coilCoroutine) {
                    val bitmap = runCatching {
                        context.imageLoader.execute(
                            ImageRequest.Builder(context)
                                .data(url.resize(width = 900))
                                .allowHardware(false)
                                .build()
                        ).image?.toBitmap()
                    }.getOrNull() ?: return@withContext
                    val bounds = bitmap.detectLetterboxContentBounds() ?: return@withContext
                    croppedThumbnail = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
                }
            }

            val hasLetterbox = croppedThumbnail != null
            val baseAspect = croppedThumbnail?.let { it.width.toFloat() / it.height } ?: naturalAspect ?: (4f / 3)
            // Displaying the image at its exact own ratio made a wide banner photo's header
            // shallow - a real shape, not a bug, but a strip that thin read as cramped rather than
            // a proper header. Boosting (scaling the ratio down, which makes the box taller for
            // the same width) fixes that, but not by the same amount for every source:
            // - Letterboxed source: the black bars already got cropped off above, so
            //   `baseAspect` IS the real photo's own shape already - boosting it further on top of
            //   that just adds more blank space above the image (it's bottom-aligned) for no
            //   reason. No boost here.
            // - Genuinely square, no letterboxing found: nothing else is shaping this box, so it
            //   gets the strongest boost - otherwise the name overlaps the photo's own content
            //   with no room to breathe.
            // - Anything else (a real wide/landscape banner): the original, milder boost.
            val boost = when {
                hasLetterbox -> 1f
                baseAspect in (1f - SQUARE_ASPECT_TOLERANCE)..(1f + SQUARE_ASPECT_TOLERANCE) -> HEADER_HEIGHT_BOOST_SQUARE
                else -> HEADER_HEIGHT_BOOST
            }
            val headerAspect = (baseAspect * boost).coerceIn(0.6f, 1.6f)

            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (thumbnail != null) Modifier.aspectRatio(headerAspect) else Modifier
                        )
                ) {
                    if (thumbnail != null) {
                        // Bottom-aligned, not centred: the box is now deliberately taller than the
                        // image's own shape (HEADER_HEIGHT_BOOST), and centring a same-size image
                        // in a taller box split that extra room evenly above *and* below it -
                        // pulling the image's bottom edge away from the artist-name text pinned to
                        // the box's own bottom, so the name ended up floating over bare background
                        // instead of over the photo's (faded) bottom edge. Anchoring the image to
                        // the bottom keeps it flush with the text overlay exactly as before; the
                        // extra height shows up above the image instead, which is the one place
                        // adding it doesn't disturb anything (that's already just the area behind
                        // the transparent status bar/app bar).
                        val fadeModifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fadingEdge(
                                top = WindowInsets.systemBars
                                    .asPaddingValues()
                                    .calculateTopPadding() + AppBarHeight,
                                bottom = 64.dp
                            )
                        val cropped = croppedThumbnail
                        if (cropped != null) {
                            Image(
                                bitmap = cropped.asImageBitmap(),
                                contentDescription = null,
                                modifier = fadeModifier,
                            )
                        } else {
                            AsyncImage(
                                // Width only, not a fixed width+height: the latter is what forced
                                // the crop above. resize() fills in a height that preserves the
                                // source's own aspect ratio when it knows one (googleusercontent),
                                // and every other scheme here ignores the height argument anyway.
                                model = thumbnail.resize(width = 1200),
                                contentDescription = null,
                                modifier = fadeModifier,
                            )
                        }
                    }
                    AutoResizeText(
                        text = artistName
                            ?: "Unknown",
                        style = MaterialTheme.typography.displayLarge,
                        fontSizeRange = FontSizeRange(32.sp, 58.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 48.dp)
                            .then(
                                if (thumbnail == null) {
                                    Modifier.padding(
                                        top = WindowInsets.systemBars
                                            .asPaddingValues()
                                            .calculateTopPadding() + AppBarHeight
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    Button(
                        onClick = {
                            val watchEndpoint = artistPage?.artist?.shuffleEndpoint ?: artistPage?.artist?.playEndpoint
                            playerConnection.playQueue(
                                if (!showLocal && watchEndpoint != null) YouTubeQueue(watchEndpoint)
                                else ListQueue(
                                    title = artistName,
                                    items = librarySongs.map { it.toMediaMetadata() },
                                    startShuffled = true,
                                ),
                                isRadio = true,
                                title = artistName
                            )
                        },
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(
                            text = stringResource(R.string.shuffle)
                        )
                    }

                    if (!showLocal) {
                        artistPage?.artist?.radioEndpoint?.let { radioEndpoint ->
                            OutlinedButton(
                                onClick = {
                                    playerConnection.playQueue(
                                        YouTubeQueue(radioEndpoint),
                                        isRadio = true,
                                        title = "Radio: ${artistPage.artist.title}"
                                    )
                                },
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Radio,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.radio))
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .add(
                    WindowInsets(
                        top = -WindowInsets.systemBars.asPaddingValues()
                            .calculateTopPadding() - AppBarHeight
                    )
                )
                .asPaddingValues()
        ) {
            if (isLoading && artistPage == null && !showLocal) {
                item(key = "shimmer") {
                    ArtistPagePlaceholder()
                }
            } else {
                item(key = "header") {
                    artistHead()
                }

                if (showLocal) {
                    if (librarySongs.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.songs),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/songs")
                                }
                            )
                        }

                        val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                        itemsIndexed(
                            items = librarySongs,
                            key = { _, item -> item.hashCode() }
                        ) { index, song ->
                            SongListItem(
                                song = song,
                                navController = navController,
                                snackbarHostState = snackbarHostState,

                                isActive = song.song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                inSelectMode = false,
                                isSelected = false,
                                onSelectedChange = { },
                                swipeEnabled = swipeEnabled,

                                thumbnailSize = thumbnailSize,
                                onPlay = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "Library: ${libraryArtist?.artist?.name}",
                                            items = librarySongs.map { it.toMediaMetadata() },
                                            startIndex = index
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            )

                        }
                    }

                    if (libraryAlbums.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.albums),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/albums")
                                }
                            )
                        }

                        item {
                            LazyRow {
                                items(
                                    items = libraryAlbums,
                                    key = { it.id }
                                ) { album ->
                                    AlbumGridItem(
                                        album = album,
                                        isActive = album.id == mediaMetadata?.album?.id,
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    navController.navigate("album/${album.id}")
                                                },
                                                onLongClick = {
                                                    menuState.show {
                                                        AlbumMenu(
                                                            originalAlbum = album,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                }
                                            )
                                            .animateItem()
                                    )
                                }
                            }
                        }

                    }
                } else artistPage?.sections?.fastForEach { section ->
                    val isSongsSection = (section.items.firstOrNull() as? SongItem)?.album != null

                    item {
                        NavigationTitle(
                            title = if (isSongsSection) stringResource(R.string.songs) else section.title,
                            onClick = section.moreEndpoint?.let {
                                {
                                    navController.navigate("artist/${viewModel.artistId}/items?browseId=${it.browseId}?params=${it.params}")
                                }
                            }
                        )
                    }

                    if (isSongsSection) {
                        items(
                            items = section.items,
                            key = { it.id }
                        ) { song ->
                            SwipeToQueueBox(
                                item = (song as SongItem).toMediaItem(),
                                swipeEnabled = swipeEnabled,
                                snackbarHostState = snackbarHostState
                            ) {
                                YouTubeListItem(
                                    item = song,
                                    isActive = mediaMetadata?.id == song.id,
                                    isPlaying = isPlaying,
                                    trailingContent = {
                                        IconButton(
                                            onClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.MoreVert,
                                                contentDescription = null
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = {
                                                if (song.id == mediaMetadata?.id) {
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = "Artist songs (preview): ${artistPage.artist.title}",
                                                            items = section.items.map { (it as SongItem).toMediaMetadata() },
                                                            startIndex = section.items.indexOf(
                                                                song
                                                            )
                                                        )
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss
                                                    )
                                                }
                                            }
                                        )
                                        .animateItem()
                                )
                            }

                        }
                    } else {
                        item {
                            LazyRow {
                                items(
                                    items = section.items,
                                    key = { it.id }
                                ) { item ->
                                    YouTubeGridItem(
                                        item = item,
                                        isActive = when (item) {
                                            is SongItem -> mediaMetadata?.id == item.id
                                            is AlbumItem -> mediaMetadata?.album?.id == item.id
                                            else -> false
                                        },
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier
                                            .combinedClickable(
                                                onClick = {
                                                    when (item) {
                                                        is SongItem -> playerConnection.playQueue(
                                                            YouTubeQueue.radio(
                                                                item.toMediaMetadata()
                                                            ),
                                                            isRadio = true,
                                                            title = artistPage.artist.title
                                                        )

                                                        is AlbumItem -> navController.navigate(
                                                            "album/${item.id}"
                                                        )

                                                        is ArtistItem -> navController.navigate(
                                                            "artist/${item.id}"
                                                        )

                                                        is PlaylistItem -> navController.navigate(
                                                            "online_playlist/${item.id}"
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    menuState.show {
                                                        when (item) {
                                                            is SongItem -> YouTubeSongMenu(
                                                                song = item,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss
                                                            )

                                                            is AlbumItem -> YouTubeAlbumMenu(
                                                                albumItem = item,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss
                                                            )

                                                            is ArtistItem -> YouTubeArtistMenu(
                                                                artist = item,
                                                                onDismiss = menuState::dismiss
                                                            )

                                                            is PlaylistItem -> YouTubePlaylistMenu(
                                                                navController = navController,
                                                                playlist = item,
                                                                coroutineScope = coroutineScope,
                                                                onDismiss = menuState::dismiss
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                            .animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        HideOnScrollFAB(
            visible = librarySongs.isNotEmpty() && libraryArtist?.artist?.isLocal != true,
            lazyListState = lazyListState,
            icon = if (showLocal) Icons.Rounded.LibraryMusic else Icons.Rounded.Language,
            onClick = {
                showLocal = showLocal.not()
                if (!showLocal && artistPage == null) viewModel.fetchArtistsFromYTM()
            }
        )

        TopAppBar(
            title = { if (!transparentAppBar) Text(artistPage?.artist?.title.orEmpty()) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        database.transaction {
                            val artist = libraryArtist?.artist
                            if (artist != null) {
                                update(artist.toggleLike())
                            } else {
                                artistPage?.artist?.let {
                                    insert(
                                        ArtistEntity(
                                            id = it.id,
                                            name = it.title,
                                            channelId = it.channelId,
                                            thumbnailUrl = it.thumbnail,
                                        ).toggleLike()
                                    )
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (libraryArtist?.artist?.bookmarkedAt != null) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        tint = if (libraryArtist?.artist?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null
                    )
                }

                IconButton(
                    onClick = {
                        viewModel.artistPage?.artist?.shareLink?.let { link ->
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, link)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null
                    )
                }
            },
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior,
            colors = if (transparentAppBar) {
                TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            } else {
                TopAppBarDefaults.topAppBarColors()
            }
        )

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}