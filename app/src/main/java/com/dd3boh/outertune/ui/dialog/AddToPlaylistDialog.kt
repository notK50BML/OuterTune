package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalSyncUtils
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.PlaylistSortTypeKey
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.PlaylistListItem
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Picks the playlists to add songs to. A tap adds to that playlist; a long press starts choosing
 * several, and the button pinned at the bottom adds to all of them at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddToPlaylistDialog(
    navController: NavController,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    songIds: List<String>?, // song ids to insert.
    onPreAdd: (suspend (Playlist) -> List<String>)? = null,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val syncUtils = LocalSyncUtils.current
    val haptic = LocalHapticFeedback.current

    // The local rows and the remote playlist, together, so no caller can do one and forget the
    // other - which is what happened to both duplicate-dialog paths below: they wrote the database
    // and never told YouTube anything.
    fun addRemotely(playlist: Playlist, ids: List<String>) {
        if (playlist.playlist.isLocal) return
        playlist.playlist.browseId?.let { syncUtils.addToRemotePlaylist(it, ids, playlistId = playlist.id) }
    }

    // Non-null while choosing several playlists (a long press starts it): the ids picked so far.
    var picked by remember { mutableStateOf<Set<String>?>(null) }

    val (sortType, onSortTypeChange) = rememberEnumPreference(PlaylistSortTypeKey, PlaylistSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSortDescendingKey, true)
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

    var playlists by remember {
        mutableStateOf(emptyList<Playlist>())
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    var showDuplicateDialog by remember {
        mutableStateOf(false)
    }
    var selectedPlaylist by remember {
        mutableStateOf<Playlist?>(null)
    }
    var songIds by remember {
        mutableStateOf<List<String>?>(songIds) // list is not saveable
    }
    var playlistIdsSongParticipation by remember {
        mutableStateOf<List<String>?>(null)
    }
    var duplicates by remember {
        mutableStateOf(emptyList<String>())
    }

    // Keyed on the sort and sync mode so a change from the sort header restarts the query; keyed on
    // Unit it ran once with whatever order was set when the dialog opened.
    LaunchedEffect(sortType, sortDescending, syncMode) {
        if (syncMode == SyncMode.RO) {
            database.playlists(PlaylistFilter.LIBRARY, sortType, sortDescending, 1).collect {
                playlists = it
            }
        } else {
            database.playlists(PlaylistFilter.LIBRARY, sortType, sortDescending, 2).collect {
                playlists = it
            }
        }
    }

    LaunchedEffect(playlists) {
        coroutineScope.launch(Dispatchers.IO) {
            songIds?.let { s ->
                playlistIdsSongParticipation = database.playlistIdBySongs(s).first()
            }
        }
    }

    // Adds to every playlist chosen at once. A song already in one of them is skipped there rather
    // than asked about: one duplicates prompt cannot speak for several playlists at a time.
    fun addToChosen(chosen: List<Playlist>) {
        if (chosen.isEmpty()) return
        coroutineScope.launch(Dispatchers.IO) {
            if (onPreAdd != null) {
                val result = onPreAdd(chosen.first())
                if (songIds == null) songIds = result
            }
            val ids = songIds ?: return@launch
            for (playlist in chosen) {
                val dupes = database.playlistDuplicates(playlist.id, ids).toSet()
                val toAdd = ids.filterNot { it in dupes }
                if (toAdd.isEmpty()) continue
                database.addSongToPlaylist(playlist, toAdd)
                addRemotely(playlist, toAdd)
            }
            onDismiss()
        }
    }

    ListDialog(
        onDismiss = onDismiss,
        footer = picked?.let { selection ->
            {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                ) {
                    TextButton(onClick = { picked = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { addToChosen(playlists.filter { it.id in selection }) },
                        enabled = selection.isNotEmpty(),
                    ) {
                        Text(
                            if (selection.isEmpty()) stringResource(R.string.playlist_choose_several)
                            else pluralStringResource(R.plurals.add_to_n_playlists, selection.size, selection.size)
                        )
                    }
                }
            }
        },
    ) {
        item {
            ListItem(
                title = stringResource(R.string.create_playlist),
                thumbnailContent = {
                    Image(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                        modifier = Modifier.size(ListThumbnailSize)
                    )
                },
                modifier = Modifier.clickable {
                    showCreatePlaylistDialog = true
                }
            )
        }

        item {
            InfoLabel(
                text = stringResource(R.string.playlist_add_local_to_synced_note),
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        if (picked == null) {
            item {
                InfoLabel(
                    text = stringResource(R.string.playlist_hold_to_choose_several),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }

        item {
            SortHeader(
                sortType = sortType,
                sortDescending = sortDescending,
                onSortTypeChange = onSortTypeChange,
                onSortDescendingChange = onSortDescendingChange,
                sortTypeText = { sortType ->
                    when (sortType) {
                        PlaylistSortType.CREATE_DATE -> R.string.sort_by_create_date
                        PlaylistSortType.NAME -> R.string.sort_by_name
                        PlaylistSortType.SONG_COUNT -> R.string.sort_by_song_count
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
            )
        }

        items(playlists, key = { it.id }) { playlist ->
            val selection = picked
            val selected = selection?.contains(playlist.id) == true
            PlaylistListItem(
                playlist = playlist,
                trailingContent = {
                    val inPlaylist =
                        playlistIdsSongParticipation != null && playlist.id in playlistIdsSongParticipation!!
                    if (selection != null) {
                        // Choosing several: a small tick still says where the song already is, and
                        // the box is the choice.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (inPlaylist) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.playlist_song_already_in),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(
                                imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    } else {
                        val icon =
                            if (inPlaylist && songIds?.size == 1) {
                                Icons.Rounded.CheckBox
                            } else if (inPlaylist) {
                                Icons.Rounded.IndeterminateCheckBox
                            } else {
                                Icons.Rounded.CheckBoxOutlineBlank
                            }

                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 8.dp)
                        )
                    }
                },
                modifier = Modifier
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .combinedClickable(
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            picked = (selection ?: emptySet()) + playlist.id
                        },
                    ) {
                    if (selection != null) {
                        picked = if (selected) selection - playlist.id else selection + playlist.id
                        return@combinedClickable
                    }
                    selectedPlaylist = playlist
                    coroutineScope.launch(Dispatchers.IO) {
                        if (onPreAdd != null) {
                            val result = onPreAdd(playlist)
                            if (songIds == null) {
                                songIds = result
                            }
                        }
                        duplicates = database.playlistDuplicates(playlist.id, songIds!!)
                        if (duplicates.isNotEmpty()) {
                            showDuplicateDialog = true
                        } else {
                            onDismiss()
                            database.addSongToPlaylist(playlist, songIds!!)
                            // Handed to SyncUtils rather than looped here: this scope dies with the
                            // dialog, which onDismiss() has just closed. See addToRemotePlaylist.
                            addRemotely(playlist, songIds!!)
                        }
                    }
                }
            )
        }

        if (syncMode == SyncMode.RO) {
            item {
                TextButton(
                    onClick = {
                        navController.navigate("settings/account_sync")
                        onDismiss()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.playlist_missing_note),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = TextUnit(12F, TextUnitType.Sp),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }

    // duplicate songs warning
    if (showDuplicateDialog) {
        DefaultDialog(
            title = { Text(stringResource(R.string.duplicates)) },
            buttons = {
                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                        onDismiss()
                        val toAdd = songIds!!.filterNot { duplicates.contains(it) }
                        database.transaction { addSongToPlaylist(selectedPlaylist!!, toAdd) }
                        addRemotely(selectedPlaylist!!, toAdd)
                    }
                ) {
                    Text(stringResource(R.string.skip_duplicates))
                }

                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                        onDismiss()
                        database.transaction { addSongToPlaylist(selectedPlaylist!!, songIds!!) }
                        addRemotely(selectedPlaylist!!, songIds!!)
                    }
                ) {
                    Text(stringResource(R.string.add_anyway))
                }

                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            onDismiss = {
                showDuplicateDialog = false
            }
        ) {
            Text(
                text = if (duplicates.size == 1) {
                    stringResource(R.string.duplicates_description_single)
                } else {
                    stringResource(R.string.duplicates_description_multiple, duplicates.size)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}
