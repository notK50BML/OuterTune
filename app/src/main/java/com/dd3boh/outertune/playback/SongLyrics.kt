package com.dd3boh.outertune.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Lyrics resolved for one particular song, tagged with that song's id.
 *
 * [lyrics] is null while nothing is known yet for [mediaId] - its row has not been read yet, or the
 * song has not been fetched yet - which the lyrics view shows as loading. Carrying the id is what
 * lets the view refuse lyrics that belong to a song other than the one it is drawing, instead of
 * trusting that this flow has caught up with a track change by the time the frame is drawn.
 */
data class SongLyrics<out T : Any>(val mediaId: String, val lyrics: T?)

/**
 * The lyrics for whichever song [this] currently names, switching over on every song change.
 *
 * A song change first emits an empty [SongLyrics] for the new song, before [resolve] has produced
 * anything for it. Without that, whatever the previous song resolved to stayed up until the new
 * song's first value arrived - a database read and a parse at best, a forced refetch of a stored row
 * that does not parse at worst - so the old song's lyrics sat under the new one. flatMapLatest
 * cancels the previous song's resolution on the switch, so a slow read or fetch for that song can
 * never emit after it.
 *
 * Songs are compared by id: the metadata object for one song is rebuilt now and then, and is not
 * even equal to itself across rebuilds (see MediaMetadata.composeUidWorkaround). Restarting on each
 * of those would flash the loading state over lyrics that are already showing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <M : Any, T : Any> Flow<M?>.lyricsPerSong(
    idOf: (M) -> String,
    resolve: (M) -> Flow<T?>,
): Flow<SongLyrics<T>?> =
    distinctUntilChangedBy { song -> song?.let(idOf) }
        .flatMapLatest { song ->
            if (song == null) {
                flowOf(null)
            } else {
                val id = idOf(song)
                resolve(song)
                    .map { SongLyrics(id, it) }
                    .onStart { emit(SongLyrics(id, null)) }
            }
        }
