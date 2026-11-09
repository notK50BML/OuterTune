package com.dd3boh.outertune.extensions

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.SongItem

val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

/**
 * The picture the media session - and so the notification, the lock screen and the system's media
 * controls - shows for a song.
 *
 * This used to be the remote thumbnail URL alone. A song from this phone's own storage often has
 * none (its cover is inside the audio file), so it was handed no artwork at all, and a downloaded
 * song ignored the full-size cover already saved for it. With nothing to show, the system's media
 * card falls back to the app's icon - which is what HyperOS was showing. In order of preference:
 * the saved full-size cover, the remote art, then the audio file itself, whose embedded picture
 * CoilBitmapLoader knows how to pull out.
 */
private fun artworkUri(thumbnailPath: String?, thumbnailUrl: String?, isLocal: Boolean, localPath: String?) =
    (thumbnailPath ?: thumbnailUrl ?: localPath.takeIf { isLocal })?.toUri()

fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.id)
    .setCustomCacheKey(song.id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(artworkUri(song.thumbnailPath, song.thumbnailUrl, song.isLocal, song.localPath))
            .setAlbumTitle(song.albumName)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

fun SongItem.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(thumbnail.toUri())
            .setAlbumTitle(album?.name)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

fun MediaMetadata.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(this)
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(artists.joinToString { it.name })
            .setArtist(artists.joinToString { it.name })
            .setArtworkUri(artworkUri(thumbnailPath, thumbnailUrl, isLocal, localPath))
            .setAlbumTitle(album?.title)
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()