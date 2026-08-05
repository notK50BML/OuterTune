package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.betterlyrics.BetterLyrics
import com.dd3boh.outertune.constants.EnableBetterLyricsKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get

object BetterLyricsProvider : LyricsProvider {
    override val id = "betterlyrics"
    override val name = "BetterLyrics"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int): LyricsFetchResult =
        BetterLyrics.getLyrics(title, artist, duration).toFetchResult()
}
