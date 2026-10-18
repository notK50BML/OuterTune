package com.dd3boh.outertune.ui.menu

import coil3.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.delay
import com.zionhuang.innertube.models.ArtistItem
import com.dd3boh.outertune.utils.LocalArtistLinker
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Link
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.ArtistListItem
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArtistMenu(
    originalArtist: Artist,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isNetworkConnected = LocalNetworkConnected.current
    val artistState = database.artist(originalArtist.id).collectAsState(initial = originalArtist)
    val artist = artistState.value ?: originalArtist
    // Local songs credited to a real channel are ones LocalArtistLinker linked, and can be unlinked.
    val localSongCount by database.localSongCountForArtist(originalArtist.id).collectAsState(initial = 0)
    var showLinkDialog by remember { mutableStateOf(false) }

    if (showLinkDialog) {
        LinkArtistDialog(
            name = artist.artist.name,
            onDismiss = { showLinkDialog = false },
            onPick = { picked ->
                showLinkDialog = false
                coroutineScope.launch { LocalArtistLinker.linkByHand(context, database, artist.artist, picked) }
                onDismiss()
            },
        )
    }

    ArtistListItem(
        artist = artist,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    database.transaction {
                        update(artist.artist.toggleLike())
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (artist.artist.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (artist.artist.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null
                )
            }
        }
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (artist.songCount > 0) {
            GridMenuItem(
                icon = Icons.Rounded.PlayArrow,
                title = R.string.play
            ) {
                coroutineScope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        database.artistSongs(artist.id, ArtistSongSortType.CREATE_DATE, true).first()
                            .map { it.toMediaMetadata() }
                    }

                    val playlistId = withContext(Dispatchers.IO) {
                        YouTube.artist(artist.id).getOrNull()?.artist?.shuffleEndpoint?.playlistId
                    }

                    playerConnection.playQueue(
                        ListQueue(
                            title = artist.artist.name,
                            items = songs,
                            playlistId = playlistId
                        )
                    )
                }
                onDismiss()
            }
            GridMenuItem(
                icon = Icons.Rounded.Shuffle,
                title = R.string.shuffle
            ) {
                coroutineScope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        database.artistSongs(artist.id, ArtistSongSortType.CREATE_DATE, true).first()
                            .map { it.toMediaMetadata() }
                            .shuffled()
                    }

                    val playlistId = withContext(Dispatchers.IO) {
                        YouTube.artist(artist.id).getOrNull()?.artist?.shuffleEndpoint?.playlistId
                    }

                    playerConnection.playQueue(
                        ListQueue(
                            title = artist.artist.name,
                            items = songs,
                            playlistId = playlistId
                        )
                    )
                }
                onDismiss()
            }
        }
        if (!artist.artist.isYouTubeArtist && isNetworkConnected) {
            GridMenuItem(
                icon = { Icon(Icons.Rounded.Link, null) },
                title = R.string.link_artist_ytm,
            ) { showLinkDialog = true }
        } else if (artist.artist.isYouTubeArtist && localSongCount > 0) {
            GridMenuItem(
                icon = { Icon(Icons.Rounded.LinkOff, null) },
                title = R.string.unlink_local_files,
            ) {
                coroutineScope.launch { LocalArtistLinker.unlinkLocal(context, database, artist.artist) }
                onDismiss()
            }
        }
        if (artist.artist.isYouTubeArtist) {
            GridMenuItem(
                icon = Icons.Rounded.Share,
                title = R.string.share
            ) {
                onDismiss()
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/channel/${artist.id}")
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }
}

/** Picks the YouTube Music artist a local artist is, searched by their name to start with. */
@Composable
private fun LinkArtistDialog(name: String, onDismiss: () -> Unit, onPick: (ArtistItem) -> Unit) {
    var query by remember { mutableStateOf(name) }
    var results by remember { mutableStateOf<List<ArtistItem>?>(null) }
    LaunchedEffect(query) {
        delay(350)
        results = null
        results = runCatching { LocalArtistLinker.candidates(query) }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_artist_dialog_title, name)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val found = results
                when {
                    found == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    found.isEmpty() -> Text(stringResource(R.string.link_artist_none_found), Modifier.padding(8.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(found, key = { it.id }) { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 8.dp),
                            ) {
                                AsyncImage(
                                    model = item.thumbnail,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
