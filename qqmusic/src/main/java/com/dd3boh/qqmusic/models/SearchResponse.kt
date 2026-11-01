package com.dd3boh.qqmusic.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parts of `musicu.fcg`'s `DoSearchForQQMusicDesktop` answer that are read: the songs, with the
 * numeric id the QRC download wants, the `mid` the LRC endpoint wants, and the length to match on.
 */
@Serializable
data class SearchResponse(
    val code: Int = 0,
    val req: Req? = null,
) {
    @Serializable
    data class Req(
        val code: Int = 0,
        val data: Data? = null,
    )

    @Serializable
    data class Data(
        val body: Body? = null,
    )

    @Serializable
    data class Body(
        val song: SongList? = null,
    )

    @Serializable
    data class SongList(
        val list: List<Song> = emptyList(),
    )

    @Serializable
    data class Song(
        val id: Long = 0,
        val mid: String = "",
        /** The plain name, e.g. "Shape Of You"; [title] adds the version, "Shape Of You (Elephante Remix)". */
        val name: String = "",
        val title: String = "",
        /** Length in seconds. */
        @SerialName("interval") val duration: Int = 0,
        val singer: List<Singer> = emptyList(),
        /** Other versions QQ folds under this one (live takes, other albums) - candidates too. */
        val grp: List<Song> = emptyList(),
    )

    @Serializable
    data class Singer(
        val name: String = "",
    )
}

/** `fcg_query_lyric_new.fcg`: the line-timed LRC, base64. A non-zero [retcode] means there is none. */
@Serializable
data class LrcResponse(
    val retcode: Int = 0,
    val code: Int = 0,
    val lyric: String? = null,
)

/**
 * `search_for_qq_cp`: QQ's oldest public search, in its own older shape - songid/songmid/songname -
 * read into the same [SearchResponse.Song] the rest of the code matches on. As of November 2026 it is
 * the one search still answering: the desktop search refuses with 2001 from the first request, and
 * `client_search_cp` returns HTTP 500.
 */
@Serializable
data class OldSearchResponse(
    val code: Int = 0,
    val data: Data? = null,
) {
    @Serializable
    data class Data(val song: SongList? = null)

    @Serializable
    data class SongList(val list: List<Song> = emptyList())

    @Serializable
    data class Song(
        val songid: Long = 0,
        val songmid: String = "",
        val songname: String = "",
        val interval: Int = 0,
        val singer: List<SearchResponse.Singer> = emptyList(),
        val grp: List<Song> = emptyList(),
    ) {
        fun toSong(): SearchResponse.Song = SearchResponse.Song(
            id = songid,
            mid = songmid,
            name = songname,
            title = songname,
            duration = interval,
            singer = singer,
            grp = grp.map { it.toSong() },
        )
    }
}

/** `client_search_cp` with new_json=1: the same songs as [SearchResponse], under data.song.list. */
@Serializable
data class LegacySearchResponse(
    val code: Int = 0,
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val song: SearchResponse.SongList? = null,
    )
}
