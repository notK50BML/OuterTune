package com.dd3boh.outertune.ui.screens.playlist

import com.dd3boh.outertune.playback.removeDownloadKeepingCache
import android.util.Log
import com.dd3boh.outertune.constants.UI_DEBUG
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastSumBy
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.LocalSyncUtils
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AlbumCornerRadius
import com.dd3boh.outertune.constants.AlbumThumbnailSize
import com.dd3boh.outertune.constants.CONTENT_TYPE_HEADER
import com.dd3boh.outertune.constants.CONTENT_TYPE_SONG
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlaylistEditLockKey
import com.dd3boh.outertune.constants.PlaylistSongSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSongSortType
import com.dd3boh.outertune.constants.PlaylistSongSortTypeKey
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.extensions.move
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.ExoDownloadService
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.AutoResizeText
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.FloatingFooter
import com.dd3boh.outertune.ui.component.FontSizeRange
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.ScrollToTopManager
import com.dd3boh.outertune.ui.component.SelectHeader
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.PlaylistThumbnail
import com.dd3boh.outertune.ui.component.items.SavedPlaylistBadge
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.RemoveDownloadsDialog
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.utils.getNSongsString
import com.dd3boh.outertune.ui.utils.m3uFileName
import com.dd3boh.outertune.ui.utils.rememberM3uExportLauncher
import com.dd3boh.outertune.utils.getDownloadState
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import com.dd3boh.outertune.utils.makeTimeString
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.syncCoroutine
import com.dd3boh.outertune.viewmodels.LocalPlaylistViewModel
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun LocalPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LocalPlaylistViewModel = hiltViewModel(),
) {
    if (UI_DEBUG) Log.v("LocalPlaylistScreen", "P_RC-1")
    val context = LocalContext.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val snackbarHostState = LocalSnackbarHostState.current

    val playlistWithSongs by viewModel.playlistWithSongs.collectAsState()

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val mutableSongs = remember { mutableStateListOf<PlaylistSong>() }

    val (sortType, onSortTypeChange) = rememberEnumPreference(PlaylistSongSortTypeKey, PlaylistSongSortType.CUSTOM)
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSongSortDescendingKey, true)
    var locked by rememberPreference(PlaylistEditLockKey, defaultValue = false)
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

    var inSelectMode by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSaveable(
        saver = listSaver<MutableList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() }
        )
    ) { mutableStateListOf() }
    val onExitSelectionMode = {
        inSelectMode = false
        selection.clear()
    }

    // search
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
        if (isSearching) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(query) {
        snapshotFlow { searchQuery }.debounce { 300L }.collectLatest {
            if (searchQuery.text != query.text) {
                searchQuery = query

                if (!searchQuery.text.isEmpty()) {
                    mutableSongs.clear()
                    mutableSongs.addAll(
                        playlistWithSongs.second.filter { song ->
                            song.song.title.contains(searchQuery.text, ignoreCase = true) || song.song.artists.fastAny {
                                it.name.contains(searchQuery.text, ignoreCase = true)
                            }
                        }
                    )
                }
            }
        }
    }

    if (inSelectMode) {
        BackHandler(onBack = onExitSelectionMode)
    } else if (isSearching) {
        BackHandler {
            isSearching = false
            query = TextFieldValue()
        }
    }

    val editable: Boolean =
        playlistWithSongs.first?.playlist?.isLocal == true || (playlistWithSongs.first?.playlist?.isEditable == true && syncMode == SyncMode.RW)

    LaunchedEffect(playlistWithSongs.second, isSearching) {
        if (!isSearching) {
            mutableSongs.apply {
                clear()
                addAll(playlistWithSongs.second)
            }
        }
    }

    var showEditDialog by remember {
        mutableStateOf(false)
    }

    if (showEditDialog) {
        playlistWithSongs.first?.playlist?.let { playlistEntity ->
            TextFieldDialog(
                icon = { Icon(imageVector = Icons.Rounded.Edit, contentDescription = null) },
                title = { Text(text = stringResource(R.string.edit_playlist)) },
                onDismiss = { showEditDialog = false },
                initialTextFieldValue = TextFieldValue(playlistEntity.name, TextRange(playlistEntity.name.length)),
                onDone = { name ->
                    database.query {
                        update(playlistEntity.copy(name = name))
                    }

                    viewModel.viewModelScope.launch(syncCoroutine) {
                        playlistEntity.browseId?.let { YouTube.renamePlaylist(it, name) }
                    }
                }
            )
        }
    }

    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }

    if (showRemoveDownloadDialog) {
        RemoveDownloadsDialog(
            songIds = playlistWithSongs.second.map { it.song.id },
            onDismiss = { showRemoveDownloadDialog = false },
            onConfirm = {
                // Unchanged from before the dialog: a playlist this account cannot edit keeps its
                // songs here only as a copy to download from, so the copy goes with the downloads.
                if (!editable) {
                    database.transaction {
                        playlistWithSongs.first?.id?.let { clearPlaylist(it) }
                    }
                }
            }
        )
    }

    var showDeletePlaylistDialog by remember {
        mutableStateOf(false)
    }

    if (showDeletePlaylistDialog) {
        DefaultDialog(
            onDismiss = { showDeletePlaylistDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.delete_playlist_confirm, playlistWithSongs.first?.playlist!!.name),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showDeletePlaylistDialog = false
                        database.query {
                            playlistWithSongs.first?.let { delete(it.playlist) }
                        }

                        viewModel.viewModelScope.launch(Dispatchers.IO) {
                            playlistWithSongs.first?.playlist?.browseId?.let { YouTube.deletePlaylist(it) }
                        }

                        navController.popBackStack()
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    val headerItems = 2
    val lazyListState = rememberLazyListState()
    var dragInfo by remember {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
//        scrollThresholdPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    ) { from, to ->
        if (to.index >= headerItems && from.index >= headerItems) {
            val currentDragInfo = dragInfo
            dragInfo = if (currentDragInfo == null) {
                (from.index - headerItems) to (to.index - headerItems)
            } else {
                currentDragInfo.first to (to.index - headerItems)
            }

            mutableSongs.move(from.index - headerItems, to.index - headerItems)
        }
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            dragInfo?.let { (from, to) ->
                database.transaction {
                    move(viewModel.playlistId, from, to)
                }
                if (playlistWithSongs.first?.playlist?.isLocal == false) {
                    viewModel.viewModelScope.launch(Dispatchers.IO) {
                        val from = from
                        val to = to
                        val playlistSongMap = database.songMapsToPlaylist(viewModel.playlistId, 0)

                        var fromIndex = from //- headerItems
                        val toIndex = to //- headerItems

                        var successorIndex = if (fromIndex > toIndex) toIndex else toIndex + 1

                        /*
                        * Because of how YouTube Music handles playlist changes, you necessarily need to
                        * have the SetVideoId of the successor when trying to move a song inside of a
                        * playlist.
                        * For this reason, if we are trying to move a song to the last element of a playlist,
                        * we need to first move it as penultimate and then move the last element before it.
                        */
                        if (successorIndex >= playlistSongMap.size) {
                            playlistSongMap[fromIndex].setVideoId?.let { setVideoId ->
                                playlistSongMap[toIndex].setVideoId?.let { successorSetVideoId ->
                                    playlistWithSongs.first?.playlist?.browseId?.let { browseId ->
                                        YouTube.moveSongPlaylist(browseId, setVideoId, successorSetVideoId)
                                    }
                                }
                            }

                            successorIndex = fromIndex
                            fromIndex = toIndex
                        }

                        playlistSongMap[fromIndex].setVideoId?.let { setVideoId ->
                            playlistSongMap[successorIndex].setVideoId?.let { successorSetVideoId ->
                                playlistWithSongs.first?.playlist?.browseId?.let { browseId ->
                                    YouTube.moveSongPlaylist(browseId, setVideoId, successorSetVideoId)
                                }
                            }
                        }
                    }
                }
                dragInfo = null
            }
        }
    }

    val showTopBarTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (UI_DEBUG) Log.v("LocalPlaylistScreen", "P_RC-2.1")
        ScrollToTopManager(navController, lazyListState)
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime).asPaddingValues(),
            modifier = Modifier.padding(bottom = if (inSelectMode) 64.dp else 0.dp)
        ) {
            if (UI_DEBUG) Log.v("LocalPlaylistScreen", "P_RC-2.2")
            playlistWithSongs.first?.let { playlist ->
                if (playlist.songCount == 0) {
                    item {
                        EmptyPlaceholder(
                            icon = Icons.Rounded.MusicNote,
                            text = stringResource(R.string.playlist_is_empty),
                            modifier = Modifier.animateItem()
                        )
                    }
                } else {
                    // playlist header
                    if (!isSearching) {
                        item(
                            key = "playlist header",
                            contentType = CONTENT_TYPE_HEADER
                        ) {
                            LocalPlaylistHeader(
                                playlist = playlist,
                                songs =  playlistWithSongs.second,
                                canEdit = playlist.playlist.isLocal || playlist.playlist.isEditable,
                                onShowEditDialog = { showEditDialog = true },
                                onShowRemoveDownloadDialog = { showRemoveDownloadDialog = true },
                                snackbarHostState = snackbarHostState,
                                modifier = Modifier // .animateItem()
                            )
                        }
                    }

                    item(
                        key = "action header",
                        contentType = CONTENT_TYPE_HEADER
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp)
                        ) {
                            SortHeader(
                                sortType = sortType,
                                sortDescending = sortDescending,
                                onSortTypeChange = onSortTypeChange,
                                onSortDescendingChange = onSortDescendingChange,
                                sortTypeText = { sortType ->
                                    when (sortType) {
                                        PlaylistSongSortType.CUSTOM -> R.string.sort_by_custom
                                        PlaylistSongSortType.NAME -> R.string.sort_by_name
                                        PlaylistSongSortType.ARTIST -> R.string.sort_by_artist
                                        PlaylistSongSortType.ADDED_DATE -> R.string.sort_by_create_date
                                        PlaylistSongSortType.MODIFIED_DATE -> R.string.sort_by_date_modified
                                        PlaylistSongSortType.RELEASE_DATE -> R.string.sort_by_date_released
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            if (editable && !(inSelectMode || isSearching)) {
                                IconButton(
                                    onClick = { locked = !locked },
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // songs
            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            itemsIndexed(
                items = mutableSongs,
                key = { _, song -> song.map.id },
                contentType = { _, song -> CONTENT_TYPE_SONG },
            ) { index, song ->
                ReorderableItem(
                    state = reorderableState,
                    key = song.map.id,
                    enabled = editable
                ) {
                    SongListItem(
                        song = song.song,
                        thumbnailSize = thumbnailSize,
                        playlistSong = song,
                        playlist =  playlistWithSongs.first,
                        navController = navController,
                        snackbarHostState = snackbarHostState,

                        isActive = song.song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        swipeEnabled = swipeEnabled,
                        onSelectedChange = {
                            inSelectMode = true
                            if (it) {
                                selection.add(song.song.id)
                            } else {
                                selection.remove(song.song.id)
                            }
                        },
                        inSelectMode = inSelectMode,
                        isSelected = selection.contains(song.song.id),

                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title =  playlistWithSongs.first!!.playlist.name,
                                    items = mutableSongs.map { it.song.toMediaMetadata() },
                                    startIndex = index,
                                    playlistId =  playlistWithSongs.first?.playlist?.browseId
                                )
                            )
                        },
                        dragHandleModifier = if (sortType == PlaylistSongSortType.CUSTOM && !locked && !isSearching && editable) Modifier.draggableHandle() else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
            }
        }

        LazyColumnScrollbar(
            state = lazyListState,
        )

        TopAppBar(
            title = {
                if (isSearching) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search),
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                } else if (showTopBarTitle) {
                    Text( playlistWithSongs.first?.playlist?.name.orEmpty())
                }
            },
            actions = {
                if (!isSearching) {
                    IconButton(
                        onClick = {
                            isSearching = true
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            query = TextFieldValue()
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onLongClick = {
                        if (!isSearching) {
                            navController.backToMain()
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            },
//            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior
        )

        FloatingFooter(inSelectMode) {
            SelectHeader(
                navController = navController,
                selectedItems = selection.mapNotNull { id ->
                    playlistWithSongs.second.find { it.song.id == id }?.song
                }.map { it.toMediaMetadata() },
                totalItemCount = playlistWithSongs.second.map { it.song }.size,
                onSelectAll = {
                    selection.clear()
                    selection.addAll(playlistWithSongs.second.map { it.song }.map { it.song.id })
                },
                onDeselectAll = { selection.clear() },
                menuState = menuState,
                onDismiss = onExitSelectionMode
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
//                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.union(WindowInsets.ime))
                .align(Alignment.BottomCenter)
        )
    }
}


@Composable
fun LocalPlaylistHeader(
    playlist: Playlist,
    songs: List<PlaylistSong>,
    canEdit: Boolean,
    onShowEditDialog: () -> Unit,
    onShowRemoveDownloadDialog: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier,
) {
    if (UI_DEBUG) Log.v("LocalPlaylistScreen", "P_H_RC-1")
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    val database = LocalDatabase.current
    val isNetworkConnected = LocalNetworkConnected.current
    val scope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val buttons = rememberPlaylistHeaderButtons()
    val m3uLauncher = rememberM3uExportLauncher { songs.map { it.song } }

    val playlistLength = remember(songs) {
        songs.fastSumBy { it.song.song.duration }
    }

    val downloadUtil = LocalDownloadUtil.current
    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    // Back on. With this commented out the state never left STATE_STOPPED, so a playlist whose
    // every song was on the device still offered "Download" where Liked songs, an album or an
    // online playlist showed the downloaded pin - and the pin, being the way into removing the
    // downloads, was never there either. Same as the other screens: all non-local songs downloaded
    // is "downloaded", any of them in flight is "downloading". Reset first, so emptying the
    // playlist does not leave the last list's state behind.
    LaunchedEffect(songs) {
        downloadState = Download.STATE_STOPPED
        val songs = songs.filterNot { it.song.song.isLocal }
        if (songs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(songs.map { downloads[it.song.id] })
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(12.dp)
    ) {
        // A Row, as on the album, Liked songs and online playlist pages - not the FlowRow this was.
        // The FlowRow had no width of its own and centred each line it made, so as soon as the
        // title column wanted more width than was left beside the cover (a long name, which saved
        // playlists from YouTube Music nearly always have, or the extra buttons they carry), the
        // column wrapped below and the cover was left alone on its line, centred: pushed right,
        // away from the edge every other page draws it against. In a Row the column takes the
        // remaining width and the title ellipsises instead.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistThumbnail(
                playlist = playlist.playlist,
                thumbnails = playlist.thumbnails,
                size = AlbumThumbnailSize,
                shape = RoundedCornerShape(AlbumCornerRadius),
                iconPadding = AlbumThumbnailSize / 16,
                iconTint = LocalContentColor.current.copy(alpha = 0.8f),
            )

            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f)
            ) {
                AutoResizeText(
                    text = playlist.playlist.name,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSizeRange = FontSizeRange(16.sp, 22.sp)
                )

                // Mark saved playlists: someone else's playlist says so on its own page too.
                SavedPlaylistBadge(playlist.playlist)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playlist.downloadCount > 0) {
                        Icon(
                            imageVector = Icons.Rounded.OfflinePin,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 2.dp)
                        )
                    }

                    Text(
                        text = getNSongsString(songs.size, playlist.downloadCount),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal
                    )
                }

                Text(
                    text = makeTimeString(playlistLength * 1000L),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal
                )

                // The buttons chosen in Settings > Library and content > Playlist buttons, each where
                // it applies. A FlowRow here, so that choosing many wraps them under each other
                // instead of pushing the last ones out of sight.
                FlowRow {
                    // A saved playlist is someone else's: it cannot be edited, but it can be taken
                    // off the library - first, where Edit would be. Toggling rather than leaving the
                    // page, so a mistaken tap is undone with the same button.
                    if (playlist.playlist.isSaved) {
                        val inLibrary = playlist.playlist.bookmarkedAt != null
                        IconButton(
                            onClick = {
                                database.query {
                                    update(playlist.playlist.toggleLike())
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (inLibrary) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
                                contentDescription = stringResource(
                                    if (inLibrary) R.string.remove_from_library else R.string.add_to_library
                                )
                            )
                        }
                    }

                    for (button in buttons) {
                        when (button) {
                            PlaylistHeaderButton.EDIT -> if (canEdit) {
                                PlaylistHeaderIconButton(button, onClick = onShowEditDialog)
                            }

                            PlaylistHeaderButton.SYNC -> playlist.playlist.browseId?.let { browseId ->
                                PlaylistHeaderIconButton(button, enabled = isNetworkConnected) {
                                    scope.launch {
                                        val playlistSyncedMessage = resources.getString(R.string.playlist_synced)
                                        syncUtils.syncPlaylist(browseId, playlist.id)
                                        snackbarHostState.showSnackbar(
                                            message = playlistSyncedMessage,
                                            withDismissAction = true
                                        )
                                    }
                                }
                            }

                            PlaylistHeaderButton.DOWNLOAD -> if (songs.any { !it.song.song.isLocal }) {
                                PlaylistDownloadButton(
                                    state = downloadState,
                                    onDownload = {
                                        downloadUtil.download(
                                            songs.filterNot { it.song.song.isLocal }.map { it.song.toMediaMetadata() }
                                        )
                                    },
                                    onRemoveDownload = onShowRemoveDownloadDialog
                                )
                            }

                            PlaylistHeaderButton.QUEUE -> if (songs.isNotEmpty()) {
                                PlaylistHeaderIconButton(button) {
                                    playerConnection.enqueueEnd(
                                        items = songs.map { it.song.toMediaItem() }
                                    )
                                }
                            }

                            PlaylistHeaderButton.PLAY_NEXT -> if (songs.isNotEmpty()) {
                                PlaylistHeaderIconButton(button) {
                                    playerConnection.enqueueNext(songs.map { it.song.toMediaItem() })
                                }
                            }

                            PlaylistHeaderButton.EXPORT -> if (songs.isNotEmpty()) {
                                PlaylistHeaderIconButton(button) {
                                    m3uLauncher.launch(m3uFileName(playlist.playlist.name))
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = playlist.playlist.name,
                            items = songs.map { it.song.toMediaMetadata() }.toList()
                        )
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.play))
            }

            OutlinedButton(
                onClick = {
                    playerConnection.playQueue(
                        ListQueue(
                            title = playlist.playlist.name,
                            items = songs.map { it.song.toMediaMetadata() },
                            startShuffled = true,
                        )
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
                Text(stringResource(R.string.shuffle))
            }
        }
    }
}
