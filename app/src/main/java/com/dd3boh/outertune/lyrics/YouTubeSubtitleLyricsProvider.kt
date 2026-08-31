package com.dd3boh.outertune.lyrics

import android.content.Context
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CancellationException

object YouTubeSubtitleLyricsProvider : LyricsProvider {
    override val id = "youtube-subtitle"
    override val name = "YouTube Subtitle"
    override fun isEnabled(context: Context) = true

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int): LyricsFetchResult =
        YouTube.transcript(id).fold(
            // Captions are a transcript rather than a lyric sheet - see cleanCaptionLyrics for what
            // that means in practice and why only this provider gets the treatment.
            onSuccess = { LyricsFetchResult.Found(cleanCaptionLyrics(it)) },
            onFailure = {
                if (it is CancellationException) throw it
                // transcript() signals a missing or empty caption track with IllegalStateException (via
                // check()); transport errors surface as other exception types.
                if (it is IllegalStateException) LyricsFetchResult.NotFound else LyricsFetchResult.Failed(it)
            }
        )
}
