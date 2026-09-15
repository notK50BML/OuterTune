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

    // Only ever called while something is actually playing - a paused/stopped player calls
    // stopActivity() instead, so there is no "paused" card to render here at all.
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long = 0L,
    ) = runCatching {
        val thisUpdate = ++latestUpdate
        updateMutex.withLock {
            // Something newer started while this one waited for the lock; that one wins.
            if (thisUpdate != latestUpdate) return@runCatching
            sendPresence(song, currentPlaybackTimeMillis)
        }
    }.onFailure {
        // runCatching swallows CancellationException, which would quietly break the caller's
        // structured concurrency: a superseded update would look like it completed normally
        // instead of unwinding. Let cancellation through; keep swallowing real failures.
        if (it is CancellationException) throw it
    }

    private suspend fun sendPresence(song: Song, currentPlaybackTimeMillis: Long) {
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

        // Every string goes through RpcText first. Discord validates these and rejects the *whole*
        // presence when one is out of range - without trimming, and without saying so - so a track
        // titled "4", or one carrying three features and a remix credit, produced no card at all.
        // That looks exactly like rich presence being broken rather than like one unusual title.
        //
        // The artist line falls back rather than going out empty: a song with no credits would
        // otherwise send a blank state, which is below the minimum and fails the same way.
        setActivity(
            name = context.getString(R.string.app_name).removeSuffix(" Debug"),
            details = RpcText.fit(song.song.title, fallback = context.getString(R.string.unknown)),
            detailsUrl = "https://music.youtube.com/watch?v=${song.song.id}",
            state = RpcText.fit(
                song.artists.joinToString { it.name },
                fallback = context.getString(R.string.unknown),
            ),
            stateUrl = artistUrl,
            largeImage = song.song.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            smallImage = song.artists.firstOrNull()?.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            // Hover text only. Discord has no url slot for the large image, so the album
            // cannot be made clickable the way the title and artist can.
            largeText = RpcText.fit(song.album?.title),
            smallText = RpcText.fit(song.artists.firstOrNull()?.name),
            buttons = listOf(
                RpcText.fitButton(context.getString(R.string.rpc_listen_ytm)) to
                        "https://music.youtube.com/watch?v=${song.song.id}",
                RpcText.fitButton(
                    context.getString(R.string.rpc_visit, context.getString(R.string.app_name))
                ) to "https://github.com/notK50BML/OuterTune"
            ),
            type = Type.LISTENING,
            statusDisplayType = StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = calculatedEndTime,
            applicationId = APPLICATION_ID
        )
    }

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"
    }
}