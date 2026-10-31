package com.dd3boh.outertune.lyrics

import android.content.Context
import com.dd3boh.outertune.constants.EnableQqMusicKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.qqmusic.QQMusic

/**
 * QQ Music, asked directly - see `:qqmusic`. Word-timed TTML when QQ has its QRC for the song, its
 * line-timed LRC otherwise; both read by the same parse path every other provider's text goes through.
 */
object QQMusicLyricsProvider : LyricsProvider {
    override val id = "qqmusic"
    override val name = "QQ Music"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableQqMusicKey] ?: true

    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int): LyricsFetchResult =
        QQMusic.getLyrics(title, artist, duration).toFetchResult()

    override suspend fun getAllLyrics(id: String, title: String, artist: String, duration: Int, callback: (String) -> Unit) {
        QQMusic.getAllLyrics(title, artist, duration, callback)
    }
}
