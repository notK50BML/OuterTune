package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.constants.EnableSimpMusicKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.simpmusic.SimpMusicLyrics
import com.dd3boh.simpmusic.SimpMusicRefusedException

object SimpMusicLyricsProvider : LyricsProvider {
    override val id = "simpmusic"
    override val name = "SimpMusic"

    override fun isEnabled(context: Context): Boolean =
        // Off unless turned on: SimpMusic's server now answers only its own app, so asking it
        // costs a request on every song for a refusal.
        context.dataStore[EnableSimpMusicKey] ?: false

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int): LyricsFetchResult {
        val result = SimpMusicLyrics.getLyrics(id, duration)
        // The API turns away every app but SimpMusic's own (see SimpMusicLyrics). That is a standing
        // answer rather than a failure: reported as Failed it would leave every song the others have
        // nothing for undecided, so it was never remembered as having no lyrics and was looked up
        // again on every play. As NotFound it simply contributes nothing.
        if (result.exceptionOrNull() is SimpMusicRefusedException) return LyricsFetchResult.NotFound
        return result.toFetchResult()
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        SimpMusicLyrics.getAllLyrics(id, duration, callback)
    }
}
