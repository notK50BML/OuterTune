package com.dd3boh.outertune.utils
import android.content.Context
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Song
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiscordRPC(
    val context: Context,
    token: String,
) : KizzyRPC(token) {
    /**
     * Presence writes are serialised and superseded ones are dropped.
     *
     * setActivity resolves both artwork URLs over the network before sending, so two updates can
     * easily overlap - the track-change collector and the play/pause hook both call this. Without
     * a guard the older one can land last and leave the card describing the previous song.
     */
    private val updateMutex = Mutex()

    @Volatile
    private var latestUpdate = 0L

    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long = 0L,
        paused: Boolean = false,
    ) = runCatching {
        val thisUpdate = ++latestUpdate
        updateMutex.withLock {
            // Something newer started while this one waited for the lock; that one wins.
            if (thisUpdate != latestUpdate) return@runCatching
            sendPresence(song, currentPlaybackTimeMillis, paused)
        }
    }.onFailure {
        // runCatching swallows CancellationException, which would quietly break the caller's
        // structured concurrency: a superseded update would look like it completed normally
        // instead of unwinding. Let cancellation through; keep swallowing real failures.
        if (it is CancellationException) throw it
    }

    /**
     * @param paused keep the card up but drop its timestamps. Discord derives the progress bar
     *   entirely from start and end, so a paused track has to send neither - with them it would
     *   carry on counting as though the song were still playing.
     */
    private suspend fun sendPresence(song: Song, currentPlaybackTimeMillis: Long, paused: Boolean) {
        val currentTime = System.currentTimeMillis()
        val calculatedStartTime = currentTime - currentPlaybackTimeMillis

        // SongEntity.duration is seconds and defaults to -1 when it is not known yet, which is
        // common for a track that has only just been inserted. Left alone that produced an end
        // timestamp earlier than the start, and Discord draws that as a bar stuck at zero.
        // Sending no end at all is better: Discord then shows elapsed time counting up.
        val durationMs = song.song.duration.takeIf { it > 0 }?.times(1000L)
        val calculatedEndTime = durationMs?.let { currentTime + (it - currentPlaybackTimeMillis) }

        // Discord makes details and state clickable when a matching *_url is supplied, and there
        // are only those two slots. The title already uses details_url; give the artist line
        // state_url so it opens the artist on YouTube Music. Local artists have a generated "LA"
        // id that resolves to nothing, so they stay plain text.
        val artistUrl = song.artists.firstOrNull()
            ?.takeIf { !it.isLocal }
            ?.let { "https://music.youtube.com/channel/${it.id}" }

        setActivity(
            name = context.getString(R.string.app_name).removeSuffix(" Debug"),
            details = song.song.title,
            detailsUrl = "https://music.youtube.com/watch?v=${song.song.id}",
            state = song.artists.joinToString { it.name },
            stateUrl = artistUrl,
            largeImage = song.song.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            smallImage = song.artists.firstOrNull()?.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            // Hover text only. Discord has no url slot for the large image, so the album
            // cannot be made clickable the way the title and artist can.
            largeText = song.album?.title,
            smallText = if (paused) context.getString(R.string.rpc_paused)
            else song.artists.firstOrNull()?.name,
            buttons = listOf(
                context.getString(R.string.rpc_listen_ytm) to
                        "https://music.youtube.com/watch?v=${song.song.id}",
                context.getString(R.string.rpc_visit, context.getString(R.string.app_name)) to
                        "https://github.com/notK50BML/OuterTune"
            ),
            type = Type.LISTENING,
            statusDisplayType = StatusDisplayType.STATE,
            since = currentTime,
            startTime = if (paused) null else calculatedStartTime,
            endTime = if (paused) null else calculatedEndTime,
            applicationId = APPLICATION_ID
        )
    }

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"
    }
}